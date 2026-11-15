package app.fcc.claude

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Um único FCC Server para o aplicativo inteiro.
 * Reutiliza servidor saudável, supervisiona o processo próprio e
 * impede que supervisores antigos interfiram após stop/restart.
 */
object FccServer {
    enum class State { STOPPED, STARTING, READY, FAILED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var port = 8082
        private set
    @Volatile var lastError = ""
        private set
    @Volatile var listener: ((State, String) -> Unit)? = null

    private const val BASE = 8082
    private const val SPAN = 8
    private const val STARTUP_TIMEOUT_MS = 7 * 60 * 1000L

    private val lock = Any()
    private var generation = 0L
    private var wanted = false
    private var worker: Thread? = null
    @Volatile private var process: Process? = null

    fun logFile(ctx: Context) = File(Proot.tmpDir(ctx), "fcc-server.log")

    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (wanted && worker?.isAlive == true) return
            startLocked(app)
        }
    }

    private fun startLocked(ctx: Context) {
        wanted = true
        val token = ++generation
        worker = Thread({ supervise(ctx, token) }, "fcc-server-supervisor").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Invalida imediatamente o supervisor anterior. O novo só começa
     * depois que a thread anterior termina ou atinge o limite de espera.
     */
    fun restart(ctx: Context) {
        val app = ctx.applicationContext
        val oldWorker: Thread?
        val oldProcess: Process?
        val restartToken: Long

        synchronized(lock) {
            wanted = false
            restartToken = ++generation
            oldWorker = worker
            oldProcess = process
            worker = null
            process = null
        }

        oldWorker?.interrupt()
        terminate(oldProcess)
        reportStopped()

        Thread({
            try {
                oldWorker?.join(10_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            synchronized(lock) {
                // Se outro ensure/stop/restart ocorreu, esta solicitação expirou.
                if (generation == restartToken && !wanted) startLocked(app)
            }
        }, "fcc-server-restart").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val oldWorker: Thread?
        val oldProcess: Process?

        synchronized(lock) {
            wanted = false
            generation++
            oldWorker = worker
            oldProcess = process
            worker = null
            process = null
        }

        oldWorker?.interrupt()
        terminate(oldProcess)
        reportStopped()
    }

    private fun terminate(p: Process?) {
        if (p == null) return
        try { p.destroy() } catch (_: Exception) {}
        Thread({
            try {
                if (!p.waitFor(4, TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: Exception) {
                try { p.destroyForcibly() } catch (_: Exception) {}
            }
        }, "fcc-server-terminate").apply {
            isDaemon = true
            start()
        }
    }

    private fun isCurrent(token: Long): Boolean =
        synchronized(lock) { wanted && generation == token }

    private fun report(token: Long, s: State, msg: String) {
        val callback = synchronized(lock) {
            if (!wanted || generation != token) return
            state = s
            if (s == State.FAILED) lastError = msg
            listener
        }
        callback?.let {
            try { it(s, msg) } catch (_: Exception) {}
        }
    }

    private fun reportStopped() {
        val callback = synchronized(lock) {
            state = State.STOPPED
            lastError = ""
            listener
        }
        callback?.let {
            try { it(State.STOPPED, "") } catch (_: Exception) {}
        }
    }

    private fun pause(token: Long, ms: Long): Boolean {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // A mudança de geração é a autoridade para cancelar o supervisor.
        }
        return isCurrent(token)
    }

    // ---------------------------------------------------------------- supervisão

    private fun supervise(ctx: Context, token: Long) {
        var failures = 0
        var windowStart = System.currentTimeMillis()

        while (isCurrent(token)) {
            report(token, State.STARTING, "procurando servidor FCC…")

            val existing = findHealthy()
            if (existing != null) {
                port = existing
                publish(ctx)
                report(token, State.READY, "pronto na porta $existing")
                waitWhileHealthy(null, token)
                if (!isCurrent(token)) return
                continue
            }

            val free = (BASE..BASE + SPAN).firstOrNull { !portInUse(it) }
            if (free == null) {
                report(token, State.FAILED, "Nenhuma porta livre entre $BASE e ${BASE + SPAN}.")
                return
            }

            val proc = try {
                launch(ctx, free)
            } catch (e: Exception) {
                report(token, State.FAILED, "Não foi possível iniciar o servidor: ${e.message}")
                return
            }

            val accepted = synchronized(lock) {
                if (wanted && generation == token) {
                    process = proc
                    true
                } else false
            }

            if (!accepted) {
                terminate(proc)
                return
            }

            report(token, State.STARTING, "iniciando na porta $free…")
            val startedAt = System.currentTimeMillis()
            var healthy = false

            while (isCurrent(token) &&
                System.currentTimeMillis() - startedAt < STARTUP_TIMEOUT_MS) {
                if (health(free)) {
                    healthy = true
                    break
                }
                if (!proc.isAlive) break
                if (!pause(token, 1000)) break
            }

            if (!isCurrent(token)) {
                terminate(proc)
                clearProcess(proc)
                return
            }

            if (healthy) {
                port = free
                publish(ctx)
                report(token, State.READY, "pronto na porta $free")
                waitWhileHealthy(proc, token)
                clearProcess(proc)

                if (!isCurrent(token)) return
                report(token, State.STARTING, "o servidor parou — reiniciando…")
            } else {
                lastError = tail(logFile(ctx), 12)
                    .ifBlank { "o servidor não respondeu a tempo" }
                terminate(proc)
                clearProcess(proc)
            }

            val now = System.currentTimeMillis()
            if (now - windowStart > 10 * 60 * 1000L) {
                failures = 0
                windowStart = now
            }

            failures++
            if (failures >= 3) {
                report(token, State.FAILED,
                    lastError.ifBlank { "o servidor caiu 3 vezes seguidas" })
                return
            }

            if (!pause(token, 2000L * failures)) return
        }
    }

    private fun clearProcess(p: Process) {
        synchronized(lock) {
            if (process === p) process = null
        }
    }

    private fun waitWhileHealthy(owned: Process?, token: Long) {
        var bad = 0
        while (isCurrent(token)) {
            if (!pause(token, 5000)) return
            val alive = owned?.isAlive ?: true
            if (alive && health(port)) bad = 0 else bad++
            if (bad >= 3 || (owned != null && !alive)) return
        }
    }

    private fun launch(ctx: Context, p: Int): Process {
        Proot.prepareRootfs(ctx)
        val log = logFile(ctx)
        if (log.exists() && log.length() > 1_000_000) log.delete()

        val cmd = Proot.args(
            ctx,
            emptyList(),
            "/root",
            listOf("HOST=127.0.0.1", "PORT=$p"),
            listOf("/root/.local/bin/fcc-server")
        )

        return ProcessBuilder(cmd).apply {
            environment().putAll(
                Proot.env(ctx).associate {
                    it.substringBefore('=') to it.substringAfter('=')
                }
            )
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }.start()
    }

    // ---------------------------------------------------------------- utilidades

    private fun publish(ctx: Context) {
        try { File(Proot.tmpDir(ctx), "fcc-port").writeText(port.toString()) }
        catch (_: Exception) {}
    }

    private fun findHealthy(): Int? =
        (BASE..BASE + SPAN).firstOrNull { health(it) }

    private fun health(p: Int): Boolean = try {
        val c = URL("http://127.0.0.1:$p/health")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 3000
        try { c.responseCode == 200 } finally { c.disconnect() }
    } catch (_: Exception) {
        false
    }

    private fun portInUse(p: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", p), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun tail(f: File, n: Int): String = try {
        f.readLines().takeLast(n).joinToString("\n") {
            if (it.length > 200) it.take(200) + "…" else it
        }
    } catch (_: Exception) {
        ""
    }
}

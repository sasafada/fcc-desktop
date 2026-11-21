package app.fcc.ai

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * UM único FCC Server para o app inteiro (as conversas só se conectam a ele).
 *  - reaproveita um servidor já saudável (nunca sobe um segundo);
 *  - escolhe porta livre entre 8082 e 8090 e só escuta em 127.0.0.1;
 *  - vigia o processo e o reinicia se cair (com limite de tentativas);
 *  - encerra apenas o processo que ELE mesmo iniciou (sem pkill).
 */
object FccServer {
    enum class State { STOPPED, STARTING, READY, FAILED }

    @Volatile var state = State.STOPPED; private set
    @Volatile var port = 8082; private set
    @Volatile var lastError = ""; private set
    @Volatile var listener: ((State, String) -> Unit)? = null

    private const val BASE = 8082
    private const val SPAN = 8
    private const val STARTUP_TIMEOUT_MS = 7 * 60 * 1000L

    private var worker: Thread? = null
    @Volatile private var process: Process? = null
    @Volatile private var wanted = false
    private val lock = Any()

    fun logFile(ctx: Context) = File(Proot.tmpDir(ctx), "fcc-server.log")

    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (worker?.isAlive == true) return
            wanted = true
            worker = Thread({ supervise(app) }, "fcc-server-supervisor").also { it.isDaemon = true; it.start() }
        }
    }

    /** Reinicia somente o servidor iniciado por este app. */
    fun restart(ctx: Context) {
        stop()
        Thread { try { Thread.sleep(1500) } catch (_: InterruptedException) {}; ensure(ctx) }.start()
    }

    fun stop() {
        wanted = false
        val p = process
        process = null
        if (p != null) {
            p.destroy()                                   // SIGTERM (encerramento limpo)
            Thread {
                try { if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
            }.start()
        }
        set(State.STOPPED, "")
    }

    // ------------------------------------------------------------------ supervisão
    private fun supervise(ctx: Context) {
        var failures = 0
        var windowStart = System.currentTimeMillis()
        while (wanted) {
            set(State.STARTING, "procurando servidor FCC…")
            val existing = findHealthy()
            if (existing != null) {                       // reutiliza instância saudável
                port = existing; publish(ctx); set(State.READY, "pronto na porta $existing")
                waitWhileHealthy(null)
                if (!wanted) return
                continue
            }
            val free = (BASE..BASE + SPAN).firstOrNull { !portInUse(it) }
            if (free == null) { lastError = "Nenhuma porta livre entre $BASE e ${BASE + SPAN}."; set(State.FAILED, lastError); return }

            val proc = try { launch(ctx, free) } catch (e: Exception) {
                lastError = "Não foi possível iniciar o servidor: ${e.message}"; set(State.FAILED, lastError); return
            }
            process = proc
            set(State.STARTING, "iniciando na porta $free…")
            val t0 = System.currentTimeMillis()
            var ok = false
            while (wanted && System.currentTimeMillis() - t0 < STARTUP_TIMEOUT_MS) {
                if (health(free)) { ok = true; break }
                if (!proc.isAlive) break
                sleep(1000)
            }
            if (!wanted) { proc.destroy(); return }
            if (ok) {
                port = free; publish(ctx); set(State.READY, "pronto na porta $free")
                waitWhileHealthy(proc)
                if (!wanted) return
                set(State.STARTING, "o servidor parou — reiniciando…")
            } else {
                lastError = tail(logFile(ctx), 12).ifBlank { "o servidor não respondeu a tempo" }
                if (proc.isAlive) proc.destroy()
            }
            // limite: 3 falhas seguidas em 10 minutos → desiste e mostra o log
            val now = System.currentTimeMillis()
            if (now - windowStart > 10 * 60 * 1000L) { failures = 0; windowStart = now }
            failures++
            if (failures >= 3) { set(State.FAILED, lastError.ifBlank { "o servidor caiu 3 vezes seguidas" }); return }
            sleep(2000L * failures)
        }
    }

    /** Fica aqui enquanto o servidor estiver saudável; sai quando cair (ou quando o app pedir parada). */
    private fun waitWhileHealthy(owned: Process?) {
        var bad = 0
        while (wanted) {
            sleep(5000)
            val alive = owned?.isAlive ?: true
            if (alive && health(port)) bad = 0 else bad++
            if (bad >= 3 || (owned != null && !alive)) return
        }
    }

    private fun launch(ctx: Context, p: Int): Process {
        Proot.prepareRootfs(ctx)
        val log = logFile(ctx)
        if (log.exists() && log.length() > 1_000_000) log.delete()
        val cmd = Proot.args(ctx, emptyList(), "/root", listOf("HOST=127.0.0.1", "PORT=$p"), listOf("/root/.local/bin/fcc-server"))
        val pb = ProcessBuilder(cmd)
        pb.environment().putAll(Proot.env(ctx).associate { it.substringBefore('=') to it.substringAfter('=') })
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        return pb.start()
    }

    // ------------------------------------------------------------------ utilidades
    private fun set(s: State, msg: String) { state = s; listener?.invoke(s, msg) }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
    private fun publish(ctx: Context) { try { File(Proot.tmpDir(ctx), "fcc-port").writeText(port.toString()) } catch (_: Exception) {} }
    private fun findHealthy(): Int? = (BASE..BASE + SPAN).firstOrNull { health(it) }

    private fun health(p: Int): Boolean = try {
        val c = URL("http://127.0.0.1:$p/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 3000
        val ok = c.responseCode == 200
        c.disconnect(); ok
    } catch (_: Exception) { false }

    private fun portInUse(p: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 300); true }
    } catch (_: Exception) { false }

    private fun tail(f: File, n: Int): String = try {
        f.readLines().takeLast(n).joinToString("\n") { if (it.length > 200) it.take(200) + "…" else it }
    } catch (_: Exception) { "" }
}

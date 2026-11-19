package app.fcc.claude

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Executa UM turno do Claude Code (processo em modo -p) e entrega os [ChatEvent]s.
 * Independente de Android: recebe a linha de comando pronta. [stop] encerra o processo.
 */
class ClaudeRunner(
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workDir: File? = null,
) {
    @Volatile private var process: Process? = null
    @Volatile var stopped = false; private set

    interface Listener {
        fun onEvent(ev: ChatEvent)
        fun onFinished(exitCode: Int, stoppedByUser: Boolean, stderrTail: String)
    }

    fun start(l: Listener): Thread = Thread({ run(l) }, "claude-runner").also { it.isDaemon = true; it.start() }

    private fun run(l: Listener) {
        val stderrBuf = StringBuilder()
        var code = -1
        try {
            val pb = ProcessBuilder(command)
            pb.environment().putAll(env)
            if (workDir != null) pb.directory(workDir)
            pb.redirectErrorStream(false)
            val p = pb.start()
            process = p
            if (stopped) p.destroy()                       // Parar foi tocado antes do processo existir
            p.outputStream.close()                       // o Claude não lê nada da entrada neste modo
            val errThread = Thread({
                try {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).forEachLine { line ->
                        synchronized(stderrBuf) { stderrBuf.append(line).append('\n'); if (stderrBuf.length > 6000) stderrBuf.delete(0, stderrBuf.length - 4000) }
                    }
                } catch (_: Exception) {}
            }, "claude-stderr").also { it.isDaemon = true; it.start() }
            val parser = StreamParser()
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).forEachLine { line ->
                for (ev in parser.parse(line)) l.onEvent(ev)
            }
            code = if (p.waitFor(5, TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 }
            errThread.join(1000)
        } catch (e: Exception) {
            if (!stopped) synchronized(stderrBuf) { stderrBuf.append(e.javaClass.simpleName).append(": ").append(e.message) }
        } finally {
            process = null
            val tail = synchronized(stderrBuf) { stderrBuf.toString().trim().takeLast(1500) }
            l.onFinished(code, stopped, tail)
        }
    }

    /** Interrompe a geração (botão "Parar"). */
    fun stop() {
        stopped = true
        val p = process ?: return
        p.destroy()
        Thread { try { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {} }.start()
    }
}

package app.fcc.claude

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Extrai o Linux embutido (assets/rootfs.tar.gz ou assets/rootfs.tar) para o armazenamento do app.
 *
 * Melhorias em relação à versão anterior:
 *  - lê o APK com ZipFile (verifica CRC e dá erros claros) em vez do AssetManager;
 *  - uma única extração por vez, mesmo se a Activity for recriada (evita duas threads brigando);
 *  - extrai para "rootfs.tmp" e só renomeia no final (nunca fica um rootfs pela metade);
 *  - confere espaço livre antes e diz exatamente em qual arquivo falhou.
 */
object Rootfs {
    interface Listener {
        fun onProgress(fraction: Float, doneBytes: Long, totalBytes: Long)
        fun onFinished(error: Throwable?)
    }

    private val lock = Any()
    private var listener: Listener? = null
    private var worker: Thread? = null
    private var finished = false
    private var failure: Throwable? = null
    private var lastDone = 0L
    private var lastTotal = 0L

    fun dir(ctx: Context) = File(ctx.filesDir, "rootfs")
    fun isReady(ctx: Context) = File(dir(ctx), ".ready").exists()

    fun attach(ctx: Context, l: Listener) {
        val app = ctx.applicationContext
        var replay: (() -> Unit)? = null
        synchronized(lock) {
            listener = l
            when {
                finished -> { val f = failure; replay = { l.onFinished(f) } }
                worker?.isAlive == true -> {
                    val d = lastDone; val t = lastTotal
                    replay = { if (t > 0) l.onProgress(d.toFloat() / t, d, t) }
                }
                else -> startWorker(app)
            }
        }
        replay?.invoke()
    }

    fun detach(l: Listener) { synchronized(lock) { if (listener === l) listener = null } }

    fun retry(ctx: Context) {
        synchronized(lock) {
            if (worker?.isAlive != true) startWorker(ctx.applicationContext)
        }
    }

    private fun startWorker(app: Context) {
        finished = false
        failure = null
        lastDone = 0; lastTotal = 0
        worker = Thread({
            var err: Throwable? = null
            try { doExtract(app) } catch (t: Throwable) { err = t }
            val l: Listener?
            synchronized(lock) { finished = true; failure = err; l = listener }
            l?.onFinished(err)
        }, "rootfs-extract").also { it.start() }
    }

    private fun report(done: Long, total: Long) {
        val l: Listener?
        synchronized(lock) { lastDone = done; lastTotal = total; l = listener }
        l?.onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f, done, total)
    }

    private class Counting(input: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        var total = 0L
        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) bump(it) }
        private fun bump(n: Long) { total += n; onBytes(total) }
    }

    private fun doExtract(ctx: Context) {
        val mb = 1024L * 1024L
        val tmp = File(ctx.filesDir, "rootfs.tmp")
        tmp.deleteRecursively()

        ZipFile(ctx.applicationInfo.sourceDir).use { zip ->
            val entry = zip.getEntry("assets/rootfs.tar.gz") ?: zip.getEntry("assets/rootfs.tar")
                ?: throw IllegalStateException("O APK não contém o Linux embutido (assets/rootfs.tar.gz).")
            val gz = entry.name.endsWith(".gz")
            val total = if (entry.size > 0) entry.size else entry.compressedSize

            val need = (if (gz) total * 3 else total + total / 3) + 300 * mb
            val free = ctx.filesDir.usableSpace
            if (free < need) {
                throw IllegalStateException(
                    "Espaço insuficiente: precisa de ~${need / mb} MB livres e há ${free / mb} MB. Libere espaço e tente de novo.")
            }
            if (!tmp.mkdirs()) throw IllegalStateException("Não foi possível criar ${tmp.path}")

            val base = tmp.toPath().toAbsolutePath().normalize()
            var lastRep = 0L
            val counting = Counting(zip.getInputStream(entry)) { n ->
                if (n - lastRep >= total / 400 || n >= total) { lastRep = n; report(minOf(n, total), total) }
            }
            val raw: InputStream = BufferedInputStream(counting, 1 shl 16)
            val src: InputStream = if (gz) GZIPInputStream(raw, 1 shl 16) else raw

            var current = "(início)"
            try {
                TarArchiveInputStream(src).use { tar ->
                    while (true) {
                        val e = tar.nextTarEntry ?: break
                        val name = e.name.removePrefix("./").trimEnd('/')
                        if (name.isEmpty() || name == ".") continue
                        current = name
                        val target = base.resolve(name).normalize()
                        if (!target.startsWith(base)) continue // proteção contra path traversal
                        val f = target.toFile()
                        val mode = e.mode and 0xFFF

                        when {
                            e.isDirectory -> { f.mkdirs(); chmod(f, mode or 0x1C0) }
                            e.isSymbolicLink -> {
                                f.parentFile?.mkdirs(); f.delete()
                                Os.symlink(e.linkName, f.path)
                            }
                            e.isLink -> {
                                val srcFile = base.resolve(e.linkName.removePrefix("./")).normalize().toFile()
                                f.parentFile?.mkdirs(); f.delete()
                                if (srcFile.exists()) { srcFile.copyTo(f, true); chmod(f, mode or 0x180) }
                            }
                            e.isFile -> {
                                f.parentFile?.mkdirs(); f.delete()
                                f.outputStream().use { out -> tar.copyTo(out, 1 shl 16) }
                                chmod(f, mode or 0x180)
                            }
                            else -> { /* dispositivos/fifos: ignorados */ }
                        }
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("${t.javaClass.simpleName} em '$current': ${t.message}", t)
            }
        }

        File(tmp, "etc").mkdirs()
        File(tmp, ".ready").writeText("ok")
        val finalDir = dir(ctx)
        finalDir.deleteRecursively()
        if (!tmp.renameTo(finalDir)) throw IllegalStateException("Não foi possível finalizar a instalação do ambiente.")
        report(1, 1)
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode) } catch (_: Exception) {}
    }
}

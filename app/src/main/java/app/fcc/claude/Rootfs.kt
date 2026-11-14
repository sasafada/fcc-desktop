package app.fcc.claude

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Extrai o Linux (Ubuntu + FCC + Claude Code) embutido no APK para o armazenamento do app. */
object Rootfs {
    private const val ASSET = "rootfs.tar.gz"

    fun dir(ctx: Context) = File(ctx.filesDir, "rootfs")
    fun isReady(ctx: Context) = File(dir(ctx), ".ready").exists()

    private class Counting(input: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        var total = 0L
        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        private fun bump(n: Long) { total += n; onBytes(total) }
    }

    fun extract(ctx: Context, onProgress: (Float) -> Unit) {
        val dest = dir(ctx)
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.toPath().toAbsolutePath().normalize()

        val afd = ctx.assets.openFd(ASSET)
        val size = afd.length.toFloat()
        var lastPct = -1
        val counting = Counting(afd.createInputStream()) { read ->
            val pct = (read / size * 1000).toInt()
            if (pct != lastPct) { lastPct = pct; onProgress(read / size) }
        }

        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(counting, 1 shl 16), 1 shl 16)).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name == ".") continue
                val target = destPath.resolve(name).normalize()
                if (!target.startsWith(destPath)) continue // proteção contra path traversal
                val f = target.toFile()
                val mode = e.mode and 0xFFF

                when {
                    e.isDirectory -> {
                        f.mkdirs()
                        chmod(f, mode or 0x1C0) // garante rwx para o dono
                    }
                    e.isSymbolicLink -> {
                        f.parentFile?.mkdirs()
                        f.delete()
                        Os.symlink(e.linkName, f.path)
                    }
                    e.isLink -> { // hard link -> cópia
                        val src = destPath.resolve(e.linkName.removePrefix("./")).normalize().toFile()
                        f.parentFile?.mkdirs()
                        f.delete()
                        if (src.exists()) { src.copyTo(f, true); chmod(f, mode or 0x180) }
                    }
                    e.isFile -> {
                        f.parentFile?.mkdirs()
                        f.delete()
                        f.outputStream().use { out -> tar.copyTo(out, 1 shl 16) }
                        chmod(f, mode or 0x180) // garante rw para o dono
                    }
                    else -> { /* dispositivos/fifos: ignorados */ }
                }
            }
        }
        afd.close()

        File(dest, "etc").mkdirs()
        File(dest, ".ready").writeText("ok")
        onProgress(1f)
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.path, mode) } catch (_: Exception) {}
    }
}

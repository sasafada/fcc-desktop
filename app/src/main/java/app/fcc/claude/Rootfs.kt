package app.fcc.claude

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** Extrai o Linux (Ubuntu + FCC + Claude Code) embutido no APK para o armazenamento do app. */
object Rootfs {
    private const val ASSET = "rootfs.tar"
    private const val SIZE_ASSET = "rootfs.tar.size"

    fun dir(ctx: Context) = File(ctx.filesDir, "rootfs")
    fun isReady(ctx: Context) = File(dir(ctx), ".ready").exists()

    private class Counting(
        input: InputStream,
        private val onBytes: (Long) -> Unit
    ) : FilterInputStream(input) {
        var total = 0L

        override fun read(): Int =
            super.read().also { if (it >= 0) bump(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }

        private fun bump(n: Long) {
            total += n
            onBytes(total)
        }
    }

    fun extract(ctx: Context, onProgress: (Float) -> Unit) {
        val dest = dir(ctx)
        if (dest.exists()) dest.deleteRecursively()
        check(dest.mkdirs()) { "Não foi possível criar o diretório rootfs" }

        val destPath = dest.toPath().toAbsolutePath().normalize()

        val expectedSize = ctx.assets.open(SIZE_ASSET).bufferedReader().use {
            it.readText().trim().toLong()
        }
        check(expectedSize > 0L) { "Tamanho do rootfs inválido" }

        var lastPct = -1
        val counting = Counting(ctx.assets.open(ASSET)) { read ->
            val pct = ((read * 1000L) / expectedSize).coerceIn(0L, 1000L).toInt()
            if (pct != lastPct) {
                lastPct = pct
                onProgress(read.toFloat() / expectedSize.toFloat())
            }
        }

        TarArchiveInputStream(
            BufferedInputStream(counting, 1 shl 16)
        ).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name == ".") continue

                val target = destPath.resolve(name).normalize()
                if (!target.startsWith(destPath)) continue

                val f = target.toFile()
                val mode = e.mode and 0xFFF

                when {
                    e.isDirectory -> {
                        f.mkdirs()
                        chmod(f, mode or 0x1C0)
                    }

                    e.isSymbolicLink -> {
                        f.parentFile?.mkdirs()
                        f.delete()
                        Os.symlink(e.linkName, f.path)
                    }

                    e.isLink -> {
                        val src = destPath.resolve(
                            e.linkName.removePrefix("./")
                        ).normalize().toFile()

                        f.parentFile?.mkdirs()
                        f.delete()

                        if (src.exists()) {
                            src.copyTo(f, true)
                            chmod(f, mode or 0x180)
                        }
                    }

                    e.isFile -> {
                        f.parentFile?.mkdirs()
                        f.delete()
                        f.outputStream().use { out ->
                            tar.copyTo(out, 1 shl 16)
                        }
                        chmod(f, mode or 0x180)
                    }

                    else -> {
                        // Dispositivos e FIFOs são ignorados.
                    }
                }
            }
        }

        File(dest, "etc").mkdirs()
        File(dest, ".ready").writeText("ok")
        onProgress(1f)
    }

    private fun chmod(f: File, mode: Int) {
        try {
            Os.chmod(f.path, mode)
        } catch (_: Exception) {
        }
    }
}

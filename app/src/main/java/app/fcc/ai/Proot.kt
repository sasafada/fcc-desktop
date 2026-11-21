package app.fcc.ai

import android.content.Context
import java.io.File

/** Linha de comando do proot, compartilhada pelas sessões do terminal e pelo servidor FCC. */
object Proot {
    const val PATH = "/root/.local/bin:/root/.claude/local:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun binary(ctx: Context) = "${ctx.applicationInfo.nativeLibraryDir}/libproot.so"
    fun rootfs(ctx: Context) = Rootfs.dir(ctx)
    fun tmpDir(ctx: Context) = File(ctx.filesDir, "tmp").apply { mkdirs() }

    /** Arquivos que o Linux espera encontrar (rede, nomes locais) e os scripts do app. */
    fun prepareRootfs(ctx: Context) {
        val root = rootfs(ctx)
        File(root, "etc/resolv.conf").apply {
            parentFile?.mkdirs(); delete()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        // /etc/hosts vinha VAZIO: sem "localhost" o FCC não consegue abrir a porta de retorno do login OAuth.
        File(root, "etc/hosts").apply {
            delete()
            writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        File(root, "workspace").mkdirs()                     // ponto de montagem da pasta da conversa
        for (name in listOf("fcc-app-launch", "fcc-ctl")) {
            val dst = File(root, "usr/local/bin/$name")
            dst.parentFile?.mkdirs()
            ctx.assets.open("$name.sh").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dst.setExecutable(true, false)
        }
    }

    fun env(ctx: Context): Array<String> {
        val nl = ctx.applicationInfo.nativeLibraryDir
        val tmp = tmpDir(ctx).path
        return arrayOf(
            "PROOT_TMP_DIR=$tmp", "PROOT_LOADER=$nl/libproot-loader.so",
            "LD_LIBRARY_PATH=$nl", "PROOT_NO_SECCOMP=1", "TMPDIR=$tmp")
    }

    /**
     * @param binds pares "origem:destino" adicionais (ex.: pasta da conversa → /workspace)
     * @param guestEnv variáveis extras para dentro do Linux (ex.: "PORT=8082")
     * @param inner comando executado dentro do Linux
     */
    fun args(ctx: Context, binds: List<String>, cwd: String, guestEnv: List<String>, inner: List<String>): List<String> {
        val tmp = tmpDir(ctx).path
        val a = ArrayList<String>()
        a += listOf(binary(ctx), "--kill-on-exit", "--link2symlink", "-0", "-r", rootfs(ctx).path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "$tmp:/tmp", "-b", "$tmp:/dev/shm")
        binds.forEach { a += listOf("-b", it) }
        a += listOf("-w", cwd, "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "TERM=xterm-256color", "COLORTERM=truecolor", "PATH=$PATH")
        a += guestEnv
        a += inner
        return a
    }
}

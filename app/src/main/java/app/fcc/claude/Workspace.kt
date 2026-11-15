package app.fcc.claude

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pasta de trabalho de uma conversa:
 *   filesDir/workspaces/{id}/files   ← é o que o proot monta em /workspace
 *   filesDir/workspace-meta/{id}.*   ← estado da sincronização, lixeira, último relatório
 * A pasta do Android (SAF) é só o "espelho"; o Linux nunca enxerga content://.
 */
object Workspace {
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(id: String): Any =
        locks.getOrPut(id) { Any() }


    fun dir(ctx: Context, id: String) = File(ctx.filesDir, "workspaces/$id/files")
    private fun meta(ctx: Context, id: String) = File(ctx.filesDir, "workspace-meta")
    private fun stateFile(ctx: Context, id: String) = File(meta(ctx, id), "$id.state")
    private fun trashDir(ctx: Context, id: String) = File(meta(ctx, id), "$id.trash")
    fun reportFile(ctx: Context, id: String) = File(meta(ctx, id), "$id.report.txt")

    /** A permissão persistida do SAF ainda existe (com escrita)? */
    fun hasPermission(ctx: Context, tree: String): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission && it.isWritePermission }

    /** Guarda a permissão da pasta escolhida para sobreviver a reinícios. */
    fun takePermission(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun releasePermission(ctx: Context, tree: String) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    /** Sincroniza (uma por vez por conversa). Nunca lança: erros vão para o relatório. */
    fun sync(ctx: Context, conv: Conversation, progress: (String) -> Unit = {}): SyncReport {
        val lock = lockFor(conv.id)
        synchronized(lock) {
            val tree = conv.treeUri
            val report: SyncReport
            if (tree == null) {
                report = SyncReport()
            } else if (!hasPermission(ctx, tree)) {
                report = SyncReport()
                report.errors.add("A permissão da pasta foi perdida. Use “Escolher pasta” novamente.")
            } else {
                report = try {
                    val local = dir(ctx, conv.id).apply { mkdirs() }
                    SyncEngine(local, SafRemote(ctx, Uri.parse(tree)), stateFile(ctx, conv.id), trashDir(ctx, conv.id)).sync(progress)
                } catch (e: Exception) {
                    SyncReport().also { it.errors.add("${e.javaClass.simpleName}: ${e.message}") }
                }
            }
            try {
                meta(ctx, conv.id).mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                reportFile(ctx, conv.id).writeText("Sincronização de “${conv.name}” — $stamp\n${report.summary()}\n\n${report.details()}")
            } catch (_: Exception) {}
            return report
        }
    }

    /** Ao trocar de pasta: a cópia local antiga é arquivada (nada é apagado) e o estado é zerado. */
    fun resetForNewFolder(ctx: Context, id: String) {
        synchronized(lockFor(id)) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val old = dir(ctx, id)

            if (old.exists()) {
                val children = old.list()
                    ?: throw IllegalStateException("Não foi possível verificar a pasta local antiga")

                if (children.isNotEmpty()) {
                    val arch = File(
                        ctx.filesDir,
                        "workspaces/archive/$id-$stamp-${java.util.UUID.randomUUID()}"
                    )
                    if (!arch.parentFile!!.exists() && !arch.parentFile!!.mkdirs())
                        throw IllegalStateException("Não foi possível criar a pasta de arquivo")

                    java.nio.file.Files.move(old.toPath(), arch.toPath())
                } else if (!old.deleteRecursively()) {
                    throw IllegalStateException("Não foi possível remover a pasta local vazia")
                }
            }

            val state = stateFile(ctx, id)
            if (state.exists() && !state.delete())
                throw IllegalStateException("Não foi possível limpar o estado antigo da sincronização")

            val fresh = dir(ctx, id)
            if (!fresh.exists() && !fresh.mkdirs())
                throw IllegalStateException("Não foi possível criar a nova pasta de trabalho")
        }
    }

    fun deleteAll(ctx: Context, id: String) {
        synchronized(lockFor(id)) {
            val workspace = File(ctx.filesDir, "workspaces/$id")
            val state = stateFile(ctx, id)
            val report = reportFile(ctx, id)
            val trash = trashDir(ctx, id)

            if (workspace.exists() && !workspace.deleteRecursively())
                throw IllegalStateException("Não foi possível excluir a cópia local da conversa")
            if (state.exists() && !state.delete())
                throw IllegalStateException("Não foi possível excluir o estado da sincronização")
            if (report.exists() && !report.delete())
                throw IllegalStateException("Não foi possível excluir o relatório da conversa")
            if (trash.exists() && !trash.deleteRecursively())
                throw IllegalStateException("Não foi possível excluir a lixeira da conversa")
        }
    }
}

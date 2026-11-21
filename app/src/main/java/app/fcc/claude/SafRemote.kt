package app.fcc.ai

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Implementa [RemoteTree] sobre uma pasta escolhida com ACTION_OPEN_DOCUMENT_TREE (SAF).
 * Usa somente DocumentsContract/ContentResolver (nada de montar content:// no proot).
 */
class SafRemote(ctx: Context, private val tree: Uri) : RemoteTree {
    private val cr: ContentResolver = ctx.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val ids = HashMap<String, String>()   // caminho relativo -> documentId ("" = raiz)

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    override fun scan(ignore: (String) -> Boolean): Map<String, RemoteTree.Entry> {
        val out = LinkedHashMap<String, RemoteTree.Entry>()
        ids.clear(); ids[""] = rootId
        fun walk(parentId: String, prefix: String) {
            val dirs = ArrayList<Pair<String, String>>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            cr.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val mt = if (c.isNull(4)) 0L else c.getLong(4)
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (ignore(path)) continue
                    val isDir = mime == Document.MIME_TYPE_DIR
                    ids[path] = id
                    out[path] = RemoteTree.Entry(path, isDir, if (isDir) 0 else size, if (isDir) 0 else mt)
                    if (isDir) dirs.add(path to id)
                }
            }
            for ((p, id) in dirs) walk(id, p)
        }
        walk(rootId, "")
        return out
    }

    override fun readTo(path: String, dest: File) {
        val id = ids[path] ?: throw FileNotFoundException(path)
        dest.parentFile?.mkdirs()
        val input = cr.openInputStream(docUri(id)) ?: throw FileNotFoundException(path)
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    }

    override fun writeFrom(path: String, src: File): RemoteTree.Entry {
        val docId: String = ids[path] ?: createFile(path)
        val out = cr.openOutputStream(docUri(docId), "wt") ?: throw java.io.IOException("Não foi possível gravar '$path'")
        out.use { o -> src.inputStream().use { i -> i.copyTo(o, 1 shl 16) } }
        return entryOf(path, docId)
    }

    private fun createFile(path: String): String {
        val slash = path.lastIndexOf('/')
        val parent = if (slash >= 0) path.substring(0, slash) else ""
        val name = path.substring(slash + 1)
        val parentId = ensureDir(parent)
        val uri = DocumentsContract.createDocument(cr, docUri(parentId), mimeFor(name), name)
            ?: throw java.io.IOException("O provedor recusou criar '$path'")
        var docId: String = DocumentsContract.getDocumentId(uri)
        // alguns provedores acrescentam extensão conforme o tipo MIME: normaliza o nome
        val shown = displayName(docId)
        if (shown != null && shown != name) {
            val renamed = DocumentsContract.renameDocument(cr, docUri(docId), name)
            if (renamed != null) docId = DocumentsContract.getDocumentId(renamed)
        }
        ids[path] = docId
        return docId
    }

    override fun mkdir(path: String) { ensureDir(path) }

    override fun delete(path: String) {
        val id = ids[path] ?: return
        DocumentsContract.deleteDocument(cr, docUri(id))
        ids.keys.filter { it == path || it.startsWith("$path/") }.forEach { ids.remove(it) }
    }

    override fun move(from: String, to: String): RemoteTree.Entry {
        val id = ids[from] ?: throw FileNotFoundException(from)
        val fromParent = from.substringBeforeLast('/', "")
        val toParent = to.substringBeforeLast('/', "")
        val newName = to.substringAfterLast('/')
        var curId = id
        if (fromParent != toParent) {
            val srcParentId = ids[fromParent] ?: rootId
            val dstParentId = ensureDir(toParent)
            val moved = DocumentsContract.moveDocument(cr, docUri(curId), docUri(srcParentId), docUri(dstParentId))
                ?: throw UnsupportedOperationException("O provedor não suporta mover")
            curId = DocumentsContract.getDocumentId(moved)
        }
        if (from.substringAfterLast('/') != newName) {
            val renamed = DocumentsContract.renameDocument(cr, docUri(curId), newName)
                ?: throw UnsupportedOperationException("O provedor não suporta renomear")
            curId = DocumentsContract.getDocumentId(renamed)
        }
        ids.remove(from)
        ids[to] = curId
        return entryOf(to, curId)
    }

    // ------------------------------------------------------------------ auxiliares
    private fun ensureDir(path: String): String {
        if (path.isEmpty()) return rootId
        ids[path]?.let { return it }
        val parentId = ensureDir(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val uri = DocumentsContract.createDocument(cr, docUri(parentId), Document.MIME_TYPE_DIR, name)
            ?: throw java.io.IOException("O provedor recusou criar a pasta '$path'")
        val id = DocumentsContract.getDocumentId(uri)
        ids[path] = id
        return id
    }

    private fun displayName(id: String): String? =
        cr.query(docUri(id), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun entryOf(path: String, id: String): RemoteTree.Entry {
        cr.query(docUri(id), arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val size = if (c.isNull(0)) 0L else c.getLong(0)
                val mt = if (c.isNull(1)) 0L else c.getLong(1)
                return RemoteTree.Entry(path, false, size, mt)
            }
        }
        throw FileNotFoundException(path)
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

package app.fcc.claude

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class Conversation(
    val id: String,
    var name: String,
    var treeUri: String?,
    val createdAt: Long,
)

class ConversationStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "conversations.json")
    private val atomicFile = AtomicFile(file)

    val items = ArrayList<Conversation>()
    var activeId: String = ""

    init { load() }

    private fun newConversation(name: String) =
        Conversation(UUID.randomUUID().toString(), name, null, System.currentTimeMillis())

    private fun load() {
        if (file.exists()) {
            try {
                val j = JSONObject(atomicFile.openRead().bufferedReader().use { it.readText() })
                val arr = j.optJSONArray("items")
                    ?: throw IllegalStateException("Lista de conversas ausente")

                val loaded = ArrayList<Conversation>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    if (id.isBlank() || loaded.any { it.id == id })
                        throw IllegalStateException("ID de conversa inválido ou duplicado")

                    loaded.add(
                        Conversation(
                            id,
                            o.optString("name", "Conversa"),
                            if (o.isNull("tree")) null else o.optString("tree").ifEmpty { null },
                            o.optLong("created", System.currentTimeMillis())
                        )
                    )
                }
                if (loaded.isEmpty())
                    throw IllegalStateException("Arquivo não contém conversas")

                items.addAll(loaded)
                activeId = j.optString("active", "")
                if (items.none { it.id == activeId }) activeId = items[0].id
                return
            } catch (e: Exception) {
                val backup = File(ctx.filesDir, "conversations.corrupt-${System.currentTimeMillis()}.json")
                try {
                    java.nio.file.Files.move(file.toPath(), backup.toPath())
                    android.util.Log.e("FCC", "Arquivo de conversas inválido preservado em ${backup.name}", e)
                } catch (moveError: Exception) {
                    throw IllegalStateException(
                        "Não foi possível recuperar nem preservar conversations.json. Nenhum dado foi sobrescrito.",
                        moveError
                    )
                }
            }
        }

        items.add(newConversation("Conversa 1"))
        activeId = items[0].id
        save()
    }

    fun active(): Conversation =
        items.firstOrNull { it.id == activeId }
            ?: throw IllegalStateException("Conversa ativa inexistente")

    fun find(id: String): Conversation? = items.firstOrNull { it.id == id }

    fun add(name: String): Conversation {
        val c = newConversation(name.ifBlank { "Conversa ${items.size + 1}" })
        items.add(c)
        try {
            save()
        } catch (e: Exception) {
            items.remove(c)
            throw e
        }
        return c
    }

    fun remove(id: String) {
        val previous = items.toList()
        val previousActive = activeId
        items.removeAll { it.id == id }
        if (items.isEmpty()) items.add(newConversation("Conversa 1"))
        if (activeId == id) activeId = items[0].id
        try {
            save()
        } catch (e: Exception) {
            items.clear()
            items.addAll(previous)
            activeId = previousActive
            throw e
        }
    }

    fun setActive(id: String) {
        require(items.any { it.id == id }) { "Conversa inexistente: $id" }
        val previous = activeId
        activeId = id
        try {
            save()
        } catch (e: Exception) {
            activeId = previous
            throw e
        }
    }

    @Synchronized
    fun save() {
        require(items.isNotEmpty()) { "Não é permitido salvar uma lista vazia de conversas" }
        require(items.any { it.id == activeId }) { "Conversa ativa inválida" }

        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("tree", it.treeUri ?: JSONObject.NULL)
                    .put("created", it.createdAt)
            )
        }

        val bytes = JSONObject()
            .put("active", activeId)
            .put("items", arr)
            .toString()
            .toByteArray(Charsets.UTF_8)

        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) atomicFile.failWrite(stream)
            throw IllegalStateException("Falha ao salvar conversas; versão anterior preservada.", e)
        }
    }
}

package app.fcc.claude

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Uma conversa: identificador estável, nome e (opcionalmente) a pasta do Android escolhida via SAF. */
data class Conversation(
    val id: String,
    var name: String,
    var treeUri: String?,
    val createdAt: Long,
    var internal: Boolean = false,      // escolheu usar só a pasta interna do app (sem sincronizar)
    var treeName: String? = null,       // nome exibido da pasta escolhida
)

/** Persistência simples (JSON em filesDir) das conversas e de qual está ativa. */
class ConversationStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "conversations.json")
    val items = ArrayList<Conversation>()
    var activeId: String = ""

    init { load() }

    private fun load() {
        try {
            if (file.exists()) {
                val j = JSONObject(file.readText())
                activeId = j.optString("active", "")
                val arr = j.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(Conversation(
                        o.getString("id"), o.optString("name", "Conversa"),
                        if (o.isNull("tree")) null else o.optString("tree").ifEmpty { null },
                        o.optLong("created", System.currentTimeMillis()),
                        o.optBoolean("internal", false),
                        if (o.isNull("treeName")) null else o.optString("treeName").ifEmpty { null }))
                }
            }
        } catch (_: Exception) { items.clear() }
        if (items.isEmpty()) { items.add(newConversation("Conversa 1")); activeId = items[0].id; save() }
        if (items.none { it.id == activeId }) activeId = items[0].id
    }

    private fun newConversation(name: String) = Conversation(UUID.randomUUID().toString(), name, null, System.currentTimeMillis())

    fun active(): Conversation = items.first { it.id == activeId }
    fun find(id: String): Conversation? = items.firstOrNull { it.id == id }

    fun add(name: String): Conversation {
        val c = newConversation(name.ifBlank { "Conversa ${items.size + 1}" })
        items.add(c); save(); return c
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        if (items.isEmpty()) items.add(newConversation("Conversa 1"))
        if (activeId == id) activeId = items[0].id
        save()
    }

    fun setActive(id: String) { activeId = id; save() }

    fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("tree", it.treeUri ?: JSONObject.NULL).put("created", it.createdAt)
                .put("internal", it.internal).put("treeName", it.treeName ?: JSONObject.NULL))
        }
        val tmp = File(ctx.filesDir, "conversations.json.tmp")
        tmp.writeText(JSONObject().put("active", activeId).put("items", arr).toString())
        tmp.renameTo(file)
    }
}

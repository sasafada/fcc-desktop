package app.fcc.ai

import java.io.File
import java.util.UUID

/** Bloco de uma resposta: texto, atividade de ferramenta ou erro. */
class Block(
    var kind: String,                 // "text" | "tool" | "error"
    var text: String = "",
    var toolId: String = "",
    var name: String = "",
    var label: String = "",
    var detail: String = "",
    var status: String = "run",       // "run" | "ok" | "err"
    var output: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "kind" to kind, "text" to text, "toolId" to toolId, "name" to name,
        "label" to label, "detail" to detail, "status" to status, "output" to output)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>) = Block(
            m["kind"] as? String ?: "text", m["text"] as? String ?: "", m["toolId"] as? String ?: "",
            m["name"] as? String ?: "", m["label"] as? String ?: "", m["detail"] as? String ?: "",
            m["status"] as? String ?: "ok", m["output"] as? String ?: "")
    }
}

class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,                 // "user" | "assistant"
    val ts: Long = System.currentTimeMillis(),
    val blocks: MutableList<Block> = ArrayList(),
    var streaming: Boolean = false,
    var note: String = "",            // aviso transitório (ex.: "Reconectando ao provedor (3/10)…")
    var noteKind: String = "",        // "retry" | ""
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "role" to role, "ts" to ts, "streaming" to streaming, "note" to note, "noteKind" to noteKind,
        "blocks" to blocks.map { it.toMap() })

    fun plainText(): String = blocks.filter { it.kind == "text" }.joinToString("\n\n") { it.text }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): Message {
            val bl = (m["blocks"] as? List<Any?>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(Block::fromMap) }
            return Message(m["id"] as? String ?: UUID.randomUUID().toString(), m["role"] as? String ?: "assistant",
                (m["ts"] as? Number)?.toLong() ?: 0L, bl.toMutableList(), false)
        }
    }
}

/** Aplica [ChatEvent]s a uma mensagem da IA. Retorna true se algo mudou. */
class MessageBuilder(val msg: Message) {
    private var needSep = false
    var sessionId: String? = null
    var finished = false

    fun apply(ev: ChatEvent): Boolean {
        when (ev) {
            is ChatEvent.Session -> { sessionId = ev.id; return false }
            is ChatEvent.TurnStart -> { needSep = true; return false }
            is ChatEvent.Retry -> {
                msg.note = "Reconectando ao provedor (tentativa ${ev.attempt}/${ev.max}, erro ${ev.status})…"
                msg.noteKind = "retry"
                return true
            }
            is ChatEvent.Text -> {
                msg.note = ""; msg.noteKind = ""
                val last = msg.blocks.lastOrNull()
                if (last != null && last.kind == "text") {
                    if (needSep && last.text.isNotEmpty()) last.text += "\n\n"
                    last.text += ev.text
                } else msg.blocks.add(Block("text", ev.text))
                needSep = false
                return true
            }
            is ChatEvent.ToolStart -> {
                if (msg.blocks.none { it.kind == "tool" && it.toolId == ev.id })
                    msg.blocks.add(Block("tool", toolId = ev.id, name = ev.name, label = ev.name))
                return true
            }
            is ChatEvent.ToolInfo -> {
                val b = msg.blocks.firstOrNull { it.kind == "tool" && it.toolId == ev.id }
                    ?: Block("tool", toolId = ev.id).also { msg.blocks.add(it) }
                b.name = ev.name; b.label = ev.label; b.detail = ev.detail
                return true
            }
            is ChatEvent.ToolResult -> {
                val b = msg.blocks.firstOrNull { it.kind == "tool" && it.toolId == ev.id } ?: return false
                b.status = if (ev.ok) "ok" else "err"; b.output = ev.output
                return true
            }
            is ChatEvent.Done -> {
                ev.sessionId?.let { sessionId = it }
                // ferramentas que não receberam resultado terminam como "ok"
                msg.blocks.filter { it.kind == "tool" && it.status == "run" }.forEach { it.status = "ok" }
                if (ev.isError) msg.blocks.add(Block("error", text = ev.text.ifBlank { "Não foi possível concluir a resposta." }))
                else if (msg.blocks.none { it.kind == "text" && it.text.isNotBlank() } && ev.text.isNotBlank())
                    msg.blocks.add(Block("text", ev.text))
                finished = true; msg.streaming = false; msg.note = ""; msg.noteKind = ""
                return true
            }
            is ChatEvent.Log -> return false
        }
    }
}

/** Histórico de uma conversa em arquivo JSON (filesDir/chats/{id}.json). */
class ChatStore(private val dir: File) {
    init { dir.mkdirs() }
    private fun file(id: String) = File(dir, "$id.json")

    @Suppress("UNCHECKED_CAST")
    fun load(id: String): MutableList<Message> {
        val f = file(id)
        if (!f.exists()) return ArrayList()
        return try {
            (MiniJson.parse(f.readText()) as? List<Any?>).orEmpty()
                .mapNotNull { (it as? Map<String, Any?>)?.let(Message::fromMap) }.toMutableList()
        } catch (_: Exception) { ArrayList() }
    }

    fun save(id: String, msgs: List<Message>) {
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(MiniJson.stringify(msgs.map { it.toMap() }))
        if (!tmp.renameTo(file(id))) { file(id).delete(); tmp.renameTo(file(id)) }
    }

    fun delete(id: String) { file(id).delete() }
}

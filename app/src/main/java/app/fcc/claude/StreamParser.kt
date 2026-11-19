package app.fcc.claude

/** Eventos de alto nível extraídos do `claude -p --output-format stream-json`. */
sealed class ChatEvent {
    data class Session(val id: String) : ChatEvent()
    object TurnStart : ChatEvent()
    data class Text(val text: String) : ChatEvent()
    data class ToolStart(val id: String, val name: String) : ChatEvent()
    data class ToolInfo(val id: String, val name: String, val label: String, val detail: String) : ChatEvent()
    data class ToolResult(val id: String, val ok: Boolean, val output: String) : ChatEvent()
    data class Done(val isError: Boolean, val text: String, val sessionId: String?, val costUsd: Double?, val durationMs: Long?) : ChatEvent()
    data class Retry(val attempt: Int, val max: Int, val status: Int, val error: String) : ChatEvent()
    data class Log(val line: String) : ChatEvent()
}

/** Converte linhas JSON do Claude Code em [ChatEvent]. Linhas que não são JSON viram [ChatEvent.Log]. */
class StreamParser {
    private var streamed = 0
    private var turns = 0

    @Suppress("UNCHECKED_CAST")
    fun parse(line: String): List<ChatEvent> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        if (!t.startsWith("{")) return listOf(ChatEvent.Log(t))
        val d = try { MiniJson.parse(t) as? Map<String, Any?> } catch (_: Exception) { null } ?: return listOf(ChatEvent.Log(t))
        val out = ArrayList<ChatEvent>()
        when (d["type"]) {
            "system" -> when (d["subtype"]) {
                "init" -> (d["session_id"] as? String)?.let { out.add(ChatEvent.Session(it)) }
                "api_retry" -> out.add(ChatEvent.Retry(
                    (d["attempt"] as? Number)?.toInt() ?: 0, (d["max_retries"] as? Number)?.toInt() ?: 0,
                    (d["error_status"] as? Number)?.toInt() ?: 0, d["error"] as? String ?: ""))
            }
            "stream_event" -> {
                val e = d["event"] as? Map<String, Any?> ?: return out
                when (e["type"]) {
                    "message_start" -> { streamed = 0; if (turns++ > 0) out.add(ChatEvent.TurnStart) }
                    "content_block_start" -> {
                        val cb = e["content_block"] as? Map<String, Any?>
                        if (cb?.get("type") == "tool_use") out.add(ChatEvent.ToolStart(cb["id"] as? String ?: "", cb["name"] as? String ?: "tool"))
                    }
                    "content_block_delta" -> {
                        val dl = e["delta"] as? Map<String, Any?>
                        if (dl?.get("type") == "text_delta") {
                            val tx = dl["text"] as? String ?: ""
                            if (tx.isNotEmpty()) { streamed += tx.length; out.add(ChatEvent.Text(tx)) }
                        }
                    }
                }
            }
            "assistant" -> {
                val msg = d["message"] as? Map<String, Any?> ?: return out
                for (b in (msg["content"] as? List<Any?>).orEmpty()) {
                    val blk = b as? Map<String, Any?> ?: continue
                    when (blk["type"]) {
                        "text" -> { val tx = blk["text"] as? String ?: ""; if (streamed == 0 && tx.isNotEmpty()) { streamed += tx.length; out.add(ChatEvent.Text(tx)) } }
                        "tool_use" -> {
                            val name = blk["name"] as? String ?: "tool"
                            val input = blk["input"] as? Map<String, Any?> ?: emptyMap()
                            val (label, detail) = describeTool(name, input)
                            out.add(ChatEvent.ToolInfo(blk["id"] as? String ?: "", name, label, detail))
                        }
                    }
                }
            }
            "user" -> {
                val msg = d["message"] as? Map<String, Any?> ?: return out
                val c = msg["content"]
                if (c is List<*>) for (b in c) {
                    val blk = b as? Map<String, Any?> ?: continue
                    if (blk["type"] == "tool_result") {
                        val isErr = blk["is_error"] == true
                        out.add(ChatEvent.ToolResult(blk["tool_use_id"] as? String ?: "", !isErr, flatten(blk["content"])))
                    }
                }
            }
            "result" -> out.add(ChatEvent.Done(
                d["is_error"] == true, d["result"] as? String ?: "", d["session_id"] as? String,
                (d["total_cost_usd"] as? Number)?.toDouble(), (d["duration_ms"] as? Number)?.toLong()))
        }
        return out
    }

    private fun flatten(c: Any?): String {
        val s = when (c) {
            is String -> c
            is List<*> -> c.joinToString("\n") { x -> ((x as? Map<*, *>)?.get("text") as? String) ?: "" }
            else -> ""
        }
        return if (s.length > 4000) s.take(4000) + "\n… (saída cortada)" else s
    }

    companion object {
        /** Texto amigável (rótulo, detalhe) para uma ferramenta do Claude Code. */
        fun describeTool(name: String, input: Map<String, Any?>): Pair<String, String> {
            fun str(vararg keys: String): String = keys.firstNotNullOfOrNull { (input[it] as? String)?.takeIf { v -> v.isNotBlank() } } ?: ""
            fun path(p: String) = p.removePrefix("/workspace/").removePrefix("/workspace")
            return when (name) {
                "Write" -> "Criando arquivo" to path(str("file_path"))
                "Edit", "MultiEdit", "NotebookEdit" -> "Editando arquivo" to path(str("file_path", "notebook_path"))
                "Read" -> "Lendo arquivo" to path(str("file_path"))
                "Bash", "BashOutput" -> "Executando comando" to str("command").take(160)
                "Glob" -> "Procurando arquivos" to str("pattern")
                "Grep" -> "Buscando no código" to str("pattern")
                "LS" -> "Listando pasta" to path(str("path"))
                "WebFetch" -> "Acessando página" to str("url")
                "WebSearch" -> "Pesquisando na web" to str("query")
                "Task", "Agent" -> "Delegando tarefa" to str("description")
                "TodoWrite" -> "Atualizando lista de tarefas" to ""
                else -> name to str("file_path", "command", "pattern", "url", "description").take(160)
            }
        }
    }
}

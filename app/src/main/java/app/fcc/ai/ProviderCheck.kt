package app.fcc.ai

import java.net.HttpURLConnection
import java.net.URL

/**
 * Verificação rápida ANTES de gerar uma resposta: o provedor do modelo padrão do FCC está configurado?
 * Sem isso o Claude Code tenta 10 vezes (erro 503) em silêncio. Se não der para consultar, não bloqueia.
 */
object ProviderCheck {
    data class Result(val ready: Boolean, val message: String = "")

    /** Lê a configuração do FCC Server (somente local). */
    fun check(port: Int): Result = try {
        val c = URL("http://127.0.0.1:$port/admin/api/config").openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 5000
        val body = try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else "" } finally { c.disconnect() }
        if (body.isEmpty()) Result(true) else evaluate(body)
    } catch (_: Exception) { Result(true) }

    @Suppress("UNCHECKED_CAST")
    fun evaluate(configJson: String): Result {
        val root = try { MiniJson.parse(configJson) as? Map<String, Any?> } catch (_: Exception) { null } ?: return Result(true)
        val fields = root["fields"] as? List<Any?> ?: return Result(true)
        val model = fields.firstNotNullOfOrNull { f ->
            (f as? Map<String, Any?>)?.takeIf { it["key"] == "MODEL" }?.get("value") as? String
        }?.takeIf { it.contains('/') } ?: return Result(true)
        val providerId = model.substringBefore('/')
        val entry = (root["provider_status"] as? List<Any?>)?.firstNotNullOfOrNull { p ->
            (p as? Map<String, Any?>)?.takeIf { it["provider_id"] == providerId }
        } ?: return Result(true)
        if (entry["status"] == "configured") return Result(true)
        val name = entry["display_name"] as? String ?: providerId
        val what = when (entry["status"]) {
            "missing_key" -> "ainda não tem chave de API"
            "missing_url" -> "ainda não tem o endereço configurado"
            else -> "ainda não está configurado"
        }
        return Result(false, "O provedor do modelo padrão ($name) $what. Abra “Provedores e APIs” para conectar um provedor ou entrar com sua conta, e escolha o modelo.")
    }
}

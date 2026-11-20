package app.fcc.claude

/** JSON mínimo (sem dependências): parse -> Map/List/String/Long/Double/Boolean/null e stringify. */
object MiniJson {
    fun parse(s: String): Any? {
        val p = P(s); p.ws()
        val v = p.value(); p.ws()
        if (p.i != s.length) throw IllegalArgumentException("JSON com lixo no final")
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("fim inesperado")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("caractere inválido '$c' em $i")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw IllegalArgumentException("literal inválido em $i")
            i += w.length; return v
        }
        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
        fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("string não terminada")
                val c = s[i++]
                if (c == '"') break
                if (c == '\\') {
                    val e = s[i++]
                    when (e) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        '/' -> sb.append('/'); '\\' -> sb.append('\\'); '"' -> sb.append('"')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalArgumentException("escape inválido \\$e")
                    }
                } else sb.append(c)
            }
            return sb.toString()
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("array inválido em $i") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); if (s[i] != '"') throw IllegalArgumentException("chave inválida em $i")
                val k = str(); ws()
                if (s[i++] != ':') throw IllegalArgumentException("esperado ':' em $i")
                ws(); out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("objeto inválido em $i") }
            }
        }
    }

    fun stringify(v: Any?): String { val sb = StringBuilder(); write(sb, v); return sb.toString() }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> { val d = (v as Number).toDouble(); if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString()) }
            is Map<*, *> -> {
                sb.append('{'); var first = true
                for ((k, x) in v) { if (!first) sb.append(','); first = false; quote(sb, k.toString()); sb.append(':'); write(sb, x) }
                sb.append('}')
            }
            is Iterable<*> -> { sb.append('['); var first = true; for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }; sb.append(']') }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == '\u2028' || c == '\u2029' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

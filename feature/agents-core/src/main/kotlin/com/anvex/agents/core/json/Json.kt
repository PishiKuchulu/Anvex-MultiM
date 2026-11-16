package com.anvex.agents.core.json

sealed interface JsonValue
data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue
data class JsonArray(val items: List<JsonValue>) : JsonValue
data class JsonString(val value: String) : JsonValue
data class JsonNumber(val raw: String) : JsonValue {
    /** Exact integer or null when the number has a fraction/exponent or does not fit a Long. */
    fun toLongOrNull(): Long? = if (raw.any { it == '.' || it == 'e' || it == 'E' }) null else raw.toLongOrNull()
    fun toDouble(): Double = raw.toDouble()
}
data class JsonBool(val value: Boolean) : JsonValue
data object JsonNull : JsonValue

class JsonParseException(message: String, val offset: Int) : RuntimeException("$message at offset $offset")

/**
 * Strict RFC 8259 parser for untrusted model output (ADR-008): no comments, no trailing commas, no NaN/Infinity,
 * no duplicate object keys (ambiguity attack), no raw control characters in strings, no lone surrogates, bounded
 * length and nesting. Anything else is an error, never "best effort".
 */
object Json {
    const val MAX_LENGTH = 1_000_000
    const val MAX_DEPTH = 32
    const val MAX_ENTRIES = 20_000

    fun parse(text: String): JsonValue {
        if (text.length > MAX_LENGTH) throw JsonParseException("input too long", 0)
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (!p.end()) throw p.err("trailing data")
        return v
    }

    fun write(v: JsonValue): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: JsonValue, sb: StringBuilder) {
        when (v) {
            is JsonNull -> sb.append("null")
            is JsonBool -> sb.append(v.value)
            is JsonNumber -> sb.append(v.raw)
            is JsonString -> quote(v.value, sb)
            is JsonArray -> { sb.append('['); v.items.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(x, sb) }; sb.append(']') }
            is JsonObject -> { sb.append('{'); var first = true; for ((k, x) in v.fields) { if (!first) sb.append(','); first = false; quote(k, sb); sb.append(':'); write(x, sb) }; sb.append('}') }
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        var entries = 0
        fun err(m: String) = JsonParseException(m, i)
        fun end() = i >= s.length
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw err("nesting too deep")
            if (end()) throw err("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonString(str())
                't' -> lit("true", JsonBool(true))
                'f' -> lit("false", JsonBool(false))
                'n' -> lit("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') num() else throw err("unexpected character")
            }
        }

        fun lit(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) throw err("invalid literal")
            i += word.length
            if (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) throw err("invalid literal")
            return v
        }

        fun count() { if (++entries > MAX_ENTRIES) throw err("too many entries") }

        fun obj(depth: Int): JsonValue {
            i++; ws()
            val m = LinkedHashMap<String, JsonValue>()
            if (peek() == '}') { i++; return JsonObject(m) }
            while (true) {
                ws(); if (peek() != '"') throw err("object key must be a string")
                val k = str(); ws()
                if (peek() != ':') throw err("expected ':'"); i++; ws()
                count()
                if (m.put(k, value(depth + 1)) != null) throw err("duplicate key")
                ws()
                when (peek()) { ',' -> i++; '}' -> { i++; return JsonObject(m) }; else -> throw err("expected ',' or '}'") }
            }
        }

        fun arr(depth: Int): JsonValue {
            i++; ws()
            val l = ArrayList<JsonValue>()
            if (peek() == ']') { i++; return JsonArray(l) }
            while (true) {
                ws(); count(); l += value(depth + 1); ws()
                when (peek()) { ',' -> i++; ']' -> { i++; return JsonArray(l) }; else -> throw err("expected ',' or ']'") }
            }
        }

        fun peek(): Char = if (end()) throw err("unexpected end") else s[i]

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (end()) throw err("unterminated string")
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++; if (end()) throw err("unterminated escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { val cu = hex4(i + 1); i += 4; appendUnit(sb, cu) }
                            else -> throw err("invalid escape '\\$e'")
                        }
                        i++
                    }
                    c.code < 0x20 -> throw err("raw control character in string")
                    Character.isSurrogate(c) -> {
                        if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { sb.append(c).append(s[i + 1]); i += 2 }
                        else throw err("lone surrogate")
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        fun hex4(at: Int): Int {
            if (at + 4 > s.length) throw err("truncated unicode escape")
            var v = 0
            for (k in 0 until 4) { val d = Character.digit(s[at + k], 16); if (d < 0 || s[at + k].code > 0x7F) throw err("invalid unicode escape"); v = v * 16 + d }
            return v
        }

        fun appendUnit(sb: StringBuilder, cu: Int) {
            if (cu in 0xD800..0xDBFF) {
                if (i + 6 < s.length + 0 && s[i + 1] == '\\' && s[i + 2] == 'u') {
                    val lo = hex4(i + 3)
                    if (lo !in 0xDC00..0xDFFF) throw err("invalid surrogate pair")
                    sb.append(cu.toChar()).append(lo.toChar()); i += 6
                } else throw err("lone high surrogate")
            } else if (cu in 0xDC00..0xDFFF) throw err("lone low surrogate")
            else sb.append(cu.toChar())
        }

        fun num(): JsonValue {
            val st = i
            if (s[i] == '-') i++
            if (end()) throw err("invalid number")
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (!end() && s[i] in '0'..'9') i++ } else throw err("invalid number")
            if (!end() && s[i] == '.') { i++; val d = i; while (!end() && s[i] in '0'..'9') i++; if (i == d) throw err("digits expected after '.'") }
            if (!end() && (s[i] == 'e' || s[i] == 'E')) { i++; if (!end() && (s[i] == '+' || s[i] == '-')) i++; val d = i; while (!end() && s[i] in '0'..'9') i++; if (i == d) throw err("digits expected in exponent") }
            return JsonNumber(s.substring(st, i))
        }
    }
}

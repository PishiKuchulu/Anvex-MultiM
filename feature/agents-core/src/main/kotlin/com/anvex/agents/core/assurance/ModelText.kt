package com.anvex.agents.core.assurance

/** Helpers for text produced by a model (untrusted). */
object ModelText {
    const val MAX_LENGTH = 1_000_000

    /**
     * Extracts the first balanced JSON object, preferring a fenced block (three backticks + "json"). The scan is aware of
     * strings and escapes, never evaluates anything, and returns null when no balanced object exists. The result must still
     * pass the strict [com.anvex.agents.core.json.Json] parser before any use.
     */
    fun extractJsonObject(text: String): String? {
        if (text.length > MAX_LENGTH) return null
        val fence = Regex("```[ \\t]*json[ \\t]*\\r?\\n", RegexOption.IGNORE_CASE).find(text)
        if (fence != null) {
            val body = text.substring(fence.range.last + 1)
            val end = body.indexOf("```")
            val inner = if (end >= 0) body.substring(0, end) else body
            balanced(inner)?.let { return it }
        }
        return balanced(text)
    }

    private fun balanced(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inString = false; var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> inString = false }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }
}

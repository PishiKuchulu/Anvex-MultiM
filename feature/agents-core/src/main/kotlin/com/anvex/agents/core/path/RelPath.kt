package com.anvex.agents.core.path

/**
 * Root-relative, canonical path used everywhere in the engine (ADR-004).
 *
 * A string is accepted only if it is already in canonical form, i.e. exactly what Anvex
 * `PathSanitizer.sanitize` would return for it. That makes RelPath a strict subset of what the host
 * accepts: no `..`, no `.`/empty segments, no backslashes, no leading/trailing whitespace, no
 * absolute or drive-letter form, no NUL/control/bidi characters. Two extra limits apply: a segment
 * is at most 255 chars (same as the host) and the whole path at most [MAX_TOTAL] chars.
 *
 * Deliberately no dependency on java.io.File / java.nio.file / Uri (architecture guard).
 */
@JvmInline
value class RelPath private constructor(val value: String) : Comparable<RelPath> {

    val isRoot: Boolean get() = value.isEmpty()
    val segments: List<String> get() = if (value.isEmpty()) emptyList() else value.split('/')
    val name: String get() = value.substringAfterLast('/')

    val parent: RelPath?
        get() = when {
            value.isEmpty() -> null
            !value.contains('/') -> ROOT
            else -> RelPath(value.substringBeforeLast('/'))
        }

    fun resolve(child: String): RelPath = of(if (value.isEmpty()) child else "$value/$child")

    fun startsWith(prefix: RelPath): Boolean =
        prefix.isRoot || value == prefix.value || value.startsWith(prefix.value + "/")

    override fun compareTo(other: RelPath): Int = value.compareTo(other.value)
    override fun toString(): String = value

    companion object {
        const val MAX_TOTAL = 4096
        const val MAX_SEGMENT = 255
        val ROOT: RelPath = RelPath("")

        /** Returns the path or throws [IllegalArgumentException] with the reason. */
        fun of(raw: String): RelPath =
            parse(raw) ?: throw IllegalArgumentException("Invalid path: ${check(raw) ?: "unknown"}")

        fun parse(raw: String): RelPath? = if (check(raw) == null) RelPath(raw) else null

        /** Null when valid, otherwise a short machine-readable reason. */
        fun check(raw: String): String? {
            if (raw.isEmpty()) return "empty"
            if (raw.length > MAX_TOTAL) return "too-long"
            if (raw.indexOf('\u0000') >= 0) return "nul"
            if (raw != raw.trim()) return "outer-whitespace"
            if (raw.indexOf('\\') >= 0) return "backslash"
            if (raw.startsWith("/")) return "absolute"
            if (raw.length >= 2 && raw[1] == ':' && (raw[0] in 'A'..'Z' || raw[0] in 'a'..'z')) return "drive-letter"
            for (seg in raw.split('/')) {
                if (seg.isEmpty()) return "empty-segment"
                if (seg == ".") return "dot-segment"
                if (seg == "..") return "dotdot-segment"
                if (seg.length > MAX_SEGMENT) return "segment-too-long"
                for (c in seg) {
                    val code = c.code
                    if (code < 0x20 || code == 0x7F || code in 0x202A..0x202E || code in 0x2066..0x2069) return "control-or-bidi"
                }
            }
            return null
        }
    }
}

package com.anvex.agents.core.path

/**
 * Minimal segment-based glob: `**` matches any number of whole segments, `*` any run of characters
 * inside one segment, `?` exactly one character inside one segment. No regex (no backtracking blow-up).
 * Patterns are root-relative like RelPath; a pattern without wildcards names a file or a directory
 * (a directory pattern covers everything below it).
 */
object Glob {
    fun isValid(pattern: String): Boolean {
        if (pattern.isEmpty() || pattern.length > RelPath.MAX_TOTAL) return false
        if (pattern.startsWith("/") || pattern.contains('\\') || pattern.contains('\u0000')) return false
        return pattern.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** True when [path] is covered by [pattern]. Invalid patterns match nothing (fail closed). */
    fun matches(pattern: String, path: RelPath): Boolean {
        if (!isValid(pattern)) return false
        return matchSegments(pattern.split('/'), 0, path.segments, 0)
    }

    fun matchesAny(patterns: Collection<String>, path: RelPath): Boolean = patterns.any { matches(it, path) }

    private fun matchSegments(pat: List<String>, pi: Int, seg: List<String>, si: Int): Boolean {
        var p = pi
        var s = si
        while (true) {
            if (p == pat.size) return true // pattern exhausted: it named this path or a directory above it
            if (pat[p] == "**") {
                if (p == pat.size - 1) return true
                for (k in s..seg.size) if (matchSegments(pat, p + 1, seg, k)) return true
                return false
            }
            if (s == seg.size) return false
            if (!matchSegment(pat[p], seg[s])) return false
            p++; s++
        }
    }

    private fun matchSegment(pattern: String, text: String): Boolean {
        var pi = 0; var ti = 0; var star = -1; var mark = 0
        while (ti < text.length) {
            if (pi < pattern.length && (pattern[pi] == '?' || pattern[pi] == text[ti])) { pi++; ti++ }
            else if (pi < pattern.length && pattern[pi] == '*') { star = pi++; mark = ti }
            else if (star >= 0) { pi = star + 1; ti = ++mark }
            else return false
        }
        while (pi < pattern.length && pattern[pi] == '*') pi++
        return pi == pattern.length
    }

    /**
     * Conservative overlap test: may return true for patterns that never match a common path, never
     * returns false for patterns that do. Used only to serialize tasks (the merge step protects data).
     */
    fun mayOverlap(a: String, b: String): Boolean {
        if (!isValid(a) || !isValid(b)) return true // unknown => assume overlap
        val x = a.split('/'); val y = b.split('/')
        var i = 0
        while (i < x.size && i < y.size) {
            if (x[i] == "**" || y[i] == "**") return true
            if (!segmentsMayOverlap(x[i], y[i])) return false
            i++
        }
        return true // one is a prefix (directory) of the other, or equal
    }

    private fun segmentsMayOverlap(a: String, b: String): Boolean {
        val wa = a.any { it == '*' || it == '?' }; val wb = b.any { it == '*' || it == '?' }
        if (!wa && !wb) return a == b
        if (!wa) return matchSegment(b, a)
        if (!wb) return matchSegment(a, b)
        return true
    }

    fun anyMayOverlap(a: Collection<String>, b: Collection<String>): Boolean =
        a.any { x -> b.any { y -> mayOverlap(x, y) } }
}

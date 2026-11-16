package com.anvex.agents.core.merge

import com.anvex.agents.core.changeset.Limits

enum class ConflictKind { OVERLAP, BINARY, DELETE_EDIT, ADD_ADD, TOO_LARGE, TYPE_CLASH }

sealed interface MergeResult {
    /** [bytes] == null means the file is absent in the merged result (deleted). */
    class Clean(val bytes: ByteArray?) : MergeResult {
        override fun equals(other: Any?) = other is Clean && (bytes == null && other.bytes == null || bytes != null && other.bytes != null && bytes.contentEquals(other.bytes))
        override fun hashCode() = bytes?.contentHashCode() ?: 0
        override fun toString() = if (bytes == null) "Clean(absent)" else "Clean(${bytes.size} bytes)"
    }
    /** [baseLines] are 1-based inclusive line ranges of the base that conflict (text merges only). */
    data class Conflict(val kind: ConflictKind, val detail: String, val baseLines: List<IntRange> = emptyList()) : MergeResult
}

/**
 * Line-based three-way merge, ADR-006. Automatic only for non-overlapping regions; hunks that touch the
 * same or adjacent base lines conflict. Line terminators stay attached to their line, so CRLF/LF
 * differences are ordinary edits and are never normalized. Edge-case table: 04_ARCHITECTURE/03_backend_architecture.md.
 *
 * base == null: file absent in the base. ours/theirs == null: file absent on that side.
 */
object ThreeWayMerge {

    fun merge(base: ByteArray?, ours: ByteArray?, theirs: ByteArray?): MergeResult {
        fun same(a: ByteArray?, b: ByteArray?) = (a == null && b == null) || (a != null && b != null && a.contentEquals(b))
        if (base == null) {
            return when {
                ours == null && theirs == null -> MergeResult.Clean(null)
                ours == null -> MergeResult.Clean(theirs)
                theirs == null -> MergeResult.Clean(ours)
                same(ours, theirs) -> MergeResult.Clean(ours)
                else -> MergeResult.Conflict(ConflictKind.ADD_ADD, "both sides added this file with different content")
            }
        }
        if (ours == null || theirs == null) {
            return when {
                ours == null && theirs == null -> MergeResult.Clean(null)
                ours == null -> if (same(theirs, base)) MergeResult.Clean(null) else MergeResult.Conflict(ConflictKind.DELETE_EDIT, "deleted on our side, edited on theirs")
                else -> if (same(ours, base)) MergeResult.Clean(null) else MergeResult.Conflict(ConflictKind.DELETE_EDIT, "deleted on their side, edited on ours")
            }
        }
        if (same(ours, theirs)) return MergeResult.Clean(ours)
        if (same(ours, base)) return MergeResult.Clean(theirs)
        if (same(theirs, base)) return MergeResult.Clean(ours)
        val bt = decode(base); val ot = decode(ours); val tt = decode(theirs)
        if (bt == null || ot == null || tt == null) return MergeResult.Conflict(ConflictKind.BINARY, "binary, non-UTF-8 or oversized content changed on both sides")
        return mergeText(bt, ot, tt)
    }

    private fun decode(bytes: ByteArray): String? {
        if (bytes.size > Limits.MAX_MERGE_BYTES || bytes.any { it == 0.toByte() } || !Utf8.isValid(bytes)) return null
        return String(bytes, Charsets.UTF_8)
    }

    private class Hunk(val start: Int, val end: Int, val lines: List<Int>)
    private class Tagged(val side: Int, val h: Hunk)

    internal fun splitLines(text: String): List<String> {
        val out = ArrayList<String>(); var s = 0
        while (s < text.length) {
            val i = text.indexOf('\n', s)
            if (i < 0) { out.add(text.substring(s)); break }
            out.add(text.substring(s, i + 1)); s = i + 1
        }
        return out
    }

    private fun mergeText(base: String, ours: String, theirs: String): MergeResult {
        val ids = HashMap<String, Int>(); val table = ArrayList<String>()
        fun intern(lines: List<String>) = IntArray(lines.size) { ids.getOrPut(lines[it]) { table.add(lines[it]); table.size - 1 } }
        val a = intern(splitLines(base)); val o = intern(splitLines(ours)); val t = intern(splitLines(theirs))
        val hO = hunks(a, o) ?: return MergeResult.Conflict(ConflictKind.TOO_LARGE, "our side differs from the base by more than ${Limits.MAX_EDIT_DISTANCE} edits")
        val hT = hunks(a, t) ?: return MergeResult.Conflict(ConflictKind.TOO_LARGE, "their side differs from the base by more than ${Limits.MAX_EDIT_DISTANCE} edits")
        val all = (hO.map { Tagged(0, it) } + hT.map { Tagged(1, it) }).sortedWith(compareBy({ it.h.start }, { it.h.end }, { it.side }))
        val out = ArrayList<Int>(a.size)
        val conflicts = ArrayList<IntRange>()
        var pos = 0; var i = 0
        while (i < all.size) {
            val cs = all[i].h.start; var ce = all[i].h.end; var j = i + 1
            while (j < all.size && all[j].h.start <= ce) { ce = maxOf(ce, all[j].h.end); j++ }
            for (k in pos until cs) out.add(a[k])
            val group = all.subList(i, j)
            val mine = group.filter { it.side == 0 }.map { it.h }; val theirsH = group.filter { it.side == 1 }.map { it.h }
            val chosen: List<Int>? = when {
                theirsH.isEmpty() -> sideText(a, mine, cs, ce)
                mine.isEmpty() -> sideText(a, theirsH, cs, ce)
                else -> { val x = sideText(a, mine, cs, ce); val y = sideText(a, theirsH, cs, ce); if (x == y) x else null }
            }
            if (chosen == null) conflicts.add((cs + 1)..maxOf(cs + 1, ce)) else out.addAll(chosen)
            pos = ce; i = j
        }
        if (conflicts.isNotEmpty()) return MergeResult.Conflict(ConflictKind.OVERLAP, "${conflicts.size} overlapping region(s)", conflicts)
        for (k in pos until a.size) out.add(a[k])
        val sb = StringBuilder(); for (id in out) sb.append(table[id])
        return MergeResult.Clean(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sideText(base: IntArray, hs: List<Hunk>, cs: Int, ce: Int): List<Int> {
        val r = ArrayList<Int>(); var p = cs
        for (h in hs.sortedBy { it.start }) { for (k in p until h.start) r.add(base[k]); r.addAll(h.lines); p = h.end }
        for (k in p until ce) r.add(base[k])
        return r
    }

    /** Hunks turning [a] into [b]; null when the edit distance exceeds the cap. */
    private fun hunks(a: IntArray, b: IntArray): List<Hunk>? {
        var pre = 0; while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0; while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.copyOfRange(pre, a.size - suf); val bm = b.copyOfRange(pre, b.size - suf)
        if (am.isEmpty() && bm.isEmpty()) return emptyList()
        if (am.isEmpty() || bm.isEmpty()) return listOf(Hunk(pre, pre + am.size, bm.toList()))
        val matches = myers(am, bm) ?: return null
        val out = ArrayList<Hunk>(); var pa = 0; var pb = 0
        fun gap(ai: Int, bi: Int) { if (ai > pa || bi > pb) out.add(Hunk(pre + pa, pre + ai, bm.slice(pb until bi))) }
        for ((ai, bi) in matches) { gap(ai, bi); pa = ai + 1; pb = bi + 1 }
        gap(am.size, bm.size)
        return out
    }

    /** Myers O(ND) shortest edit script; returns the matched index pairs (a[i] == b[j]) in order. */
    internal fun myers(a: IntArray, b: IntArray): List<Pair<Int, Int>>? {
        val n = a.size; val m = b.size
        val maxD = minOf(n + m, Limits.MAX_EDIT_DISTANCE)
        val off = maxD + 1
        val v = IntArray(2 * maxD + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        loop@ for (d in 0..maxD) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) { found = d; break@loop }
                k += 2
            }
        }
        if (found < 0) return null
        val matches = ArrayList<Pair<Int, Int>>()
        var x = n; var y = m
        for (d in found downTo 1) {
            val vv = trace[d]; val k = x - y
            val prevK = if (k == -d || (k != d && vv[off + k - 1] < vv[off + k + 1])) k + 1 else k - 1
            val prevX = vv[off + prevK]; val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y--; matches.add(x to y) }
            x = prevX; y = prevY
        }
        while (x > 0 && y > 0) { x--; y--; matches.add(x to y) }
        matches.reverse()
        return matches
    }
}

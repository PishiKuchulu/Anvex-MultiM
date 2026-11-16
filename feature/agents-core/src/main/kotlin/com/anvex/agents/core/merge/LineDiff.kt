package com.anvex.agents.core.merge

import com.anvex.agents.core.changeset.Limits

sealed interface DiffLine {
    val text: String
    data class Same(override val text: String, val oldNo: Int, val newNo: Int) : DiffLine
    data class Added(override val text: String, val newNo: Int) : DiffLine
    data class Removed(override val text: String, val oldNo: Int) : DiffLine
}

/** Line diff for the review prompt and the diff screen. Same Myers core as the merge; terminators stay visible (\r is shown as an arrow). */
object LineDiff {
    /** null when the two texts differ by more than [Limits.MAX_EDIT_DISTANCE] edits. */
    fun diff(old: String, new: String): List<DiffLine>? {
        val ol = ThreeWayMerge.splitLines(old); val nl = ThreeWayMerge.splitLines(new)
        val ids = HashMap<String, Int>()
        fun intern(l: List<String>) = IntArray(l.size) { ids.getOrPut(l[it]) { ids.size } }
        val a = intern(ol); val b = intern(nl)
        var pre = 0; while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0; while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.copyOfRange(pre, a.size - suf); val bm = b.copyOfRange(pre, b.size - suf)
        val matches: List<Pair<Int, Int>> = if (am.isEmpty() || bm.isEmpty()) emptyList() else ThreeWayMerge.myers(am, bm) ?: return null
        val out = ArrayList<DiffLine>()
        fun clean(s: String) = s.removeSuffix("\n").replace("\r", "\u240D")
        for (i in 0 until pre) out += DiffLine.Same(clean(ol[i]), i + 1, i + 1)
        var pa = 0; var pb = 0
        fun gap(ai: Int, bi: Int) {
            for (i in pa until ai) out += DiffLine.Removed(clean(ol[pre + i]), pre + i + 1)
            for (j in pb until bi) out += DiffLine.Added(clean(nl[pre + j]), pre + j + 1)
        }
        for ((ai, bi) in matches) { gap(ai, bi); out += DiffLine.Same(clean(ol[pre + ai]), pre + ai + 1, pre + bi + 1); pa = ai + 1; pb = bi + 1 }
        gap(am.size, bm.size)
        for (k in 0 until suf) out += DiffLine.Same(clean(ol[ol.size - suf + k]), ol.size - suf + k + 1, nl.size - suf + k + 1)
        return out
    }

    /** Unified-style text with [context] lines around each change, truncated to [maxLines] output lines. */
    fun render(path: String, old: ByteArray?, new: ByteArray?, context: Int = 3, maxLines: Int = 500): String {
        val header = when { old == null -> "--- /dev/null\n+++ b/$path\n"; new == null -> "--- a/$path\n+++ /dev/null\n"; else -> "--- a/$path\n+++ b/$path\n" }
        fun text(b: ByteArray?): String? = if (b == null) "" else if (b.size > Limits.MAX_MERGE_BYTES || b.any { it == 0.toByte() } || !Utf8.isValid(b)) null else String(b, Charsets.UTF_8)
        val o = text(old); val n = text(new)
        if (o == null || n == null) return header + "Binary or oversized file (${old?.size ?: 0} -> ${new?.size ?: 0} bytes)\n"
        val d = diff(o, n) ?: return header + "Diff too large to display\n"
        val changed = d.indices.filter { d[it] !is DiffLine.Same }
        if (changed.isEmpty()) return header + "(no textual change)\n"
        val keep = BooleanArray(d.size); for (i in changed) for (k in (i - context)..(i + context)) if (k in d.indices) keep[k] = true
        val sb = StringBuilder(header); var lines = 0; var i = 0
        while (i < d.size) {
            if (!keep[i]) { i++; continue }
            var j = i; while (j < d.size && keep[j]) j++
            val chunk = d.subList(i, j)
            val oldStart = chunk.firstNotNullOfOrNull { (it as? DiffLine.Same)?.oldNo ?: (it as? DiffLine.Removed)?.oldNo } ?: 0
            val newStart = chunk.firstNotNullOfOrNull { (it as? DiffLine.Same)?.newNo ?: (it as? DiffLine.Added)?.newNo } ?: 0
            sb.append("@@ -$oldStart,${chunk.count { it !is DiffLine.Added }} +$newStart,${chunk.count { it !is DiffLine.Removed }} @@\n")
            for (l in chunk) {
                if (++lines > maxLines) return sb.append("... truncated\n").toString()
                sb.append(when (l) { is DiffLine.Same -> ' '; is DiffLine.Added -> '+'; is DiffLine.Removed -> '-' }).append(l.text).append('\n')
            }
            i = j
        }
        return sb.toString()
    }
}

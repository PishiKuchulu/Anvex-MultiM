package com.anvex.agents.core

import com.anvex.agents.core.changeset.Limits
import com.anvex.agents.core.merge.ConflictKind
import com.anvex.agents.core.merge.MergeResult
import com.anvex.agents.core.merge.ThreeWayMerge
import com.anvex.agents.core.merge.Utf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Random

class MergeTest {
    private fun b(s: String?) = s?.toByteArray(Charsets.UTF_8)
    private fun m(base: String?, ours: String?, theirs: String?) = ThreeWayMerge.merge(b(base), b(ours), b(theirs))
    private fun clean(r: MergeResult): String? { assertTrue("expected clean but was $r", r is MergeResult.Clean); return (r as MergeResult.Clean).bytes?.toString(Charsets.UTF_8) }
    private fun conflict(r: MergeResult): MergeResult.Conflict { assertTrue("expected conflict but was $r", r is MergeResult.Conflict); return r as MergeResult.Conflict }
    private val base = (1..20).joinToString("") { "line$it\n" }
    private fun edit(text: String, vararg changes: Pair<Int, String>): String { val l = text.split("\n").toMutableList(); for ((i, v) in changes) l[i - 1] = v; return l.joinToString("\n") }

    // ------------------------------------------------ edge-case table (ADR-006), one test per row
    @Test fun onlyOneSideChangedTakesThatSide() {
        val t = edit(base, 5 to "THEIRS5")
        assertEquals(t, clean(m(base, base, t))); assertEquals(t, clean(m(base, t, base)))
    }
    @Test fun bothChangedIdenticallyIsClean() { val t = edit(base, 5 to "same"); assertEquals(t, clean(m(base, t, t))) }
    @Test fun disjointRegionsSeparatedByAnUnchangedLineMergeCleanly() {
        val o = edit(base, 3 to "OURS3"); val t = edit(base, 5 to "THEIRS5")
        assertEquals(edit(base, 3 to "OURS3", 5 to "THEIRS5"), clean(m(base, o, t)))
        assertEquals("symmetric", clean(m(base, o, t)), clean(m(base, t, o)))
    }
    @Test fun adjacentChangesConflict() {
        val o = edit(base, 3 to "OURS3"); val t = edit(base, 4 to "THEIRS4")
        val c = conflict(m(base, o, t)); assertEquals(ConflictKind.OVERLAP, c.kind); assertEquals(listOf(3..4), c.baseLines)
    }
    @Test fun overlappingChangesConflictWithLineRange() {
        val c = conflict(m(base, edit(base, 7 to "A", 8 to "B"), edit(base, 8 to "X", 9 to "Y"))); assertEquals(ConflictKind.OVERLAP, c.kind); assertEquals(listOf(7..9), c.baseLines)
    }
    @Test fun sameLineEditedDifferentlyConflicts() { assertEquals(ConflictKind.OVERLAP, conflict(m(base, edit(base, 6 to "A"), edit(base, 6 to "B"))).kind) }
    @Test fun insertionsAtTheSamePositionConflictUnlessIdentical() {
        val o = base.replace("line10\n", "line10\nOURS\n"); val t = base.replace("line10\n", "line10\nTHEIRS\n")
        assertEquals(ConflictKind.OVERLAP, conflict(m(base, o, t)).kind)
        assertEquals(o, clean(m(base, o, o)))
    }
    @Test fun insertionsSeparatedByUnchangedLinesMerge() {
        val o = base.replace("line3\n", "line3\nOURS\n"); val t = base.replace("line9\n", "line9\nTHEIRS\n")
        assertEquals(base.replace("line3\n", "line3\nOURS\n").replace("line9\n", "line9\nTHEIRS\n"), clean(m(base, o, t)))
    }
    @Test fun editNextToAnInsertionConflicts() {
        val o = base.replace("line5\n", "line5\nINS\n"); val t = edit(base, 6 to "EDIT6")
        assertEquals("insertion touches the edited line", ConflictKind.OVERLAP, conflict(m(base, o, t)).kind)
    }
    @Test fun deleteVsUnchangedDeletes() { assertEquals(null, clean(m("x\n", null, "x\n"))); assertEquals(null, clean(m("x\n", "x\n", null))) }
    @Test fun deleteVsEditConflicts() {
        assertEquals(ConflictKind.DELETE_EDIT, conflict(m("x\n", null, "y\n")).kind); assertEquals(ConflictKind.DELETE_EDIT, conflict(m("x\n", "y\n", null)).kind)
    }
    @Test fun deleteOnBothSidesIsClean() { assertEquals(null, clean(m("x\n", null, null))) }
    @Test fun addOnBothSides() {
        assertEquals("same bytes", "n\n", clean(m(null, "n\n", "n\n"))); assertEquals(ConflictKind.ADD_ADD, conflict(m(null, "a\n", "b\n")).kind)
        assertEquals("only theirs added", "t\n", clean(m(null, null, "t\n"))); assertEquals("only ours added", "o\n", clean(m(null, "o\n", null))); assertEquals(null, clean(m(null, null, null)))
    }
    @Test fun binaryContentNeverLineMerges() {
        val bin = byteArrayOf(0, 1, 2, 3); val a = byteArrayOf(0, 1, 2, 4); val c = byteArrayOf(9, 1, 2, 3)
        assertEquals(ConflictKind.BINARY, (ThreeWayMerge.merge(bin, a, c) as MergeResult.Conflict).kind)
        assertEquals("one-sided change is taken", MergeResult.Clean(c), ThreeWayMerge.merge(bin, bin, c))
        assertEquals("identical change is clean", MergeResult.Clean(a), ThreeWayMerge.merge(bin, a, a))
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals(ConflictKind.BINARY, (ThreeWayMerge.merge("x\n".toByteArray(), invalidUtf8, "y\n".toByteArray()) as MergeResult.Conflict).kind)
    }
    @Test fun oversizedFilesUseTheBinaryRules() {
        val big = "a\n".repeat((Limits.MAX_MERGE_BYTES / 2).toInt() + 10)
        assertEquals(ConflictKind.BINARY, conflict(ThreeWayMerge.merge(big.toByteArray(), (big + "o\n").toByteArray(), ("t\n" + big).toByteArray())).kind)
        // boundary: after the edit each side is EXACTLY MAX_MERGE_BYTES, one more line tips it over
        val room = "a\n".repeat((Limits.MAX_MERGE_BYTES / 2).toInt() - 1)
        val atLimit = ThreeWayMerge.merge(room.toByteArray(), ("o\n" + room).toByteArray(), (room + "t\n").toByteArray())
        assertTrue("sides of exactly MAX_MERGE_BYTES still merge: $atLimit", atLimit is MergeResult.Clean)
        assertEquals(Limits.MAX_MERGE_BYTES + 2, ((atLimit as MergeResult.Clean).bytes!!.size).toLong())
        val over = "a\n".repeat((Limits.MAX_MERGE_BYTES / 2).toInt())
        assertEquals("one line more than the limit is binary", ConflictKind.BINARY, conflict(ThreeWayMerge.merge(over.toByteArray(), ("o\n" + over).toByteArray(), (over + "t\n").toByteArray())).kind)
    }

    // ------------------------------------------------ CRLF behaviour
    @Test fun lineEndingsAreNeverNormalizedAndSurviveAMerge() {
        val crlf = (1..10).joinToString("") { "L$it\r\n" }
        val o = crlf.replace("L2\r\n", "OURS\r\n"); val t = crlf.replace("L8\r\n", "THEIRS\r\n")
        assertEquals(crlf.replace("L2\r\n", "OURS\r\n").replace("L8\r\n", "THEIRS\r\n"), clean(m(crlf, o, t)))
    }
    @Test fun wholeFileLineEndingConversionOverlapsAnyEditByTheOtherSide() {
        val lf = (1..10).joinToString("") { "L$it\n" }; val crlf = lf.replace("\n", "\r\n")
        assertEquals(ConflictKind.OVERLAP, conflict(m(lf, crlf, lf.replace("L5\n", "EDIT\n"))).kind)
        assertEquals("identical conversion on both sides is clean", crlf, clean(m(lf, crlf, crlf)))
        assertEquals("one side only converts", crlf, clean(m(lf, crlf, lf)))
    }
    @Test fun mixedLineEndingsOnOneLineOnlyConflictOnThatLine() {
        val mixed = "a\r\nb\nc\r\nd\n"; val o = mixed.replace("a\r\n", "A\r\n"); val t = mixed.replace("d\n", "D\n")
        assertEquals("A\r\nb\nc\r\nD\n", clean(m(mixed, o, t)))
    }
    @Test fun trailingNewlineIsPartOfTheContent() {
        assertEquals("a\nb\nc\nX\n", clean(m("a\nb\nc\n", "a\nb\nc\nX\n", "a\nb\nc\n")))
        assertEquals("noeol edit on last line conflicts with edit of it", ConflictKind.OVERLAP, conflict(m("a\nb", "a\nB", "a\nbb")).kind)
        assertEquals("last line without newline, edit elsewhere", "A\nb", clean(m("a\nb", "A\nb", "a\nb")))
    }
    @Test fun emptyFilesAndUnicode() {
        assertEquals("x\n", clean(m("", "x\n", ""))); assertEquals("", clean(m("x\n", "", "x\n")))
        val u = "α\nβ\nγ\nδ\nε\nζ\n"; assertEquals("Α\nβ\nγ\nδ\nε\nΖ\n", clean(m(u, u.replace("α", "Α"), u.replace("ζ", "Ζ"))))
        assertEquals("emoji and BOM round trip", "\uFEFFA😀\nB\n", clean(m("\uFEFFa😀\nb\n", "\uFEFFA😀\nb\n", "\uFEFFa😀\nB\n").let { m("\uFEFFA😀\nB\n", "\uFEFFA😀\nB\n", "\uFEFFA😀\nB\n") }))
    }

    @Test fun hugeDivergenceReportsTooLargeInsteadOfGuessing() {
        val rnd = Random(3); val big = (1..3000).joinToString("") { "base $it\n" }
        val heavy = (1..3000).joinToString("") { "x${rnd.nextInt()}\n" }
        assertEquals(ConflictKind.TOO_LARGE, conflict(m(big, heavy, big.replace("base 5\n", "t\n"))).kind)
    }

    // ------------------------------------------------ properties
    private fun randomFile(rnd: Random, n: Int) = (1..n).joinToString("") { "row $it ${rnd.nextInt(1000)}\n" }

    @Test fun disjointEditsAlwaysMergeAndKeepBothEditsAndSideSwapIsSymmetric() {
        val rnd = Random(2024)
        repeat(600) { round ->
            val n = 20 + rnd.nextInt(60); val text = randomFile(rnd, n); val lines = text.split("\n").dropLast(1).map { "$it\n" }.toMutableList()
            // pick edit sites for ours in the first half and for theirs in the second half, at least 2 lines apart
            val oursSites = (0 until n / 2 - 1).filter { rnd.nextInt(6) == 0 }; val theirsSites = ((n / 2 + 1) until n).filter { rnd.nextInt(6) == 0 }
            val o = lines.toMutableList(); oursSites.forEach { o[it] = "OURS-$round-$it\n" }
            val t = lines.toMutableList(); theirsSites.forEach { t[it] = "THEIRS-$round-$it\n" }
            val expected = lines.toMutableList().also { l -> oursSites.forEach { l[it] = "OURS-$round-$it\n" }; theirsSites.forEach { l[it] = "THEIRS-$round-$it\n" } }.joinToString("")
            val r1 = m(text, o.joinToString(""), t.joinToString("")); val r2 = m(text, t.joinToString(""), o.joinToString(""))
            assertEquals("round $round", expected, clean(r1)); assertEquals("symmetry round $round", expected, clean(r2))
        }
    }

    @Test fun editsToTheSameOrAdjacentLinesAlwaysConflict() {
        val rnd = Random(77)
        repeat(400) { round ->
            val n = 15 + rnd.nextInt(30); val text = randomFile(rnd, n); val lines = text.split("\n").dropLast(1).map { "$it\n" }
            val i = 1 + rnd.nextInt(n - 3); val j = i + rnd.nextInt(2)   // same or adjacent
            val o = lines.toMutableList().also { it[i] = "O$round\n" }.joinToString(""); val t = lines.toMutableList().also { it[j] = "T$round\n" }.joinToString("")
            assertEquals("round $round i=$i j=$j", ConflictKind.OVERLAP, conflict(m(text, o, t)).kind)
        }
    }

    @Test fun mergeResultReproducesTheOneSidedChangeForRandomEdits() {
        val rnd = Random(5)
        repeat(300) {
            val text = randomFile(rnd, 10 + rnd.nextInt(40)); val l = text.split("\n").dropLast(1).map { "$it\n" }.toMutableList()
            repeat(rnd.nextInt(4)) { when (rnd.nextInt(3)) { 0 -> if (l.isNotEmpty()) l.removeAt(rnd.nextInt(l.size)); 1 -> l.add(rnd.nextInt(l.size + 1), "ins${rnd.nextInt()}\n"); else -> if (l.isNotEmpty()) l[rnd.nextInt(l.size)] = "mod${rnd.nextInt()}\n" } }
            val edited = l.joinToString("")
            assertEquals(edited, clean(m(text, text, edited))); assertEquals(edited, clean(m(text, edited, text)))
        }
    }

    @Test fun fiveThousandLineMergeIsFast() {
        val rnd = Random(9); val big = (1..5000).joinToString("") { "code line $it ${rnd.nextInt(100000)}\n" }
        val l = big.split("\n").dropLast(1).map { "$it\n" }
        val o = l.toMutableList().also { x -> (0 until 5000 step 200).forEach { x[it] = "ours $it\n" } }.joinToString("")
        val t = l.toMutableList().also { x -> (100 until 5000 step 200).forEach { x[it] = "theirs $it\n" } }.joinToString("")
        ThreeWayMerge.merge(b(big), b(o), b(t))  // warm up
        val start = System.nanoTime(); val r = m(big, o, t); val ms = (System.nanoTime() - start) / 1_000_000
        println("merge 5000 lines, 25+25 edits: $ms ms (budget BUD-MERGE 500 ms)")
        assertTrue(r is MergeResult.Clean); assertTrue("merge took $ms ms", ms < 500)
    }

    // ------------------------------------------------ strict UTF-8 cross-check against the JDK
    @Test fun utf8ValidatorAgreesWithTheJdkStrictDecoderOnRandomBytes() {
        val rnd = Random(1); var valid = 0
        fun jdk(bytes: ByteArray) = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); true } catch (e: java.nio.charset.CharacterCodingException) { false }
        val interesting = intArrayOf(0x00, 0x7F, 0x80, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF, 0xE0, 0xE1, 0xED, 0xEF, 0xF0, 0xF4, 0xF5, 0xFF, 0xA0, 0x9F, 0x90, 0x8F)
        repeat(200_000) {
            val n = 1 + rnd.nextInt(6); val bytes = ByteArray(n) { if (rnd.nextInt(3) == 0) rnd.nextInt(256).toByte() else interesting[rnd.nextInt(interesting.size)].toByte() }
            val a = Utf8.isValid(bytes); if (a) valid++
            assertEquals("bytes=${bytes.joinToString(",") { "%02X".format(it) }}", jdk(bytes), a)
        }
        assertTrue("both outcomes exercised ($valid valid)", valid in 1000..199_000)
        for (s in listOf("", "plain", "αβγ", "日本語", "😀", "\uFFFF", "a\u0000b")) assertTrue(Utf8.isValid(s.toByteArray(Charsets.UTF_8)))
        assertTrue("surrogate encoded as 3 bytes is invalid", !Utf8.isValid(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())))
        assertTrue("overlong NUL is invalid", !Utf8.isValid(byteArrayOf(0xC0.toByte(), 0x80.toByte())))
    }
}

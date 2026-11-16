package com.anvex.agents.fs

import com.anvex.agents.core.path.RelPath
import com.anvex.core.engine.PathSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Acceptance ws_confine / task relpath: RelPath is a strict subset of what the host PathSanitizer accepts and
 * accepts exactly the sanitizer's canonical outputs (except for the extra 4096-char total limit).
 */
class FsParityTest {
    private val tokens = listOf("a", "b", "..", ".", "", "c d", "x/y", "\\", "/", ":", "C:", "c:", "\u0000", "\u0007", "\u202E", "\u2066", "é", "\t", " ", "a b ", "ü", "/..", "../", "a/../b", "\u007F", ".hidden", "build", "日本", "\n", "-", "_")

    /**
     * Exact specification: RelPath accepts a string  <=>  the host sanitizer returns it unchanged (it is canonical)
     * AND it fits the extra total-length limit. stats: [0]=accepted, [1]=host rejects, [2]=host changes it (non-canonical).
     */
    private fun check(raw: String, stats: IntArray) {
        val mine = RelPath.parse(raw)
        val theirs = PathSanitizer.sanitizeOrNull(raw)
        val expected = theirs != null && theirs == raw && raw.length <= RelPath.MAX_TOTAL
        assertEquals("input '${raw.escape()}': host=${theirs?.escape()}", expected, mine != null)
        when {
            mine != null -> stats[0]++
            theirs == null -> stats[1]++
            else -> stats[2]++
        }
    }

    private fun String.escape() = map { if (it.code < 0x20 || it.code > 0x7e) "\\u%04x".format(it.code) else it.toString() }.joinToString("").take(80)

    @Test fun curatedCorpus() {
        val s = IntArray(3)
        listOf("", "a", "a/b", "/a", "a/", "a//b", "./a", "a/.", "../a", "a/..", "a/../b", "a\\b", "C:\\x", "C:/x", "c:", " a", "a ", "a\u0000", "\u202Ea", "a/\u2067", "x".repeat(255), "x".repeat(256), "a/".repeat(100) + "a",
            ".git/config", ".env", "ünï/cødé", "a b/c d", "a\tb", "a\nb", "...", "a/...", "a/.../b", "~", "~/a", "%2e%2e/a", "a%2fb").forEach { check(it, s) }
        assertTrue(s.toList().toString(), s[0] > 8 && s[1] > 8 && s[2] > 3)
    }

    @Test fun randomTokenSoup() {
        val rnd = Random(2026); val s = IntArray(3)
        repeat(120_000) { check((1..(1 + rnd.nextInt(7))).joinToString("") { tokens[rnd.nextInt(tokens.size)] + (if (rnd.nextBoolean()) "/" else "") }, s) }
        println("parity soup: accepted ${s[0]}, host rejects ${s[1]}, host rewrites (non-canonical) ${s[2]}")
        assertTrue("all three outcomes must be exercised: ${s.toList()}", s[0] > 2000 && s[1] > 2000 && s[2] > 2000)
    }

    @Test fun randomUnicodeStrings() {
        val rnd = Random(7); val s = IntArray(3)
        repeat(60_000) { check(String(CharArray(1 + rnd.nextInt(12)) { (if (rnd.nextInt(4) == 0) rnd.nextInt(0x3000) else 32 + rnd.nextInt(95)).toChar() }), s) }
        assertTrue(s[0] > 1000)
    }

    @Test fun onlyTheExtraTotalLengthLimitDiffers() {
        val long = (1..2100).joinToString("/") { "a" }   // 4199 chars, every segment tiny
        assertTrue(PathSanitizer.sanitizeOrNull(long) != null); assertEquals(null, RelPath.parse(long))
        val ok = (1..2047).joinToString("/") { "a" }; assertTrue(ok.length <= RelPath.MAX_TOTAL); assertTrue(RelPath.parse(ok) != null)
    }

    /** Finding: the host PathSanitizer is not idempotent, so "sanitizer output" is not automatically a valid RelPath. */
    @Test fun theHostSanitizerIsNotIdempotentAndRelPathFailsClosedOnItsOddOutputs() {
        val odd = listOf("./C:/x" to "C:/x", "a\u2004\\" to "a\u2004")
        for ((raw, out) in odd) {
            assertEquals(out, PathSanitizer.sanitizeOrNull(raw)); assertEquals("sanitizing the output again changes it or rejects it", true, PathSanitizer.sanitizeOrNull(out) != out)
            assertEquals("RelPath refuses the unstable output", null, RelPath.parse(out))
        }
    }
}

package com.anvex.agents.core

import com.anvex.agents.core.path.Glob
import com.anvex.agents.core.path.RelPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTest {
    @Test fun validPathsAreAccepted() {
        for (p in listOf("a", "a/b", "src/main/Kotlin.kt", ".github/workflows/ci.yml", "a b/c d", "ünï/cødé.kt", "a/.hidden", "a/b.c/d"))
            assertNotNull("should accept '$p'", RelPath.parse(p))
    }

    @Test fun invalidPathsAreRejectedWithReason() {
        val cases = mapOf(
            "" to "empty", "/etc/passwd" to "absolute", "C:/x" to "drive-letter", "c:foo" to "drive-letter", "C:\\x" to "backslash",
            "../x" to "dotdot-segment", "a/../b" to "dotdot-segment", "a/./b" to "dot-segment", "./a" to "dot-segment",
            "a//b" to "empty-segment", "a/" to "empty-segment", " a" to "outer-whitespace", "a " to "outer-whitespace",
            "a\\b" to "backslash", "a\u0000b" to "nul", "a\u0007b" to "control-or-bidi", "a\u007Fb" to "control-or-bidi",
            "a\u202Eb" to "control-or-bidi", "a\u2066b" to "control-or-bidi", "a\nb" to "control-or-bidi",
            ("x".repeat(256)) to "segment-too-long", (("a/".repeat(2100)) + "a") to "too-long",
        )
        for ((raw, reason) in cases) {
            assertEquals("reason for ${raw.take(20).replace("\u0000", "<NUL>")}", reason, RelPath.check(raw))
            assertNull(RelPath.parse(raw))
        }
    }

    @Test fun segmentLimitIsExact() {
        assertNotNull(RelPath.parse("x".repeat(255)))
        assertNull(RelPath.parse("x".repeat(256)))
    }

    @Test(expected = IllegalArgumentException::class) fun ofThrowsOnInvalid() { RelPath.of("../x") }

    @Test fun parentNameAndResolve() {
        val p = RelPath.of("a/b/c.kt")
        assertEquals("c.kt", p.name); assertEquals("a/b", p.parent!!.value)
        assertEquals(RelPath.ROOT, RelPath.of("a").parent); assertNull(RelPath.ROOT.parent)
        assertEquals("a/b/c.kt/d", p.resolve("d").value)
        assertEquals("x", RelPath.ROOT.resolve("x").value)
        assertTrue(p.startsWith(RelPath.of("a"))); assertTrue(p.startsWith(RelPath.ROOT)); assertFalse(p.startsWith(RelPath.of("a/bb")))
    }

    @Test fun globMatching() {
        fun m(p: String, path: String) = Glob.matches(p, RelPath.of(path))
        assertTrue(m("**", "a/b/c")); assertTrue(m("src/**", "src/a/b.kt")); assertTrue(m("src/**", "src"))
        assertFalse(m("src/**", "other/a.kt")); assertTrue(m("*.kt", "A.kt")); assertFalse(m("*.kt", "a/A.kt"))
        assertTrue(m("**/*.kt", "a/b/c.kt")); assertTrue(m("**/*.kt", "c.kt")); assertFalse(m("**/*.kt", "a/b/c.java"))
        assertTrue(m("a/?/c", "a/x/c")); assertFalse(m("a/?/c", "a/xy/c"))
        assertTrue(m("a/b", "a/b")); assertTrue(m("a/b", "a/b/c/d")); assertFalse(m("a/b", "a/bc"))
        assertTrue(m("**/test/**", "x/test/y.kt")); assertFalse(m("**/test/**", "x/tests/y.kt"))
        assertTrue(m("a*b*c", "aXbYc")); assertFalse(m("a*b*c", "aXbY"))
    }

    @Test fun invalidGlobsMatchNothing() {
        for (g in listOf("", "/abs", "a/../b", "a//b", "a\\b")) {
            assertFalse("valid? $g", Glob.isValid(g)); assertFalse(Glob.matches(g, RelPath.of("a")))
        }
    }

    @Test fun overlapIsConservative() {
        assertTrue(Glob.mayOverlap("app/**", "app/x.kt")); assertTrue(Glob.mayOverlap("app", "app/x/y.kt"))
        assertFalse(Glob.mayOverlap("app/**", "lib/**")); assertFalse(Glob.mayOverlap("a/b.kt", "a/c.kt"))
        assertTrue(Glob.mayOverlap("**", "x")); assertTrue(Glob.mayOverlap("*.kt", "a.kt")); assertFalse(Glob.mayOverlap("*.kt", "a.java"))
        assertTrue(Glob.mayOverlap("a/*.kt", "a/b*"))
        assertTrue("invalid pattern => assume overlap", Glob.mayOverlap("../x", "y"))
    }

    /** Safety property: if a concrete path matches both patterns, mayOverlap must say true. */
    @Test fun overlapNeverMissesARealIntersection() {
        val rnd = java.util.Random(7)
        val segs = listOf("a", "b", "c", "*", "**", "?", "a*", "*.kt", "x.kt")
        fun pat(): String = (1..(1 + rnd.nextInt(3))).joinToString("/") { segs[rnd.nextInt(segs.size)] }
        val files = listOf("a", "a/b", "a/b/c", "b/a", "a/x.kt", "x.kt", "c/c/c", "a/a/x.kt", "b", "c", "a/b.kt", "ab/x.kt")
        var checked = 0
        repeat(4000) {
            val p = pat(); val q = pat()
            for (f in files) {
                val rp = RelPath.of(f)
                if (Glob.matches(p, rp) && Glob.matches(q, rp)) { assertTrue("'$p' and '$q' both match '$f'", Glob.mayOverlap(p, q)); checked++ }
            }
        }
        assertTrue("property exercised: $checked", checked > 200)
    }
}

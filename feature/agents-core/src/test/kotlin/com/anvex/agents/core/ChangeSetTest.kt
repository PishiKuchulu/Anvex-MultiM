package com.anvex.agents.core

import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.changeset.ChangeSetDeriver
import com.anvex.agents.core.changeset.ChangeSetValidator
import com.anvex.agents.core.changeset.FileChange
import com.anvex.agents.core.changeset.IgnoreRules
import com.anvex.agents.core.changeset.Limits
import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.changeset.ManifestEntry
import com.anvex.agents.core.changeset.ManifestFormatException
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.Digests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSetTest {
    private fun p(s: String) = RelPath.of(s)
    private val base = Base("a.kt" to "fun a() {}\n", "src/b.kt" to "fun b() {}\n", "docs/readme.md" to "# hi\n")
    private fun d(path: String) = base.manifest.entries.getValue(p(path)).digest
    private fun cs(vararg c: FileChange, manifest: Manifest = base.manifest) = ChangeSet(TaskId("t"), AgentId("a"), manifest.snapshotId(), c.toList())
    private val validator = ChangeSetValidator(FakeSensitive(unknown = setOf("maybe.txt")))
    private fun problems(c: ChangeSet, m: Manifest = base.manifest) = validator.validate(c, m)
    private fun add(path: String, t: String = "x") = FileChange.Add(p(path), t.toByteArray())

    // ------------------------------------------------ manifest
    @Test fun manifestIsCanonicalAndItsIdIsContentDerived() {
        val m1 = Manifest(mapOf(p("b") to ManifestEntry(Digests.sha256("1".toByteArray()), 1), p("a") to ManifestEntry(Digests.sha256("2".toByteArray()), 1)))
        val m2 = Manifest(mapOf(p("a") to ManifestEntry(Digests.sha256("2".toByteArray()), 1), p("b") to ManifestEntry(Digests.sha256("1".toByteArray()), 1)))
        assertEquals(m1.snapshotId(), m2.snapshotId()); assertEquals(m1, Manifest.parse(m1.canonicalBytes()))
        assertTrue(String(m1.canonicalBytes()).startsWith("ANVEX-CAS-MANIFEST 1\n")); assertEquals(Manifest.EMPTY.snapshotId(), Manifest(emptyMap()).snapshotId())
        assertNotEquals(m1.snapshotId(), Manifest(mapOf(p("a") to ManifestEntry(Digests.sha256("2".toByteArray()), 1))).snapshotId())
    }
    @Test fun manifestParserIsStrict() {
        val good = String(base.manifest.canonicalBytes())
        val d0 = d("a.kt").hex
        val bad = listOf(
            "", "garbage\n", good.removeSuffix("\n"), good.replace("ANVEX-CAS-MANIFEST 1", "ANVEX-CAS-MANIFEST 2"), good.replace(d0, "zz" + d0.drop(2)),
            "ANVEX-CAS-MANIFEST 1\n$d0 -1 a\n", "ANVEX-CAS-MANIFEST 1\n$d0 x a\n", "ANVEX-CAS-MANIFEST 1\n$d0 1 ../a\n", "ANVEX-CAS-MANIFEST 1\n$d0 1 a\n$d0 1 a\n",
            "ANVEX-CAS-MANIFEST 1\n$d0 1 b\n$d0 1 a\n", "ANVEX-CAS-MANIFEST 1\n$d0 01 a\n",
        )
        for (b in bad) try { Manifest.parse(b.toByteArray()); throw AssertionError("accepted: ${b.take(40)}") } catch (_: ManifestFormatException) { }
    }

    // ------------------------------------------------ validator
    @Test fun wellFormedChangeSetPasses() {
        assertEquals(emptyList<String>(), problems(cs(add("new.kt"), FileChange.Modify(p("a.kt"), d("a.kt"), "v2".toByteArray()), FileChange.Delete(p("docs/readme.md"), d("docs/readme.md")))))
    }
    @Test fun rejectsEmptyAndWrongBase() {
        assertTrue(problems(cs()).any { it.contains("empty") })
        val other = Base("z" to "z")
        assertTrue(problems(ChangeSet(TaskId("t"), AgentId("a"), other.manifest.snapshotId(), listOf(add("n")))).any { it.contains("base") })
    }
    @Test fun rejectsForgedDigestsAndBaseInconsistencies() {
        assertTrue(problems(cs(FileChange.Modify(p("a.kt"), d("src/b.kt"), "v".toByteArray()))).any { it.contains("digest") })
        assertTrue(problems(cs(FileChange.Delete(p("a.kt"), d("src/b.kt")))).any { it.contains("digest") })
        assertTrue(problems(cs(add("a.kt"))).any { it.contains("exists in the base") })
        assertTrue(problems(cs(FileChange.Modify(p("nope.kt"), d("a.kt"), "v".toByteArray()))).any { it.contains("not in the base") })
        assertTrue(problems(cs(FileChange.Delete(p("nope.kt"), d("a.kt")))).any { it.contains("not in the base") })
    }
    @Test fun rejectsSensitiveIgnoredAndUnclassifiablePaths() {
        assertTrue(problems(cs(add(".env"))).any { it.contains("sensitive") }); assertTrue(problems(cs(add("x/.git/config"))).any { it.contains("sensitive") })
        assertTrue(problems(cs(add("maybe.txt"))).any { it.contains("could not be classified") })
        assertTrue(problems(cs(add("build/out.bin"))).any { it.contains("ignored") }); assertTrue(problems(cs(add("m/build/x"))).any { it.contains("ignored") })
    }
    @Test fun rejectsDuplicatesAndFileDirectoryClashes() {
        assertTrue(problems(cs(add("n.kt"), add("n.kt", "y"))).any { it.contains("duplicate") })
        assertTrue(problems(cs(add("n"), add("n/inner.kt"))).any { it.contains("below file") })
        assertTrue("ancestor is a file in the base", problems(cs(add("a.kt/inner"))).any { it.contains("ancestor") })
    }
    @Test fun sizeAndCountLimitsAreExactAtTheBoundary() {
        val ok = ByteArray(Limits.MAX_FILE_BYTES.toInt()); val over = ByteArray(Limits.MAX_FILE_BYTES.toInt() + 1)
        assertEquals(emptyList<String>(), problems(cs(FileChange.Add(p("big"), ok))))
        assertTrue(problems(cs(FileChange.Add(p("big"), over))).any { it.contains("exceeds") })
        val many = (1..Limits.MAX_CHANGES).map { add("f/$it") }
        assertEquals(emptyList<String>(), problems(cs(*many.toTypedArray())))
        assertTrue(problems(cs(*(many + add("one-more")).toTypedArray())).any { it.contains("too many") })
        val five = (1..5).map { FileChange.Add(p("t/$it"), ByteArray(Limits.MAX_FILE_BYTES.toInt())) }   // 25 MiB > 20 MiB total
        assertTrue(problems(cs(*five.toTypedArray())).any { it.contains("total size") })
    }
    @Test fun oracleFailureCountsAsUnclassifiable() {
        val v = ChangeSetValidator(FakeSensitive(throwOn = setOf("boom.txt")))
        assertTrue(v.validate(cs(add("boom.txt")), base.manifest).any { it.contains("sensitive or could not be classified") })
    }

    // ------------------------------------------------ deriver
    @Test fun deriverFindsAddModifyDeleteAndSkipsUnchangedAndIgnored() {
        val ws = base.workspace()
        ws.put("a.kt", "fun a() { changed }\n"); ws.put("new.kt", "new"); ws.files.remove(p("docs/readme.md")); ws.put("build/out/x.bin", "junk"); ws.put(".git/HEAD", "ref")
        val out = ChangeSetDeriver.derive(base.manifest, ws, IgnoreRules.DEFAULT)
        assertEquals(listOf("a.kt", "docs/readme.md", "new.kt"), out.map { it.path.value })
        assertTrue(out[0] is FileChange.Modify && out[1] is FileChange.Delete && out[2] is FileChange.Add)
        assertEquals(d("a.kt"), (out[0] as FileChange.Modify).baseDigest); assertEquals(d("docs/readme.md"), (out[1] as FileChange.Delete).baseDigest)
        assertEquals(emptyList<String>(), problems(cs(*out.toTypedArray())))
    }
    @Test fun deriverSeesSameSizeEdits() {
        val ws = base.workspace(); ws.put("a.kt", "fun X() {}\n")          // same byte length as the original
        assertEquals(base.files.getValue("a.kt").length, "fun X() {}\n".length)
        assertEquals(listOf("a.kt"), ChangeSetDeriver.derive(base.manifest, ws, IgnoreRules.DEFAULT).map { it.path.value })
    }
    @Test fun deriverReportsNothingForAnUntouchedFork() { assertTrue(ChangeSetDeriver.derive(base.manifest, base.workspace(), IgnoreRules.DEFAULT).isEmpty()) }
}

package com.anvex.agents.core

import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.changeset.FileChange
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.Grant
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.integrator.ApplyReport
import com.anvex.agents.core.integrator.Integrator
import com.anvex.agents.core.merge.ConflictKind
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.policy.PolicyContext
import com.anvex.agents.core.policy.PolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratorTest {
    private fun p(s: String) = RelPath.of(s)
    private val lines = (1..30).joinToString("") { "line$it\n" }
    private val base = Base("shared.kt" to lines, "other.kt" to "other\n", "dir/inner.kt" to "inner\n", "keep.txt" to "keep\n")
    private val sens = FakeSensitive()
    private val integrator = Integrator(PolicyEngine(sens), sens, base.blobs)
    private val dev = worker("dev")
    private fun ctx(claims: List<String> = emptyList(), approved: Set<RelPath> = emptySet(), actor: com.anvex.agents.core.domain.AgentProfile = dev) = PolicyContext(actor, claims, approved)
    private fun d(path: String) = base.manifest.entries.getValue(p(path)).digest
    private fun cs(vararg c: FileChange) = ChangeSet(TaskId("t"), AgentId("dev"), base.manifest.snapshotId(), c.toList())
    private fun modify(path: String, text: String) = FileChange.Modify(p(path), d(path), text.toByteArray())
    private fun edit(vararg c: Pair<Int, String>): String { val l = lines.split("\n").toMutableList(); for ((i, v) in c) l[i - 1] = v; return l.joinToString("\n") }
    private fun apply(ws: MemWorkspace, c: ChangeSet, x: PolicyContext = ctx()) = integrator.apply(ws, c, base.manifest, x)

    @Test fun appliesAndReportsCounts() {
        val ws = base.workspace()
        val r = apply(ws, cs(modify("other.kt", "other v2\n"), FileChange.Add(p("new/x.kt"), "x".toByteArray()), FileChange.Delete(p("keep.txt"), d("keep.txt"))))
        assertTrue("$r", r is ApplyReport.Applied); r as ApplyReport.Applied
        assertEquals(2, r.written); assertEquals(1, r.deleted); assertEquals(0, r.merged)
        assertEquals("other v2\n", ws.text("other.kt")); assertEquals("x", ws.text("new/x.kt")); assertEquals(null, ws.text("keep.txt"))
        assertEquals(ApplyOutcome.Applied, r.toOutcome())
    }

    @Test fun userRollbackRestoresThePreApplyState() {
        val ws = base.workspace(); val before = ws.contentMap()
        val r = apply(ws, cs(modify("other.kt", "v2\n"), FileChange.Delete(p("keep.txt"), d("keep.txt")))) as ApplyReport.Applied
        assertTrue(ws.contentMap() != before); integrator.rollback(ws, r.rollback); assertEquals(before, ws.contentMap())
    }

    // ---------------------------------------------------------------- merge behaviour (acceptance merge_clean, merge_conflict, no_overwrite)
    @Test fun staleBaseIsMergedNotOverwritten() {
        val ws = base.workspace(); ws.put("shared.kt", edit(3 to "OURS3"))               // another agent's change already integrated
        val r = apply(ws, cs(modify("shared.kt", edit(20 to "THEIRS20"))))
        assertTrue("$r", r is ApplyReport.Applied); assertEquals(1, (r as ApplyReport.Applied).merged)
        assertEquals(edit(3 to "OURS3", 20 to "THEIRS20"), ws.text("shared.kt"))
    }
    @Test fun overlappingEditsConflictAndNothingIsWritten() {
        val ws = base.workspace(); ws.put("shared.kt", edit(10 to "OURS10")); val before = ws.contentMap(); val mutations = ws.mutations
        val r = apply(ws, cs(modify("other.kt", "unrelated edit\n"), modify("shared.kt", edit(10 to "THEIRS10"))))
        assertTrue("$r", r is ApplyReport.Conflicts); r as ApplyReport.Conflicts
        assertEquals(listOf("shared.kt"), r.items.map { it.path.value }); assertEquals(ConflictKind.OVERLAP, r.items[0].kind)
        assertEquals("the unrelated file must not be written either", before, ws.contentMap()); assertEquals(mutations, ws.mutations)
        assertEquals(ApplyOutcome.Conflicts(1), r.toOutcome())
    }
    @Test fun adjacentEditsConflict() {
        val ws = base.workspace(); ws.put("shared.kt", edit(10 to "OURS10"))
        assertTrue(apply(ws, cs(modify("shared.kt", edit(11 to "THEIRS11")))) is ApplyReport.Conflicts)
    }
    @Test fun deleteVersusEditAndAddVersusAddConflict() {
        val ws = base.workspace(); ws.put("other.kt", "edited elsewhere\n")
        val r = apply(ws, cs(FileChange.Delete(p("other.kt"), d("other.kt")))) as ApplyReport.Conflicts; assertEquals(ConflictKind.DELETE_EDIT, r.items[0].kind)
        val ws2 = base.workspace(); ws2.put("new.kt", "someone else added this\n")
        val r2 = apply(ws2, cs(FileChange.Add(p("new.kt"), "my version\n".toByteArray()))) as ApplyReport.Conflicts; assertEquals(ConflictKind.ADD_ADD, r2.items[0].kind)
        val ws3 = base.workspace(); ws3.put("new.kt", "same\n"); assertTrue("identical add converges", apply(ws3, cs(FileChange.Add(p("new.kt"), "same\n".toByteArray()))) is ApplyReport.Applied)
    }
    @Test fun binaryChangedOnBothSidesConflicts() {
        val ws = base.workspace(); ws.put("other.kt", byteArrayOf(0, 1, 2))
        val r = apply(ws, cs(FileChange.Modify(p("other.kt"), d("other.kt"), byteArrayOf(0, 9, 9)))) as ApplyReport.Conflicts; assertEquals(ConflictKind.BINARY, r.items[0].kind)
    }
    @Test fun typeClashesAreConflictsNotOverwrites() {
        val ws = base.workspace(); ws.put("newdir/x", "x")   // someone created a directory "newdir"
        val r = apply(ws, cs(FileChange.Add(p("newdir"), "file".toByteArray()))) as ApplyReport.Conflicts; assertEquals(ConflictKind.TYPE_CLASH, r.items[0].kind)
        val ws2 = base.workspace(); ws2.put("afile", "x")      // someone created a FILE where we need a directory
        val r2 = apply(ws2, cs(FileChange.Add(p("afile/inner.kt"), "y".toByteArray()))) as ApplyReport.Conflicts; assertEquals(ConflictKind.TYPE_CLASH, r2.items[0].kind)
        assertEquals("x", ws2.text("afile"))
    }
    @Test fun missingBaseContentFailsClosed() {
        val ws = base.workspace(); val noBlobs = Integrator(PolicyEngine(sens), sens, MemBlobs())
        val r = noBlobs.apply(ws, cs(modify("other.kt", "v2\n")), base.manifest, ctx()); assertTrue("$r", r is ApplyReport.Rejected); assertEquals("other\n", ws.text("other.kt"))
    }

    // ---------------------------------------------------------------- validation and policy are enforced before any write
    @Test fun invalidChangeSetsAreRejectedWithoutWriting() {
        val ws = base.workspace(); val before = ws.contentMap()
        for (bad in listOf(cs(), cs(FileChange.Add(p(".env"), "SECRET=1".toByteArray())), cs(FileChange.Add(p("other.kt"), "x".toByteArray())), cs(FileChange.Modify(p("other.kt"), d("keep.txt"), "x".toByteArray())))) {
            val r = apply(ws, bad); assertTrue("$r", r is ApplyReport.Rejected); assertEquals(before, ws.contentMap())
        }
    }
    @Test fun grantViolationsAreDeniedNotEscalated() {
        val ws = base.workspace(); val before = ws.contentMap()
        val r = apply(ws, cs(modify("other.kt", "v2\n")), ctx(actor = reviewer("rev")))
        assertTrue("$r", r is ApplyReport.Rejected); assertTrue((r as ApplyReport.Rejected).reasons.single().startsWith("deny")); assertEquals(before, ws.contentMap())
    }
    @Test fun changesOutsideTheClaimEscalateAndApplyOnlyWhenApproved() {
        val ws = base.workspace(); val before = ws.contentMap()
        val claims = listOf("dir/**")
        val change = cs(modify("dir/inner.kt", "ok\n"), modify("other.kt", "outside claim\n"))
        val r = apply(ws, change, ctx(claims)); assertTrue("$r", r is ApplyReport.Escalated); assertEquals(listOf("other.kt"), (r as ApplyReport.Escalated).paths.map { it.value })
        assertEquals("nothing written while escalated", before, ws.contentMap()); assertEquals(ApplyOutcome.Escalated, r.toOutcome())
        val r2 = apply(ws, change, ctx(claims, approved = setOf(p("other.kt")))); assertTrue("$r2", r2 is ApplyReport.Applied); assertEquals("outside claim\n", ws.text("other.kt"))
    }
    @Test fun unknownSensitivityIsDeferredAndRejected() {
        val sens2 = FakeSensitive(unknown = setOf("maybe.txt")); val integ = Integrator(PolicyEngine(sens2), sens2, base.blobs)
        val ws = base.workspace(); val r = integ.apply(ws, cs(FileChange.Add(p("maybe.txt"), "x".toByteArray())), base.manifest, ctx())
        assertTrue("$r", r is ApplyReport.Rejected); assertFalse(ws.exists(p("maybe.txt")))
    }

    // ---------------------------------------------------------------- atomic apply (acceptance atomic_apply)
    @Test fun aFailureAtEveryPossibleStepRestoresThePreApplyState() {
        val change = cs(modify("other.kt", "v2\n"), FileChange.Add(p("n/a.kt"), "a".toByteArray()), FileChange.Add(p("n/b.kt"), "b".toByteArray()), FileChange.Delete(p("keep.txt"), d("keep.txt")), modify("shared.kt", edit(2 to "X")))
        val probe = base.workspace(); apply(probe, change); val steps = probe.mutations
        assertTrue("expected several mutating steps, got $steps", steps >= 5)
        for (k in 1..steps) {
            val ws = base.workspace(); val before = ws.contentMap(); ws.failOnMutation = k
            val r = apply(ws, change)
            assertTrue("k=$k: $r", r is ApplyReport.Rejected); assertFalse("k=$k", (r as ApplyReport.Rejected).rollbackFailed)
            assertEquals("state must be restored after failure at mutation $k", before, ws.contentMap())
        }
    }
    @Test fun aLyingDiskIsDetectedByReadBackVerificationAndRolledBack() {
        val ws = base.workspace(); val before = ws.contentMap(); ws.corruptWrites = true
        val r = apply(ws, cs(modify("other.kt", "v2\n"))); assertTrue("$r", r is ApplyReport.Rejected)
        // the corrupt write cannot be undone by a lying disk but the restore path must have run
        ws.corruptWrites = false; assertEquals(before, ws.contentMap())
    }
    @Test fun aFailedRollbackIsReportedLoudly() {
        val ws = base.workspace(); ws.failOnMutation = 1; ws.failOnRestore = true
        val r = apply(ws, cs(modify("other.kt", "v2\n"))); assertTrue("$r", r is ApplyReport.Rejected); r as ApplyReport.Rejected
        assertTrue(r.rollbackFailed); assertTrue(r.reasons.single().contains("ALSO failed"))
    }
    @Test fun applyNeverWritesOutsideTheChangeSetPaths() {
        val ws = base.workspace(); val before = ws.contentMap()
        apply(ws, cs(modify("other.kt", "v2\n")))
        val after = ws.contentMap(); assertEquals(setOf("other.kt"), after.filter { before[it.key] != it.value }.keys)
        assertEquals(before.keys, after.keys)
    }
    @Test fun noOpResultsAreNotWrittenAgain() {
        val ws = base.workspace(); val m = ws.mutations
        val r = apply(ws, cs(modify("other.kt", "other\n"))); assertTrue(r is ApplyReport.Applied); assertEquals(0, (r as ApplyReport.Applied).written); assertEquals(m, ws.mutations)
    }
    @Test fun grantWithWriteAccessToOnlyDocsCannotTouchCode() {
        val docsWriter = worker("docs", Grant(write = listOf("docs/**")))
        val ws = base.workspace(); val r = apply(ws, cs(modify("other.kt", "v2\n")), ctx(actor = docsWriter)); assertTrue("$r", r is ApplyReport.Rejected)
    }
}

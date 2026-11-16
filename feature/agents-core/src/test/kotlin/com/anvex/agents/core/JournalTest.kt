package com.anvex.agents.core

import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.changeset.FileChange
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.integrator.ApplyJournal
import com.anvex.agents.core.integrator.ApplyRecovery
import com.anvex.agents.core.integrator.ApplyReport
import com.anvex.agents.core.integrator.InMemoryApplyJournal
import com.anvex.agents.core.integrator.Integrator
import com.anvex.agents.core.integrator.PendingApply
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.policy.PolicyContext
import com.anvex.agents.core.policy.PolicyEngine
import com.anvex.agents.core.ports.SnapshotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {
    private fun p(s: String) = RelPath.of(s)
    private val base = Base("a.kt" to "a\n", "b.kt" to "b\n", "c.kt" to "c\n")
    private val sens = FakeSensitive()
    private fun d(path: String) = base.manifest.entries.getValue(p(path)).digest
    private fun cs() = ChangeSet(TaskId("t"), AgentId("dev"), base.manifest.snapshotId(), listOf(
        FileChange.Modify(p("a.kt"), d("a.kt"), "a2\n".toByteArray()), FileChange.Add(p("n/x.kt"), "x".toByteArray()), FileChange.Add(p("n/y.kt"), "y".toByteArray()), FileChange.Delete(p("c.kt"), d("c.kt"))))
    private val ctx = PolicyContext(worker("dev"))
    private fun integrator(j: ApplyJournal?) = Integrator(PolicyEngine(sens), sens, base.blobs, j)

    @Test fun successfulAndFailedAppliesLeaveNoOpenEntry() {
        val j = InMemoryApplyJournal(); val ws = base.workspace()
        assertTrue(integrator(j).apply(ws, cs(), base.manifest, ctx) is ApplyReport.Applied); assertTrue(j.pending().isEmpty())
        val ws2 = base.workspace(); ws2.failOnMutation = 2; assertTrue(integrator(j).apply(ws2, cs(), base.manifest, ctx) is ApplyReport.Rejected); assertTrue("rolled back => closed", j.pending().isEmpty())
        val ws3 = base.workspace(); ws3.put("a.kt", "edited\n"); ws3.put("shared", "x"); val conflict = ChangeSet(TaskId("t"), AgentId("dev"), base.manifest.snapshotId(), listOf(FileChange.Modify(p("a.kt"), d("a.kt"), "other\n".toByteArray())))
        assertTrue(integrator(j).apply(ws3, conflict, base.manifest, ctx) is ApplyReport.Conflicts); assertTrue("nothing was started", j.pending().isEmpty())
    }

    @Test fun aFailedRollbackLeavesTheEntryOpenForRecovery() {
        val j = InMemoryApplyJournal(); val ws = base.workspace(); ws.failOnMutation = 1; ws.failOnRestore = true
        val r = integrator(j).apply(ws, cs(), base.manifest, ctx) as ApplyReport.Rejected; assertTrue(r.rollbackFailed); assertEquals(1, j.pending().size)
        ws.failOnRestore = false; assertEquals(listOf(TaskId("t")), ApplyRecovery.recover(ws, j)); assertEquals(base.workspace().contentMap(), ws.contentMap()); assertTrue(j.pending().isEmpty())
    }

    @Test fun aCrashAtEveryMutationIsRepairedByRecovery() {
        val probe = base.workspace(); integrator(null).apply(probe, cs(), base.manifest, ctx); val steps = probe.mutations
        for (k in 1..steps) {
            val j = InMemoryApplyJournal(); val ws = base.workspace(); val pre = ws.contentMap(); ws.crashOnMutation = k
            try { integrator(j).apply(ws, cs(), base.manifest, ctx); throw AssertionError("k=$k no crash") } catch (_: SimulatedCrash) { }
            assertEquals("k=$k", 1, j.pending().size); ws.crashOnMutation = 0
            assertEquals(listOf(TaskId("t")), ApplyRecovery.recover(ws, j)); assertEquals("k=$k", pre, ws.contentMap()); assertTrue(j.pending().isEmpty())
            assertEquals("recovery is idempotent", emptyList<TaskId>(), ApplyRecovery.recover(ws, j))
        }
    }

    @Test fun ifTheJournalCannotBeWrittenNothingIsTouched() {
        val failing = object : ApplyJournal { override fun started(task: TaskId, rollback: SnapshotId) { throw IllegalStateException("journal disk full") }
            override fun finished(task: TaskId) {}; override fun pending(): List<PendingApply> = emptyList() }
        val ws = base.workspace(); val before = ws.contentMap(); val m = ws.mutations
        val r = integrator(failing).apply(ws, cs(), base.manifest, ctx); assertTrue("$r", r is ApplyReport.Rejected); assertEquals(before, ws.contentMap()); assertEquals(m, ws.mutations)
    }

    @Test fun precheckReportsTheSameProblemsAsApply() {
        val bad = ChangeSet(TaskId("t"), AgentId("dev"), base.manifest.snapshotId(), listOf(FileChange.Add(p(".env"), "k".toByteArray())))
        assertTrue(integrator(null).precheck(bad, base.manifest).isNotEmpty()); assertEquals(emptyList<String>(), integrator(null).precheck(cs(), base.manifest))
    }
}

package com.anvex.agents.core

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.GateState
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.SchedulerCommand
import com.anvex.agents.core.events.SchedulerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequeueTest {
    private fun requeue(sim: Sim, id: String) = sim.send(SchedulerEvent.Requeue(sim.t + 1, TaskId(id), "process restarted"))

    @Test fun interruptedWorkIsRedoneWithoutConsumingAnAttempt() {
        val sim = Sim(); sim.add(task("t1")); sim.started("t1", "a")
        requeue(sim, "t1"); assertEquals("re-admitted immediately", TaskState.Assigned, sim.state("t1")); assertEquals("the interrupted attempt is not counted", 1, sim.node("t1").attempts)
        assertTrue(sim.last.any { it is SchedulerCommand.CancelAgent } || sim.last.any { it is SchedulerCommand.StartAgent })
        assertEquals(listOf("t1:a", "t1:a"), sim.allStarts())
    }

    @Test fun eligibleStatesAreRequeuedAndOthersIgnored() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1"); assertEquals(GateState.Required, sim.node("t1").gate)
        requeue(sim, "t1"); assertEquals(TaskState.Assigned, sim.state("t1")); assertEquals(GateState.NotRequired, sim.node("t1").gate); assertTrue(sim.node("t1").author == null)
        val s2 = Sim(); s2.add(task("t1")); s2.runToReview("t1"); s2.approveBy("t1"); s2.applied("t1", ApplyOutcome.Conflicts(1)); requeue(s2, "t1"); assertEquals(TaskState.Assigned, s2.state("t1"))
        val s3 = Sim(); s3.add(task("t1")); s3.complete("t1"); val before = s3.s; requeue(s3, "t1"); assertEquals("completed work is never redone", before.tasks, s3.s.tasks); assertEquals(1, s3.last.count { it is SchedulerCommand.Audit })
        val s4 = Sim(); s4.add(task("t1")); s4.started("t1", "a"); s4.failed("t1", "a", FailureKind.Fatal); requeue(s4, "t1"); assertEquals(TaskState.Failed, s4.state("t1"))
        val s5 = Sim(); s5.send(SchedulerEvent.Requeue(5, TaskId("ghost"), "x")); assertEquals(1, s5.last.count { it is SchedulerCommand.Audit })
    }

    @Test fun requeuedTasksKeepHoldingTheirFileClaims() {
        val sim = Sim(); sim.add(task("t1", claims = listOf("app/**"))); sim.add(task("t2", claims = listOf("app/x"), assignees = listOf("b")))
        assertEquals(listOf("t1:a"), sim.allStarts()); sim.started("t1", "a"); requeue(sim, "t1"); assertEquals(TaskState.Pending, sim.state("t2"))
    }

    @Test fun repeatedRestartsNeverExhaustAttempts() {
        val sim = Sim(); sim.add(task("t1")); repeat(10) { requeue(sim, "t1") }
        assertEquals(TaskState.Assigned, sim.state("t1")); assertEquals(1, sim.node("t1").attempts); assertEquals(11, sim.allStarts().size)
    }

    @Test fun requeueClearsAnyApplyOrReviewState() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1"); sim.approveBy("t1")
        assertTrue(sim.node("t1").applyRequested); requeue(sim, "t1")
        val n = sim.node("t1"); assertTrue(!n.applyRequested && n.verifiedBy == null && n.author == null && n.runs.values.all { !it.isTerminal })
        sim.applied("t1"); assertTrue("a stale apply result after the requeue is a violation", sim.last.any { it is SchedulerCommand.Audit && it.kind == "GateViolation" })
        assertEquals(AgentId("a"), n.spec.assignees.single())
    }
}

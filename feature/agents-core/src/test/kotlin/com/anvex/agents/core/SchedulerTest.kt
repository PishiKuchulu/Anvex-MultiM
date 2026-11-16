package com.anvex.agents.core

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.budget.RetryPolicy
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.BlockReason
import com.anvex.agents.core.domain.GateState
import com.anvex.agents.core.domain.RunState
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.Notice
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerCommand
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.events.VerdictChannel
import com.anvex.agents.core.scheduler.SchedulerConfig
import com.anvex.agents.core.scheduler.UserNeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    private fun List<SchedulerCommand>.audits(kind: String) = filterIsInstance<SchedulerCommand.Audit>().filter { it.kind == kind }
    private fun List<SchedulerCommand>.notices() = filterIsInstance<SchedulerCommand.Notify>().map { it.notice }

    // ---------------------------------------------------------------- parallelism and ordering
    @Test fun independentTasksRunInParallelUpToTheCap() {
        val sim = Sim(SchedulerConfig(maxConcurrent = 2))
        sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b"))); sim.add(task("t3", assignees = listOf("c")))
        assertEquals(listOf("t1:a", "t2:b"), sim.allStarts()); assertEquals(TaskState.Pending, sim.state("t3"))
        assertEquals(2, sim.s.liveRuns())
        sim.started("t1", "a"); sim.finished("t1", "a")
        assertEquals("a finished run frees a slot immediately", listOf("t1:a", "t2:b", "t3:c"), sim.allStarts())
        assertEquals(2, sim.s.liveRuns())
    }

    @Test fun dependentTaskWaitsForCompletionNotJustFinish() {
        val sim = Sim()
        sim.add(task("t1")); sim.add(task("t2", deps = listOf("t1"), assignees = listOf("b")))
        assertEquals(listOf("t1:a"), sim.allStarts())
        sim.runToReview("t1"); assertEquals("review pending: dependent must wait", TaskState.Pending, sim.state("t2"))
        sim.approveBy("t1"); assertEquals(TaskState.Pending, sim.state("t2"))
        sim.applied("t1"); assertEquals(TaskState.Assigned, sim.state("t2")); assertEquals(listOf("t1:a", "t2:b"), sim.allStarts())
    }

    @Test fun overlappingClaimsAreSerializedAndDisjointOnesRunTogether() {
        val sim = Sim()
        sim.add(task("t1", claims = listOf("app/**")))
        sim.add(task("t2", claims = listOf("app/x.kt"), assignees = listOf("b")))
        sim.add(task("t3", claims = listOf("lib/**"), assignees = listOf("c")))
        assertEquals(listOf("t1:a", "t3:c"), sim.allStarts()); assertEquals(TaskState.Pending, sim.state("t2"))
        sim.runToReview("t1"); assertEquals("claims are held until the task is Completed", TaskState.Pending, sim.state("t2"))
        sim.approveBy("t1"); sim.applied("t1")
        assertTrue("t2 starts once t1 completed", sim.allStarts().contains("t2:b"))
    }

    @Test fun unclaimedTasksParallelByDefaultAndSerializeWhenConfigured() {
        val par = Sim(); par.add(task("t1")); par.add(task("t2", assignees = listOf("b"))); assertEquals(2, par.allStarts().size)
        val ser = Sim(SchedulerConfig(serializeUnclaimed = true)); ser.add(task("t1")); ser.add(task("t2", assignees = listOf("b")))
        assertEquals(listOf("t1:a"), ser.allStarts())
        val mixed = Sim(SchedulerConfig(serializeUnclaimed = true)); mixed.add(task("t1")); mixed.add(task("t2", claims = listOf("lib/**"), assignees = listOf("b")))
        assertEquals("an unclaimed task is treated as claiming everything", listOf("t1:a"), mixed.allStarts())
    }

    @Test fun priorityDecidesAdmissionOrder() {
        val sim = Sim(SchedulerConfig(maxConcurrent = 1))
        sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b"))); sim.add(task("t3", assignees = listOf("c"), priority = 5)); sim.add(task("t4", assignees = listOf("b"), priority = 5))
        sim.complete("t1")
        assertEquals("highest priority, then insertion order", listOf("t1:a", "t3:c"), sim.allStarts())
        sim.complete("t3", "c"); assertEquals(listOf("t1:a", "t3:c", "t4:b"), sim.allStarts())
    }

    @Test fun pausedRunStillHoldsItsSlotAndResumeWorks() {
        val sim = Sim(SchedulerConfig(maxConcurrent = 1))
        sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b"))); sim.started("t1", "a")
        sim.send(SchedulerEvent.PauseAgent(sim.t + 1, AgentId("a")))
        assertEquals(RunState.Paused, sim.node("t1").runs[AgentId("a")]); assertTrue(sim.last.any { it is SchedulerCommand.PauseAgent })
        sim.kick(); assertEquals("paused run keeps the slot", listOf("t1:a"), sim.allStarts())
        sim.send(SchedulerEvent.ResumeAgent(sim.t + 1, AgentId("a"))); assertEquals(RunState.Running, sim.node("t1").runs[AgentId("a")])
        assertTrue(sim.last.any { it is SchedulerCommand.ResumeAgent })
        sim.send(SchedulerEvent.PauseAgent(sim.t + 1, AgentId("b"))); assertEquals(1, sim.last.audits("EventIgnored").size)
    }

    @Test fun raisingTheCapStartsWaitingWorkAndInvalidValuesAreIgnored() {
        val sim = Sim(SchedulerConfig(maxConcurrent = 1)); sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b")))
        assertEquals(1, sim.allStarts().size)
        sim.send(SchedulerEvent.SetMaxConcurrent(sim.t + 1, 2)); assertEquals(2, sim.allStarts().size)
        sim.send(SchedulerEvent.SetMaxConcurrent(sim.t + 1, 0)); assertEquals(1, sim.last.audits("EventIgnored").size); assertEquals(2, sim.s.config.maxConcurrent)
    }

    // ---------------------------------------------------------------- failures and retries
    @Test fun retryUsesBackoffThenFailsAndBlocksDependents() {
        val sim = Sim(SchedulerConfig(retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 1000, factor = 2.0, maxDelayMs = 60_000, rateLimitDelayMs = 5_000)))
        sim.add(task("t1")); sim.add(task("t2", deps = listOf("t1"), assignees = listOf("b")))
        sim.started("t1", "a"); sim.failed("t1", "a", FailureKind.Error)
        val nb1 = sim.node("t1").notBefore
        assertEquals(TaskState.Pending, sim.state("t1")); assertEquals(sim.s.now + 1000, nb1); assertEquals(nb1, sim.s.nextWakeAt())
        sim.kick(nb1 - 1); assertEquals("too early", 1, sim.allStarts().size)
        sim.kick(nb1); assertEquals(listOf("t1:a", "t1:a"), sim.allStarts()); assertEquals(2, sim.node("t1").attempts)
        assertEquals(2, (sim.commands.filterIsInstance<SchedulerCommand.StartAgent>().last()).attempt)
        sim.started("t1", "a"); sim.failed("t1", "a", FailureKind.Timeout)
        val nb2 = sim.node("t1").notBefore; assertEquals("second delay doubles", sim.s.now + 2000, nb2)
        sim.kick(nb2); sim.started("t1", "a"); sim.failed("t1", "a", FailureKind.Error, "still broken")
        assertEquals(TaskState.Failed, sim.state("t1")); assertTrue(sim.last.notices().contains(Notice.TaskFailed))
        assertEquals(TaskState.Blocked, sim.state("t2")); assertEquals(BlockReason.DependencyFailed, sim.node("t2").block)
        sim.send(SchedulerEvent.RetryTask(sim.t + 1, TaskId("t1")))
        assertEquals(TaskState.Assigned, sim.state("t1")); assertEquals(1, sim.node("t1").attempts); assertEquals(TaskState.Pending, sim.state("t2"))
    }

    @Test fun fatalFailureIsNotRetriedAndRateLimitWaitsLonger() {
        val sim = Sim(SchedulerConfig(retry = RetryPolicy(maxAttempts = 5, baseDelayMs = 100, factor = 2.0, maxDelayMs = 1000, rateLimitDelayMs = 7_000)))
        sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b")))
        sim.started("t1", "a"); sim.failed("t1", "a", FailureKind.Fatal); assertEquals(TaskState.Failed, sim.state("t1")); assertEquals(1, sim.node("t1").attempts)
        sim.started("t2", "b"); sim.failed("t2", "b", FailureKind.RateLimited)
        assertEquals(sim.s.now + 7000, sim.node("t2").notBefore)
    }

    @Test fun failureTextIsScrubbedOfCredentials() {
        val sim = Sim(); sim.add(task("t1")); sim.started("t1", "a")
        sim.failed("t1", "a", FailureKind.Fatal, "401 for key sk-abcdefghijklmnopqrstuvwxyz0123")
        assertFalse(sim.node("t1").failure!!.contains("sk-abcdefghijklmnop")); assertTrue(sim.node("t1").failure!!.contains("[REDACTED]"))
        assertTrue(sim.commands.filterIsInstance<SchedulerCommand.Notify>().none { it.detail.contains("sk-abcdefghijklmnop") })
    }

    // ---------------------------------------------------------------- gates (maker is never verifier)
    @Test fun authorCannotVerifyOwnWork() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1")
        assertEquals(GateState.Required, sim.node("t1").gate)
        sim.verdict("t1", Reviewer.Agent(AgentId("a")), true, VerdictChannel.ReviewerRun)
        assertEquals(TaskState.UnderReview, sim.state("t1")); assertEquals(GateState.Required, sim.node("t1").gate)
        assertEquals(1, sim.last.audits("GateViolation").size); assertTrue(sim.last.notices().contains(Notice.GateViolationAttempt))
    }

    /** The author HAS the approve grant and uses the right channel: only the independence rule can stop it. */
    @Test fun anApproveCapableAuthorStillCannotVerifyItsOwnWork() {
        val sim = Sim(); sim.add(task("t1", assignees = listOf("qa"))); sim.runToReview("t1", "qa")
        sim.verdict("t1", Reviewer.Agent(AgentId("qa")), true, VerdictChannel.ReviewerRun)
        assertEquals(GateState.Required, sim.node("t1").gate); assertEquals(1, sim.last.audits("GateViolation").size)
        assertTrue(sim.last.audits("GateViolation").single().detail.contains("author"))
        sim.verdict("t1", Reviewer.Agent(AgentId("qb")), true, VerdictChannel.ReviewerRun)   // a different approve-capable agent may
        assertEquals(GateState.Satisfied, sim.node("t1").gate)
    }

    @Test fun anApproveCapableCompetitorCannotReviewTheTaskEither() {
        val sim = Sim(); sim.add(task("c1", assignees = listOf("a", "qa"))); sim.started("c1", "a"); sim.started("c1", "qa"); sim.finished("c1", "a"); sim.finished("c1", "qa"); sim.chose("c1", "a")
        sim.verdict("c1", Reviewer.Agent(AgentId("qa")), true, VerdictChannel.ReviewerRun)
        assertEquals("the losing competitor is an author too", GateState.Required, sim.node("c1").gate)
        assertTrue(sim.last.audits("GateViolation").single().detail.contains("author"))
    }

    @Test fun messagesAndWrongChannelsNeverSatisfyAGate() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1")
        sim.verdict("t1", Reviewer.Agent(AgentId("r1")), true, VerdictChannel.AgentMessage)
        assertEquals("manager-style approval by message", GateState.Required, sim.node("t1").gate)
        sim.verdict("t1", Reviewer.User, true, VerdictChannel.AgentMessage); assertEquals(GateState.Required, sim.node("t1").gate)
        sim.verdict("t1", Reviewer.User, true, VerdictChannel.ReviewerRun); assertEquals("a user verdict must come from the UI", GateState.Required, sim.node("t1").gate)
        sim.verdict("t1", Reviewer.Agent(AgentId("r1")), true, VerdictChannel.UserUi); assertEquals("an agent cannot use the user channel", GateState.Required, sim.node("t1").gate)
        assertEquals(4, sim.commands.audits("GateViolation").size)
        assertTrue("no apply request may have been issued", sim.commands.none { it is SchedulerCommand.RequestApply })
    }

    @Test fun reviewersNeedApproveGrantAndMustExist() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1")
        sim.approveBy("t1", "noapprove"); assertEquals(GateState.Required, sim.node("t1").gate)
        sim.approveBy("t1", "ghost"); assertEquals(GateState.Required, sim.node("t1").gate)
        sim.approveBy("t1", "r2"); assertEquals(GateState.Satisfied, sim.node("t1").gate); assertEquals(Reviewer.Agent(AgentId("r2")), sim.node("t1").verifiedBy)
    }

    @Test fun userCanSatisfyAndRejectThroughTheUi() {
        val sim = Sim(); sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b"))); sim.runToReview("t1"); sim.runToReview("t2", "b")
        sim.verdict("t1", Reviewer.User, true, VerdictChannel.UserUi); assertEquals(GateState.Satisfied, sim.node("t1").gate)
        assertTrue(sim.last.any { it is SchedulerCommand.RequestApply && it.task == TaskId("t1") })
        sim.verdict("t2", Reviewer.User, false, VerdictChannel.UserUi); assertEquals(TaskState.Assigned, sim.state("t2")); assertEquals(2, sim.node("t2").attempts)
    }

    @Test fun applyWithoutSatisfiedGateIsAViolationAndCannotComplete() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1")
        sim.applied("t1"); assertEquals(TaskState.UnderReview, sim.state("t1")); assertEquals(1, sim.last.audits("GateViolation").size)
        sim.approveBy("t1"); sim.applied("t1"); assertEquals(TaskState.Completed, sim.state("t1"))
        sim.applied("t1"); assertEquals("a second apply is a violation", 1, sim.last.audits("GateViolation").size); assertEquals(TaskState.Completed, sim.state("t1"))
    }

    /** Defense in depth: even if the apply flag disagreed with the gate (a bug elsewhere), a task cannot complete without the gate. */
    @Test fun applyIsRefusedWhenTheGateIsUnsatisfiedEvenIfTheApplyFlagIsSet() {
        for (gate in listOf(GateState.Required, GateState.Rejected)) {
            val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1")
            val n = sim.node("t1"); sim.s = sim.s.copy(tasks = sim.s.tasks + (n.spec.id to n.copy(applyRequested = true, gate = gate)))
            sim.applied("t1")
            assertEquals("gate=$gate", TaskState.UnderReview, sim.state("t1")); assertEquals(1, sim.last.audits("GateViolation").size)
        }
    }

    @Test fun verdictForTaskNotAwaitingReviewIsIgnored() {
        val sim = Sim(); sim.add(task("t1")); sim.approveBy("t1"); assertEquals(1, sim.last.audits("VerdictIgnored").size); assertEquals(TaskState.Assigned, sim.state("t1"))
        sim.approveBy("nope"); assertEquals(1, sim.last.audits("VerdictIgnored").size)
    }

    @Test fun reviewRejectionTriggersRevisionAndEventuallyFails() {
        val sim = Sim(SchedulerConfig(retry = RetryPolicy(maxAttempts = 2)))
        sim.add(task("t1")); sim.runToReview("t1"); sim.rejectBy("t1")
        assertEquals(TaskState.Assigned, sim.state("t1")); assertEquals(2, sim.node("t1").attempts); assertEquals(GateState.NotRequired, sim.node("t1").gate); assertNull(sim.node("t1").author)
        sim.runToReview("t1"); sim.rejectBy("t1")
        assertEquals("attempts exhausted", TaskState.Failed, sim.state("t1")); assertTrue(sim.last.notices().contains(Notice.TaskFailed))
    }

    @Test fun taskWithoutReviewRequirementGoesStraightToApply() {
        val sim = Sim(); sim.add(task("t1", review = false)); sim.runToReview("t1")
        assertEquals(GateState.NotRequired, sim.node("t1").gate); assertTrue(sim.last.any { it is SchedulerCommand.RequestApply })
        sim.applied("t1"); assertEquals(TaskState.Completed, sim.state("t1"))
    }

    // ---------------------------------------------------------------- competing implementations
    @Test fun competingResultsRequireAUserChoiceThenReview() {
        val sim = Sim(); sim.add(task("c1", assignees = listOf("a", "b")))
        assertEquals(listOf("c1:a", "c1:b"), sim.allStarts()); sim.started("c1", "a"); sim.started("c1", "b")
        sim.finished("c1", "a"); assertEquals(TaskState.InProgress, sim.state("c1"))
        sim.finished("c1", "b"); assertEquals(TaskState.UnderReview, sim.state("c1")); assertTrue(sim.node("c1").awaitingChoice); assertTrue(sim.last.notices().contains(Notice.ChoiceNeeded))
        sim.approveBy("c1"); assertEquals("no verdict before a result was chosen", 1, sim.last.audits("VerdictIgnored").size)
        sim.applied("c1"); assertEquals(1, sim.last.audits("GateViolation").size)
        sim.chose("c1", "b"); assertEquals(AgentId("b"), sim.node("c1").author); assertTrue(sim.last.any { it is SchedulerCommand.RequestReview && it.author == AgentId("b") })
        sim.approveBy("c1", "r1"); sim.applied("c1"); assertEquals(TaskState.Completed, sim.state("c1"))
    }

    @Test fun anyCompetitorIsExcludedFromReviewingTheTask() {
        val sim = Sim(); sim.add(task("c1", assignees = listOf("a", "b"))); sim.started("c1", "a"); sim.started("c1", "b"); sim.finished("c1", "a"); sim.finished("c1", "b"); sim.chose("c1", "a")
        sim.approveBy("c1", "b"); assertEquals("the losing competitor is still an author", GateState.Required, sim.node("c1").gate); assertEquals(1, sim.last.audits("GateViolation").size)
    }

    @Test fun choiceMustNameAFinishedCompetitor() {
        val sim = Sim(); sim.add(task("c1", assignees = listOf("a", "b"))); sim.started("c1", "a"); sim.started("c1", "b"); sim.finished("c1", "a"); sim.failed("c1", "b", FailureKind.Fatal)
        assertEquals("only one finished: it is chosen automatically", AgentId("a"), sim.node("c1").author); assertFalse(sim.node("c1").awaitingChoice)
        val s2 = Sim(); s2.add(task("c1", assignees = listOf("a", "b"))); s2.started("c1", "a"); s2.started("c1", "b"); s2.finished("c1", "a"); s2.finished("c1", "b")
        s2.chose("c1", "c"); assertTrue(s2.node("c1").awaitingChoice); assertEquals(1, s2.last.audits("EventIgnored").size)
    }

    @Test fun competitorsQueueWhenCapacityIsSmallAndBothFailingRetries() {
        val sim = Sim(SchedulerConfig(maxConcurrent = 1)); sim.add(task("c1", assignees = listOf("a", "b")))
        assertEquals(listOf("c1:a"), sim.allStarts()); assertEquals(RunState.Queued, sim.node("c1").runs[AgentId("b")])
        sim.started("c1", "a"); sim.finished("c1", "a"); assertEquals(listOf("c1:a", "c1:b"), sim.allStarts()); assertEquals(TaskState.InProgress, sim.state("c1"))
        sim.started("c1", "b"); sim.failed("c1", "b", FailureKind.Error); assertEquals("one result is enough", AgentId("a"), sim.node("c1").author)
        val both = Sim(); both.add(task("c2", assignees = listOf("a", "b"))); both.started("c2", "a"); both.started("c2", "b"); both.failed("c2", "a"); both.failed("c2", "b")
        assertEquals(TaskState.Pending, both.state("c2")); assertTrue(both.node("c2").notBefore > both.s.now)
    }

    // ---------------------------------------------------------------- apply outcomes and user decisions
    @Test fun conflictBlocksForTheUserKeepsClaimsAndRerunWorks() {
        val sim = Sim(); sim.add(task("t1", claims = listOf("app/**"))); sim.add(task("t2", claims = listOf("app/x"), assignees = listOf("b")))
        sim.runToReview("t1"); sim.approveBy("t1"); sim.applied("t1", ApplyOutcome.Conflicts(2))
        assertEquals(TaskState.Blocked, sim.state("t1")); assertEquals(UserNeed.ResolveConflict, sim.node("t1").userNeed); assertTrue(sim.last.notices().contains(Notice.ConflictNeedsUser))
        assertEquals("claims stay held while the user resolves", TaskState.Pending, sim.state("t2"))
        sim.userDecision("t1", UserAction.Approve); assertEquals("approve is not valid for a conflict", 1, sim.last.audits("EventIgnored").size); assertEquals(TaskState.Blocked, sim.state("t1"))
        sim.userDecision("t1", UserAction.Rerun); assertEquals(TaskState.Assigned, sim.state("t1")); assertEquals(2, sim.node("t1").attempts)
    }

    @Test fun escalationNeedsUserApprovalThenAppliesWithTheFlag() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1"); sim.approveBy("t1"); sim.applied("t1", ApplyOutcome.Escalated)
        assertEquals(UserNeed.ApprovalForApply, sim.node("t1").userNeed); assertTrue(sim.last.notices().contains(Notice.ApprovalNeeded))
        sim.userDecision("t1", UserAction.Approve)
        assertTrue(sim.last.any { it is SchedulerCommand.RequestApply && it.userApprovedEscalation }); assertEquals(TaskState.UnderReview, sim.state("t1"))
        sim.applied("t1"); assertEquals(TaskState.Completed, sim.state("t1"))
        val rej = Sim(); rej.add(task("t1")); rej.runToReview("t1"); rej.approveBy("t1"); rej.applied("t1", ApplyOutcome.Escalated); rej.userDecision("t1", UserAction.Reject)
        assertEquals(TaskState.Cancelled, rej.state("t1"))
    }

    @Test fun rejectedChangeSetGoesBackToTheAuthor() {
        val sim = Sim(); sim.add(task("t1")); sim.runToReview("t1"); sim.approveBy("t1"); sim.applied("t1", ApplyOutcome.Rejected("path outside grant"))
        assertEquals(TaskState.Assigned, sim.state("t1")); assertEquals(2, sim.node("t1").attempts)
    }

    @Test fun userDecisionOnATaskNotWaitingIsIgnored() {
        val sim = Sim(); sim.add(task("t1")); sim.userDecision("t1", UserAction.Reject); assertEquals(1, sim.last.audits("EventIgnored").size); assertEquals(TaskState.Assigned, sim.state("t1"))
    }

    // ---------------------------------------------------------------- cancellation, stop
    @Test fun cancellationPropagatesToDependentsAndKillsRuns() {
        val sim = Sim(); sim.add(task("t1")); sim.add(task("t2", deps = listOf("t1"), assignees = listOf("b"))); sim.add(task("t3", deps = listOf("t2"), assignees = listOf("c"))); sim.add(task("t4", assignees = listOf("c")))
        sim.started("t1", "a"); sim.cancel("t1")
        assertEquals(listOf(TaskState.Cancelled, TaskState.Cancelled, TaskState.Cancelled), listOf("t1", "t2", "t3").map { sim.state(it) })
        assertTrue(sim.last.any { it is SchedulerCommand.CancelAgent && it.task == TaskId("t1") }); assertEquals("unrelated task untouched", TaskState.Assigned, sim.state("t4"))
        assertEquals(RunState.Cancelled, sim.node("t1").runs[AgentId("a")])
    }

    @Test fun cancelAllStopsEverythingAndRejectsNewTasks() {
        val sim = Sim(); sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b"))); sim.started("t1", "a"); sim.send(SchedulerEvent.CancelAll(sim.t + 1))
        assertEquals(2, sim.last.count { it is SchedulerCommand.CancelAgent }); assertEquals(0, sim.s.liveRuns()); assertTrue(sim.s.tasks.values.all { it.state == TaskState.Cancelled })
        sim.add(task("t9")); assertEquals(1, sim.last.audits("TaskRejected").size); assertNull(sim.s.tasks[TaskId("t9")])
    }

    @Test fun stoppingAnAgentLeavesTheDecisionToTheUser() {
        val sim = Sim(); sim.add(task("t1")); sim.started("t1", "a"); sim.send(SchedulerEvent.StopAgent(sim.t + 1, AgentId("a")))
        assertEquals(RunState.Stopping, sim.node("t1").runs[AgentId("a")]); assertTrue(sim.last.any { it is SchedulerCommand.CancelAgent })
        sim.failed("t1", "a", FailureKind.Cancelled, "stopped by user")
        assertEquals(TaskState.Blocked, sim.state("t1")); assertEquals(UserNeed.StoppedByUser, sim.node("t1").userNeed); assertTrue(sim.last.notices().contains(Notice.AgentStopped))
        sim.userDecision("t1", UserAction.Rerun); assertEquals(TaskState.Assigned, sim.state("t1")); assertEquals(2, sim.node("t1").attempts)
    }

    @Test fun stoppingAQueuedCompetitorKeepsTheOtherRunning() {
        val sim = Sim(SchedulerConfig(maxConcurrent = 1)); sim.add(task("c1", assignees = listOf("a", "b")))
        sim.send(SchedulerEvent.StopAgent(sim.t + 1, AgentId("b"))); assertEquals(RunState.Cancelled, sim.node("c1").runs[AgentId("b")]); assertEquals(RunState.Starting, sim.node("c1").runs[AgentId("a")])
        sim.started("c1", "a"); sim.finished("c1", "a"); assertEquals(AgentId("a"), sim.node("c1").author)
    }

    // ---------------------------------------------------------------- budgets
    @Test fun budgetWarnsOnceThenStopsEverythingAndResumesWhenRaised() {
        val sim = Sim(SchedulerConfig(limits = BudgetLimits(maxTokens = 1000), warnFraction = 0.8))
        sim.add(task("t1")); sim.add(task("t2", assignees = listOf("b"))); sim.started("t1", "a"); sim.started("t2", "b")
        sim.usage("a", 400, 350, null, "t1"); assertTrue(sim.last.notices().isEmpty())
        sim.usage("a", 50, 0, null, "t1"); assertEquals(listOf(Notice.BudgetWarning), sim.last.notices())
        sim.usage("a", 10, 0, null, "t1"); assertTrue("warn only once", sim.last.notices().isEmpty())
        sim.usage("b", 100, 0, null, "t2"); assertTrue(sim.last.notices().isEmpty())
        sim.usage("b", 100, 0, null, "t2")
        assertEquals(listOf(Notice.BudgetExhausted), sim.last.notices()); assertEquals(2, sim.last.count { it is SchedulerCommand.CancelAgent })
        assertEquals(0, sim.s.liveRuns()); assertEquals(BlockReason.BudgetExhausted, sim.node("t1").block); assertEquals(0, sim.node("t1").attempts)
        sim.add(task("t3", assignees = listOf("c"))); assertEquals("exhausted budget admits nothing", 2, sim.allStarts().size); assertEquals(TaskState.Pending, sim.state("t3"))
        sim.send(SchedulerEvent.RaiseBudget(sim.t + 1, BudgetLimits(maxTokens = 5000)))
        assertEquals(3, sim.s.liveRuns()); assertEquals(1, sim.node("t1").attempts); assertFalse(sim.s.budget.exhausted)
    }

    @Test fun costCapOnlyCountsPricedUsageAndRespectsTokenCap() {
        val sim = Sim(SchedulerConfig(limits = BudgetLimits(maxCostUsd = 1.0))); sim.add(task("t1")); sim.started("t1", "a")
        sim.usage("a", 10_000_000, 10_000_000, null, "t1"); assertFalse("unpriced usage cannot trip a cost cap", sim.s.budget.exhausted)
        sim.usage("a", 1, 1, 0.9, "t1"); assertFalse(sim.s.budget.exhausted); sim.usage("a", 1, 1, 0.2, "t1"); assertTrue(sim.s.budget.exhausted)
        assertEquals(1, sim.s.budget.ledger.total.unpricedResponses)
    }

    @Test fun invalidUsageIsIgnored() {
        val sim = Sim(); sim.add(task("t1")); sim.usage("a", -1, 0, null); assertEquals(1, sim.last.audits("EventIgnored").size); assertEquals(0L, sim.s.budget.ledger.total.tokens)
        sim.usage("a", 1, 1, Double.NaN); assertEquals(1, sim.last.audits("EventIgnored").size)
    }

    // ---------------------------------------------------------------- robustness
    @Test fun staleOrBogusEventsAreAuditedAndChangeNothing() {
        val sim = Sim(); sim.add(task("t1")); val before = sim.s
        sim.started("ghost", "a"); sim.finished("t1", "b"); sim.failed("t1", "b"); sim.chose("t1", "a")
        assertEquals(before.copy(now = sim.s.now), sim.s)
        sim.started("t1", "a"); sim.started("t1", "a"); assertEquals(1, sim.last.audits("StaleEvent").size)
        sim.finished("t1", "a"); sim.finished("t1", "a"); assertEquals(1, sim.last.audits("StaleEvent").size)
    }

    @Test fun taskAdditionValidation() {
        val sim = Sim(); sim.add(task("t1"))
        sim.add(task("t1")); assertEquals("duplicate", 1, sim.last.audits("TaskRejected").size)
        sim.add(task("t2", deps = listOf("missing"))); assertEquals(1, sim.last.audits("TaskRejected").size)
        sim.add(task("t3", assignees = listOf("ghost"))); assertEquals(1, sim.last.audits("TaskRejected").size)
        assertEquals(1, sim.s.tasks.size)
    }

    @Test fun addingATaskWhoseDependencyAlreadyFailedStartsBlocked() {
        val sim = Sim(); sim.add(task("t1")); sim.started("t1", "a"); sim.failed("t1", "a", FailureKind.Fatal)
        sim.add(task("t2", deps = listOf("t1"), assignees = listOf("b"))); assertEquals(TaskState.Blocked, sim.state("t2")); assertEquals(BlockReason.DependencyFailed, sim.node("t2").block)
    }

    @Test fun timeNeverMovesBackwards() {
        val sim = Sim(); sim.kick(5000); sim.kick(100); assertEquals(5000L, sim.s.now)
    }

    @Test fun wakeTimeIsReportedForWaitingRetries() {
        val sim = Sim(); assertNull(sim.s.nextWakeAt()); sim.add(task("t1")); sim.started("t1", "a"); sim.failed("t1", "a"); assertNotNull(sim.s.nextWakeAt())
    }
}

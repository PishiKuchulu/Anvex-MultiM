package com.anvex.agents.core

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.budget.RetryPolicy
import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.BlockReason
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.events.EventCodec
import com.anvex.agents.core.events.EventLog
import com.anvex.agents.core.events.EventRecord
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.GuardedEventStore
import com.anvex.agents.core.events.InMemoryEventStore
import com.anvex.agents.core.events.Notice
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.events.VerdictChannel
import com.anvex.agents.core.integrator.Integrator
import com.anvex.agents.core.integrator.InMemoryApplyJournal
import com.anvex.agents.core.orchestrator.AuditPort
import com.anvex.agents.core.orchestrator.Notifier
import com.anvex.agents.core.orchestrator.Orchestrator
import com.anvex.agents.core.orchestrator.RunLauncher
import com.anvex.agents.core.policy.PolicyEngine
import com.anvex.agents.core.ports.ClockPort
import com.anvex.agents.core.ports.EventStorePort
import com.anvex.agents.core.ports.WorkspaceFileSystem
import com.anvex.agents.core.scheduler.Scheduler
import com.anvex.agents.core.scheduler.SchedulerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecLauncher : RunLauncher {
    class Start(val task: TaskSpec, val agent: AgentProfile, val attempt: Int, val ws: WorkspaceFileSystem)
    class Review(val task: TaskSpec, val author: AgentId, val reviewer: AgentProfile, val cs: ChangeSet)
    val starts = ArrayList<Start>(); val reviews = ArrayList<Review>(); val cancels = ArrayList<String>(); val pauses = ArrayList<String>(); val resumes = ArrayList<String>()
    override fun startAgent(task: TaskSpec, agent: AgentProfile, attempt: Int, workspace: WorkspaceFileSystem) { starts += Start(task, agent, attempt, workspace) }
    override fun cancel(task: TaskId, agent: AgentId) { cancels += "$task:$agent" }
    override fun pause(task: TaskId, agent: AgentId) { pauses += "$task:$agent" }
    override fun resume(task: TaskId, agent: AgentId) { resumes += "$task:$agent" }
    override fun startReview(task: TaskSpec, author: AgentId, reviewer: AgentProfile, changes: ChangeSet) { reviews += Review(task, author, reviewer, changes) }
    fun lastStart(task: String, agent: String) = starts.last { it.task.id.value == task && it.agent.id.value == agent }
    fun startNames() = starts.map { "${it.task.id}:${it.agent.id}" }
}

class FlakyStore(private val inner: EventStorePort) : EventStorePort {
    var fail = false
    override fun append(runId: String, record: EventRecord) { if (fail) throw IllegalStateException("disk full"); inner.append(runId, record) }
    override fun readAll(runId: String) = inner.readAll(runId)
}

class OrchRig(
    files: Map<String, String>, val cfg: SchedulerConfig = SchedulerConfig(), val agents: List<AgentProfile> = defaultAgents(),
    val rawStore: InMemoryEventStore = InMemoryEventStore(), val mem: MemStore = MemStore(),
) {
    val integration = MemWorkspace("proj", mem)
    val journal = InMemoryApplyJournal()
    val provider = MemProvider(mem)
    val sens = FakeSensitive()
    val integrator = Integrator(PolicyEngine(sens), sens, mem.blobs, journal)
    val launcher = RecLauncher()
    val flaky = FlakyStore(rawStore)
    val notices = ArrayList<Notice>(); val audits = ArrayList<String>()
    var tick = 1000L
    val clock = object : ClockPort { override fun nowMillis() = ++tick }
    private val notifier = object : Notifier { override fun notify(notice: Notice, task: TaskId?, detail: String) { notices += notice } }
    private val auditPort = object : AuditPort { override fun record(kind: String, task: TaskId?, agent: AgentId?, detail: String) { audits += kind } }
    val orch: Orchestrator

    init {
        files.forEach { (p, t) -> integration.put(p, t) }
        orch = Orchestrator("run1", Scheduler.initial(cfg, agents), provider, provider, integration, integrator, GuardedEventStore(flaky), launcher, notifier, auditPort, journal, clock)
    }
    fun at() = ++tick
    fun add(spec: TaskSpec) = orch.submit(SchedulerEvent.TaskAdded(at(), spec))
    fun state(task: String) = orch.state.tasks[TaskId(task)]!!.state
    fun node(task: String) = orch.state.tasks[TaskId(task)]!!
    fun text(path: String) = integration.text(path)

    fun recoverOrch(l: RecLauncher): Orchestrator = Orchestrator.recover("run1", cfg, agents, provider, provider, integration, integrator, GuardedEventStore(rawStore), l, notifier, auditPort, journal, clock)

    /** Simulates the host running an agent: started, work happens in the fork, finished, thread has ended. */
    fun agentRuns(task: String, agent: String, edit: (MemWorkspace) -> Unit = {}) = agentRuns(orch, launcher, task, agent, edit)
    fun agentRuns(o: Orchestrator, l: RecLauncher, task: String, agent: String, edit: (MemWorkspace) -> Unit = {}) {
        val ws = l.lastStart(task, agent).ws as MemWorkspace
        o.submit(SchedulerEvent.AgentStarted(at(), TaskId(task), AgentId(agent))); edit(ws)
        o.submit(SchedulerEvent.AgentFinished(at(), TaskId(task), AgentId(agent))); o.runEnded(TaskId(task), AgentId(agent))
    }
    fun reviewerOf(l: RecLauncher, task: String) = l.reviews.last { it.task.id.value == task }.reviewer.id.value
    fun approve(task: String) = approve(orch, launcher, task)
    fun approve(o: Orchestrator, l: RecLauncher, task: String) = o.submit(SchedulerEvent.ReviewVerdict(at(), TaskId(task), Reviewer.Agent(AgentId(reviewerOf(l, task))), true, VerdictChannel.ReviewerRun))
    fun userDecision(task: String, a: UserAction) = orch.submit(SchedulerEvent.UserDecision(at(), TaskId(task), a))
}

class OrchestratorTest {
    private val lines = (1..30).joinToString("") { "line $it\n" }
    private fun edit(vararg c: Pair<Int, String>): String { val l = lines.split("\n").toMutableList(); for ((i, v) in c) l[i - 1] = v; return l.joinToString("\n") }
    private fun replayState(r: OrchRig) = Scheduler.replay(Scheduler.initial(r.cfg, r.agents), r.orch.log.events()).state

    @Test fun twoParallelAgentsAreIsolatedReviewedIndependentlyAndMerged() {
        val r = OrchRig(mapOf("shared.kt" to lines, "other.kt" to "o\n"))
        r.add(task("t1", assignees = listOf("a"))); r.add(task("t2", assignees = listOf("b")))
        assertEquals(listOf("t1:a", "t2:b"), r.launcher.startNames()); assertEquals(2, r.provider.live.size)
        assertTrue("forks are separate workspaces", r.launcher.starts[0].ws !== r.launcher.starts[1].ws)
        r.agentRuns("t1", "a") { it.put("shared.kt", edit(3 to "A3")) }
        r.agentRuns("t2", "b") { it.put("shared.kt", edit(28 to "B28")); it.put("new.kt", "n\n") }
        assertEquals("nothing is integrated before the review", lines, r.text("shared.kt"))
        for (t in listOf("t1", "t2")) { val rv = r.launcher.reviews.single { it.task.id.value == t }; assertTrue(rv.reviewer.grant.approve); assertFalse(rv.reviewer.id in rv.task.assignees); assertEquals(t, rv.cs.task.value) }
        r.approve("t1"); assertEquals(TaskState.Completed, r.state("t1")); assertEquals(edit(3 to "A3"), r.text("shared.kt"))
        r.approve("t2"); assertEquals(TaskState.Completed, r.state("t2"))
        assertEquals(edit(3 to "A3", 28 to "B28"), r.text("shared.kt")); assertEquals("n\n", r.text("new.kt")); assertEquals("o\n", r.text("other.kt"))
        assertTrue(r.provider.live.isEmpty()); assertEquals(2, r.provider.disposed.size); assertEquals(0, r.orch.liveForkCount())
        assertTrue(r.orch.log.verify()); assertEquals(replayState(r), r.orch.state); assertEquals(r.rawStore.readAll("run1").size, r.orch.log.size)
    }

    @Test fun aLaterTaskForksFromTheAlreadyIntegratedState() {
        val r = OrchRig(mapOf("shared.kt" to lines))
        r.add(task("t1")); r.add(task("t2", deps = listOf("t1"), assignees = listOf("b")))
        r.agentRuns("t1", "a") { it.put("shared.kt", edit(3 to "A3")) }; r.approve("t1")
        assertEquals("t2 starts only after t1 completed, from the updated project", edit(3 to "A3"), (r.launcher.lastStart("t2", "b").ws as MemWorkspace).text("shared.kt"))
    }

    @Test fun overlappingWorkBlocksForTheUserAndARerunStartsFromTheUpdatedProject() {
        val r = OrchRig(mapOf("shared.kt" to lines))
        r.add(task("t1")); r.add(task("t2", assignees = listOf("b")))
        r.agentRuns("t1", "a") { it.put("shared.kt", edit(10 to "A10")) }; r.agentRuns("t2", "b") { it.put("shared.kt", edit(10 to "B10")) }
        r.approve("t1"); val afterA = r.integration.contentMap()
        r.approve("t2")
        assertEquals(TaskState.Blocked, r.state("t2")); assertEquals(BlockReason.AwaitingUser, r.node("t2").block); assertTrue(r.notices.contains(Notice.ConflictNeedsUser))
        assertEquals("the conflicting change set wrote nothing", afterA, r.integration.contentMap())
        r.userDecision("t2", UserAction.Rerun)
        val second = r.launcher.lastStart("t2", "b"); assertEquals(2, second.attempt); assertEquals(edit(10 to "A10"), (second.ws as MemWorkspace).text("shared.kt"))
        r.agentRuns("t2", "b") { it.put("shared.kt", edit(10 to "A10", 25 to "B25")) }; r.approve("t2")
        assertEquals(edit(10 to "A10", 25 to "B25"), r.text("shared.kt")); assertEquals(TaskState.Completed, r.state("t2"))
    }

    @Test fun unusableOutputNeverReachesReviewAndBecomesARetryableFailure() {
        val r = OrchRig(mapOf("a.txt" to "a\n"), SchedulerConfig(retry = RetryPolicy(maxAttempts = 3, baseDelayMs = 100, factor = 2.0, maxDelayMs = 1000)))
        r.add(task("t1")); r.agentRuns("t1", "a") { it.put(".env", "KEY=1\n"); it.put("a.txt", "a2\n") }
        assertEquals(TaskState.Pending, r.state("t1")); assertTrue(r.launcher.reviews.isEmpty()); assertFalse(r.integration.exists(com.anvex.agents.core.path.RelPath.of(".env")))
        val events = r.orch.log.events().filter { (it as? SchedulerEvent.AgentFailed)?.task?.value == "t1" || (it as? SchedulerEvent.AgentFinished)?.task?.value == "t1" }
        assertTrue("the scheduler must never have seen 'finished'", events.none { it is SchedulerEvent.AgentFinished })
        assertTrue((events.single() as SchedulerEvent.AgentFailed).reason.startsWith("invalid change set")); assertEquals(FailureKind.Error, (events.single() as SchedulerEvent.AgentFailed).kind)
        r.orch.submit(SchedulerEvent.Kick(r.node("t1").notBefore)); assertEquals(2, r.launcher.lastStart("t1", "a").attempt)
    }

    @Test fun anAgentThatChangedNothingFails() {
        val r = OrchRig(mapOf("a.txt" to "a\n")); r.add(task("t1")); r.agentRuns("t1", "a")
        assertEquals(TaskState.Pending, r.state("t1")); assertTrue(r.node("t1").failure!!.contains("empty change set"))
    }

    @Test fun withoutAnIndependentReviewerAgentOnlyTheUserCanSatisfyTheGate() {
        val r = OrchRig(mapOf("a.txt" to "a\n"), agents = listOf(worker("a"), qaWorker("qa")))
        r.add(task("t1", assignees = listOf("qa"))); r.agentRuns("t1", "qa") { it.put("a.txt", "qa edit\n") }
        assertTrue("the only approve-capable agent is the author: no reviewer run may start", r.launcher.reviews.isEmpty()); assertTrue(r.audits.contains("NoReviewerAgent"))
        r.orch.submit(SchedulerEvent.ReviewVerdict(r.at(), TaskId("t1"), Reviewer.Agent(AgentId("qa")), true, VerdictChannel.ReviewerRun))
        assertEquals(TaskState.UnderReview, r.state("t1")); assertTrue(r.audits.contains("GateViolation"))
        r.orch.submit(SchedulerEvent.ReviewVerdict(r.at(), TaskId("t1"), Reviewer.User, true, VerdictChannel.UserUi))
        assertEquals(TaskState.Completed, r.state("t1")); assertEquals("qa edit\n", r.text("a.txt"))
    }

    @Test fun escalationNeedsTheUserAndThenAppliesOnlyTheApprovedPaths() {
        val r = OrchRig(mapOf("src/keep.kt" to "k\n"))
        r.add(task("t1", claims = listOf("src/**"))); r.agentRuns("t1", "a") { it.put("src/a.kt", "a\n"); it.put("docs/x.md", "x\n") }; r.approve("t1")
        assertEquals(TaskState.Blocked, r.state("t1")); assertTrue(r.notices.contains(Notice.ApprovalNeeded)); assertFalse("nothing written while escalated", r.integration.exists(com.anvex.agents.core.path.RelPath.of("src/a.kt")))
        r.userDecision("t1", UserAction.Approve)
        assertEquals(TaskState.Completed, r.state("t1")); assertEquals("a\n", r.text("src/a.kt")); assertEquals("x\n", r.text("docs/x.md"))
    }

    @Test fun userRollbackUndoesAnAppliedTask() {
        val r = OrchRig(mapOf("a.txt" to "a\n")); val before = r.integration.contentMap()
        r.add(task("t1")); r.agentRuns("t1", "a") { it.put("a.txt", "changed\n"); it.put("new.txt", "n\n") }; r.approve("t1")
        assertTrue(r.integration.contentMap() != before); assertTrue(r.orch.rollbackApplied(TaskId("t1"))); assertEquals(before, r.integration.contentMap()); assertFalse(r.orch.rollbackApplied(TaskId("nope")))
    }

    @Test fun cancelDisposesTheForkOnlyAfterTheRunHasReallyEnded() {
        val r = OrchRig(mapOf("a.txt" to "a\n")); r.add(task("t1"))
        r.orch.submit(SchedulerEvent.AgentStarted(r.at(), TaskId("t1"), AgentId("a"))); r.orch.submit(SchedulerEvent.CancelTask(r.at(), TaskId("t1")))
        assertEquals(listOf("t1:a"), r.launcher.cancels); assertEquals("the run may still be writing", 1, r.provider.live.size)
        r.orch.runEnded(TaskId("t1"), AgentId("a")); assertEquals(0, r.provider.live.size); assertEquals(0, r.orch.liveForkCount())
    }

    @Test fun budgetExhaustionCancelsRunningAgentsAndRaisingItRestartsThem() {
        val r = OrchRig(mapOf("a.txt" to "a\n"), SchedulerConfig(limits = BudgetLimits(maxTokens = 1000)))
        r.add(task("t1")); r.add(task("t2", assignees = listOf("b"))); r.orch.submit(SchedulerEvent.AgentStarted(r.at(), TaskId("t1"), AgentId("a")))
        r.orch.submit(SchedulerEvent.UsageReported(r.at(), TaskId("t1"), AgentId("a"), 700, 600, null))
        assertEquals(setOf("t1:a", "t2:b"), r.launcher.cancels.toSet()); assertTrue(r.notices.contains(Notice.BudgetExhausted)); assertEquals(BlockReason.BudgetExhausted, r.node("t1").block)
        r.orch.submit(SchedulerEvent.RaiseBudget(r.at(), BudgetLimits(maxTokens = 100_000))); assertEquals(4, r.launcher.starts.size)
    }

    @Test fun secretsInFailureTextNeverReachTheLogOrTheState() {
        val r = OrchRig(mapOf("a.txt" to "a\n")); r.add(task("t1"))
        r.orch.submit(SchedulerEvent.AgentStarted(r.at(), TaskId("t1"), AgentId("a")))
        r.orch.submit(SchedulerEvent.AgentFailed(r.at(), TaskId("t1"), AgentId("a"), FailureKind.Fatal, "401: bad key sk-abcdefghijklmnopqrstuvwxyz0123"))
        assertEquals("the event was not dropped by the guard", TaskState.Failed, r.state("t1"))
        assertTrue(r.rawStore.readAll("run1").none { EventCodec.encode(it.event).contains("sk-abcdefghijklmnop") }); assertTrue(r.node("t1").failure!!.contains("[REDACTED]"))
    }

    @Test fun aFailingEventStoreDropsTheEventAndChangesNothing() {
        val r = OrchRig(mapOf("a.txt" to "a\n")); r.flaky.fail = true
        r.add(task("t1")); assertTrue(r.orch.state.tasks.isEmpty()); assertEquals(0, r.orch.log.size); assertTrue(r.audits.contains("ProcessingError")); assertTrue(r.launcher.starts.isEmpty())
        r.flaky.fail = false; r.add(task("t1")); assertEquals(1, r.launcher.starts.size)
    }

    @Test fun commandFailuresAreContainedAndTurnIntoAgentFailures() {
        val r = OrchRig(mapOf("a.txt" to "a\n"), SchedulerConfig(retry = RetryPolicy(maxAttempts = 1)))
        r.integration.failOnRestore = false
        val broken = object : RunLauncher by r.launcher { override fun startAgent(task: TaskSpec, agent: AgentProfile, attempt: Int, workspace: WorkspaceFileSystem) { throw IllegalStateException("cannot start") } }
        val o = Orchestrator("run2", Scheduler.initial(r.cfg, r.agents), r.provider, r.provider, r.integration, r.integrator, InMemoryEventStore(), broken, object : Notifier { override fun notify(notice: Notice, task: TaskId?, detail: String) {} }, object : AuditPort { override fun record(kind: String, task: TaskId?, agent: AgentId?, detail: String) { r.audits += kind } }, r.journal, r.clock)
        o.submit(SchedulerEvent.TaskAdded(r.at(), task("t1")))
        assertEquals(TaskState.Failed, o.state.tasks[TaskId("t1")]!!.state); assertTrue(r.audits.contains("CommandFailed"))
    }

    @Test fun observersSeeEveryCommittedEventAndCannotBreakTheEngine() {
        val seen = ArrayList<Int>(); val mem = MemStore()
        val r = OrchRig(mapOf("a.txt" to "a\n"), mem = mem)
        val o = Orchestrator("obs", Scheduler.initial(r.cfg, r.agents), r.provider, r.provider, r.integration, r.integrator, InMemoryEventStore(), r.launcher,
            object : Notifier { override fun notify(notice: Notice, task: TaskId?, detail: String) {} }, object : AuditPort { override fun record(kind: String, task: TaskId?, agent: AgentId?, detail: String) { r.audits += kind } },
            r.journal, r.clock, onChange = { st -> seen += st.tasks.size; if (st.tasks.size == 2) throw IllegalStateException("observer bug") })
        o.submit(SchedulerEvent.TaskAdded(r.at(), task("t1"))); o.submit(SchedulerEvent.TaskAdded(r.at(), task("t2", assignees = listOf("b"))))
        assertEquals(listOf(1, 2), seen.filterIndexed { i, _ -> i < 2 }); assertTrue(r.audits.contains("ObserverFailed")); assertEquals(2, o.state.tasks.size)
    }

    // ------------------------------------------------------------------ recovery
    @Test fun recoveryRebuildsTheStateRequeuesInterruptedWorkAndFinishes() {
        val r = OrchRig(mapOf("shared.kt" to lines))
        r.add(task("t1")); r.add(task("t2", assignees = listOf("b"))); r.orch.submit(SchedulerEvent.AgentStarted(r.at(), TaskId("t1"), AgentId("a")))
        val l2 = RecLauncher(); val o2 = r.recoverOrch(l2)
        assertEquals("replay reproduces the exact state", r.orch.state, o2.state); assertTrue(l2.starts.isEmpty())
        o2.reconcile()
        assertEquals(listOf("t1:a", "t2:b"), l2.startNames()); assertEquals("a restart does not use up an attempt", listOf(1, 1), l2.starts.map { it.attempt })
        r.agentRuns(o2, l2, "t1", "a") { it.put("shared.kt", edit(2 to "after restart")) }; r.approve(o2, l2, "t1")
        assertEquals(TaskState.Completed, o2.state.tasks[TaskId("t1")]!!.state); assertEquals(edit(2 to "after restart"), r.text("shared.kt"))
    }

    @Test fun aTamperedLogIsRefusedAtRecovery() {
        val r = OrchRig(mapOf("a.txt" to "a\n")); r.add(task("t1")); r.add(task("t2", assignees = listOf("b")))
        val forged = InMemoryEventStore()
        r.rawStore.readAll("run1").forEachIndexed { i, rec -> forged.append("run1", if (i == 1) rec.copy(event = SchedulerEvent.Kick(1)) else rec) }
        val noopNotifier = object : Notifier { override fun notify(notice: Notice, task: TaskId?, detail: String) {} }
        val noopAudit = object : AuditPort { override fun record(kind: String, task: TaskId?, agent: AgentId?, detail: String) {} }
        try { Orchestrator.recover("run1", r.cfg, r.agents, r.provider, r.provider, r.integration, r.integrator, forged, RecLauncher(), noopNotifier, noopAudit, r.journal, r.clock); throw AssertionError("forged log accepted") } catch (_: IllegalStateException) { }
        assertNotNull("the untouched log still recovers", r.recoverOrch(RecLauncher()))
    }

    /** Acceptance recovery + atomic_apply: the process dies at every possible moment of an apply; restart repairs the project. */
    @Test fun aCrashAtEveryStepOfTheApplyIsRepairedOnRestart() {
        val files = mapOf("other.kt" to "other\n", "gone.kt" to "gone\n", "shared.kt" to lines)
        val work: (MemWorkspace) -> Unit = { it.put("other.kt", "other v2\n"); it.put("new/a.kt", "a\n"); it.put("new/b.kt", "b\n"); it.files.remove(com.anvex.agents.core.path.RelPath.of("gone.kt")); it.put("shared.kt", edit(4 to "X")) }
        val probe = OrchRig(files); probe.add(task("t1")); probe.agentRuns("t1", "a", work); val m0 = probe.integration.mutations; probe.approve("t1"); val steps = probe.integration.mutations - m0
        assertTrue("steps=$steps", steps >= 5)
        for (k in 1..steps) {
            val r = OrchRig(files); r.add(task("t1")); r.agentRuns("t1", "a", work); val pre = r.integration.contentMap()
            r.integration.crashOnMutation = r.integration.mutations + k
            try { r.approve("t1"); throw AssertionError("k=$k: expected the simulated crash") } catch (_: SimulatedCrash) { }
            assertEquals("k=$k: the apply was journaled before the first write", 1, r.journal.pending().size)
            r.integration.crashOnMutation = 0
            val l2 = RecLauncher(); val o2 = r.recoverOrch(l2); o2.reconcile()
            assertEquals("k=$k: restart must restore the exact pre-apply project", pre, r.integration.contentMap()); assertTrue(r.journal.pending().isEmpty())
            assertEquals("k=$k: the interrupted task is redone", 1, l2.starts.size)
            r.agentRuns(o2, l2, "t1", "a", work); r.approve(o2, l2, "t1")
            assertEquals("k=$k", TaskState.Completed, o2.state.tasks[TaskId("t1")]!!.state); assertEquals("other v2\n", r.text("other.kt")); assertEquals(null, r.text("gone.kt"))
        }
    }
}

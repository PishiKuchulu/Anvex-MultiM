package com.anvex.agents.core.orchestrator

import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.changeset.ChangeSetDeriver
import com.anvex.agents.core.changeset.IgnoreRules
import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.CredentialGuard
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.domain.BlockReason
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.events.EventLog
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.Notice
import com.anvex.agents.core.events.SchedulerCommand
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.integrator.ApplyJournal
import com.anvex.agents.core.integrator.ApplyRecovery
import com.anvex.agents.core.integrator.ApplyReport
import com.anvex.agents.core.integrator.Integrator
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.policy.PolicyContext
import com.anvex.agents.core.ports.ClockPort
import com.anvex.agents.core.ports.EventStorePort
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem
import com.anvex.agents.core.ports.WorkspaceProvider
import com.anvex.agents.core.scheduler.Scheduler
import com.anvex.agents.core.scheduler.SchedulerConfig
import com.anvex.agents.core.scheduler.SchedulerState
import com.anvex.agents.core.scheduler.UserNeed

/** The isolated workspace of one agent run and the snapshot it was forked from. */
class ForkInfo(val workspace: WorkspaceFileSystem, val base: SnapshotId, val manifest: Manifest)

/**
 * Starts work asynchronously. Results come back through [Orchestrator.submit]; when a run's coroutine/thread has
 * really stopped the launcher calls [Orchestrator.runEnded] (only then is the agent's fork deleted).
 */
interface RunLauncher {
    fun startAgent(task: TaskSpec, agent: AgentProfile, attempt: Int, workspace: WorkspaceFileSystem)
    /** Cooperative: asks the run to stop; the run ends through runEnded. */
    fun cancel(task: TaskId, agent: AgentId)
    fun pause(task: TaskId, agent: AgentId)
    fun resume(task: TaskId, agent: AgentId)
    /** Runs the independent reviewer; its verdict must be submitted with channel ReviewerRun. */
    fun startReview(task: TaskSpec, author: AgentId, reviewer: AgentProfile, changes: ChangeSet)
}
interface Notifier { fun notify(notice: Notice, task: TaskId?, detail: String) }
interface AuditPort { fun record(kind: String, task: TaskId?, agent: AgentId?, detail: String) }
interface SnapshotCatalog { fun manifest(id: SnapshotId): Manifest? }

/**
 * Executes the scheduler's commands against real collaborators. Everything decision-like stays in the pure
 * scheduler/policy/integrator; this class only wires them:
 *  - every event is persisted BEFORE it changes state (write-ahead), so replaying the log rebuilds the state;
 *  - agent output is vetted (derive + validate) before the scheduler may see "finished" (ADR-008);
 *  - events are processed one at a time; submit() may be called from any thread and from inside command execution.
 */
class Orchestrator(
    private val runId: String,
    initial: SchedulerState,
    private val workspaces: WorkspaceProvider,
    private val catalog: SnapshotCatalog,
    private val integration: WorkspaceFileSystem,
    private val integrator: Integrator,
    private val store: EventStorePort,
    private val launcher: RunLauncher,
    private val notifier: Notifier,
    private val audit: AuditPort,
    private val journal: ApplyJournal,
    private val clock: ClockPort,
    private val ignore: IgnoreRules = IgnoreRules.DEFAULT,
    initialLog: EventLog = EventLog.empty(),
    /** Called after every committed event (host UIs observe state through it); exceptions are contained. */
    private val onChange: (SchedulerState) -> Unit = {},
) {
    @Volatile var state: SchedulerState = initial; private set
    @Volatile var log: EventLog = initialLog; private set

    private val monitor = Any()
    private val queueLock = Any()
    private val queue = ArrayDeque<SchedulerEvent>()
    private var draining = false
    private val forks = HashMap<Pair<TaskId, AgentId>, ForkInfo>()
    private val changeSets = HashMap<Pair<TaskId, AgentId>, ChangeSet>()
    private val activeRuns = HashSet<Pair<TaskId, AgentId>>()
    private val escalated = HashMap<TaskId, List<RelPath>>()
    private val rollbacks = HashMap<TaskId, SnapshotId>()

    /** Thread-safe entry point for every event (host callbacks, user actions, reviewer verdicts). */
    fun submit(event: SchedulerEvent) {
        synchronized(queueLock) { queue.addLast(event); if (draining) return; draining = true }
        while (true) {
            val next = synchronized(queueLock) { val n = queue.removeFirstOrNull(); if (n == null) draining = false; n } ?: break
            synchronized(monitor) { try { process(next) } catch (e: Exception) { audit.record("ProcessingError", null, null, "${e::class.simpleName}: ${(e.message ?: "").take(150)}") } }
        }
    }

    /** Free text from agents/hosts is scrubbed before it can reach the log (credentials never persist). */
    private fun sanitize(e: SchedulerEvent): SchedulerEvent = when (e) {
        is SchedulerEvent.AgentFailed -> e.copy(reason = CredentialGuard.scrub(e.reason).take(500))
        is SchedulerEvent.Requeue -> e.copy(reason = CredentialGuard.scrub(e.reason).take(500))
        is SchedulerEvent.ApplyResult -> (e.outcome as? ApplyOutcome.Rejected)?.let { e.copy(outcome = ApplyOutcome.Rejected(CredentialGuard.scrub(it.reason).take(500))) } ?: e
        else -> e
    }

    private fun process(submitted: SchedulerEvent) {
        val original = sanitize(submitted)
        val event = if (original is SchedulerEvent.AgentFinished) vetOutput(original) else original
        val step = Scheduler.next(state, event)                     // pure: nothing has changed yet
        val newLog = log.append(event)
        store.append(runId, newLog.records.last())                  // write-ahead; if this throws the event is dropped and nothing changed
        log = newLog; state = step.state
        for (c in step.commands) executeCommand(c)
        disposeFinishedForks()
        try { onChange(state) } catch (e: Exception) { audit.record("ObserverFailed", null, null, e::class.simpleName ?: "") }
    }

    /** Derives and validates the agent's ChangeSet. Unusable output turns "finished" into a retryable failure. */
    private fun vetOutput(e: SchedulerEvent.AgentFinished): SchedulerEvent {
        val key = e.task to e.agent
        val fork = forks[key] ?: return e
        fun fail(reason: String) = SchedulerEvent.AgentFailed(e.at, e.task, e.agent, FailureKind.Error, reason)
        val changes = try { ChangeSetDeriver.derive(fork.manifest, fork.workspace, ignore) } catch (x: Exception) { return fail("output could not be read: ${x::class.simpleName}") }
        val cs = ChangeSet(e.task, e.agent, fork.base, changes)
        val problems = integrator.precheck(cs, fork.manifest)
        if (problems.isNotEmpty()) return fail("invalid change set: " + problems.take(3).joinToString("; "))
        changeSets[key] = cs
        return e
    }

    private fun now() = clock.nowMillis()

    private fun executeCommand(c: SchedulerCommand) {
        try {
            when (c) {
                is SchedulerCommand.StartAgent -> startAgent(c)
                is SchedulerCommand.CancelAgent -> launcher.cancel(c.task, c.agent)
                is SchedulerCommand.PauseAgent -> launcher.pause(c.task, c.agent)
                is SchedulerCommand.ResumeAgent -> launcher.resume(c.task, c.agent)
                is SchedulerCommand.RequestReview -> requestReview(c)
                is SchedulerCommand.RequestApply -> requestApply(c)
                is SchedulerCommand.Notify -> notifier.notify(c.notice, c.task, c.detail)
                is SchedulerCommand.Audit -> audit.record(c.kind, c.task, c.agent, c.detail)
            }
        } catch (x: Exception) {
            audit.record("CommandFailed", null, null, "${c::class.simpleName}: ${x::class.simpleName}")
            if (c is SchedulerCommand.StartAgent) { activeRuns.remove(c.task to c.agent); submit(SchedulerEvent.AgentFailed(now(), c.task, c.agent, FailureKind.Error, "could not start the agent run: ${x::class.simpleName}")) }
        }
    }

    private fun startAgent(c: SchedulerCommand.StartAgent) {
        val key = c.task to c.agent
        val spec = state.tasks[c.task]?.spec ?: return
        val profile = state.agents[c.agent] ?: return
        disposeFork(key); changeSets.remove(key)
        val base = integration.createSnapshot()                      // each fork starts from the CURRENT integrated state
        val manifest = catalog.manifest(base) ?: throw IllegalStateException("snapshot manifest missing")
        val ws = workspaces.fork(integration, base, "${c.task.value}-${c.agent.value}")
        forks[key] = ForkInfo(ws, base, manifest); activeRuns += key
        launcher.startAgent(spec, profile, c.attempt, ws)
    }

    /** Deterministic independent reviewer: an agent with the approve grant that is not an author of the task. */
    fun reviewerFor(spec: TaskSpec): AgentProfile? =
        state.agents.values.filter { it.grant.approve && it.id !in spec.assignees }.minByOrNull { it.id.value }

    private fun requestReview(c: SchedulerCommand.RequestReview) {
        val spec = state.tasks[c.task]?.spec ?: return
        val cs = changeSets[c.task to c.author] ?: run { audit.record("ReviewSkipped", c.task, c.author, "change set missing"); return }
        val reviewer = reviewerFor(spec) ?: run { audit.record("NoReviewerAgent", c.task, null, "no independent reviewer agent; a user verdict is required"); return }
        launcher.startReview(spec, c.author, reviewer, cs)
    }

    private fun requestApply(c: SchedulerCommand.RequestApply) {
        val key = c.task to c.author
        val spec = state.tasks[c.task]?.spec ?: return
        val cs = changeSets[key]; val fork = forks[key]; val actor = state.agents[c.author]
        if (cs == null || fork == null || actor == null) { submit(SchedulerEvent.ApplyResult(now(), c.task, ApplyOutcome.Rejected("the change set is no longer available"))); return }
        val approved = if (c.userApprovedEscalation) (escalated[c.task] ?: emptyList()).toSet() else emptySet()
        val report = integrator.apply(integration, cs, fork.manifest, PolicyContext(actor, spec.claims, approved))
        when (report) {
            is ApplyReport.Applied -> { rollbacks[c.task] = report.rollback; escalated.remove(c.task) }
            is ApplyReport.Escalated -> escalated[c.task] = report.paths
            is ApplyReport.Rejected -> if (report.rollbackFailed) { audit.record("CRITICAL_ROLLBACK_FAILED", c.task, c.author, report.reasons.joinToString("; ").take(300)); submit(SchedulerEvent.CancelAll(now())) }
            is ApplyReport.Conflicts -> Unit
        }
        submit(SchedulerEvent.ApplyResult(now(), c.task, report.toOutcome()))
    }

    // ------------------------------------------------------------------ lifecycle of forks
    private fun disposeFork(key: Pair<TaskId, AgentId>) { forks.remove(key)?.let { try { workspaces.dispose(it.workspace) } catch (x: Exception) { audit.record("DisposeFailed", key.first, key.second, x::class.simpleName ?: "") } } }

    private fun disposeFinishedForks() {
        for (key in forks.keys.toList()) {
            val n = state.tasks[key.first] ?: continue
            if (n.state.isTerminal && key !in activeRuns) disposeFork(key)
        }
    }

    /** Called by the launcher when a run's thread/coroutine has really stopped writing. */
    fun runEnded(task: TaskId, agent: AgentId) = synchronized(monitor) { activeRuns.remove(task to agent); disposeFinishedForks() }

    // ------------------------------------------------------------------ queries and user rollback
    fun changeSetOf(task: TaskId, agent: AgentId): ChangeSet? = synchronized(monitor) { changeSets[task to agent] }
    fun forkOf(task: TaskId, agent: AgentId): ForkInfo? = synchronized(monitor) { forks[task to agent] }
    fun liveForkCount(): Int = synchronized(monitor) { forks.size }

    /** Undo of an applied task: restores the integration workspace to the snapshot taken right before that apply. */
    fun rollbackApplied(task: TaskId): Boolean = synchronized(monitor) {
        val snap = rollbacks[task] ?: return false
        integrator.rollback(integration, snap); audit.record("UserRollback", task, null, "restored $snap"); true
    }

    /**
     * After a restart: restore the integration workspace if an apply was interrupted, then redo interrupted work.
     * Forks of the previous process are not reused (their runs are gone); the tasks are requeued and re-run.
     */
    fun reconcile() {
        val restored = synchronized(monitor) { ApplyRecovery.recover(integration, journal) }
        for (t in restored) audit.record("ApplyRecovered", t, null, "interrupted apply was rolled back")
        val toRedo = state.tasks.values.sortedBy { it.seq }.filter {
            it.state == TaskState.Assigned || it.state == TaskState.InProgress || it.state == TaskState.UnderReview ||
                (it.state == TaskState.Blocked && it.block == BlockReason.AwaitingUser)
        }
        for (n in toRedo) submit(SchedulerEvent.Requeue(now(), n.spec.id, "process restarted"))
    }

    companion object {
        /** Rebuilds the scheduler state from the persisted, hash-verified event log. Call [reconcile] afterwards. */
        fun recover(
            runId: String, config: SchedulerConfig, agents: Collection<AgentProfile>, workspaces: WorkspaceProvider, catalog: SnapshotCatalog,
            integration: WorkspaceFileSystem, integrator: Integrator, store: EventStorePort, launcher: RunLauncher, notifier: Notifier, audit: AuditPort,
            journal: ApplyJournal, clock: ClockPort, ignore: IgnoreRules = IgnoreRules.DEFAULT, onChange: (SchedulerState) -> Unit = {},
        ): Orchestrator {
            val log = EventLog.fromRecords(store.readAll(runId))
            check(log.verify()) { "the persisted event log failed verification and will not be replayed" }
            val state = Scheduler.replay(Scheduler.initial(config, agents), log.events()).state
            return Orchestrator(runId, state, workspaces, catalog, integration, integrator, store, launcher, notifier, audit, journal, clock, ignore, log, onChange)
        }
    }
}

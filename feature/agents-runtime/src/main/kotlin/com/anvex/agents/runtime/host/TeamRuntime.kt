package com.anvex.agents.runtime.host

import com.anvex.agents.core.changeset.FileChange
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.merge.LineDiff
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.events.GuardedEventStore
import com.anvex.agents.core.events.Notice
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.integrator.Integrator
import com.anvex.agents.core.orchestrator.Notifier
import com.anvex.agents.core.orchestrator.Orchestrator
import com.anvex.agents.core.policy.PolicyEngine
import com.anvex.agents.core.ports.ClockPort
import com.anvex.agents.core.scheduler.Scheduler
import com.anvex.agents.core.scheduler.SchedulerConfig
import com.anvex.agents.core.scheduler.SchedulerState
import com.anvex.agents.fs.CasStore
import com.anvex.agents.fs.RealWorkspaceProvider
import com.anvex.agents.runtime.AgentsHost
import com.anvex.agents.runtime.db.AgentsDatabase
import com.anvex.agents.runtime.db.RoomApplyJournal
import com.anvex.agents.runtime.db.RoomAuditPort
import com.anvex.agents.runtime.db.RoomEventStore
import com.anvex.agents.runtime.db.RunEntity
import com.anvex.agents.runtime.runner.AnvexAgentRunner
import com.anvex.agents.runtime.runner.ReviewRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class NoticeEvent(val notice: Notice, val task: TaskId?, val detail: String)

/** [diffs] renders the change set of (task, author) as (path, unified diff) pairs for the diff screen. */
class RunHandle(val runId: String, val teamId: String, val projectId: String, val orchestrator: Orchestrator, val state: StateFlow<SchedulerState?>, val notices: SharedFlow<NoticeEvent>, val diffs: (TaskId, AgentId) -> List<Pair<String, String>>) {
    fun submit(event: SchedulerEvent) = orchestrator.submit(event)
}

/**
 * UNVERIFIED (never compiled). Composition root of one run: wires the tested core (scheduler, policy, integrator, orchestrator)
 * to the host, Room and coroutines. Contains no decision logic.
 */
class TeamRuntime(private val host: AgentsHost, private val db: AgentsDatabase, private val scope: CoroutineScope) {
    private val clock: ClockPort = SystemClock()
    private val cas by lazy { CasStore(host.agentsStorage("cas")) }
    private val roots = HostWorkspaceRoots(host)
    private val active = ConcurrentHashMap<String, RunHandle>()

    fun handle(runId: String): RunHandle? = active[runId]

    /** Starts a new run. Throws [UnsupportedProjectException] for SAF-backed projects. */
    fun start(runId: String, teamId: String, projectId: String, config: SchedulerConfig, agents: List<AgentProfile>, tasks: List<TaskSpec>): RunHandle {
        val h = build(runId, teamId, projectId, config, agents, recover = false)
        db.teams().putRun(RunEntity(runId, teamId, projectId, "Running", clock.nowMillis(), null))
        for (t in tasks) h.submit(SchedulerEvent.TaskAdded(clock.nowMillis(), t))
        return h
    }

    /** Rebuilds a run after a process restart from its verified event log; interrupted work is redone. */
    fun resume(runId: String, teamId: String, projectId: String, config: SchedulerConfig, agents: List<AgentProfile>): RunHandle {
        val h = build(runId, teamId, projectId, config, agents, recover = true)
        db.teams().putRun(RunEntity(runId, teamId, projectId, "Running", clock.nowMillis(), null))
        return h
    }

    private fun build(runId: String, teamId: String, projectId: String, config: SchedulerConfig, agents: List<AgentProfile>, recover: Boolean): RunHandle {
        val provider = RealWorkspaceProvider(roots, cas)
        val integration = provider.open(projectId)                       // fails closed for SAF projects
        val sens = HostSensitivePathPort(host)
        val journal = RoomApplyJournal(runId, db.journal())
        val integrator = Integrator(PolicyEngine(sens), sens, cas, journal)
        val store = GuardedEventStore(RoomEventStore(db.events()))
        val audit = RoomAuditPort(runId, db.audit(), clock)
        val flow = MutableStateFlow<SchedulerState?>(null)
        val notices = MutableSharedFlow<NoticeEvent>(extraBufferCapacity = 64)
        val notifier = object : Notifier { override fun notify(notice: Notice, task: TaskId?, detail: String) { notices.tryEmit(NoticeEvent(notice, task, detail)) } }
        val launcher = CoroutineRunLauncher(scope, AnvexAgentRunner(host, HostContextPort(host, projectId), clock), ReviewRunner(host, cas), clock, audit)
        val catalog = CasCatalog(cas)
        val onChange = { st: SchedulerState -> flow.value = st }
        val orch = if (recover)
            Orchestrator.recover(runId, config, agents, provider, catalog, integration, integrator, store, launcher, notifier, audit, journal, clock, onChange = onChange)
        else
            Orchestrator(runId, Scheduler.initial(config, agents), provider, catalog, integration, integrator, store, launcher, notifier, audit, journal, clock, onChange = onChange)
        launcher.bind(orch); flow.value = orch.state
        val diffs = { task: TaskId, agent: AgentId ->
            orch.changeSetOf(task, agent)?.changes.orEmpty().map { c ->
                val base: ByteArray? = when (c) { is FileChange.Modify -> cas.get(c.baseDigest); is FileChange.Delete -> cas.get(c.baseDigest); is FileChange.Add -> null }
                val now: ByteArray? = when (c) { is FileChange.Add -> c.bytes; is FileChange.Modify -> c.bytes; is FileChange.Delete -> null }
                c.path.value to LineDiff.render(c.path.value, base, now)
            }
        }
        val handle = RunHandle(runId, teamId, projectId, orch, flow, notices, diffs)
        active[runId] = handle
        if (recover) orch.reconcile()
        return handle
    }
}

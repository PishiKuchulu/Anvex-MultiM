package com.anvex.agents.core

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.Grant
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerCommand
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.events.VerdictChannel
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.SensitivePathPort
import com.anvex.agents.core.ports.SensitiveVerdict
import com.anvex.agents.core.scheduler.Scheduler
import com.anvex.agents.core.scheduler.SchedulerConfig
import com.anvex.agents.core.scheduler.SchedulerState
import com.anvex.agents.core.scheduler.TaskNode

fun worker(id: String, grant: Grant = Grant(write = listOf("**"))) =
    AgentProfile(AgentId(id), id, "role of $id", "prov-$id", "model-x", grant = grant)
fun reviewer(id: String, approve: Boolean = true) =
    AgentProfile(AgentId(id), id, "reviewer $id", "prov-$id", "model-r", grant = Grant(approve = approve))

/** Writes code AND may approve (like the qa-engineer role): only the independence rule can stop it from verifying its own work. */
fun qaWorker(id: String = "qa") = AgentProfile(AgentId(id), id, "qa $id", "prov-$id", "model-q", grant = Grant(write = listOf("**"), approve = true))

fun defaultAgents() = listOf(worker("a"), worker("b"), worker("c"), reviewer("r1"), reviewer("r2"), worker("noapprove"), qaWorker("qa"), qaWorker("qb"))

fun task(
    id: String, deps: List<String> = emptyList(), claims: List<String> = emptyList(),
    assignees: List<String> = listOf("a"), priority: Int = 0, review: Boolean = true,
) = TaskSpec(TaskId(id), "title $id", "acceptance $id", deps.map { TaskId(it) }.toSet(), claims, assignees.map { AgentId(it) }, priority, review)

/** Test host: feeds events to the pure scheduler and records everything. */
class Sim(cfg: SchedulerConfig = SchedulerConfig(), agents: List<AgentProfile> = defaultAgents()) {
    val initial: SchedulerState = Scheduler.initial(cfg, agents)
    var s: SchedulerState = initial
    val events = ArrayList<SchedulerEvent>()
    val commands = ArrayList<SchedulerCommand>()
    var last: List<SchedulerCommand> = emptyList()
    var t = 1000L

    fun send(e: SchedulerEvent): List<SchedulerCommand> {
        val st = Scheduler.next(s, e)
        s = st.state; last = st.commands; commands += st.commands; events += e; t = maxOf(t, e.at)
        return last
    }
    private fun at() = ++t

    fun add(spec: TaskSpec) = send(SchedulerEvent.TaskAdded(at(), spec))
    fun kick(at: Long = at()) = send(SchedulerEvent.Kick(at))
    fun started(task: String, agent: String) = send(SchedulerEvent.AgentStarted(at(), TaskId(task), AgentId(agent)))
    fun finished(task: String, agent: String) = send(SchedulerEvent.AgentFinished(at(), TaskId(task), AgentId(agent)))
    fun failed(task: String, agent: String, kind: FailureKind = FailureKind.Error, reason: String = "boom") =
        send(SchedulerEvent.AgentFailed(at(), TaskId(task), AgentId(agent), kind, reason))
    fun usage(agent: String, tokensIn: Long, tokensOut: Long, cost: Double? = null, task: String? = null) =
        send(SchedulerEvent.UsageReported(at(), task?.let { TaskId(it) }, AgentId(agent), tokensIn, tokensOut, cost))
    fun verdict(task: String, reviewer: Reviewer, approved: Boolean, channel: VerdictChannel) =
        send(SchedulerEvent.ReviewVerdict(at(), TaskId(task), reviewer, approved, channel))
    fun approveBy(task: String, reviewerId: String = "r1") = verdict(task, Reviewer.Agent(AgentId(reviewerId)), true, VerdictChannel.ReviewerRun)
    fun rejectBy(task: String, reviewerId: String = "r1") = verdict(task, Reviewer.Agent(AgentId(reviewerId)), false, VerdictChannel.ReviewerRun)
    fun applied(task: String, outcome: ApplyOutcome = ApplyOutcome.Applied) = send(SchedulerEvent.ApplyResult(at(), TaskId(task), outcome))
    fun userDecision(task: String, a: UserAction) = send(SchedulerEvent.UserDecision(at(), TaskId(task), a))
    fun chose(task: String, agent: String) = send(SchedulerEvent.UserChoseResult(at(), TaskId(task), AgentId(agent)))
    fun cancel(task: String) = send(SchedulerEvent.CancelTask(at(), TaskId(task)))

    fun node(id: String): TaskNode = s.tasks[TaskId(id)] ?: error("no task $id")
    fun state(id: String): TaskState = node(id).state

    fun startsOf(cmds: List<SchedulerCommand>): List<String> =
        cmds.filterIsInstance<SchedulerCommand.StartAgent>().map { "${it.task}:${it.agent}" }
    fun allStarts(): List<String> = startsOf(commands)

    /** Drives task [id] with agent [agent] through start+finish (assumes it was admitted). */
    fun runToReview(id: String, agent: String = "a") { started(id, agent); finished(id, agent) }
    /** start, finish, approve by r1, apply. */
    fun complete(id: String, agent: String = "a") { runToReview(id, agent); approveBy(id); applied(id) }
}

object NoSensitive : SensitivePathPort { override fun classify(path: RelPath) = SensitiveVerdict.NotSensitive }
class FakeSensitive(private val sensitive: Set<String> = setOf(".env", ".git", "local.properties"), private val unknown: Set<String> = emptySet(), private val throwOn: Set<String> = emptySet()) : SensitivePathPort {
    override fun classify(path: RelPath): SensitiveVerdict {
        if (path.value in throwOn) throw IllegalStateException("oracle down")
        if (path.value in unknown) return SensitiveVerdict.Unknown
        return if (path.segments.any { it in sensitive }) SensitiveVerdict.Sensitive else SensitiveVerdict.NotSensitive
    }
}

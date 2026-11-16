package com.anvex.agents.core.scheduler

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.budget.BudgetState
import com.anvex.agents.core.budget.RetryPolicy
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.BlockReason
import com.anvex.agents.core.domain.CredentialGuard
import com.anvex.agents.core.domain.GateState
import com.anvex.agents.core.domain.RunState
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.Notice
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerCommand
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.events.VerdictChannel
import com.anvex.agents.core.path.Glob

data class SchedulerConfig(
    val maxConcurrent: Int = 3,
    val retry: RetryPolicy = RetryPolicy(),
    val limits: BudgetLimits = BudgetLimits(),
    val warnFraction: Double = 0.8,
    /** false: tasks without declared claims run in parallel (the merge step protects data). */
    val serializeUnclaimed: Boolean = false,
) {
    init {
        require(maxConcurrent >= 1) { "maxConcurrent must be >= 1" }
        require(warnFraction in 0.0..1.0) { "warnFraction out of range" }
    }
}

enum class UserNeed { ApprovalForApply, ResolveConflict, StoppedByUser }

data class TaskNode(
    val spec: TaskSpec,
    val seq: Int,
    val state: TaskState = TaskState.Pending,
    val block: BlockReason? = null,
    val userNeed: UserNeed? = null,
    val attempts: Int = 0,
    val notBefore: Long = 0,
    val runs: Map<AgentId, RunState> = emptyMap(),
    val gate: GateState = GateState.NotRequired,
    val author: AgentId? = null,
    val awaitingChoice: Boolean = false,
    val applyRequested: Boolean = false,
    val verifiedBy: Reviewer? = null,
    val failure: String? = null,
    val lastFailure: FailureKind? = null,
)

data class SchedulerState(
    val config: SchedulerConfig,
    val agents: Map<AgentId, AgentProfile>,
    val tasks: Map<TaskId, TaskNode> = emptyMap(),
    val nextSeq: Int = 0,
    val now: Long = 0,
    val budget: BudgetState = BudgetState(limits = config.limits),
    val cancelledAll: Boolean = false,
) {
    /** Runs holding a slot (Starting, Running, Paused, Stopping). */
    fun liveRuns(): Int = tasks.values.sumOf { n -> n.runs.values.count { it.isLive } }
    fun running(): List<Pair<TaskId, AgentId>> =
        tasks.values.sortedBy { it.seq }.flatMap { n -> n.runs.filterValues { it.isLive }.keys.map { n.spec.id to it } }
    /** Earliest time at which a waiting retry becomes admissible; the host should send a Kick then. */
    fun nextWakeAt(): Long? = tasks.values
        .filter { (it.state == TaskState.Pending || it.state == TaskState.RevisionRequired) && it.notBefore > now }
        .minOfOrNull { it.notBefore }
}

data class Step(val state: SchedulerState, val commands: List<SchedulerCommand>)

/**
 * Deterministic, I/O-free scheduler (ADR-009, ADR-020): next(state, event) -> (state, commands).
 * No clock (events carry time), no randomness, no coroutines.
 */
object Scheduler {
    fun initial(config: SchedulerConfig, agents: Collection<AgentProfile>): SchedulerState =
        SchedulerState(config = config, agents = agents.associateBy { it.id })

    fun replay(initial: SchedulerState, events: List<SchedulerEvent>): Step {
        var s = initial
        val all = ArrayList<SchedulerCommand>()
        for (e in events) { val st = next(s, e); s = st.state; all += st.commands }
        return Step(s, all)
    }

    fun next(state: SchedulerState, event: SchedulerEvent): Step {
        val w = Work(state.copy(now = maxOf(state.now, event.at)))
        when (event) {
            is SchedulerEvent.TaskAdded -> onTaskAdded(w, event.spec)
            is SchedulerEvent.Kick -> Unit
            is SchedulerEvent.AgentStarted -> onStarted(w, event)
            is SchedulerEvent.AgentFinished -> onFinished(w, event)
            is SchedulerEvent.AgentFailed -> onFailed(w, event)
            is SchedulerEvent.UsageReported -> onUsage(w, event)
            is SchedulerEvent.ReviewVerdict -> onVerdict(w, event)
            is SchedulerEvent.UserChoseResult -> onChose(w, event)
            is SchedulerEvent.ApplyResult -> onApply(w, event)
            is SchedulerEvent.UserDecision -> onUserDecision(w, event)
            is SchedulerEvent.PauseAgent -> onPause(w, event.agent)
            is SchedulerEvent.ResumeAgent -> onResume(w, event.agent)
            is SchedulerEvent.StopAgent -> onStop(w, event.agent)
            is SchedulerEvent.CancelTask -> if (w.s.tasks.containsKey(event.task)) cancelTree(w, event.task) else w.audit("EventIgnored", event.task, null, "unknown task")
            is SchedulerEvent.CancelAll -> onCancelAll(w)
            is SchedulerEvent.RetryTask -> onRetry(w, event.task)
            is SchedulerEvent.Requeue -> onRequeue(w, event)
            is SchedulerEvent.SetMaxConcurrent ->
                if (event.value < 1) w.audit("EventIgnored", null, null, "maxConcurrent must be >= 1")
                else w.s = w.s.copy(config = w.s.config.copy(maxConcurrent = event.value))
            is SchedulerEvent.RaiseBudget -> onRaiseBudget(w, event.limits)
        }
        settleDependencies(w)
        admit(w)
        settleDependencies(w)
        return Step(w.s, w.cmds.toList())
    }

    // ------------------------------------------------------------------ work buffer
    private class Work(var s: SchedulerState) {
        val cmds = ArrayList<SchedulerCommand>()
        fun put(n: TaskNode) { s = s.copy(tasks = s.tasks + (n.spec.id to n)) }
        fun cmd(c: SchedulerCommand) { cmds += c }
        fun audit(kind: String, task: TaskId?, agent: AgentId?, detail: String) { cmds += SchedulerCommand.Audit(kind, task, agent, detail.take(300)) }
        fun notify(n: Notice, task: TaskId? = null, detail: String = "") { cmds += SchedulerCommand.Notify(n, task, detail.take(300)) }
    }

    private fun clean(text: String): String = CredentialGuard.scrub(text).take(500)

    // ------------------------------------------------------------------ handlers
    private fun onTaskAdded(w: Work, spec: TaskSpec) {
        when {
            w.s.cancelledAll -> w.audit("TaskRejected", spec.id, null, "run was cancelled")
            w.s.tasks.containsKey(spec.id) -> w.audit("TaskRejected", spec.id, null, "duplicate task id")
            spec.deps.any { it !in w.s.tasks } -> w.audit("TaskRejected", spec.id, null, "unknown dependency")
            spec.assignees.any { it !in w.s.agents } -> w.audit("TaskRejected", spec.id, null, "unknown assignee")
            else -> {
                w.put(TaskNode(spec = spec, seq = w.s.nextSeq))
                w.s = w.s.copy(nextSeq = w.s.nextSeq + 1)
            }
        }
    }

    private fun onStarted(w: Work, e: SchedulerEvent.AgentStarted) {
        val n = w.s.tasks[e.task]
        if (n == null || n.runs[e.agent] != RunState.Starting) { w.audit("StaleEvent", e.task, e.agent, "AgentStarted without a starting run"); return }
        w.put(n.copy(runs = n.runs + (e.agent to RunState.Running), state = if (n.state == TaskState.Assigned) TaskState.InProgress else n.state))
    }

    private fun onFinished(w: Work, e: SchedulerEvent.AgentFinished) {
        val n = w.s.tasks[e.task]
        val r = n?.runs?.get(e.agent)
        if (n == null || (r != RunState.Starting && r != RunState.Running)) { w.audit("StaleEvent", e.task, e.agent, "AgentFinished without a running run"); return }
        w.put(n.copy(runs = n.runs + (e.agent to RunState.Finished), state = if (n.state == TaskState.Assigned) TaskState.InProgress else n.state))
        settleRuns(w, e.task)
    }

    private fun onFailed(w: Work, e: SchedulerEvent.AgentFailed) {
        val n = w.s.tasks[e.task]
        val r = n?.runs?.get(e.agent)
        if (n == null || r == null || !r.isLive) { w.audit("StaleEvent", e.task, e.agent, "AgentFailed without a live run"); return }
        val newRun = if (e.kind == FailureKind.Cancelled) RunState.Cancelled else RunState.Failed
        w.put(n.copy(runs = n.runs + (e.agent to newRun), failure = clean(e.reason), lastFailure = e.kind))
        settleRuns(w, e.task)
    }

    /** Called whenever a run ends: decides what the task does once every run is terminal. */
    private fun settleRuns(w: Work, id: TaskId) {
        val n = w.s.tasks[id] ?: return
        if (n.state.isTerminal || n.runs.values.any { !it.isTerminal }) return
        val finished = n.spec.assignees.filter { n.runs[it] == RunState.Finished }
        if (finished.isEmpty()) {
            val kind = n.lastFailure
            when {
                kind == FailureKind.Cancelled -> {
                    w.put(n.copy(state = TaskState.Blocked, block = BlockReason.AwaitingUser, userNeed = UserNeed.StoppedByUser, runs = emptyMap()))
                    w.notify(Notice.AgentStopped, id)
                }
                kind != null && kind.retryable && n.attempts < w.s.config.retry.maxAttempts -> {
                    val delay = w.s.config.retry.delayMs(n.attempts, kind == FailureKind.RateLimited)
                    w.put(n.copy(state = TaskState.Pending, runs = emptyMap(), notBefore = w.s.now + delay))
                }
                else -> failTask(w, id, n.failure ?: "agent failed")
            }
            return
        }
        if (finished.size > 1) {
            w.put(n.copy(state = TaskState.UnderReview, awaitingChoice = true, gate = GateState.NotRequired, author = null))
            w.notify(Notice.ChoiceNeeded, id)
            return
        }
        chooseAuthor(w, id, finished.first())
    }

    private fun chooseAuthor(w: Work, id: TaskId, author: AgentId) {
        val n = w.s.tasks[id] ?: return
        if (n.spec.requiresReview) {
            w.put(n.copy(state = TaskState.UnderReview, author = author, awaitingChoice = false, gate = GateState.Required, applyRequested = false))
            w.cmd(SchedulerCommand.RequestReview(id, author))
            w.notify(Notice.ReviewNeeded, id)
        } else {
            w.put(n.copy(state = TaskState.UnderReview, author = author, awaitingChoice = false, gate = GateState.NotRequired, applyRequested = true))
            w.cmd(SchedulerCommand.RequestApply(id, author))
        }
    }

    private fun onChose(w: Work, e: SchedulerEvent.UserChoseResult) {
        val n = w.s.tasks[e.task]
        if (n == null || !n.awaitingChoice || n.runs[e.agent] != RunState.Finished) { w.audit("EventIgnored", e.task, e.agent, "no result choice pending for this agent"); return }
        chooseAuthor(w, e.task, e.agent)
    }

    /** Null when the verdict may satisfy the gate; otherwise why it may not (never trusts the sender's claims). */
    private fun reviewerViolation(s: SchedulerState, n: TaskNode, reviewer: Reviewer, channel: VerdictChannel): String? = when (reviewer) {
        is Reviewer.User ->
            if (channel != VerdictChannel.UserUi) "a user verdict must come from the user interface (got $channel)" else null
        is Reviewer.Agent -> {
            val prof = s.agents[reviewer.id]
            when {
                channel != VerdictChannel.ReviewerRun -> "an agent verdict must come from the reviewer's own run channel (got $channel)"
                prof == null -> "unknown reviewer"
                !prof.grant.approve -> "reviewer lacks the approve grant"
                reviewer.id in n.spec.assignees -> "reviewer is an author of this task"
                else -> null
            }
        }
    }

    private fun onVerdict(w: Work, e: SchedulerEvent.ReviewVerdict) {
        val n = w.s.tasks[e.task]
        if (n == null || n.state != TaskState.UnderReview || n.gate != GateState.Required || n.awaitingChoice) {
            w.audit("VerdictIgnored", e.task, null, "task is not waiting for a review verdict"); return
        }
        val who = (e.reviewer as? Reviewer.Agent)?.id
        val violation = reviewerViolation(w.s, n, e.reviewer, e.channel)
        if (violation != null) {
            w.audit("GateViolation", e.task, who, violation)
            w.notify(Notice.GateViolationAttempt, e.task, violation)
            return
        }
        if (e.approved) {
            w.put(n.copy(gate = GateState.Satisfied, verifiedBy = e.reviewer, applyRequested = true))
            w.cmd(SchedulerCommand.RequestApply(e.task, n.author!!))
        } else {
            w.put(n.copy(state = TaskState.RevisionRequired, gate = GateState.Rejected, failure = "review rejected", applyRequested = false))
        }
    }

    private fun onApply(w: Work, e: SchedulerEvent.ApplyResult) {
        val n = w.s.tasks[e.task]
        val gateOk = n != null && (n.gate == GateState.Satisfied || n.gate == GateState.NotRequired)
        if (n == null || n.state != TaskState.UnderReview || !n.applyRequested || !gateOk || n.awaitingChoice) {
            w.audit("GateViolation", e.task, null, "apply result without a satisfied gate or without an apply request")
            w.notify(Notice.GateViolationAttempt, e.task, "apply result without a satisfied gate")
            return
        }
        when (val o = e.outcome) {
            is ApplyOutcome.Applied -> w.put(n.copy(state = TaskState.Completed, applyRequested = false))
            is ApplyOutcome.Conflicts -> {
                w.put(n.copy(state = TaskState.Blocked, block = BlockReason.AwaitingUser, userNeed = UserNeed.ResolveConflict, applyRequested = false))
                w.notify(Notice.ConflictNeedsUser, e.task, "${o.count} conflicting file(s)")
            }
            is ApplyOutcome.Escalated -> {
                w.put(n.copy(state = TaskState.Blocked, block = BlockReason.AwaitingUser, userNeed = UserNeed.ApprovalForApply, applyRequested = false))
                w.notify(Notice.ApprovalNeeded, e.task)
            }
            is ApplyOutcome.Rejected -> w.put(n.copy(state = TaskState.RevisionRequired, failure = clean(o.reason), applyRequested = false, gate = GateState.NotRequired, verifiedBy = null))
        }
    }

    private fun onUserDecision(w: Work, e: SchedulerEvent.UserDecision) {
        val n = w.s.tasks[e.task]
        if (n == null || n.state != TaskState.Blocked || n.block != BlockReason.AwaitingUser) { w.audit("EventIgnored", e.task, null, "task is not waiting for the user"); return }
        when (e.action) {
            UserAction.Reject -> cancelTree(w, e.task)
            UserAction.Approve ->
                if (n.userNeed == UserNeed.ApprovalForApply && n.author != null) {
                    w.put(n.copy(state = TaskState.UnderReview, block = null, userNeed = null, applyRequested = true))
                    w.cmd(SchedulerCommand.RequestApply(e.task, n.author, userApprovedEscalation = true))
                } else w.audit("EventIgnored", e.task, null, "approve is not valid for this block")
            UserAction.Rerun ->
                if (n.userNeed == UserNeed.ResolveConflict || n.userNeed == UserNeed.StoppedByUser || n.userNeed == UserNeed.ApprovalForApply)
                    w.put(n.copy(state = TaskState.RevisionRequired, block = null, userNeed = null, runs = emptyMap(), gate = GateState.NotRequired, author = null, verifiedBy = null))
                else w.audit("EventIgnored", e.task, null, "rerun is not valid for this block")
        }
    }

    private fun onPause(w: Work, agent: AgentId) {
        var did = false
        for (n in w.s.tasks.values.sortedBy { it.seq }) {
            if (n.runs[agent] == RunState.Running) {
                w.put(n.copy(runs = n.runs + (agent to RunState.Paused))); w.cmd(SchedulerCommand.PauseAgent(n.spec.id, agent)); did = true
            }
        }
        if (!did) w.audit("EventIgnored", null, agent, "no running run to pause")
    }

    private fun onResume(w: Work, agent: AgentId) {
        var did = false
        for (n in w.s.tasks.values.sortedBy { it.seq }) {
            if (n.runs[agent] == RunState.Paused) {
                w.put(n.copy(runs = n.runs + (agent to RunState.Running))); w.cmd(SchedulerCommand.ResumeAgent(n.spec.id, agent)); did = true
            }
        }
        if (!did) w.audit("EventIgnored", null, agent, "no paused run to resume")
    }

    private fun onStop(w: Work, agent: AgentId) {
        var did = false
        for (n0 in w.s.tasks.values.sortedBy { it.seq }) {
            val n = w.s.tasks[n0.spec.id]!!
            val r = n.runs[agent] ?: continue
            when {
                r.isLive && r != RunState.Stopping -> {
                    w.put(n.copy(runs = n.runs + (agent to RunState.Stopping))); w.cmd(SchedulerCommand.CancelAgent(n.spec.id, agent)); did = true
                }
                r == RunState.Queued -> {
                    w.put(n.copy(runs = n.runs + (agent to RunState.Cancelled), lastFailure = FailureKind.Cancelled)); did = true
                    settleRuns(w, n.spec.id)
                }
                else -> Unit
            }
        }
        if (!did) w.audit("EventIgnored", null, agent, "nothing to stop")
    }

    private fun onCancelAll(w: Work) {
        for (n in w.s.tasks.values.sortedBy { it.seq }) if (!n.state.isTerminal) cancelOne(w, n.spec.id)
        w.s = w.s.copy(cancelledAll = true)
    }

    private fun onRetry(w: Work, id: TaskId) {
        val n = w.s.tasks[id]
        if (n == null || n.state != TaskState.Failed) { w.audit("EventIgnored", id, null, "only a failed task can be retried"); return }
        w.put(n.copy(state = TaskState.Pending, attempts = 0, failure = null, lastFailure = null, runs = emptyMap(), notBefore = 0))
    }

    /** Interrupted work is redone: the attempt that was lost is not charged to the task. */
    private fun onRequeue(w: Work, e: SchedulerEvent.Requeue) {
        val n = w.s.tasks[e.task]
        val eligible = n != null && (n.state == TaskState.Assigned || n.state == TaskState.InProgress || n.state == TaskState.UnderReview ||
            (n.state == TaskState.Blocked && n.block == BlockReason.AwaitingUser))
        if (n == null || !eligible) { w.audit("EventIgnored", e.task, null, "task cannot be requeued"); return }
        for ((a, r) in n.runs) if (r.isLive) w.cmd(SchedulerCommand.CancelAgent(e.task, a))
        w.put(n.copy(
            state = TaskState.RevisionRequired, block = null, userNeed = null, runs = emptyMap(), gate = GateState.NotRequired, author = null,
            awaitingChoice = false, applyRequested = false, verifiedBy = null, attempts = (n.attempts - 1).coerceAtLeast(0), failure = clean(e.reason),
        ))
    }

    private fun onRaiseBudget(w: Work, limits: BudgetLimits) {
        var b = w.s.budget.copy(limits = limits)
        b = b.copy(exhausted = b.isOverLimit(), warned = b.usedFraction() >= w.s.config.warnFraction && (limits.maxTokens != null || limits.maxCostUsd != null))
        w.s = w.s.copy(budget = b)
        if (!b.exhausted) for (n in w.s.tasks.values.sortedBy { it.seq }) {
            if (n.state == TaskState.Blocked && n.block == BlockReason.BudgetExhausted) w.put(n.copy(state = TaskState.Pending, block = null))
        }
    }

    private fun onUsage(w: Work, e: SchedulerEvent.UsageReported) {
        if (e.inputTokens < 0 || e.outputTokens < 0 || (e.costUsd != null && (e.costUsd < 0.0 || !e.costUsd.isFinite()))) {
            w.audit("EventIgnored", e.task, e.agent, "invalid usage numbers"); return
        }
        var b = w.s.budget.copy(ledger = w.s.budget.ledger.record(e.agent, e.task, e.inputTokens, e.outputTokens, e.costUsd))
        val limited = b.limits.maxTokens != null || b.limits.maxCostUsd != null
        val frac = b.usedFraction()
        if (limited && !b.exhausted && frac >= 1.0) {
            b = b.copy(exhausted = true, warned = true)
            w.s = w.s.copy(budget = b)
            w.notify(Notice.BudgetExhausted, null, "budget reached")
            stopForBudget(w)
        } else {
            if (limited && !b.warned && frac >= w.s.config.warnFraction) { b = b.copy(warned = true); w.notify(Notice.BudgetWarning, null, "budget at ${(frac * 100).toInt()} percent") }
            w.s = w.s.copy(budget = b)
        }
    }

    private fun stopForBudget(w: Work) {
        for (n0 in w.s.tasks.values.sortedBy { it.seq }) {
            val n = w.s.tasks[n0.spec.id]!!
            if (n.state != TaskState.Assigned && n.state != TaskState.InProgress) continue
            var runs = n.runs
            for ((a, r) in n.runs) {
                if (r.isLive) { w.cmd(SchedulerCommand.CancelAgent(n.spec.id, a)); runs = runs + (a to RunState.Cancelled) }
                else if (r == RunState.Queued) runs = runs + (a to RunState.Cancelled)
            }
            w.put(n.copy(state = TaskState.Blocked, block = BlockReason.BudgetExhausted, runs = emptyMap(), attempts = (n.attempts - 1).coerceAtLeast(0)))
        }
    }

    // ------------------------------------------------------------------ task end states
    private fun failTask(w: Work, id: TaskId, reason: String) {
        val n = w.s.tasks[id] ?: return
        var runs = n.runs
        for ((a, r) in n.runs) if (r.isLive) { w.cmd(SchedulerCommand.CancelAgent(id, a)); runs = runs + (a to RunState.Cancelled) }
        w.put(n.copy(state = TaskState.Failed, runs = runs, failure = clean(reason)))
        w.notify(Notice.TaskFailed, id, clean(reason))
    }

    private fun cancelOne(w: Work, id: TaskId) {
        val n = w.s.tasks[id] ?: return
        if (n.state.isTerminal) return
        var runs = n.runs
        for ((a, r) in n.runs) {
            if (r.isLive) { w.cmd(SchedulerCommand.CancelAgent(id, a)); runs = runs + (a to RunState.Cancelled) }
            else if (r == RunState.Queued) runs = runs + (a to RunState.Cancelled)
        }
        w.put(n.copy(state = TaskState.Cancelled, runs = runs, block = null, userNeed = null, applyRequested = false))
    }

    /** Cancels a task and, transitively, every task that depends on it (cancellation propagation). */
    private fun cancelTree(w: Work, id: TaskId) {
        val order = ArrayList<TaskId>(); order += id
        var i = 0
        while (i < order.size) {
            val cur = order[i++]
            for (n in w.s.tasks.values.sortedBy { it.seq }) if (cur in n.spec.deps && n.spec.id !in order) order += n.spec.id
        }
        order.forEach { cancelOne(w, it) }
    }

    // ------------------------------------------------------------------ dependency blocking
    private fun depBlockReason(s: SchedulerState, n: TaskNode): BlockReason? {
        var cancelled = false
        for (d in n.spec.deps) {
            val ds = s.tasks[d] ?: continue
            if (ds.state == TaskState.Failed || (ds.state == TaskState.Blocked && ds.block == BlockReason.DependencyFailed)) return BlockReason.DependencyFailed
            if (ds.state == TaskState.Cancelled || (ds.state == TaskState.Blocked && ds.block == BlockReason.DependencyCancelled)) cancelled = true
        }
        return if (cancelled) BlockReason.DependencyCancelled else null
    }

    private fun settleDependencies(w: Work) {
        var changed = true
        var guard = 0
        while (changed && guard++ < 10_000) {
            changed = false
            for (n0 in w.s.tasks.values.sortedBy { it.seq }) {
                val n = w.s.tasks[n0.spec.id]!!
                val reason = depBlockReason(w.s, n)
                if (n.state == TaskState.Pending && reason != null) { w.put(n.copy(state = TaskState.Blocked, block = reason)); changed = true }
                else if (n.state == TaskState.Blocked && (n.block == BlockReason.DependencyFailed || n.block == BlockReason.DependencyCancelled)) {
                    if (reason == null) { w.put(n.copy(state = TaskState.Pending, block = null)); changed = true }
                    else if (reason != n.block) { w.put(n.copy(block = reason)); changed = true }
                }
            }
        }
    }

    // ------------------------------------------------------------------ admission
    private fun ordered(s: SchedulerState): List<TaskNode> =
        s.tasks.values.sortedWith(compareByDescending<TaskNode> { it.spec.priority }.thenBy { it.seq })

    private fun holdsClaims(n: TaskNode): Boolean = when (n.state) {
        TaskState.Assigned, TaskState.InProgress, TaskState.UnderReview, TaskState.RevisionRequired -> true
        TaskState.Blocked -> n.block == BlockReason.AwaitingUser
        else -> false
    }

    private fun claimConflict(s: SchedulerState, cand: TaskNode): Boolean {
        val serialize = s.config.serializeUnclaimed
        if (cand.spec.claims.isEmpty() && !serialize) return false
        val mine = cand.spec.claims.ifEmpty { listOf("**") }
        for (o in s.tasks.values) {
            if (o.spec.id == cand.spec.id || !holdsClaims(o)) continue
            if (o.spec.claims.isEmpty() && !serialize) continue
            val theirs = o.spec.claims.ifEmpty { listOf("**") }
            if (Glob.anyMayOverlap(mine, theirs)) return true
        }
        return false
    }

    private fun startQueued(w: Work, id: TaskId) {
        val n = w.s.tasks[id] ?: return
        var free = w.s.config.maxConcurrent - w.s.liveRuns()
        if (free <= 0) return
        var runs = n.runs
        for (a in n.spec.assignees) {
            if (free <= 0) break
            if (runs[a] == RunState.Queued) { runs = runs + (a to RunState.Starting); w.cmd(SchedulerCommand.StartAgent(id, a, n.attempts)); free-- }
        }
        w.put(n.copy(runs = runs))
    }

    private fun admit(w: Work) {
        if (w.s.cancelledAll || w.s.budget.exhausted) return
        // 1) attempts exhausted for tasks waiting to be re-run
        for (n in ordered(w.s)) {
            if ((n.state == TaskState.Pending || n.state == TaskState.RevisionRequired) && n.attempts > 0 && n.attempts >= w.s.config.retry.maxAttempts)
                failTask(w, n.spec.id, n.failure ?: "attempts exhausted")
        }
        // 2) start queued runs of tasks that are already admitted
        for (n in ordered(w.s)) if ((n.state == TaskState.Assigned || n.state == TaskState.InProgress) && n.runs.containsValue(RunState.Queued)) startQueued(w, n.spec.id)
        // 3) admit new tasks while capacity remains
        for (n0 in ordered(w.s)) {
            if (w.s.config.maxConcurrent - w.s.liveRuns() <= 0) break
            val n = w.s.tasks[n0.spec.id]!!
            if (n.state != TaskState.Pending && n.state != TaskState.RevisionRequired) continue
            if (n.spec.deps.any { w.s.tasks[it]?.state != TaskState.Completed }) continue
            if (w.s.now < n.notBefore) continue
            if (claimConflict(w.s, n)) continue
            w.put(n.copy(
                state = TaskState.Assigned, attempts = n.attempts + 1, runs = n.spec.assignees.associateWith { RunState.Queued },
                gate = GateState.NotRequired, author = null, awaitingChoice = false, applyRequested = false, verifiedBy = null,
                block = null, userNeed = null, failure = null, lastFailure = null,
            ))
            startQueued(w, n.spec.id)
        }
    }
}

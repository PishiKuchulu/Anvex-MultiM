package com.anvex.agents.core.events

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec

/** Who gave a verdict. */
sealed interface Reviewer {
    data object User : Reviewer
    data class Agent(val id: AgentId) : Reviewer
}

/**
 * Where a verdict came from. Only [ReviewerRun] (the reviewer agent's own run channel) and [UserUi]
 * can satisfy a gate; [AgentMessage] (any chat message, including the manager's) never can.
 */
enum class VerdictChannel { ReviewerRun, UserUi, AgentMessage }

enum class FailureKind(val retryable: Boolean) { Error(true), Timeout(true), RateLimited(true), Fatal(false), Cancelled(false) }

enum class UserAction { Approve, Reject, Rerun }

sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome
    data class Conflicts(val count: Int) : ApplyOutcome
    data object Escalated : ApplyOutcome
    data class Rejected(val reason: String) : ApplyOutcome
}

/** Inputs of the scheduler state machine. Every event carries the host clock reading [at] (epoch ms). */
sealed interface SchedulerEvent {
    val at: Long
    data class TaskAdded(override val at: Long, val spec: TaskSpec) : SchedulerEvent
    data class Kick(override val at: Long) : SchedulerEvent
    data class AgentStarted(override val at: Long, val task: TaskId, val agent: AgentId) : SchedulerEvent
    data class AgentFinished(override val at: Long, val task: TaskId, val agent: AgentId) : SchedulerEvent
    data class AgentFailed(override val at: Long, val task: TaskId, val agent: AgentId, val kind: FailureKind, val reason: String) : SchedulerEvent
    data class UsageReported(override val at: Long, val task: TaskId?, val agent: AgentId, val inputTokens: Long, val outputTokens: Long, val costUsd: Double?) : SchedulerEvent
    data class ReviewVerdict(override val at: Long, val task: TaskId, val reviewer: Reviewer, val approved: Boolean, val channel: VerdictChannel) : SchedulerEvent
    data class UserChoseResult(override val at: Long, val task: TaskId, val agent: AgentId) : SchedulerEvent
    data class ApplyResult(override val at: Long, val task: TaskId, val outcome: ApplyOutcome) : SchedulerEvent
    data class UserDecision(override val at: Long, val task: TaskId, val action: UserAction) : SchedulerEvent
    data class PauseAgent(override val at: Long, val agent: AgentId) : SchedulerEvent
    data class ResumeAgent(override val at: Long, val agent: AgentId) : SchedulerEvent
    data class StopAgent(override val at: Long, val agent: AgentId) : SchedulerEvent
    data class CancelTask(override val at: Long, val task: TaskId) : SchedulerEvent
    data class CancelAll(override val at: Long) : SchedulerEvent
    data class RetryTask(override val at: Long, val task: TaskId) : SchedulerEvent
    /** Interrupted work (e.g. the process died) is redone; the lost attempt is not counted against the task. */
    data class Requeue(override val at: Long, val task: TaskId, val reason: String) : SchedulerEvent
    data class SetMaxConcurrent(override val at: Long, val value: Int) : SchedulerEvent
    data class RaiseBudget(override val at: Long, val limits: BudgetLimits) : SchedulerEvent
}

enum class Notice { BudgetWarning, BudgetExhausted, TaskFailed, ReviewNeeded, ChoiceNeeded, ConflictNeedsUser, ApprovalNeeded, GateViolationAttempt, AgentStopped }

/** Outputs of the scheduler: pure data that the host executes. */
sealed interface SchedulerCommand {
    data class StartAgent(val task: TaskId, val agent: AgentId, val attempt: Int) : SchedulerCommand
    data class CancelAgent(val task: TaskId, val agent: AgentId) : SchedulerCommand
    data class PauseAgent(val task: TaskId, val agent: AgentId) : SchedulerCommand
    data class ResumeAgent(val task: TaskId, val agent: AgentId) : SchedulerCommand
    data class RequestReview(val task: TaskId, val author: AgentId) : SchedulerCommand
    data class RequestApply(val task: TaskId, val author: AgentId, val userApprovedEscalation: Boolean = false) : SchedulerCommand
    data class Notify(val notice: Notice, val task: TaskId? = null, val detail: String = "") : SchedulerCommand
    data class Audit(val kind: String, val task: TaskId?, val agent: AgentId?, val detail: String) : SchedulerCommand
}

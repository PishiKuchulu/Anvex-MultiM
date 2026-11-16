package com.anvex.agents.core.domain

import com.anvex.agents.core.path.Glob

@JvmInline value class TaskId(val value: String) { init { require(value.isNotBlank()) { "blank TaskId" } }; override fun toString() = value }
@JvmInline value class AgentId(val value: String) { init { require(value.isNotBlank()) { "blank AgentId" } }; override fun toString() = value }

/** Task states from the specification. `Verified` is a gate outcome, not a task state. */
enum class TaskState { Pending, Assigned, InProgress, Blocked, UnderReview, RevisionRequired, Completed, Failed, Cancelled;
    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Cancelled
}
enum class BlockReason { DependencyFailed, DependencyCancelled, AwaitingUser, BudgetExhausted }
enum class GateState { NotRequired, Required, Satisfied, Rejected }
enum class RunState { Queued, Starting, Running, Paused, Stopping, Finished, Failed, Cancelled;
    val isLive: Boolean get() = this == Starting || this == Running || this == Paused || this == Stopping
    val isTerminal: Boolean get() = this == Finished || this == Failed || this == Cancelled
}

/** What an agent may do (ADR-007). Defaults are least privilege: read everything, write nothing. */
data class Grant(
    val read: List<String> = listOf("**"),
    val write: List<String> = emptyList(),
    val execute: Boolean = false,
    val approve: Boolean = false,
) {
    init {
        (read + write).forEach { require(Glob.isValid(it)) { "invalid glob in grant: '$it'" } }
    }
}

data class AgentLimits(val maxTurns: Int = 40, val maxTokens: Long = 200_000, val timeoutMs: Long = 600_000) {
    init { require(maxTurns > 0 && maxTokens > 0 && timeoutMs > 0) { "limits must be positive" } }
}

/**
 * Configured agent. [providerRef] is the host provider ID, never a key (ADR-018). [params] may not
 * contain credential-like keys ([CredentialGuard]).
 */
data class AgentProfile(
    val id: AgentId,
    val name: String,
    val role: String,
    val providerRef: String,
    val model: String,
    val temperature: Double? = null,
    val params: Map<String, String> = emptyMap(),
    val grant: Grant = Grant(),
    val limits: AgentLimits = AgentLimits(),
    val capabilities: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank() && providerRef.isNotBlank() && model.isNotBlank()) { "name, providerRef and model are required" }
        require(temperature == null || temperature in 0.0..2.0) { "temperature out of range" }
        CredentialGuard.requireClean(params, "agent params")
        require(!CredentialGuard.looksLikeSecretValue(role) && !CredentialGuard.looksLikeSecretValue(name)) { "agent text contains a credential-like value" }
    }
}

data class TaskSpec(
    val id: TaskId,
    val title: String,
    val acceptance: String,
    val deps: Set<TaskId> = emptySet(),
    val claims: List<String> = emptyList(),
    val assignees: List<AgentId>,
    val priority: Int = 0,
    val requiresReview: Boolean = true,
) {
    init {
        require(title.isNotBlank()) { "blank title" }
        require(assignees.isNotEmpty()) { "task needs at least one assignee" }
        require(assignees.toSet().size == assignees.size) { "duplicate assignee" }
        require(id !in deps) { "task depends on itself" }
        claims.forEach { require(Glob.isValid(it)) { "invalid claim glob: '$it'" } }
    }
    val competing: Boolean get() = assignees.size > 1
}

package com.anvex.agents.core.ports

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.events.EventRecord
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.path.RelPath

interface ClockPort { fun nowMillis(): Long }
interface IdPort { fun newId(prefix: String): String }

enum class SensitiveVerdict { Sensitive, NotSensitive, Unknown }
/** Prefixes/names no agent may touch (host SensitivePaths). */
interface SensitivePathPort { fun classify(path: RelPath): SensitiveVerdict }

data class EscalationRequest(val task: TaskId?, val agent: AgentId?, val summary: String, val paths: List<RelPath> = emptyList())
enum class ApprovalDecision { Approved, Denied, TimedOut }
/** Asks the user. TimedOut is treated as Denied by every caller. */
interface ApprovalPort { fun ask(request: EscalationRequest): ApprovalDecision }

interface EventStorePort {
    fun append(runId: String, record: EventRecord)
    fun readAll(runId: String): List<EventRecord>
}

data class PriceInfo(val inputPerMillionUsd: Double, val outputPerMillionUsd: Double) {
    fun costOf(inputTokens: Long, outputTokens: Long): Double =
        inputTokens / 1_000_000.0 * inputPerMillionUsd + outputTokens / 1_000_000.0 * outputPerMillionUsd
}
/** Null price = unknown (cost is then not tracked, tokens still are). */
interface UsagePort { fun priceFor(providerRef: String, model: String): PriceInfo? }

data class RunUsage(val inputTokens: Long, val outputTokens: Long, val costUsd: Double?)

/** Cooperative control: the runner polls this between turns. */
interface RunControl {
    fun isCancelled(): Boolean
    /** Incremental usage after each model response, so a budget can stop a run mid-way. The final result's usage is informational only. */
    fun reportUsage(delta: RunUsage)
    /** Suspends while paused; returns promptly when resumed or cancelled. */
    suspend fun awaitIfPaused()
}

data class AgentRunRequest(val task: TaskSpec, val agent: AgentProfile, val workspace: WorkspaceFileSystem, val attempt: Int, val contextTokenBudget: Int)

sealed interface AgentRunResult {
    val usage: RunUsage
    data class Success(override val usage: RunUsage, val summary: String) : AgentRunResult
    data class Failure(override val usage: RunUsage, val kind: FailureKind, val reason: String) : AgentRunResult
}

/** Runs one agent on one task inside one (forked) workspace. The adapter lives in agents-runtime. */
interface AgentRunnerPort { suspend fun run(request: AgentRunRequest, control: RunControl): AgentRunResult }

data class ContextQuery(val task: TaskSpec, val tokenBudget: Int)
data class ContextItem(val path: RelPath, val excerpt: String, val reason: String)
data class ContextBundle(val items: List<ContextItem>)
interface ContextPort { fun retrieve(query: ContextQuery): ContextBundle }

enum class CommandRisk { Safe, NeedsApproval, Forbidden, Unknown }
data class ShellResult(val exitCode: Int, val output: String, val timedOut: Boolean)
/** Approved command execution inside a workspace; wraps the host ActionSafetyPolicy. */
interface ShellPort {
    fun classify(command: String): CommandRisk
    fun run(workspaceId: String, command: String, timeoutMs: Long): ShellResult
}

data class CommitResult(val ok: Boolean, val commitId: String?, val message: String)
/** Project git operations through the host GitLite; the core never sees the real root. */
interface VersionControlPort {
    fun isRepository(projectId: String): Boolean
    fun currentBranch(projectId: String): String?
    fun commitAll(projectId: String, message: String): CommitResult
}

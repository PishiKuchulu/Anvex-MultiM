package com.anvex.agents.core.budget

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId

/** Hard caps; null = unlimited. Cost is enforced only on priced usage (price may be unknown). */
data class BudgetLimits(val maxTokens: Long? = null, val maxCostUsd: Double? = null) {
    init {
        require(maxTokens == null || maxTokens > 0) { "maxTokens must be positive" }
        require(maxCostUsd == null || maxCostUsd > 0.0) { "maxCostUsd must be positive" }
    }
}

data class UsageTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val unpricedResponses: Int = 0,
) {
    val tokens: Long get() = inputTokens + outputTokens
    operator fun plus(o: UsageTotals) = UsageTotals(inputTokens + o.inputTokens, outputTokens + o.outputTokens, costUsd + o.costUsd, unpricedResponses + o.unpricedResponses)
}

/** Immutable per-agent / per-task usage ledger (requirements token_track, budgets). */
data class UsageLedger(
    val total: UsageTotals = UsageTotals(),
    val byAgent: Map<AgentId, UsageTotals> = emptyMap(),
    val byTask: Map<TaskId, UsageTotals> = emptyMap(),
) {
    fun record(agent: AgentId, task: TaskId?, inputTokens: Long, outputTokens: Long, costUsd: Double?): UsageLedger {
        require(inputTokens >= 0 && outputTokens >= 0) { "negative token count" }
        require(costUsd == null || (costUsd >= 0.0 && costUsd.isFinite())) { "invalid cost" }
        val d = UsageTotals(inputTokens, outputTokens, costUsd ?: 0.0, if (costUsd == null) 1 else 0)
        return UsageLedger(
            total = total + d,
            byAgent = byAgent + (agent to ((byAgent[agent] ?: UsageTotals()) + d)),
            byTask = if (task == null) byTask else byTask + (task to ((byTask[task] ?: UsageTotals()) + d)),
        )
    }
}

data class BudgetState(
    val limits: BudgetLimits = BudgetLimits(),
    val ledger: UsageLedger = UsageLedger(),
    val warned: Boolean = false,
    val exhausted: Boolean = false,
) {
    /** Fraction of the tightest limit used, 0.0 when nothing is limited. */
    fun usedFraction(): Double {
        val t = limits.maxTokens?.let { ledger.total.tokens.toDouble() / it } ?: 0.0
        val c = limits.maxCostUsd?.let { ledger.total.costUsd / it } ?: 0.0
        return maxOf(t, c)
    }
    fun isOverLimit(): Boolean = usedFraction() >= 1.0
}

/** Deterministic retry/backoff (no jitter, no clock). attempt is 1-based (the attempt that just failed). */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 2_000,
    val factor: Double = 2.0,
    val maxDelayMs: Long = 60_000,
    val rateLimitDelayMs: Long = 30_000,
) {
    init { require(maxAttempts >= 1 && baseDelayMs >= 0 && factor >= 1.0 && maxDelayMs >= baseDelayMs) { "invalid retry policy" } }

    fun delayMs(failedAttempt: Int, rateLimited: Boolean): Long {
        var d = baseDelayMs.toDouble()
        repeat((failedAttempt - 1).coerceAtLeast(0)) { d = minOf(d * factor, maxDelayMs.toDouble()) }
        val backoff = minOf(d, maxDelayMs.toDouble()).toLong()
        return if (rateLimited) maxOf(backoff, rateLimitDelayMs) else backoff
    }
}

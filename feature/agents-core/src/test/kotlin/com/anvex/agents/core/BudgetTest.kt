package com.anvex.agents.core

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.budget.BudgetState
import com.anvex.agents.core.budget.RetryPolicy
import com.anvex.agents.core.budget.UsageLedger
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetTest {
    @Test fun retryDelayGrowsAndCaps() {
        val p = RetryPolicy(maxAttempts = 10, baseDelayMs = 2000, factor = 2.0, maxDelayMs = 60_000, rateLimitDelayMs = 30_000)
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L), (1..7).map { p.delayMs(it, false) })
        assertEquals(30_000L, p.delayMs(1, true)); assertEquals(32_000L, p.delayMs(5, true)); assertEquals(60_000L, p.delayMs(9, true))
    }
    @Test fun retryPolicyValidation() {
        for (f in listOf({ RetryPolicy(maxAttempts = 0) }, { RetryPolicy(factor = 0.5) }, { RetryPolicy(baseDelayMs = 10, maxDelayMs = 5) }))
            try { f(); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) { }
    }
    @Test fun ledgerAccumulatesPerAgentAndTask() {
        val l = UsageLedger().record(AgentId("a"), TaskId("t1"), 100, 50, 0.5).record(AgentId("a"), TaskId("t2"), 10, 5, null).record(AgentId("b"), null, 1, 1, 0.01)
        assertEquals(167L, l.total.tokens); assertEquals(0.51, l.total.costUsd, 1e-9); assertEquals(1, l.total.unpricedResponses)
        assertEquals(165L, l.byAgent[AgentId("a")]!!.tokens); assertEquals(150L, l.byTask[TaskId("t1")]!!.tokens)
        assertEquals(2, l.byTask.size)
    }
    @Test fun ledgerRejectsInvalidNumbers() {
        for (f in listOf({ UsageLedger().record(AgentId("a"), null, -1, 0, null) }, { UsageLedger().record(AgentId("a"), null, 0, 0, -0.1) }, { UsageLedger().record(AgentId("a"), null, 0, 0, Double.NaN) }))
            try { f(); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) { }
    }
    @Test fun usedFractionUsesTightestLimit() {
        val l = UsageLedger().record(AgentId("a"), null, 400, 100, 0.2)
        assertEquals(0.5, BudgetState(BudgetLimits(maxTokens = 1000), l).usedFraction(), 1e-9)
        assertEquals(0.8, BudgetState(BudgetLimits(maxTokens = 1000, maxCostUsd = 0.25), l).usedFraction(), 1e-9)
        assertEquals(0.0, BudgetState(BudgetLimits(), l).usedFraction(), 1e-9)
        assertTrue(BudgetState(BudgetLimits(maxTokens = 500), l).isOverLimit()); assertFalse(BudgetState(BudgetLimits(maxTokens = 501), l).isOverLimit())
    }
}

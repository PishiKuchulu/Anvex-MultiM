package com.anvex.agents.core

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.policy.Action
import com.anvex.agents.core.policy.ApprovalSource
import com.anvex.agents.core.policy.Decision
import com.anvex.agents.core.policy.PolicyContext
import com.anvex.agents.core.policy.PolicyEngine
import com.anvex.agents.core.ports.CommandRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private val engine = PolicyEngine(FakeSensitive(unknown = setOf("maybe.txt"), throwOn = setOf("oracle-down.txt")))
    private val dev = worker("a")
    private val exec = worker("x", com.anvex.agents.core.domain.Grant(write = listOf("**"), execute = true))
    private fun p(s: String) = RelPath.of(s)
    private fun ev(ctx: PolicyContext, a: Action) = engine.evaluate(ctx, a)

    @Test fun readRules() {
        val ctx = PolicyContext(dev)
        assertEquals(Decision.ALLOW, ev(ctx, Action.Read(p("src/a.kt"))).decision)
        assertEquals(Decision.DENY, ev(ctx, Action.Read(p(".env"))).decision)
        assertEquals(Decision.DENY, ev(ctx, Action.Read(p("app/.git/config"))).decision)
        assertEquals(Decision.DEFER, ev(ctx, Action.Read(p("maybe.txt"))).decision)
        assertEquals("oracle failure must fail closed", Decision.DEFER, ev(ctx, Action.Read(p("oracle-down.txt"))).decision)
        val narrow = PolicyContext(worker("n", com.anvex.agents.core.domain.Grant(read = listOf("docs/**"))))
        assertEquals(Decision.ALLOW, ev(narrow, Action.Read(p("docs/a.md"))).decision)
        assertEquals(Decision.DENY, ev(narrow, Action.Read(p("src/a.kt"))).decision)
    }

    @Test fun writeRules() {
        val noClaims = PolicyContext(dev)
        assertEquals(Decision.ALLOW, ev(noClaims, Action.Write(p("src/a.kt"))).decision)
        val claims = PolicyContext(dev, taskClaims = listOf("src/**"))
        assertEquals(Decision.ALLOW, ev(claims, Action.Write(p("src/a.kt"))).decision)
        assertEquals("outside claim escalates", Decision.ESCALATE, ev(claims, Action.Write(p("build.gradle.kts"))).decision)
        val approved = claims.copy(userApprovedPaths = setOf(p("build.gradle.kts")))
        assertEquals(Decision.ALLOW, ev(approved, Action.Write(p("build.gradle.kts"))).decision)
        assertEquals("approval is per path", Decision.ESCALATE, ev(approved, Action.Write(p("settings.gradle.kts"))).decision)
        assertEquals("sensitive path is DENY even if claimed and approved", Decision.DENY,
            ev(claims.copy(taskClaims = listOf("**"), userApprovedPaths = setOf(p(".env"))), Action.Write(p(".env"))).decision)
        assertEquals(Decision.DENY, ev(PolicyContext(reviewer("r")), Action.Write(p("src/a.kt"))).decision)
        assertEquals("sensitivity unknown => DEFER (never ALLOW)", Decision.DEFER, ev(noClaims, Action.Write(p("maybe.txt"))).decision)
        assertEquals(Decision.DENY, ev(PolicyContext(worker("w", com.anvex.agents.core.domain.Grant(write = listOf("docs/**")))), Action.Write(p("src/a.kt"))).decision)
    }

    @Test fun deleteFollowsWriteRules() {
        val ctx = PolicyContext(dev, taskClaims = listOf("src/**"))
        assertEquals(Decision.ALLOW, ev(ctx, Action.Delete(p("src/old.kt"))).decision)
        assertEquals(Decision.ESCALATE, ev(ctx, Action.Delete(p("README.md"))).decision)
        assertEquals(Decision.DENY, ev(ctx, Action.Delete(p(".env"))).decision)
    }

    @Test fun executeRules() {
        assertEquals(Decision.DENY, ev(PolicyContext(dev), Action.Execute(CommandRisk.Safe)).decision)
        val c = PolicyContext(exec)
        assertEquals(Decision.ALLOW, ev(c, Action.Execute(CommandRisk.Safe)).decision)
        assertEquals(Decision.ESCALATE, ev(c, Action.Execute(CommandRisk.NeedsApproval)).decision)
        assertEquals(Decision.ALLOW, ev(c.copy(escalationApproved = true), Action.Execute(CommandRisk.NeedsApproval)).decision)
        assertEquals("forbidden stays DENY even after user approval", Decision.DENY, ev(c.copy(escalationApproved = true), Action.Execute(CommandRisk.Forbidden)).decision)
        assertEquals(Decision.DEFER, ev(c, Action.Execute(CommandRisk.Unknown)).decision)
    }

    @Test fun approveRules() {
        val rev = PolicyContext(reviewer("r1"))
        fun appr(source: ApprovalSource, author: String? = "a", assignees: Set<String> = setOf("a")) =
            Action.Approve(TaskId("t"), source, author?.let { AgentId(it) }, assignees.map { AgentId(it) }.toSet())
        assertEquals(Decision.ALLOW, ev(rev, appr(ApprovalSource.ReviewerRun)).decision)
        assertEquals("manager/chat message cannot satisfy a gate", Decision.DENY, ev(rev, appr(ApprovalSource.AgentMessage)).decision)
        assertEquals("an agent cannot claim the user's channel", Decision.DENY, ev(rev, appr(ApprovalSource.UserUi)).decision)
        assertEquals("author cannot verify", Decision.DENY, ev(PolicyContext(reviewer("a")), appr(ApprovalSource.ReviewerRun)).decision)
        assertEquals("any assignee (competitor) cannot verify", Decision.DENY, ev(PolicyContext(reviewer("b")), appr(ApprovalSource.ReviewerRun, author = "a", assignees = setOf("a", "b"))).decision)
        assertEquals("no approve grant", Decision.DENY, ev(PolicyContext(reviewer("r2", approve = false)), appr(ApprovalSource.ReviewerRun)).decision)
        assertEquals(Decision.ALLOW, ev(rev, appr(ApprovalSource.ReviewerRun, author = null, assignees = emptySet())).decision)
    }

    @Test fun overallDecisionIsTheWorstAndEmptyIsDefer() {
        val ctx = PolicyContext(dev, taskClaims = listOf("src/**"))
        val (overall, each) = engine.evaluateAll(ctx, listOf(Action.Write(p("src/a.kt")), Action.Write(p("README.md")), Action.Write(p(".env"))))
        assertEquals(Decision.DENY, overall); assertEquals(listOf(Decision.ALLOW, Decision.ESCALATE, Decision.DENY), each.map { it.decision })
        assertEquals(Decision.ESCALATE, engine.evaluateAll(ctx, listOf(Action.Write(p("src/a.kt")), Action.Write(p("README.md")))).first)
        assertEquals("no actions => no evidence => DEFER", Decision.DEFER, engine.evaluateAll(ctx, emptyList()).first)
    }

    @Test fun precedenceHoldsOnRandomInputs() {
        val rnd = java.util.Random(11)
        val paths = listOf("src/a.kt", "README.md", ".env", "maybe.txt", "docs/x.md", "oracle-down.txt", "a/.git/x")
        val actors = listOf(dev, reviewer("r"), exec, worker("n", com.anvex.agents.core.domain.Grant(read = listOf("docs/**"), write = listOf("docs/**"))))
        repeat(3000) {
            val ctx = PolicyContext(actors[rnd.nextInt(actors.size)], if (rnd.nextBoolean()) listOf("src/**") else emptyList(),
                if (rnd.nextBoolean()) setOf(p("README.md")) else emptySet(), rnd.nextBoolean())
            val path = p(paths[rnd.nextInt(paths.size)])
            val action: Action = when (rnd.nextInt(4)) { 0 -> Action.Read(path); 1 -> Action.Write(path); 2 -> Action.Delete(path); else -> Action.Execute(CommandRisk.entries[rnd.nextInt(4)]) }
            val r = ev(ctx, action)
            val votes = r.hits.map { it.vote }
            when {
                Decision.DENY in votes -> assertEquals(Decision.DENY, r.decision)
                Decision.ESCALATE in votes -> assertEquals(Decision.ESCALATE, r.decision)
                Decision.DEFER in votes -> assertEquals(Decision.DEFER, r.decision)
                else -> assertEquals(Decision.ALLOW, r.decision)
            }
            if (r.decision == Decision.ALLOW) assertTrue("ALLOW needs explicit evidence and unanimity", r.hits.isNotEmpty() && r.hits.all { it.vote == Decision.ALLOW })
        }
    }
}

package com.anvex.agents.core.policy

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.path.Glob
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.CommandRisk
import com.anvex.agents.core.ports.SensitivePathPort
import com.anvex.agents.core.ports.SensitiveVerdict

/**
 * Decision vocabulary of the PFP Runtime Enforcement Contract, implemented in code (ADR-022).
 * Precedence DENY > ESCALATE > DEFER > ALLOW. ALLOW needs at least one explicit allow rule and no
 * other vote; no evidence at all is DEFER, never ALLOW.
 */
enum class Decision(internal val rank: Int) { ALLOW(0), DEFER(1), ESCALATE(2), DENY(3) }

data class RuleHit(val rule: String, val vote: Decision, val reason: String)
data class PolicyResult(val decision: Decision, val hits: List<RuleHit>) {
    val allowed: Boolean get() = decision == Decision.ALLOW
}

enum class ApprovalSource { ReviewerRun, UserUi, AgentMessage }

sealed interface Action {
    data class Read(val path: RelPath) : Action
    data class Write(val path: RelPath) : Action
    data class Delete(val path: RelPath) : Action
    data class Execute(val risk: CommandRisk) : Action
    /** An agent tries to approve/verify [task]; [author] and [assignees] identify who did the work. */
    data class Approve(val task: TaskId, val source: ApprovalSource, val author: AgentId?, val assignees: Set<AgentId>) : Action
}

/** Evidence the caller supplies; the engine never fetches it from an agent. */
data class PolicyContext(
    val actor: AgentProfile,
    /** Files the task declared; empty = none declared (scope is then bounded only by the grant). */
    val taskClaims: List<String> = emptyList(),
    /** Paths the user explicitly approved for this ChangeSet only. */
    val userApprovedPaths: Set<RelPath> = emptySet(),
    /** True when the user already approved the escalation of a risky command. */
    val escalationApproved: Boolean = false,
)

/**
 * ADR-007: the only place where ownership, permissions and gate rules are decided. Ports are
 * mechanical and never authorize.
 */
class PolicyEngine(private val sensitive: SensitivePathPort) {

    fun evaluate(ctx: PolicyContext, action: Action): PolicyResult {
        val hits = ArrayList<RuleHit>()
        when (action) {
            is Action.Read -> { sensitiveRule(action.path, hits); grantRule(ctx.actor.grant.read, action.path, "read", hits) }
            is Action.Write -> writeRules(ctx, action.path, hits)
            is Action.Delete -> writeRules(ctx, action.path, hits)
            is Action.Execute -> executeRules(ctx, action.risk, hits)
            is Action.Approve -> approveRules(ctx, action, hits)
        }
        return combine(hits)
    }

    /** Evaluates every action; the overall decision is the worst one (fail closed). */
    fun evaluateAll(ctx: PolicyContext, actions: List<Action>): Pair<Decision, List<PolicyResult>> {
        val results = actions.map { evaluate(ctx, it) }
        val worst = results.maxOfOrNull { it.decision.rank }
        val overall = if (worst == null) Decision.DEFER else Decision.entries.first { it.rank == worst }
        return overall to results
    }

    private fun combine(hits: List<RuleHit>): PolicyResult {
        if (hits.isEmpty()) return PolicyResult(Decision.DEFER, listOf(RuleHit("R-NOEVIDENCE", Decision.DEFER, "no rule produced evidence")))
        val rank = hits.maxOf { it.vote.rank }
        return PolicyResult(Decision.entries.first { it.rank == rank }, hits)
    }

    private fun sensitiveRule(path: RelPath, hits: MutableList<RuleHit>) {
        val v = try { sensitive.classify(path) } catch (e: Exception) { SensitiveVerdict.Unknown }
        when (v) {
            SensitiveVerdict.Sensitive -> hits += RuleHit("R-SENSITIVE", Decision.DENY, "sensitive path")
            SensitiveVerdict.Unknown -> hits += RuleHit("R-SENSITIVE", Decision.DEFER, "sensitivity could not be determined")
            SensitiveVerdict.NotSensitive -> Unit
        }
    }

    private fun grantRule(globs: List<String>, path: RelPath, what: String, hits: MutableList<RuleHit>) {
        if (Glob.matchesAny(globs, path)) hits += RuleHit("R-GRANT", Decision.ALLOW, "$what allowed by grant")
        else hits += RuleHit("R-GRANT", Decision.DENY, "$what not covered by the agent's grant")
    }

    private fun writeRules(ctx: PolicyContext, path: RelPath, hits: MutableList<RuleHit>) {
        sensitiveRule(path, hits)
        grantRule(ctx.actor.grant.write, path, "write", hits)
        if (ctx.taskClaims.isNotEmpty() && path !in ctx.userApprovedPaths && !Glob.matchesAny(ctx.taskClaims, path))
            hits += RuleHit("R-CLAIM", Decision.ESCALATE, "path is outside the task's declared files")
    }

    private fun executeRules(ctx: PolicyContext, risk: CommandRisk, hits: MutableList<RuleHit>) {
        if (!ctx.actor.grant.execute) { hits += RuleHit("R-EXEC-GRANT", Decision.DENY, "agent may not execute commands"); return }
        when (risk) {
            CommandRisk.Safe -> hits += RuleHit("R-EXEC-RISK", Decision.ALLOW, "command classified safe")
            CommandRisk.NeedsApproval ->
                hits += if (ctx.escalationApproved) RuleHit("R-EXEC-RISK", Decision.ALLOW, "user approved this command")
                else RuleHit("R-EXEC-RISK", Decision.ESCALATE, "command needs user approval")
            CommandRisk.Forbidden -> hits += RuleHit("R-EXEC-RISK", Decision.DENY, "command is forbidden")
            CommandRisk.Unknown -> hits += RuleHit("R-EXEC-RISK", Decision.DEFER, "command risk unknown")
        }
    }

    private fun approveRules(ctx: PolicyContext, a: Action.Approve, hits: MutableList<RuleHit>) {
        if (!ctx.actor.grant.approve) hits += RuleHit("R-APPROVE-GRANT", Decision.DENY, "agent lacks the approve grant")
        if (a.source != ApprovalSource.ReviewerRun) hits += RuleHit("R-APPROVE-CHANNEL", Decision.DENY, "a gate can only be satisfied through the reviewer's own run channel, not via ${a.source}")
        if (ctx.actor.id == a.author || ctx.actor.id in a.assignees) hits += RuleHit("R-APPROVE-INDEPENDENCE", Decision.DENY, "the author of the work cannot verify it")
        if (hits.isEmpty()) hits += RuleHit("R-APPROVE", Decision.ALLOW, "independent reviewer through the proper channel")
    }
}

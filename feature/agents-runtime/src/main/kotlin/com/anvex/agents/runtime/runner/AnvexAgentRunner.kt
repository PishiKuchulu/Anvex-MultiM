package com.anvex.agents.runtime.runner

import com.anvex.agents.core.domain.CredentialGuard
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.ports.AgentRunRequest
import com.anvex.agents.core.ports.AgentRunResult
import com.anvex.agents.core.ports.AgentRunnerPort
import com.anvex.agents.core.ports.ClockPort
import com.anvex.agents.core.ports.ContextPort
import com.anvex.agents.core.ports.ContextQuery
import com.anvex.agents.core.ports.RunControl
import com.anvex.agents.core.ports.RunUsage
import com.anvex.agents.core.prompt.AgentPrompts
import com.anvex.agents.fs.RealWorkspaceFileSystem
import com.anvex.agents.runtime.AgentsHost
import com.anvex.core.ai.AiError
import com.anvex.core.ai.ChatRequest
import com.anvex.core.ai.ChatTurn
import com.anvex.core.ai.StreamEvent
import com.anvex.core.engine.ActionDispatcher
import com.anvex.core.engine.ActionCallbackData
import com.anvex.core.engine.BoltAction
import com.anvex.core.engine.BoltStreamParser
import com.anvex.core.engine.ParserCallbacks
import com.anvex.core.engine.SystemPromptFactory
import com.anvex.core.model.MessageRole

/**
 * UNVERIFIED (never compiled; written against the Anvex 4.15.2 sources: ProviderClient.streamChat, BoltStreamParser.parse,
 * ActionDispatcher.applyFileAction, SystemPromptFactory.buildSystemPrompt).
 *
 * Composes engine building blocks directly instead of reusing ChatSession (ADR-021). The agent edits ITS FORK through the host's
 * hardened ProjectFileSystem (ADR-002 mount). It can only write files: shell/start actions are refused in this version, and the
 * dispatcher gets no approval callback, so nothing that needs approval can be approved by the agent itself.
 */
class AnvexAgentRunner(
    private val host: AgentsHost,
    private val context: ContextPort,
    private val clock: ClockPort,
) : AgentRunnerPort {

    override suspend fun run(request: AgentRunRequest, control: RunControl): AgentRunResult {
        val agent = request.agent; val task = request.task
        var inTok = 0L; var outTok = 0L; var cost: Double? = null
        fun usage() = RunUsage(inTok, outTok, cost)
        fun fail(kind: FailureKind, reason: String) = AgentRunResult.Failure(usage(), kind, CredentialGuard.scrub(reason).take(400))

        val client = host.providerClient(agent.providerRef) ?: return fail(FailureKind.Fatal, "provider '${agent.providerRef}' is not available")
        val workspace = request.workspace as? RealWorkspaceFileSystem ?: return fail(FailureKind.Fatal, "the workspace is not a real workspace")
        val dispatcher = ActionDispatcher(workspace.projectFileSystem())   // no approval callback on purpose
        val price = host.priceFor(agent.providerRef, agent.model)

        val ctx = try { context.retrieve(ContextQuery(task, request.contextTokenBudget)).items } catch (e: Exception) { emptyList() }
        val system = AgentPrompts.system(agent, SystemPromptFactory.buildSystemPrompt())
        val turns = ArrayList<ChatTurn>()
        turns += ChatTurn(MessageRole.USER, AgentPrompts.task(task, ctx))
        val started = clock.nowMillis()
        var lastText = ""

        for (turn in 1..agent.limits.maxTurns) {
            if (control.isCancelled()) return fail(FailureKind.Cancelled, "cancelled")
            control.awaitIfPaused()
            if (control.isCancelled()) return fail(FailureKind.Cancelled, "cancelled")
            if (clock.nowMillis() - started > agent.limits.timeoutMs) return fail(FailureKind.Timeout, "the run exceeded its time limit")
            if (inTok + outTok > agent.limits.maxTokens) return fail(FailureKind.Fatal, "the agent reached its token limit")

            val messageId = "${task.id.value}-${agent.id.value}-$turn"
            val text = StringBuilder(); val results = ArrayList<String>(); var failure: AiError? = null; var wrote = 0
            val parser = BoltStreamParser(object : ParserCallbacks {
                override fun onActionClose(data: ActionCallbackData) {
                    val a = data.action
                    if (a is BoltAction.FileAction) {
                        val r = dispatcher.applyFileAction(a, messageId)
                        if (r.success) { wrote++; results += "OK ${a.filePath}" } else results += "FAILED ${a.filePath}: ${r.error ?: "refused"}"
                    } else results += "REFUSED: agents in a team cannot run commands in this version"
                }
            })
            client.streamChat(ChatRequest(system, turns.toList(), temperature = agent.temperature)).collect { ev ->
                when (ev) {
                    is StreamEvent.Token -> { text.append(ev.text); parser.parse(messageId, text.toString()) }
                    is StreamEvent.Completed -> ev.usage?.let { u ->
                        val dIn = u.promptTokens; val dOut = u.completionTokens
                        val dCost = u.costUsd ?: price?.costOf(dIn, dOut)
                        inTok += dIn; outTok += dOut; if (dCost != null) cost = (cost ?: 0.0) + dCost
                        control.reportUsage(RunUsage(dIn, dOut, dCost))
                    }
                    is StreamEvent.Failed -> failure = ev.error
                    else -> Unit
                }
            }
            val failed = failure   // plain local: a captured, modified var cannot be smart-cast
            if (failed != null) return fail(map(failed.kind), failed.userTitle + ": " + failed.message)
            lastText = text.toString()
            if (results.isEmpty()) return AgentRunResult.Success(usage(), CredentialGuard.scrub(lastText).take(500))   // no actions: the agent says it is done
            turns += ChatTurn(MessageRole.ASSISTANT, lastText)
            turns += ChatTurn(MessageRole.USER, AgentPrompts.feedback(results))
        }
        return AgentRunResult.Success(usage(), "turn limit reached: " + CredentialGuard.scrub(lastText).take(400))
    }

    private fun map(k: AiError.Kind): FailureKind = when (k) {
        AiError.Kind.RATE_LIMIT -> FailureKind.RateLimited
        AiError.Kind.NETWORK, AiError.Kind.PROTOCOL, AiError.Kind.UNKNOWN -> FailureKind.Error
        AiError.Kind.CANCELED -> FailureKind.Cancelled
        AiError.Kind.AUTH, AiError.Kind.ACCESS_RESTRICTED, AiError.Kind.NOT_FOUND -> FailureKind.Fatal
    }
}

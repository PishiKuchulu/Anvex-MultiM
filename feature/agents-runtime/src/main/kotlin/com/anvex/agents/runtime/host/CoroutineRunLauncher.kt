package com.anvex.agents.runtime.host

import com.anvex.agents.core.assurance.VerdictParse
import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.VerdictChannel
import com.anvex.agents.core.orchestrator.AuditPort
import com.anvex.agents.core.orchestrator.Orchestrator
import com.anvex.agents.core.orchestrator.RunLauncher
import com.anvex.agents.core.ports.AgentRunRequest
import com.anvex.agents.core.ports.AgentRunResult
import com.anvex.agents.core.ports.AgentRunnerPort
import com.anvex.agents.core.ports.ClockPort
import com.anvex.agents.core.ports.RunControl
import com.anvex.agents.core.ports.RunUsage
import com.anvex.agents.core.ports.WorkspaceFileSystem
import com.anvex.agents.runtime.runner.ReviewRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UNVERIFIED (never compiled). The asynchronous shell around the pure Orchestrator: it only starts coroutines and reports
 * their outcome as events. Every decision stays in the tested core. Contract with the Orchestrator: [Orchestrator.runEnded] is
 * called only after the coroutine has completely stopped, so a fork is never deleted under a running agent.
 */
class CoroutineRunLauncher(
    private val scope: CoroutineScope,
    private val runner: AgentRunnerPort,
    private val reviews: ReviewRunner,
    private val clock: ClockPort,
    private val audit: AuditPort,
) : RunLauncher {

    @Volatile private var orchestrator: Orchestrator? = null
    fun bind(o: Orchestrator) { orchestrator = o }

    private class Handle(val job: Job, val cancelled: AtomicBoolean, val paused: MutableStateFlow<Boolean>)
    private val handles = ConcurrentHashMap<String, Handle>()
    private fun key(t: TaskId, a: AgentId) = "${t.value}|${a.value}"

    override fun startAgent(task: TaskSpec, agent: AgentProfile, attempt: Int, workspace: WorkspaceFileSystem) {
        val o = orchestrator ?: error("launcher is not bound to an orchestrator")
        val cancelled = AtomicBoolean(false); val paused = MutableStateFlow(false)
        val control = object : RunControl {
            override fun isCancelled() = cancelled.get()
            override suspend fun awaitIfPaused() { if (paused.value) paused.first { !it || cancelled.get() } }
            override fun reportUsage(delta: RunUsage) { o.submit(SchedulerEvent.UsageReported(clock.nowMillis(), task.id, agent.id, delta.inputTokens, delta.outputTokens, delta.costUsd)) }
        }
        val job = scope.launch(Dispatchers.IO) {
            o.submit(SchedulerEvent.AgentStarted(clock.nowMillis(), task.id, agent.id))
            val result: AgentRunResult = try { runner.run(AgentRunRequest(task, agent, workspace, attempt, contextTokenBudget = 6000), control) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AgentRunResult.Failure(RunUsage(0, 0, null), FailureKind.Error, "the runner crashed: ${e::class.simpleName}") }
            when (result) {
                is AgentRunResult.Success -> o.submit(SchedulerEvent.AgentFinished(clock.nowMillis(), task.id, agent.id))
                is AgentRunResult.Failure -> o.submit(SchedulerEvent.AgentFailed(clock.nowMillis(), task.id, agent.id, result.kind, result.reason))
            }
        }
        val k = key(task.id, agent.id)
        handles[k] = Handle(job, cancelled, paused)
        job.invokeOnCompletion { cause ->
            handles.remove(k)
            if (cause is CancellationException) o.submit(SchedulerEvent.AgentFailed(clock.nowMillis(), task.id, agent.id, FailureKind.Cancelled, "cancelled"))   // closes a 'Stopping' run; stale otherwise
            o.runEnded(task.id, agent.id)
        }
    }

    override fun cancel(task: TaskId, agent: AgentId) { handles[key(task, agent)]?.let { it.cancelled.set(true); it.job.cancel() } }
    override fun pause(task: TaskId, agent: AgentId) { handles[key(task, agent)]?.paused?.value = true }
    override fun resume(task: TaskId, agent: AgentId) { handles[key(task, agent)]?.paused?.value = false }

    override fun startReview(task: TaskSpec, author: AgentId, reviewer: AgentProfile, changes: ChangeSet) {
        val o = orchestrator ?: error("launcher is not bound to an orchestrator")
        scope.launch(Dispatchers.IO) {
            val verdict = try { reviews.review(task, changes, reviewer) { u -> o.submit(SchedulerEvent.UsageReported(clock.nowMillis(), task.id, reviewer.id, u.inputTokens, u.outputTokens, u.costUsd)) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { VerdictParse.Invalid(listOf("the reviewer crashed: ${e::class.simpleName}")) }
            when (verdict) {
                // the only place an agent verdict enters the engine: the reviewer's own run channel (never a chat message)
                is VerdictParse.Valid -> o.submit(SchedulerEvent.ReviewVerdict(clock.nowMillis(), task.id, Reviewer.Agent(reviewer.id), verdict.verdict.approve, VerdictChannel.ReviewerRun))
                is VerdictParse.Invalid -> audit.record("ReviewerVerdictInvalid", task.id, reviewer.id, verdict.reasons.joinToString("; ").take(300))   // the task stays UnderReview for the user
            }
        }
    }
}

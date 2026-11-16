package com.anvex.agents.core

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.budget.RetryPolicy
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.BlockReason
import com.anvex.agents.core.domain.GateState
import com.anvex.agents.core.domain.RunState
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.events.VerdictChannel
import com.anvex.agents.core.path.Glob
import com.anvex.agents.core.scheduler.Scheduler
import com.anvex.agents.core.scheduler.SchedulerConfig
import com.anvex.agents.core.scheduler.TaskNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Randomized host: for 300 seeds a simulated host reacts to the scheduler's commands (and also injects
 * stale, bogus and hostile events). After EVERY event the safety invariants must hold, the scheduler must
 * never throw, and replaying the event log must reproduce the exact state and commands.
 */
class SchedulerPropertyTest {
    private val claimPool = listOf("app/**", "app/x.kt", "lib/**", "docs/*.md", "**", "core/a/**")

    private fun checkInvariants(sim: Sim, ctx: String) {
        val s = sim.s
        fun fail(msg: String): Nothing = throw AssertionError("$ctx: $msg")
        // I1 concurrency cap
        if (s.liveRuns() > s.config.maxConcurrent) fail("live runs ${s.liveRuns()} > cap ${s.config.maxConcurrent}")
        // I6 exhausted budget => nothing running or waiting to run
        if (s.budget.exhausted && s.tasks.values.any { n -> n.runs.values.any { it.isLive || it == RunState.Queued } }) fail("budget exhausted but runs still active")
        for (n in s.tasks.values) {
            val id = n.spec.id.value
            // I2 completion requires a satisfied gate (or none required), an author, completed dependencies
            if (n.state == TaskState.Completed) {
                if (n.gate != GateState.Satisfied && n.gate != GateState.NotRequired) fail("$id completed with gate ${n.gate}")
                if (n.author == null) fail("$id completed without author")
                if (n.gate == GateState.NotRequired && n.spec.requiresReview) fail("$id completed without the mandatory review")
                if (n.gate == GateState.Satisfied) {
                    when (val v = n.verifiedBy) {
                        null -> fail("$id gate satisfied without verifier")
                        is Reviewer.Agent -> {
                            if (v.id in n.spec.assignees) fail("$id verified by one of its authors")
                            if (s.agents[v.id]?.grant?.approve != true) fail("$id verified by agent without approve grant")
                        }
                        is Reviewer.User -> Unit
                    }
                }
                for (d in n.spec.deps) if (s.tasks[d]?.state != TaskState.Completed) fail("$id completed before dependency $d")
            }
            // I3 run bookkeeping
            if (n.state.isTerminal && n.runs.values.any { it.isLive }) fail("$id is ${n.state} but has live runs")
            if ((n.state == TaskState.Assigned || n.state == TaskState.InProgress) && n.runs.values.none { !it.isTerminal }) fail("$id ${n.state} without active runs")
            if (n.state == TaskState.Pending && n.runs.values.any { it.isLive }) fail("$id Pending with live runs")
            // I5 attempts
            if (n.attempts > s.config.retry.maxAttempts) fail("$id attempts ${n.attempts} > max")
            // gate bookkeeping
            if (n.state == TaskState.UnderReview && !n.awaitingChoice && n.author == null) fail("$id under review without author")
            if (n.applyRequested && n.gate == GateState.Required) fail("$id apply requested while gate still required")
            // dependents of failed tasks cannot run
            if ((n.state == TaskState.Assigned || n.state == TaskState.InProgress) && n.spec.deps.any { s.tasks[it]?.state != TaskState.Completed }) fail("$id running before dependencies completed")
        }
        // I4 claim exclusion among running tasks and claim holders
        val holders = s.tasks.values.filter { holds(it) }
        for (x in s.tasks.values.filter { it.state == TaskState.Assigned || it.state == TaskState.InProgress }) for (y in holders) {
            if (x.spec.id == y.spec.id) continue
            val cx = eff(sim, x); val cy = eff(sim, y)
            if (cx != null && cy != null && Glob.anyMayOverlap(cx, cy)) fail("tasks ${x.spec.id} and ${y.spec.id} run/hold overlapping claims $cx vs $cy")
        }
    }
    private fun holds(n: TaskNode) = when (n.state) {
        TaskState.Assigned, TaskState.InProgress, TaskState.UnderReview, TaskState.RevisionRequired -> true
        TaskState.Blocked -> n.block == BlockReason.AwaitingUser
        else -> false
    }
    private fun eff(sim: Sim, n: TaskNode): List<String>? = if (n.spec.claims.isNotEmpty()) n.spec.claims else if (sim.s.config.serializeUnclaimed) listOf("**") else null

    @Test fun invariantsHoldUnderRandomHostBehaviour() {
        var totalSteps = 0; var completed = 0; var violationsSeen = 0; var failedTasks = 0; var exhaustedRuns = 0; var conflicts = 0
        for (seed in 1..300) {
            val r = runSeed(seed)
            totalSteps += r[0]; completed += r[1]; violationsSeen += r[2]; failedTasks += r[3]; exhaustedRuns += r[4]; conflicts += r[5]
        }
        println("fuzz: 300 seeds, $totalSteps events, $completed tasks completed, $failedTasks failed, $violationsSeen gate-violation attempts rejected, $exhaustedRuns budget stops, $conflicts conflicts")
        assertTrue("fuzz must actually exercise completions ($completed)", completed > 300)
        assertTrue("fuzz must exercise hostile verdicts ($violationsSeen)", violationsSeen > 300)
        assertTrue("fuzz must exercise task failures ($failedTasks)", failedTasks > 50)
        assertTrue("fuzz must exercise budget exhaustion ($exhaustedRuns)", exhaustedRuns >= 5)
        assertTrue("fuzz must exercise conflicts ($conflicts)", conflicts > 50)
    }

    private fun runSeed(seed: Int): IntArray {
        val rnd = Random(seed.toLong())
        val cfg = SchedulerConfig(
            maxConcurrent = 1 + rnd.nextInt(3),
            retry = RetryPolicy(maxAttempts = 1 + rnd.nextInt(3), baseDelayMs = 5, maxDelayMs = 50, rateLimitDelayMs = 20),
            limits = if (rnd.nextInt(3) == 0) BudgetLimits(maxTokens = 3000L + rnd.nextInt(4000)) else BudgetLimits(),
            serializeUnclaimed = rnd.nextInt(4) == 0,
        )
        val sim = Sim(cfg)
        val total = 4 + rnd.nextInt(7)
        var added = 0; var steps = 0; var violationAttempts = 0
        val workers = listOf("a", "b", "c", "qa")
        fun addTask() {
            val i = added++
            val deps = (0 until i).filter { rnd.nextInt(4) == 0 }.take(2).map { "t$it" }
            val claims = (1..rnd.nextInt(3)).map { claimPool[rnd.nextInt(claimPool.size)] }.distinct()
            val ass = if (rnd.nextInt(5) == 0) workers.shuffled(rnd).take(2) else listOf(workers[rnd.nextInt(workers.size)])
            sim.add(task("t$i", deps, claims, ass, rnd.nextInt(3), rnd.nextInt(6) != 0))
        }
        while (steps < 600) {
            steps++
            val ctx = "seed=$seed step=$steps"
            if (added < total && rnd.nextInt(4) == 0) { guarded(ctx) { addTask() }; checkInvariants(sim, ctx); continue }
            val opts = ArrayList<() -> Unit>()
            for (n in sim.s.tasks.values) {
                val id = n.spec.id.value
                for ((ag, rs) in n.runs) {
                    val a = ag.value
                    when (rs) {
                        RunState.Starting -> { opts += { sim.started(id, a) }; opts += { sim.failed(id, a, FailureKind.entries[rnd.nextInt(5)], "start failed") } }
                        RunState.Running -> {
                            repeat(3) { opts += { sim.finished(id, a) } }
                            opts += { sim.failed(id, a, FailureKind.entries[rnd.nextInt(5)], "crash sk-abcdefghijklmnopqrstuvwxyz") }
                            opts += { sim.usage(a, rnd.nextInt(700).toLong(), rnd.nextInt(700).toLong(), if (rnd.nextBoolean()) rnd.nextDouble() else null, id) }
                            if (rnd.nextInt(6) == 0) opts += { sim.send(SchedulerEvent.PauseAgent(sim.t + 1, ag)) }
                            if (rnd.nextInt(10) == 0) opts += { sim.send(SchedulerEvent.StopAgent(sim.t + 1, ag)) }
                        }
                        RunState.Paused -> opts += { sim.send(SchedulerEvent.ResumeAgent(sim.t + 1, ag)) }
                        RunState.Stopping -> opts += { sim.failed(id, a, FailureKind.Cancelled, "stopped") }
                        else -> Unit
                    }
                }
                if (n.state == TaskState.UnderReview && n.gate == GateState.Required && !n.awaitingChoice) {
                    val reviewers = listOf<Triple<Reviewer, Boolean, VerdictChannel>>(
                        Triple(Reviewer.Agent(AgentId("r1")), true, VerdictChannel.ReviewerRun), Triple(Reviewer.Agent(AgentId("r2")), false, VerdictChannel.ReviewerRun),
                        Triple(Reviewer.User, true, VerdictChannel.UserUi), Triple(Reviewer.User, false, VerdictChannel.UserUi),
                    )
                    val hostile = listOf(
                        Triple(Reviewer.Agent(n.author ?: AgentId("a")), true, VerdictChannel.ReviewerRun), Triple(Reviewer.Agent(AgentId("r1")), true, VerdictChannel.AgentMessage),
                        Triple(Reviewer.User, true, VerdictChannel.AgentMessage), Triple(Reviewer.Agent(AgentId("noapprove")), true, VerdictChannel.ReviewerRun),
                        Triple(Reviewer.Agent(AgentId("ghost")), true, VerdictChannel.ReviewerRun), Triple(Reviewer.Agent(n.spec.assignees.last()), true, VerdictChannel.ReviewerRun),
                    )
                    repeat(2) { val v = reviewers[rnd.nextInt(reviewers.size)]; opts += { sim.verdict(id, v.first, v.second, v.third) } }
                    repeat(2) { val v = hostile[rnd.nextInt(hostile.size)]; opts += { violationAttempts++; sim.verdict(id, v.first, v.second, v.third) } }
                }
                if (n.awaitingChoice) { val fin = n.runs.filterValues { it == RunState.Finished }.keys.toList(); if (fin.isNotEmpty()) opts += { sim.chose(id, fin[rnd.nextInt(fin.size)].value) } }
                if (n.applyRequested) {
                    repeat(4) { opts += { sim.applied(id, ApplyOutcome.Applied) } }
                    opts += { sim.applied(id, ApplyOutcome.Conflicts(1 + rnd.nextInt(3))) }; opts += { sim.applied(id, ApplyOutcome.Escalated) }; opts += { sim.applied(id, ApplyOutcome.Rejected("bad")) }
                }
                if (n.state == TaskState.Blocked && n.block == BlockReason.AwaitingUser) opts += { sim.userDecision(id, UserAction.entries[rnd.nextInt(3)]) }
                if (n.state == TaskState.Failed && rnd.nextInt(4) == 0) opts += { sim.send(SchedulerEvent.RetryTask(sim.t + 1, n.spec.id)) }
                if (n.state == TaskState.UnderReview && n.gate == GateState.Required) opts += { violationAttempts++; sim.applied(id, ApplyOutcome.Applied) } // apply before the gate
            }
            opts += { sim.kick(sim.t + rnd.nextInt(80)) }
            if (sim.s.tasks.isNotEmpty() && rnd.nextInt(8) == 0) {
                val ids = sim.s.tasks.keys.toList(); val any = ids[rnd.nextInt(ids.size)].value; val ag = workers[rnd.nextInt(workers.size)]
                val bogus = listOf<() -> Unit>(
                    { sim.started(any, ag) }, { sim.finished(any, ag) }, { sim.failed(any, ag) }, { sim.applied(any, ApplyOutcome.Applied) },
                    { sim.chose(any, ag) }, { sim.approveBy(any, "r1") }, { sim.userDecision(any, UserAction.Approve) },
                    { sim.send(SchedulerEvent.SetMaxConcurrent(sim.t + 1, maxOf(sim.s.liveRuns(), 1) + rnd.nextInt(2))) },
                    { sim.send(SchedulerEvent.RaiseBudget(sim.t + 1, BudgetLimits(maxTokens = 20_000))) },
                    { sim.send(SchedulerEvent.Requeue(sim.t + 1, TaskId(any), "restart")) },
                )
                opts += bogus[rnd.nextInt(bogus.size)]
            }
            if (sim.s.tasks.size > 2 && rnd.nextInt(60) == 0) { val id = sim.s.tasks.keys.toList()[rnd.nextInt(sim.s.tasks.size)]; opts += { sim.cancel(id.value) } }
            guarded(ctx) { opts[rnd.nextInt(opts.size)]() }
            checkInvariants(sim, ctx)
        }
        // I7: replay reproduces exactly the same state and commands
        val replay = Scheduler.replay(sim.initial, sim.events)
        assertEquals("seed=$seed replay state", sim.s, replay.state); assertEquals("seed=$seed replay commands", sim.commands, replay.commands)
        // credentials never leak through failure text
        assertTrue("seed=$seed leaked a secret", sim.s.tasks.values.none { it.failure?.contains("sk-abcdefghijklmnop") == true })
        val completed = sim.s.tasks.values.count { it.state == TaskState.Completed }
        // count failure EVENTS (tasks may be retried later by the host, so the final state hides them)
        val failed = sim.commands.count { it is com.anvex.agents.core.events.SchedulerCommand.Notify && it.notice == com.anvex.agents.core.events.Notice.TaskFailed }
        val conflicts = sim.commands.count { it is com.anvex.agents.core.events.SchedulerCommand.Notify && it.notice == com.anvex.agents.core.events.Notice.ConflictNeedsUser }
        val exhausted = sim.commands.count { it is com.anvex.agents.core.events.SchedulerCommand.Notify && it.notice == com.anvex.agents.core.events.Notice.BudgetExhausted }
        return intArrayOf(sim.events.size, completed, violationAttempts, failed, exhausted, conflicts)
    }

    private fun guarded(ctx: String, block: () -> Unit) {
        try { block() } catch (e: AssertionError) { throw e } catch (e: Exception) { throw AssertionError("$ctx: scheduler threw ${e::class.simpleName}: ${e.message}", e) }
    }

    @Test fun threeHundredSeedsAreDeterministic() {
        val a = runSeed(42); val b = runSeed(42); assertTrue(a.contentEquals(b))
    }
}

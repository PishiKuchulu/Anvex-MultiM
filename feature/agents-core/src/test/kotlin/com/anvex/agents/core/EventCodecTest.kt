package com.anvex.agents.core

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.events.EventCodec
import com.anvex.agents.core.events.EventCodecException
import com.anvex.agents.core.events.EventLog
import com.anvex.agents.core.events.EventRecord
import com.anvex.agents.core.events.FailureKind
import com.anvex.agents.core.events.GuardedEventStore
import com.anvex.agents.core.events.InMemoryEventStore
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.events.VerdictChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EventCodecTest {
    private val T = TaskId("t1"); private val A = AgentId("a")
    private fun all(): List<SchedulerEvent> = listOf(
        SchedulerEvent.TaskAdded(1, task("t1", deps = emptyList(), claims = listOf("app/**", "x.kt"), assignees = listOf("a", "b"), priority = -3, review = false)),
        SchedulerEvent.TaskAdded(2, task("t2", deps = listOf("t1"))), SchedulerEvent.Kick(3), SchedulerEvent.AgentStarted(4, T, A), SchedulerEvent.AgentFinished(5, T, A),
        SchedulerEvent.AgentFailed(6, T, A, FailureKind.RateLimited, "boom \"quoted\" \n ünï 😀"), SchedulerEvent.UsageReported(7, T, A, 10, 20, 0.0000001), SchedulerEvent.UsageReported(8, null, A, 0, 0, null),
        SchedulerEvent.ReviewVerdict(9, T, Reviewer.User, false, VerdictChannel.UserUi), SchedulerEvent.ReviewVerdict(10, T, Reviewer.Agent(AgentId("r1")), true, VerdictChannel.ReviewerRun),
        SchedulerEvent.UserChoseResult(11, T, A), SchedulerEvent.ApplyResult(12, T, ApplyOutcome.Applied), SchedulerEvent.ApplyResult(13, T, ApplyOutcome.Conflicts(3)),
        SchedulerEvent.ApplyResult(14, T, ApplyOutcome.Escalated), SchedulerEvent.ApplyResult(15, T, ApplyOutcome.Rejected("why")), SchedulerEvent.UserDecision(16, T, UserAction.Rerun),
        SchedulerEvent.PauseAgent(17, A), SchedulerEvent.ResumeAgent(18, A), SchedulerEvent.StopAgent(19, A), SchedulerEvent.CancelTask(20, T), SchedulerEvent.CancelAll(21),
        SchedulerEvent.RetryTask(22, T), SchedulerEvent.Requeue(23, T, "restart"), SchedulerEvent.SetMaxConcurrent(24, 5),
        SchedulerEvent.RaiseBudget(25, BudgetLimits(1000, 2.5)), SchedulerEvent.RaiseBudget(26, BudgetLimits(null, null)),
    )

    @Test fun everyEventTypeRoundTripsExactly() { for (e in all()) assertEquals(e, EventCodec.decode(EventCodec.encode(e))) }

    @Test fun theFormatIsPinnedByGoldenStrings() {
        assertEquals("""{"t":"Kick","at":5}""", EventCodec.encode(SchedulerEvent.Kick(5)))
        assertEquals("""{"t":"AgentFailed","at":9,"task":"t1","agent":"a","kind":"Fatal","reason":"x"}""", EventCodec.encode(SchedulerEvent.AgentFailed(9, T, A, FailureKind.Fatal, "x")))
        assertEquals("""{"t":"UsageReported","at":1,"task":null,"agent":"a","in":2,"out":3,"cost":null}""", EventCodec.encode(SchedulerEvent.UsageReported(1, null, A, 2, 3, null)))
        assertEquals("""{"t":"ReviewVerdict","at":2,"task":"t1","reviewer":{"type":"agent","id":"r1"},"approved":true,"channel":"ReviewerRun"}""",
            EventCodec.encode(SchedulerEvent.ReviewVerdict(2, T, Reviewer.Agent(AgentId("r1")), true, VerdictChannel.ReviewerRun)))
    }

    @Test fun theHashChainDependsOnTheCanonicalFormNotOnToString() {
        val a = EventLog.empty().append(SchedulerEvent.Kick(1)).append(SchedulerEvent.AgentStarted(2, T, A))
        val b = EventLog.fromRecords(a.records.map { EventRecord(it.seq, EventCodec.decode(EventCodec.encode(it.event)), it.prevHash, it.hash) })
        assertTrue(b.verify()); assertEquals(a.headHash, b.headHash); assertEquals(64, a.headHash.length)
    }

    @Test fun decodingIsStrict() {
        val bad = listOf("", "[]", "{}", """{"t":"Nope","at":1}""", """{"t":"Kick"}""", """{"t":"Kick","at":"1"}""", """{"t":"Kick","at":1,"x":2}""", """{"t":"Kick","at":1.5}""",
            """{"t":"AgentStarted","at":1,"task":"","agent":"a"}""", """{"t":"AgentStarted","at":1,"task":"t","agent":5}""", """{"t":"AgentFailed","at":1,"task":"t","agent":"a","kind":"Weird","reason":"x"}""",
            """{"t":"ReviewVerdict","at":1,"task":"t","reviewer":{"type":"robot"},"approved":true,"channel":"UserUi"}""", """{"t":"ReviewVerdict","at":1,"task":"t","reviewer":{"type":"user","id":"x"},"approved":true,"channel":"UserUi"}""",
            """{"t":"ApplyResult","at":1,"task":"t","outcome":{"type":"conflicts"}}""", """{"t":"ApplyResult","at":1,"task":"t","outcome":{"type":"conflicts","count":-1}}""",
            """{"t":"TaskAdded","at":1,"spec":{"id":"t","title":" ","acceptance":"a","deps":[],"claims":[],"assignees":["a"],"priority":0,"requiresReview":true}}""",
            """{"t":"TaskAdded","at":1,"spec":{"id":"t","title":"x","acceptance":"a","deps":[],"claims":["../x"],"assignees":["a"],"priority":0,"requiresReview":true}}""",
            """{"t":"SetMaxConcurrent","at":1,"value":99999999999}""", """{"t":"RaiseBudget","at":1,"maxTokens":-5,"maxCostUsd":null}""", """{"t":"Kick","at":1,"at":2}""")
        for (b in bad) try { EventCodec.decode(b); throw AssertionError("accepted: $b") } catch (_: EventCodecException) { }
    }

    @Test fun randomEventsRoundTrip() {
        val rnd = Random(4); val evs = all()
        repeat(2000) {
            val base = evs[rnd.nextInt(evs.size)]
            val e = when (base) {
                is SchedulerEvent.AgentFailed -> base.copy(at = rnd.nextLong() and 0xFFFFFFFFFFFL, reason = String(CharArray(rnd.nextInt(20)) { (32 + rnd.nextInt(0x2000)).toChar().let { c -> if (Character.isSurrogate(c)) 'x' else c } }))
                is SchedulerEvent.UsageReported -> base.copy(inputTokens = rnd.nextInt(1_000_000).toLong(), costUsd = if (rnd.nextBoolean()) rnd.nextDouble() * 10 else null)
                else -> base
            }
            assertEquals(e, EventCodec.decode(EventCodec.encode(e)))
        }
    }

    @Test fun guardedStoreRefusesCredentialLikePayloadsAndPassesNormalOnes() {
        val inner = InMemoryEventStore(); val g = GuardedEventStore(inner)
        g.append("r", EventLog.empty().append(SchedulerEvent.AgentFailed(1, T, A, FailureKind.Error, "plain failure")).records.single())
        assertEquals(1, g.readAll("r").size)
        try { g.append("r", EventLog.empty().append(SchedulerEvent.AgentFailed(1, T, A, FailureKind.Error, "key sk-abcdefghijklmnopqrstuvwxyz0123")).records.single()); throw AssertionError("stored a secret") } catch (_: IllegalArgumentException) { }
        assertEquals(1, inner.readAll("r").size); assertFalse(inner.readAll("r").any { EventCodec.encode(it.event).contains("sk-abc") })
    }
}

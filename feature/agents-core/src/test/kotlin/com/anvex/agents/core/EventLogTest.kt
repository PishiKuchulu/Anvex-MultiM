package com.anvex.agents.core

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.events.EventLog
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.scheduler.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogTest {
    private fun sampleLog(): EventLog {
        var log = EventLog.empty()
        log = log.append(SchedulerEvent.Kick(1)); log = log.append(SchedulerEvent.AgentStarted(2, TaskId("t"), AgentId("a")))
        return log.append(SchedulerEvent.Kick(3))
    }

    @Test fun emptyLogVerifies() { assertTrue(EventLog.empty().verify()); assertEquals(EventLog.GENESIS, EventLog.empty().headHash) }

    @Test fun appendChainsHashes() {
        val log = sampleLog()
        assertEquals(3, log.size); assertTrue(log.verify())
        assertEquals(EventLog.GENESIS, log.records[0].prevHash); assertEquals(log.records[0].hash, log.records[1].prevHash)
        assertNotEquals(log.records[0].hash, log.records[1].hash); assertEquals(log.records[2].hash, log.headHash)
    }

    @Test fun sameEventsGiveSameHeadAndDifferentEventsDiffer() {
        assertEquals(sampleLog().headHash, sampleLog().headHash)
        assertNotEquals(sampleLog().headHash, sampleLog().append(SchedulerEvent.Kick(4)).headHash)
    }

    @Test fun editingARecordIsDetected() {
        val log = sampleLog()
        val tampered = log.records.toMutableList(); tampered[1] = tampered[1].copy(event = SchedulerEvent.Kick(999))
        assertFalse(EventLog.fromRecords(tampered).verify())
    }

    @Test fun removingARecordFromTheMiddleIsDetected() {
        val r = sampleLog().records
        assertFalse(EventLog.fromRecords(listOf(r[0], r[2])).verify())
        assertFalse("reordering", EventLog.fromRecords(listOf(r[1], r[0], r[2])).verify())
        assertFalse("wrong start", EventLog.fromRecords(listOf(r[1], r[2])).verify())
        assertTrue("a clean prefix is a valid log (truncation of the tail is a persistence concern)", EventLog.fromRecords(listOf(r[0], r[1])).verify())
    }

    /** Acceptance event_replay: replaying the log reproduces exactly the same state and commands after a "crash". */
    @Test fun replayReproducesStateAndCommands() {
        val sim = Sim()
        sim.add(task("t1", claims = listOf("app/**"))); sim.add(task("t2", assignees = listOf("b"), deps = listOf("t1"))); sim.add(task("t3", claims = listOf("app/x"), assignees = listOf("c")))
        sim.started("t1", "a"); sim.usage("a", 100, 50, 0.01, "t1"); sim.finished("t1", "a"); sim.approveBy("t1"); sim.applied("t1")
        sim.started("t2", "b"); sim.failed("t2", "b"); sim.kick(sim.t + 100_000)
        var log = EventLog.empty(); for (e in sim.events) log = log.append(e)
        assertTrue(log.verify())
        val replayed = Scheduler.replay(sim.initial, EventLog.fromRecords(log.records).events())
        assertEquals(sim.s, replayed.state); assertEquals(sim.commands, replayed.commands)
    }

    @Test fun replayIsDeterministicAcrossRuns() {
        fun run(): Pair<Any, Any> { val sim = Sim(); sim.add(task("a1")); sim.add(task("a2", assignees = listOf("b"))); sim.started("a1", "a"); sim.finished("a1", "a")
            val r = Scheduler.replay(sim.initial, sim.events); return r.state to r.commands }
        assertEquals(run(), run())
    }
}

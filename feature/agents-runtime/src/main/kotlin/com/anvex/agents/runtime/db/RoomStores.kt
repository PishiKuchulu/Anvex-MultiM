package com.anvex.agents.runtime.db

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.events.EventCodec
import com.anvex.agents.core.events.EventRecord
import com.anvex.agents.core.integrator.ApplyJournal
import com.anvex.agents.core.integrator.PendingApply
import com.anvex.agents.core.orchestrator.AuditPort
import com.anvex.agents.core.ports.ClockPort
import com.anvex.agents.core.ports.EventStorePort
import com.anvex.agents.core.ports.SnapshotId

/* UNVERIFIED (never compiled). Blocking Room calls: the orchestrator runs on an IO dispatcher, never on the main thread. */

/** Append-only event log. Wrap in GuardedEventStore so credential-like payloads are refused before they reach the database. */
class RoomEventStore(private val dao: EventDao) : EventStorePort {
    override fun append(runId: String, record: EventRecord) = dao.insert(EventEntity(runId = runId, seq = record.seq, json = EventCodec.encode(record.event), prevHash = record.prevHash, hash = record.hash))
    /** Decoding failures propagate: a log that cannot be decoded must not be replayed (the chain is verified by the caller). */
    override fun readAll(runId: String): List<EventRecord> = dao.all(runId).map { EventRecord(it.seq, EventCodec.decode(it.json), it.prevHash, it.hash) }
}

/** Write-ahead journal of applies; must be durable before the first workspace write (Room commits synchronously). */
class RoomApplyJournal(private val runId: String, private val dao: JournalDao) : ApplyJournal {
    override fun started(task: TaskId, rollback: SnapshotId) = dao.put(JournalEntity(task.value, runId, rollback.value))
    override fun finished(task: TaskId) = dao.remove(runId, task.value)
    override fun pending(): List<PendingApply> = dao.pending(runId).map { PendingApply(TaskId(it.taskId), SnapshotId(it.rollbackSnapshot)) }
}

class RoomAuditPort(private val runId: String, private val dao: AuditDao, private val clock: ClockPort) : AuditPort {
    override fun record(kind: String, task: TaskId?, agent: AgentId?, detail: String) =
        dao.insert(AuditEntity(runId = runId, ts = clock.nowMillis(), kind = kind.take(60), taskId = task?.value, agentId = agent?.value, detail = com.anvex.agents.core.domain.CredentialGuard.scrub(detail).take(300)))
}

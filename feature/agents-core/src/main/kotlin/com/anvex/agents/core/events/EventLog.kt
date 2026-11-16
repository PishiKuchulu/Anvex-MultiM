package com.anvex.agents.core.events

import java.security.MessageDigest

data class EventRecord(val seq: Long, val event: SchedulerEvent, val prevHash: String, val hash: String)

/**
 * Append-only, hash-chained event log (ARC-CORE-1 / ARC-SEC-3). The scheduler is pure, so
 * replaying the events reproduces the exact state (acceptance event_replay). The chain detects
 * truncation in the middle and edits; persistence stores records through EventStorePort.
 */
class EventLog private constructor(val records: List<EventRecord>) {
    val size: Int get() = records.size
    val headHash: String get() = records.lastOrNull()?.hash ?: GENESIS

    fun append(event: SchedulerEvent): EventLog {
        val seq = records.size.toLong()
        val h = hashOf(seq, headHash, event)
        return EventLog(records + EventRecord(seq, event, headHash, h))
    }

    /** True when sequence numbers are contiguous and every hash matches its predecessor and content. */
    fun verify(): Boolean {
        var prev = GENESIS
        records.forEachIndexed { i, r ->
            if (r.seq != i.toLong() || r.prevHash != prev || r.hash != hashOf(r.seq, prev, r.event)) return false
            prev = r.hash
        }
        return true
    }

    fun events(): List<SchedulerEvent> = records.map { it.event }

    companion object {
        val GENESIS: String = "0".repeat(64)
        fun empty() = EventLog(emptyList())
        /** Rebuilds a log from stored records; callers must [verify] before trusting it. */
        fun fromRecords(records: List<EventRecord>) = EventLog(records)

        internal fun hashOf(seq: Long, prev: String, event: SchedulerEvent): String {
            // Canonical JSON (EventCodec), not toString: the chain must survive compiler/library changes and match what is stored.
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest("$seq|$prev|${EventCodec.encode(event)}".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

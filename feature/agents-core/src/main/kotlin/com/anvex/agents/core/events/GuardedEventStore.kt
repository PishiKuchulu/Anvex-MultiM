package com.anvex.agents.core.events

import com.anvex.agents.core.domain.CredentialGuard
import com.anvex.agents.core.ports.EventStorePort

/** Refuses to persist any event whose JSON contains a credential-like value (acceptance creds_absent). */
class GuardedEventStore(private val delegate: EventStorePort) : EventStorePort {
    override fun append(runId: String, record: EventRecord) {
        require(!CredentialGuard.looksLikeSecretValue(EventCodec.encode(record.event))) { "event payload contains a credential-like value" }
        delegate.append(runId, record)
    }
    override fun readAll(runId: String): List<EventRecord> = delegate.readAll(runId)
}

class InMemoryEventStore : EventStorePort {
    private val map = HashMap<String, MutableList<EventRecord>>()
    override fun append(runId: String, record: EventRecord) { map.getOrPut(runId) { ArrayList() }.add(record) }
    override fun readAll(runId: String): List<EventRecord> = map[runId]?.toList() ?: emptyList()
}

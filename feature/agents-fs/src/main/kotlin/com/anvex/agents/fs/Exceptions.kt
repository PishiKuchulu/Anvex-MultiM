package com.anvex.agents.fs

import com.anvex.agents.core.ports.SnapshotId

/** A stored blob or manifest failed hash verification: it was corrupted or tampered with. */
class CorruptBlobException(val what: String, message: String) : RuntimeException(message)
class SnapshotNotFoundException(val id: SnapshotId) : RuntimeException("snapshot $id not found")
/** The workspace holds something a snapshot cannot represent faithfully (non-canonical name, oversized file). Fail closed. */
class UnsupportedEntryException(message: String) : RuntimeException(message)

package com.anvex.agents.core.ports

import com.anvex.agents.core.path.RelPath
import java.security.MessageDigest

@JvmInline value class SnapshotId(val value: String) { init { require(value.isNotBlank()) { "blank SnapshotId" } }; override fun toString() = value }
@JvmInline value class ContentDigest(val hex: String) { init { require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "digest must be 64 lowercase hex chars" } }; override fun toString() = hex }

data class EntryInfo(val path: RelPath, val size: Long)

object Digests {
    fun sha256(bytes: ByteArray): ContentDigest {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return ContentDigest(d.joinToString("") { "%02x".format(it) })
    }
}

/**
 * Port to one workspace (ADR-002/003/004). Mechanical operations only: no authorization here
 * (ADR-007). No absolute paths, no File, no Uri (architecture guard). The only implementation used by
 * the engine is RealWorkspaceFileSystem (agents-fs), mounted on a real-path ProjectFileSystem (ADR-001).
 */
interface WorkspaceFileSystem {
    val workspaceId: String
    fun exists(path: RelPath): Boolean
    /** Null when the file does not exist. Throws on a directory or on I/O failure. */
    fun readBytes(path: RelPath): ByteArray?
    /** Atomic publish: readers never observe a partial file. Creates parent directories. */
    fun writeBytes(path: RelPath, bytes: ByteArray)
    /** Deletes a single file; false when absent. Never deletes directories. */
    fun delete(path: RelPath): Boolean
    /** Direct children of [dir] (null = root), sorted by path; directories have size -1. */
    fun list(dir: RelPath?): List<EntryInfo>
    /** All regular files, sorted by path; symlinks are never reported. */
    fun walk(): List<EntryInfo>
    fun digest(path: RelPath): ContentDigest?
    fun createSnapshot(): SnapshotId
    /** Makes the workspace content equal to the snapshot; verifies every blob hash while reading. */
    fun restoreSnapshot(id: SnapshotId)
}

interface WorkspaceProvider {
    /** The project's integration workspace (the only one the Integrator writes to). */
    fun open(projectId: String): WorkspaceFileSystem
    /** Materializes snapshot [base] of [source] into a new isolated workspace. */
    fun fork(source: WorkspaceFileSystem, base: SnapshotId, label: String): WorkspaceFileSystem
    /** Deletes the workspace and releases its snapshot references. */
    fun dispose(ws: WorkspaceFileSystem)
}

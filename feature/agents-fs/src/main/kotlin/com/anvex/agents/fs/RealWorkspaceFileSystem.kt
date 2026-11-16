package com.anvex.agents.fs

import com.anvex.agents.core.changeset.IgnoreRules
import com.anvex.agents.core.changeset.Limits
import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.changeset.ManifestEntry
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.ContentDigest
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.EntryInfo
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem
import com.anvex.core.engine.ProjectFileSystem

/**
 * WorkspaceFileSystem mounted on the host's real-path ProjectFileSystem (ADR-002: delegation, not a parallel
 * implementation). Path confinement, symlink safety and atomic publishing are the delegate's job and are not
 * duplicated here; this class adds RelPath typing, content digests, CAS snapshots and restore. It performs no
 * authorization (ADR-007).
 */
class RealWorkspaceFileSystem(
    override val workspaceId: String,
    private val delegate: ProjectFileSystem,
    private val cas: CasStore,
    private val ignore: IgnoreRules = IgnoreRules.DEFAULT,
    private val maxSnapshotFileBytes: Long = Limits.MAX_SNAPSHOT_FILE_BYTES,
) : WorkspaceFileSystem {

    init { require(CasStore.isValidOwner(workspaceId)) { "invalid workspace id" } }

    /**
     * The mount point (ADR-002): the adapter layer hands this to the Anvex ActionDispatcher so an agent edits
     * this workspace through the host's hardened implementation. Not part of the WorkspaceFileSystem port.
     */
    fun projectFileSystem(): ProjectFileSystem = delegate

    override fun exists(path: RelPath): Boolean = delegate.exists(path.value)

    override fun readBytes(path: RelPath): ByteArray? {
        require(!delegate.isDirectory(path.value)) { "is a directory: ${describe(path.value)}" }
        return delegate.readFileBytes(path.value)
    }

    override fun writeBytes(path: RelPath, bytes: ByteArray) {
        require(!delegate.isDirectory(path.value)) { "is a directory: ${describe(path.value)}" }
        delegate.writeFileBytes(path.value, bytes)
    }

    override fun delete(path: RelPath): Boolean {
        if (!delegate.exists(path.value) || delegate.isDirectory(path.value)) return false
        return delegate.delete(path.value, false)
    }

    override fun list(dir: RelPath?): List<EntryInfo> {
        val d = dir ?: RelPath.ROOT
        return delegate.list(d.value).mapNotNull { e ->
            val child = RelPath.parse(if (d.isRoot) e.name else "${d.value}/${e.name}")
            if (child == null) { if (ignoredNonCanonical(if (d.isRoot) e.name else "${d.value}/${e.name}")) null else throw UnsupportedEntryException("workspace contains a non-canonical name: ${describe(e.name)}") }
            else EntryInfo(child, if (e.isDirectory) -1 else e.size)
        }.sortedBy { it.path }
    }

    override fun walk(): List<EntryInfo> {
        val out = ArrayList<EntryInfo>()
        for ((raw, size) in delegate.walkWithSizes()) {
            val p = RelPath.parse(raw)
            if (p != null) out += EntryInfo(p, size ?: -1)
            else if (!ignoredNonCanonical(raw)) throw UnsupportedEntryException("workspace contains a non-canonical path: ${describe(raw)}")
        }
        return out.sortedBy { it.path }
    }

    override fun digest(path: RelPath): ContentDigest? = readBytes(path)?.let { Digests.sha256(it) }

    private fun tracked(): List<EntryInfo> = walk().filter { !ignore.isIgnored(it.path) }

    /** Content of the tracked files (ignored areas excluded) stored in the CAS; the workspace should be quiescent. */
    override fun createSnapshot(): SnapshotId = cas.locked {
        val entries = HashMap<RelPath, ManifestEntry>()
        for (e in tracked()) {
            if (e.size > maxSnapshotFileBytes) throw UnsupportedEntryException("file exceeds the snapshot size limit: ${describe(e.path.value)}")
            val bytes = delegate.readFileBytes(e.path.value) ?: throw UnsupportedEntryException("file disappeared during snapshot: ${describe(e.path.value)}")
            if (bytes.size > maxSnapshotFileBytes) throw UnsupportedEntryException("file exceeds the snapshot size limit: ${describe(e.path.value)}")
            entries[e.path] = ManifestEntry(cas.putBlob(bytes), bytes.size.toLong())
        }
        val id = cas.putManifest(Manifest(entries))
        cas.retain(workspaceId, id)
        id
    }

    /**
     * Two passes: first every referenced blob is read and verified (a corrupt or missing blob aborts BEFORE
     * anything is modified), then files are deleted/written. Ignored areas are left untouched.
     */
    override fun restoreSnapshot(id: SnapshotId) = cas.locked {
        val m = cas.manifest(id) ?: throw SnapshotNotFoundException(id)
        for ((p, e) in m.entries) {
            val b = cas.get(e.digest) ?: throw CorruptBlobException(e.digest.hex, "blob ${e.digest.hex} for ${describe(p.value)} is missing")
            if (b.size.toLong() != e.size) throw CorruptBlobException(e.digest.hex, "blob ${e.digest.hex} has an unexpected size")
        }
        val current = tracked().associate { it.path to it.size }
        for ((p, _) in current) if (!m.entries.containsKey(p)) delete(p)
        for ((p, e) in m.entries) {
            if (delegate.isDirectory(p.value)) delegate.delete(p.value, true)        // a directory where the snapshot has a file
            if (current[p] == e.size && digest(p) == e.digest) continue
            delegate.writeFileBytes(p.value, cas.get(e.digest)!!)
        }
    }

    /** Names that cannot be RelPaths are tolerated only inside ignored areas (e.g. build output); elsewhere we fail closed. */
    private fun ignoredNonCanonical(raw: String): Boolean {
        val segs = raw.split('/')
        var prefix: RelPath? = RelPath.ROOT
        for (s in segs.dropLast(1)) {
            val next = prefix?.let { p -> RelPath.parse(if (p.isRoot) s else "${p.value}/$s") }
            if (next == null) break
            prefix = next
        }
        val dir = prefix ?: return false
        return ignore.isIgnored(dir.resolve("x")) || (dir.isRoot.not() && ignore.isIgnored(dir))
    }

    private fun describe(raw: String): String = raw.map { if (it.code < 0x20 || it.code == 0x7F) '?' else it }.joinToString("").take(120)
}

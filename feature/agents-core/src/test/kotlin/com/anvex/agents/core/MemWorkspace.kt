package com.anvex.agents.core

import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.changeset.ManifestEntry
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.BlobSource
import com.anvex.agents.core.ports.ContentDigest
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.EntryInfo
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem
import com.anvex.agents.core.orchestrator.SnapshotCatalog

/** A process dying in the middle of an operation: an Error, so nothing in the engine catches it (unlike Exception). */
class SimulatedCrash : Error("simulated process death")

/** Snapshot storage shared by all in-memory workspaces (plays the role of the CAS). */
class MemStore {
    val snapshots = HashMap<SnapshotId, Map<RelPath, ByteArray>>()
    fun manifest(id: SnapshotId): Manifest? = snapshots[id]?.let { m -> Manifest(m.mapValues { ManifestEntry(Digests.sha256(it.value), it.value.size.toLong()) }) }
    val blobs = object : BlobSource {
        override fun get(digest: ContentDigest): ByteArray? = snapshots.values.firstNotNullOfOrNull { snap -> snap.values.firstOrNull { Digests.sha256(it) == digest } }?.copyOf()
    }
}

/** In-memory workspace with fault injection, used to prove Integrator/Orchestrator logic without a real file system. */
class MemWorkspace(override val workspaceId: String = "mem", val store: MemStore = MemStore()) : WorkspaceFileSystem {
    val files = java.util.TreeMap<RelPath, ByteArray>()
    /** Throw an Exception on the n-th mutating call (1-based) when > 0. */
    var failOnMutation = 0
    /** Throw [SimulatedCrash] (an Error) on the n-th mutating call when > 0. */
    var crashOnMutation = 0
    var failOnRestore = false
    /** When set, writes silently store this instead of the requested bytes (simulates a lying disk). */
    var corruptWrites = false
    var mutations = 0

    private fun mutate() {
        mutations++
        if (crashOnMutation > 0 && mutations == crashOnMutation) throw SimulatedCrash()
        if (failOnMutation > 0 && mutations == failOnMutation) throw IllegalStateException("injected failure at mutation $mutations")
    }

    fun put(path: String, text: String) { files[RelPath.of(path)] = text.toByteArray() }
    fun put(path: String, bytes: ByteArray) { files[RelPath.of(path)] = bytes }
    fun text(path: String): String? = files[RelPath.of(path)]?.toString(Charsets.UTF_8)
    fun contentMap(): Map<String, String> = files.entries.associate { it.key.value to Digests.sha256(it.value).hex }

    override fun exists(path: RelPath) = files.containsKey(path) || files.keys.any { it.startsWith(path) && it != path }
    override fun readBytes(path: RelPath): ByteArray? = files[path]?.copyOf()
    override fun writeBytes(path: RelPath, bytes: ByteArray) { mutate(); files[path] = if (corruptWrites) bytes + byteArrayOf(1) else bytes.copyOf() }
    override fun delete(path: RelPath): Boolean { mutate(); return files.remove(path) != null }
    override fun list(dir: RelPath?): List<EntryInfo> {
        val d = dir ?: RelPath.ROOT
        val out = java.util.TreeMap<RelPath, EntryInfo>()
        for ((p, b) in files) {
            if (!p.startsWith(d) || p == d) continue
            val rest = if (d.isRoot) p.segments else p.segments.drop(d.segments.size)
            val childPath = d.resolve(rest.first())
            out[childPath] = EntryInfo(childPath, if (rest.size == 1) b.size.toLong() else -1)
        }
        return out.values.toList()
    }
    override fun walk(): List<EntryInfo> = files.map { EntryInfo(it.key, it.value.size.toLong()) }
    override fun digest(path: RelPath): ContentDigest? = files[path]?.let { Digests.sha256(it) }
    override fun createSnapshot(): SnapshotId {
        val id = Manifest(files.mapValues { ManifestEntry(Digests.sha256(it.value), it.value.size.toLong()) }).snapshotId()
        store.snapshots[id] = files.mapValues { it.value.copyOf() }
        return id
    }
    override fun restoreSnapshot(id: SnapshotId) {
        if (failOnRestore) throw IllegalStateException("injected restore failure")
        val s = store.snapshots[id] ?: throw IllegalArgumentException("unknown snapshot")
        files.clear(); s.forEach { (k, v) -> files[k] = v.copyOf() }
    }
}

class MemBlobs : BlobSource {
    private val map = HashMap<ContentDigest, ByteArray>()
    fun add(bytes: ByteArray): ContentDigest = Digests.sha256(bytes).also { map[it] = bytes }
    override fun get(digest: ContentDigest): ByteArray? = map[digest]?.copyOf()
}

/** A base (fork-time) state: workspace content plus its manifest and blob source. */
class Base(vararg files: Pair<String, String>) {
    val blobs = MemBlobs()
    val manifest: Manifest = Manifest(files.associate { (p, t) -> RelPath.of(p) to ManifestEntry(blobs.add(t.toByteArray()), t.toByteArray().size.toLong()) })
    val files = files.toMap()
    fun workspace(): MemWorkspace = MemWorkspace().also { ws -> files.forEach { (p, t) -> ws.put(p, t) } }
}

class MemProvider(val store: MemStore) : com.anvex.agents.core.ports.WorkspaceProvider, SnapshotCatalog {
    val disposed = ArrayList<String>()
    val live = LinkedHashMap<String, MemWorkspace>()
    private var n = 0
    override fun open(projectId: String): WorkspaceFileSystem = throw UnsupportedOperationException()
    override fun fork(source: WorkspaceFileSystem, base: SnapshotId, label: String): WorkspaceFileSystem {
        val ws = MemWorkspace("fork-${++n}-$label", store); ws.restoreSnapshot(base); live[ws.workspaceId] = ws; return ws
    }
    override fun dispose(ws: WorkspaceFileSystem) { disposed += ws.workspaceId; live.remove(ws.workspaceId) }
    override fun manifest(id: SnapshotId): Manifest? = store.manifest(id)
}

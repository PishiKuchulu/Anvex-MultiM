package com.anvex.agents.fs

import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.ports.BlobSource
import com.anvex.agents.core.ports.ContentDigest
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.core.engine.ProjectFileSystem
import java.util.concurrent.locks.ReentrantLock

data class GcReport(val blobsDeleted: Int, val manifestsDeleted: Int, val blobsKept: Int)

/**
 * Content-addressed snapshot store (ADR-005) over a ProjectFileSystem rooted at the agents' CAS directory.
 * Layout: blobs/<aa>/<sha256>, manifests/<snapshotId>, refs/<snapshotId>/<owner>.
 * Every blob and manifest is hash-verified when read. Mutations are serialized by one lock; callers that
 * create a snapshot hold it for the whole operation (see [locked]) so garbage collection can never delete
 * blobs of a snapshot that is still being built.
 */
class CasStore(private val fs: ProjectFileSystem) : BlobSource {
    private val lock = ReentrantLock()
    fun <T> locked(block: () -> T): T { lock.lock(); try { return block() } finally { lock.unlock() } }

    private fun blobPath(d: ContentDigest) = "blobs/${d.hex.substring(0, 2)}/${d.hex}"
    private fun checkedId(id: SnapshotId): String { require(HEX64.matches(id.value)) { "malformed snapshot id" }; return id.value }
    private fun manifestPath(id: SnapshotId) = "manifests/${checkedId(id)}"
    private fun checkedOwner(owner: String): String { require(OWNER.matches(owner)) { "malformed owner" }; return owner }

    fun hasBlob(d: ContentDigest): Boolean = locked { fs.exists(blobPath(d)) }

    /** Stores [bytes] once; an existing blob with different content (corruption) is repaired. */
    fun putBlob(bytes: ByteArray): ContentDigest = locked {
        val d = Digests.sha256(bytes); val path = blobPath(d)
        if (fs.exists(path)) { val cur = fs.readFileBytes(path); if (cur != null && cur.contentEquals(bytes)) return@locked d }
        fs.writeFileBytes(path, bytes); d
    }

    /** Verified read: null when absent, [CorruptBlobException] when the stored bytes do not match their digest. */
    override fun get(digest: ContentDigest): ByteArray? = locked {
        val b = fs.readFileBytes(blobPath(digest)) ?: return@locked null
        if (Digests.sha256(b) != digest) throw CorruptBlobException(digest.hex, "blob ${digest.hex} failed verification")
        b
    }

    fun putManifest(m: Manifest): SnapshotId = locked {
        val id = m.snapshotId(); val path = manifestPath(id); val bytes = m.canonicalBytes()
        val cur = if (fs.exists(path)) fs.readFileBytes(path) else null
        if (cur == null || !cur.contentEquals(bytes)) fs.writeFileBytes(path, bytes)
        id
    }

    /** Null when unknown; [CorruptBlobException] when the stored manifest does not hash to its id or is malformed. */
    fun manifest(id: SnapshotId): Manifest? = locked {
        val bytes = fs.readFileBytes(manifestPath(id)) ?: return@locked null
        if (Digests.sha256(bytes).hex != id.value) throw CorruptBlobException(id.value, "manifest ${id.value} failed verification")
        try { Manifest.parse(bytes) } catch (e: com.anvex.agents.core.changeset.ManifestFormatException) { throw CorruptBlobException(id.value, "manifest ${id.value} is malformed") }
    }

    fun retain(owner: String, id: SnapshotId) = locked {
        checkedOwner(owner)
        if (manifest(id) == null) throw SnapshotNotFoundException(id)
        fs.writeFileBytes("refs/${checkedId(id)}/$owner", ByteArray(0))
    }

    fun release(owner: String, id: SnapshotId) = locked { fs.delete("refs/${checkedId(id)}/${checkedOwner(owner)}", false); Unit }

    fun releaseAll(owner: String) = locked {
        checkedOwner(owner)
        for (p in fs.walk()) if (p.startsWith("refs/") && p.endsWith("/$owner")) fs.delete(p, false)
    }

    fun retainedSnapshots(): List<SnapshotId> = locked {
        fs.walk().filter { it.startsWith("refs/") && it.split('/').size == 3 }.map { SnapshotId(it.split('/')[1]) }.distinct().sortedBy { it.value }
    }

    /**
     * Deletes manifests without owners and blobs not referenced by any retained manifest. Refuses to run
     * (throws) if a retained manifest cannot be read, because the live set would then be unknown.
     */
    fun gc(): GcReport = locked {
        val all = fs.walk()
        val retained = all.filter { it.startsWith("refs/") && it.split('/').size == 3 }.map { it.split('/')[1] }.toSet()
        val live = HashSet<String>()
        for (id in retained) {
            val m = try { manifest(SnapshotId(id)) } catch (e: CorruptBlobException) { throw IllegalStateException("cannot collect garbage: retained manifest $id is corrupt") }
                ?: throw IllegalStateException("cannot collect garbage: retained manifest $id is missing")
            m.entries.values.forEach { live += it.digest.hex }
        }
        var mDel = 0
        for (p in all) if (p.startsWith("manifests/") && p.removePrefix("manifests/") !in retained) { fs.delete(p, false); mDel++ }
        var bDel = 0; var kept = 0
        for (p in all) if (p.startsWith("blobs/")) { if (p.substringAfterLast('/') in live) kept++ else { fs.delete(p, false); bDel++ } }
        GcReport(bDel, mDel, kept)
    }

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val OWNER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        fun isValidOwner(s: String) = OWNER.matches(s)
    }
}

package com.anvex.agents.core.changeset

import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.ContentDigest
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.SnapshotId

data class ManifestEntry(val digest: ContentDigest, val size: Long)

class ManifestFormatException(message: String) : RuntimeException(message)

/**
 * Sorted path -> (digest, size) listing. SnapshotId is the SHA-256 of the canonical bytes, so equal
 * content always yields the same id and a changed manifest can never keep its id (ADR-005).
 * Not captured by design: empty directories, symlinks, mode bits.
 */
class Manifest(entries: Map<RelPath, ManifestEntry>) {
    val entries: Map<RelPath, ManifestEntry> = java.util.TreeMap(entries)

    fun canonicalBytes(): ByteArray {
        val sb = StringBuilder(HEADER).append('\n')
        for ((p, e) in this.entries) sb.append(e.digest.hex).append(' ').append(e.size).append(' ').append(p.value).append('\n')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun snapshotId(): SnapshotId = SnapshotId(Digests.sha256(canonicalBytes()).hex)

    override fun equals(other: Any?): Boolean = other is Manifest && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()

    companion object {
        const val HEADER = "ANVEX-CAS-MANIFEST 1"
        val EMPTY = Manifest(emptyMap())

        /** Strict parser: any deviation from the canonical form is an error (tamper evidence). */
        fun parse(bytes: ByteArray): Manifest {
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.split('\n')
            if (lines.size < 2 || lines[0] != HEADER || lines.last() != "") throw ManifestFormatException("bad header or missing final newline")
            val map = java.util.TreeMap<RelPath, ManifestEntry>()
            for (line in lines.subList(1, lines.size - 1)) {
                val i1 = line.indexOf(' '); val i2 = if (i1 < 0) -1 else line.indexOf(' ', i1 + 1)
                if (i1 != 64 || i2 < 0) throw ManifestFormatException("bad entry")
                val digest = try { ContentDigest(line.substring(0, i1)) } catch (e: IllegalArgumentException) { throw ManifestFormatException("bad digest") }
                val size = line.substring(i1 + 1, i2).toLongOrNull()?.takeIf { it >= 0 } ?: throw ManifestFormatException("bad size")
                val path = RelPath.parse(line.substring(i2 + 1)) ?: throw ManifestFormatException("bad path")
                if (map.put(path, ManifestEntry(digest, size)) != null) throw ManifestFormatException("duplicate path")
            }
            val m = Manifest(map)
            if (!m.canonicalBytes().contentEquals(bytes)) throw ManifestFormatException("not in canonical order or form")
            return m
        }
    }
}

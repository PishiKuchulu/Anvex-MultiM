package com.anvex.agents.core.changeset

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.ContentDigest
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.SensitivePathPort
import com.anvex.agents.core.ports.SensitiveVerdict
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem

/** One file-level change relative to the base snapshot. The byte arrays are agent output: untrusted. */
sealed interface FileChange {
    val path: RelPath
    class Add(override val path: RelPath, val bytes: ByteArray) : FileChange
    class Modify(override val path: RelPath, val baseDigest: ContentDigest, val bytes: ByteArray) : FileChange
    class Delete(override val path: RelPath, val baseDigest: ContentDigest) : FileChange
}

class ChangeSet(val task: TaskId, val agent: AgentId, val base: SnapshotId, val changes: List<FileChange>) {
    val totalBytes: Long get() = changes.sumOf { (it as? FileChange.Add)?.bytes?.size?.toLong() ?: (it as? FileChange.Modify)?.bytes?.size?.toLong() ?: 0L }
}

/** Builds a ChangeSet by comparing an agent's fork with the manifest of the snapshot it was forked from. */
object ChangeSetDeriver {
    fun derive(base: Manifest, ws: WorkspaceFileSystem, ignore: IgnoreRules): List<FileChange> {
        val out = ArrayList<FileChange>()
        val seen = HashSet<RelPath>()
        for (e in ws.walk()) {
            if (ignore.isIgnored(e.path)) continue
            seen += e.path
            val b = base.entries[e.path]
            if (b == null) out += FileChange.Add(e.path, requireNotNull(ws.readBytes(e.path)) { "file vanished: ${e.path}" })
            else if (e.size != b.size || ws.digest(e.path) != b.digest) out += FileChange.Modify(e.path, b.digest, requireNotNull(ws.readBytes(e.path)) { "file vanished: ${e.path}" })
        }
        for ((p, b) in base.entries) if (p !in seen && !ignore.isIgnored(p)) out += FileChange.Delete(p, b.digest)
        return out.sortedBy { it.path }
    }
}

/**
 * Structural validation of untrusted agent output (ADR-008). Returns every problem found; an empty list
 * means the ChangeSet is well-formed. Authorization is NOT decided here (PolicyEngine does that, ADR-007);
 * sensitive paths are rejected here as defense in depth.
 */
class ChangeSetValidator(private val sensitive: SensitivePathPort, private val ignore: IgnoreRules = IgnoreRules.DEFAULT) {

    fun validate(cs: ChangeSet, base: Manifest): List<String> {
        val problems = ArrayList<String>()
        if (cs.changes.isEmpty()) problems += "empty change set"
        if (cs.changes.size > Limits.MAX_CHANGES) problems += "too many changes (${cs.changes.size} > ${Limits.MAX_CHANGES})"
        if (cs.base != base.snapshotId()) problems += "change set base does not match the supplied manifest"
        var total = 0L
        val paths = HashSet<RelPath>()
        for (c in cs.changes) {
            val p = c.path
            if (!paths.add(p)) problems += "duplicate path $p"
            if (ignore.isIgnored(p)) problems += "path $p is in an ignored area"
            val sv = try { sensitive.classify(p) } catch (e: Exception) { SensitiveVerdict.Unknown }
            if (sv != SensitiveVerdict.NotSensitive) problems += "path $p is sensitive or could not be classified"
            val be = base.entries[p]
            when (c) {
                is FileChange.Add -> {
                    if (be != null) problems += "add of $p but it exists in the base"
                    if (c.bytes.size > Limits.MAX_FILE_BYTES) problems += "file $p exceeds ${Limits.MAX_FILE_BYTES} bytes"
                    total += c.bytes.size
                    if (ancestorIsFileInBase(p, base)) problems += "an ancestor of $p is a file in the base"
                }
                is FileChange.Modify -> {
                    if (be == null) problems += "modify of $p but it is not in the base"
                    else if (be.digest != c.baseDigest) problems += "modify of $p carries a base digest that does not match the base"
                    if (c.bytes.size > Limits.MAX_FILE_BYTES) problems += "file $p exceeds ${Limits.MAX_FILE_BYTES} bytes"
                    total += c.bytes.size
                }
                is FileChange.Delete -> {
                    if (be == null) problems += "delete of $p but it is not in the base"
                    else if (be.digest != c.baseDigest) problems += "delete of $p carries a base digest that does not match the base"
                }
            }
        }
        if (total > Limits.MAX_TOTAL_BYTES) problems += "total size $total exceeds ${Limits.MAX_TOTAL_BYTES} bytes"
        // a path cannot be both a file and a directory inside one change set
        val written = cs.changes.filter { it !is FileChange.Delete }.map { it.path }
        for (w in written) for (o in cs.changes) if (o.path != w && o.path.startsWith(w)) problems += "path ${o.path} lies below file $w in the same change set"
        return problems.distinct()
    }

    private fun ancestorIsFileInBase(p: RelPath, base: Manifest): Boolean {
        var a = p.parent
        while (a != null && !a.isRoot) { if (base.entries.containsKey(a)) return true; a = a.parent }
        return false
    }

    companion object {
        fun digestOf(bytes: ByteArray): ContentDigest = Digests.sha256(bytes)
    }
}

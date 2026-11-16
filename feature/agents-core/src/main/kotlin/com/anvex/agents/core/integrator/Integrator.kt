package com.anvex.agents.core.integrator

import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.changeset.ChangeSetValidator
import com.anvex.agents.core.changeset.FileChange
import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.events.ApplyOutcome
import com.anvex.agents.core.merge.ConflictKind
import com.anvex.agents.core.merge.MergeResult
import com.anvex.agents.core.merge.ThreeWayMerge
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.policy.Action
import com.anvex.agents.core.policy.Decision
import com.anvex.agents.core.policy.PolicyContext
import com.anvex.agents.core.policy.PolicyEngine
import com.anvex.agents.core.ports.BlobSource
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.SensitivePathPort
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem

data class FileConflict(val path: RelPath, val kind: ConflictKind, val detail: String)

sealed interface ApplyReport {
    /** [rollback] is a snapshot of the integration workspace taken immediately before the apply. */
    data class Applied(val rollback: SnapshotId, val written: Int, val deleted: Int, val merged: Int) : ApplyReport
    data class Conflicts(val items: List<FileConflict>) : ApplyReport
    /** Nothing was written; the user must approve these paths (policy ESCALATE). */
    data class Escalated(val paths: List<RelPath>) : ApplyReport
    /** Validation failure, policy DENY/DEFER, missing evidence, or an apply failure that was rolled back. */
    data class Rejected(val reasons: List<String>, val rollbackFailed: Boolean = false) : ApplyReport

    fun toOutcome(): ApplyOutcome = when (this) {
        is Applied -> ApplyOutcome.Applied
        is Conflicts -> ApplyOutcome.Conflicts(items.size)
        is Escalated -> ApplyOutcome.Escalated
        is Rejected -> ApplyOutcome.Rejected(reasons.joinToString("; ").take(400))
    }
}

/**
 * The ONLY component that writes to the integration workspace (ADR-007, architecture rule 4).
 * Contract: callers serialize calls per workspace. Order of work: validate -> policy -> merge plan ->
 * (conflicts or escalation: write nothing) -> rollback snapshot -> write -> read-back verification;
 * any failure restores the rollback snapshot.
 */
class Integrator(
    private val policy: PolicyEngine,
    private val sensitive: SensitivePathPort,
    private val blobs: BlobSource,
    private val journal: ApplyJournal? = null,
) {
    private val validator = ChangeSetValidator(sensitive)

    private sealed interface Planned {
        val path: RelPath
        class Write(override val path: RelPath, val bytes: ByteArray, val merged: Boolean) : Planned
        class Remove(override val path: RelPath) : Planned
        class Keep(override val path: RelPath) : Planned
    }

    /** Structural validation only (used by the orchestrator to reject unusable output before review). */
    fun precheck(cs: ChangeSet, base: Manifest): List<String> = validator.validate(cs, base)

    fun apply(ws: WorkspaceFileSystem, cs: ChangeSet, base: Manifest, ctx: PolicyContext): ApplyReport {
        // 1) structure of untrusted output
        val problems = validator.validate(cs, base)
        if (problems.isNotEmpty()) return ApplyReport.Rejected(problems.map { "invalid change set: $it" })

        // 2) authorization (fail closed: DENY and DEFER reject, ESCALATE asks the user)
        val actions = cs.changes.map { if (it is FileChange.Delete) Action.Delete(it.path) else Action.Write(it.path) }
        val results = actions.map { policy.evaluate(ctx, it) }
        val denied = cs.changes.indices.filter { results[it].decision == Decision.DENY || results[it].decision == Decision.DEFER }
        if (denied.isNotEmpty()) return ApplyReport.Rejected(denied.map { i ->
            val r = results[i]; "${r.decision.name.lowercase()} ${cs.changes[i].path}: " + r.hits.filter { it.vote == r.decision }.joinToString(", ") { "${it.rule} (${it.reason})" }
        })
        val escalate = cs.changes.indices.filter { results[it].decision == Decision.ESCALATE }.map { cs.changes[it].path }
        if (escalate.isNotEmpty()) return ApplyReport.Escalated(escalate)

        // 3) plan every file without touching the workspace
        val plan = ArrayList<Planned>(); val conflicts = ArrayList<FileConflict>()
        for (c in cs.changes) {
            val ours = ws.readBytes(c.path)
            val baseBytes: ByteArray? = when (c) {
                is FileChange.Add -> null
                is FileChange.Modify -> blobs.get(c.baseDigest) ?: return ApplyReport.Rejected(listOf("base content for ${c.path} is unavailable"))
                is FileChange.Delete -> blobs.get(c.baseDigest) ?: return ApplyReport.Rejected(listOf("base content for ${c.path} is unavailable"))
            }
            val theirs: ByteArray? = when (c) { is FileChange.Add -> c.bytes; is FileChange.Modify -> c.bytes; is FileChange.Delete -> null }
            if (c !is FileChange.Delete) typeClash(ws, c.path)?.let { conflicts += FileConflict(c.path, ConflictKind.TYPE_CLASH, it); return@let }
            if (conflicts.any { it.path == c.path }) continue
            when (val r = ThreeWayMerge.merge(baseBytes, ours, theirs)) {
                is MergeResult.Conflict -> conflicts += FileConflict(c.path, r.kind, r.detail)
                is MergeResult.Clean -> {
                    val result = r.bytes
                    plan += when {
                        result == null -> if (ours == null) Planned.Keep(c.path) else Planned.Remove(c.path)
                        ours != null && ours.contentEquals(result) -> Planned.Keep(c.path)
                        else -> Planned.Write(c.path, result, merged = ours != null && baseBytes != null && !result.contentEquals(theirs ?: ByteArray(0)))
                    }
                }
            }
        }
        if (conflicts.isNotEmpty()) return ApplyReport.Conflicts(conflicts)

        // 4) apply with rollback
        val rollback = try { ws.createSnapshot() } catch (e: Exception) { return ApplyReport.Rejected(listOf("could not create rollback snapshot: ${e::class.simpleName}")) }
        try { journal?.started(cs.task, rollback) } catch (e: Exception) { return ApplyReport.Rejected(listOf("could not journal the apply: ${e::class.simpleName}")) }
        var written = 0; var deleted = 0; var merged = 0
        try {
            for (p in plan) when (p) {
                is Planned.Write -> { ws.writeBytes(p.path, p.bytes); written++; if (p.merged) merged++ }
                is Planned.Remove -> { ws.delete(p.path); deleted++ }
                is Planned.Keep -> Unit
            }
            for (p in plan) if (p is Planned.Write && ws.digest(p.path) != Digests.sha256(p.bytes)) throw IllegalStateException("read-back verification failed for ${p.path}")
            for (p in plan) if (p is Planned.Remove && ws.exists(p.path)) throw IllegalStateException("delete verification failed for ${p.path}")
        } catch (e: Exception) {
            return try {
                ws.restoreSnapshot(rollback)
                journal?.finished(cs.task)
                ApplyReport.Rejected(listOf("apply failed and was rolled back: ${e::class.simpleName}"))
            } catch (r: Exception) {
                ApplyReport.Rejected(listOf("apply failed and the rollback ALSO failed (${r::class.simpleName}); restore snapshot $rollback manually"), rollbackFailed = true)
            }
        }
        journal?.finished(cs.task)
        return ApplyReport.Applied(rollback, written, deleted, merged)
    }

    /** User-initiated undo of an applied change set. */
    fun rollback(ws: WorkspaceFileSystem, snapshot: SnapshotId) = ws.restoreSnapshot(snapshot)

    /** A directory at the path, or a file at one of its ancestors, cannot be merged automatically. */
    private fun typeClash(ws: WorkspaceFileSystem, path: RelPath): String? {
        fun kind(p: RelPath): Int { // 0 absent, 1 file, 2 directory
            val parent = p.parent ?: return 2
            val e = ws.list(if (parent.isRoot) null else parent).firstOrNull { it.path == p } ?: return 0
            return if (e.size < 0) 2 else 1
        }
        if (kind(path) == 2) return "a directory exists at $path"
        var a = path.parent
        while (a != null && !a.isRoot) { if (kind(a) == 1) return "a file exists at $a, an ancestor of $path"; a = a.parent }
        return null
    }
}

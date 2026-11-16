package com.anvex.agents.core.integrator

import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem

data class PendingApply(val task: TaskId, val rollback: SnapshotId)

/**
 * Write-ahead record of an apply in progress. If the process dies between [started] and [finished] the integration
 * workspace may be half-written; [ApplyRecovery] then restores the pre-apply snapshot (threat partial_apply).
 * Implementations must persist [started] durably BEFORE the first workspace write.
 */
interface ApplyJournal {
    fun started(task: TaskId, rollback: SnapshotId)
    fun finished(task: TaskId)
    fun pending(): List<PendingApply>
}

class InMemoryApplyJournal : ApplyJournal {
    private val open = LinkedHashMap<TaskId, SnapshotId>()
    override fun started(task: TaskId, rollback: SnapshotId) { open[task] = rollback }
    override fun finished(task: TaskId) { open.remove(task) }
    override fun pending() = open.map { PendingApply(it.key, it.value) }
}

object ApplyRecovery {
    /** Restores the workspace for every unfinished apply (oldest first) and clears the journal entries. Returns the recovered tasks. */
    fun recover(ws: WorkspaceFileSystem, journal: ApplyJournal): List<TaskId> {
        val recovered = ArrayList<TaskId>()
        for (p in journal.pending()) { ws.restoreSnapshot(p.rollback); journal.finished(p.task); recovered += p.task }
        return recovered
    }
}

package com.anvex.agents.fs

import com.anvex.agents.core.changeset.IgnoreRules
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem
import com.anvex.agents.core.ports.WorkspaceProvider

/** Creates the integration workspace of a project and isolated forks of it (ADR-005, ARC-FS-3). */
class RealWorkspaceProvider(
    private val roots: WorkspaceRoots,
    private val cas: CasStore,
    private val ignore: IgnoreRules = IgnoreRules.DEFAULT,
) : WorkspaceProvider {

    private val integration = HashSet<String>()
    private val scratchOf = HashMap<String, String>()   // workspaceId -> scratch id

    override fun open(projectId: String): WorkspaceFileSystem {
        val id = "proj-" + projectId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifEmpty { "x" }
        require(CasStore.isValidOwner(id)) { "invalid project id" }
        synchronized(integration) { integration += id }
        return RealWorkspaceFileSystem(id, roots.projectRoot(projectId), cas, ignore)
    }

    /** Materializes [base] into a brand new directory; the fork never aliases [source]. */
    override fun fork(source: WorkspaceFileSystem, base: SnapshotId, label: String): WorkspaceFileSystem {
        require(source is RealWorkspaceFileSystem) { "source is not a workspace of this provider" }
        val scratch = roots.createScratch(label)
        val wsId = "fork-${scratch.id.filter { it.isLetterOrDigit() || it == '-' }.take(50)}"
        try {
            val ws = RealWorkspaceFileSystem(wsId, scratch.fs, cas, ignore)
            cas.retain(wsId, base)
            ws.restoreSnapshot(base)
            synchronized(scratchOf) { scratchOf[wsId] = scratch.id }
            return ws
        } catch (e: Exception) {
            try { cas.releaseAll(wsId) } catch (_: Exception) { }
            try { roots.destroyScratch(scratch.id) } catch (_: Exception) { }
            throw e
        }
    }

    override fun dispose(ws: WorkspaceFileSystem) {
        require(ws is RealWorkspaceFileSystem) { "workspace was not created by this provider" }
        val scratchId = synchronized(scratchOf) { scratchOf.remove(ws.workspaceId) }
            ?: throw IllegalArgumentException("only forks can be disposed (the integration workspace is never deleted)")
        cas.releaseAll(ws.workspaceId)
        roots.destroyScratch(scratchId)
    }
}

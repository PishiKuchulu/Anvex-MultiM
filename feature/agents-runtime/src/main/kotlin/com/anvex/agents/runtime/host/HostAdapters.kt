package com.anvex.agents.runtime.host

import com.anvex.agents.core.orchestrator.SnapshotCatalog
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.ClockPort
import com.anvex.agents.core.ports.ContextBundle
import com.anvex.agents.core.ports.ContextItem
import com.anvex.agents.core.ports.ContextPort
import com.anvex.agents.core.ports.ContextQuery
import com.anvex.agents.core.ports.SensitivePathPort
import com.anvex.agents.core.ports.SensitiveVerdict
import com.anvex.agents.fs.CasStore
import com.anvex.agents.fs.Scratch
import com.anvex.agents.fs.WorkspaceRoots
import com.anvex.agents.runtime.AgentsHost
import com.anvex.core.engine.ProjectFileSystem

/* UNVERIFIED (never compiled): thin adapters from the host interface to the tested core ports. No decisions are made here. */

/** Acceptance saf_rejected: a SAF-backed project fails closed with this message; the engine never sees a content:// URI (ADR-001). */
class UnsupportedProjectException : IllegalStateException("This project is stored in a shared folder (SAF) and is not supported by Agent Teams yet.")

class HostSensitivePathPort(private val host: AgentsHost) : SensitivePathPort {
    override fun classify(path: RelPath): SensitiveVerdict =
        try { if (host.isSensitive(path.value)) SensitiveVerdict.Sensitive else SensitiveVerdict.NotSensitive } catch (e: Exception) { SensitiveVerdict.Unknown }
}

class HostContextPort(private val host: AgentsHost, private val projectId: String) : ContextPort {
    override fun retrieve(query: ContextQuery): ContextBundle {
        val items = host.retrieveContext(projectId, "${query.task.title} ${query.task.acceptance}", query.tokenBudget * 4)
            .mapNotNull { (p, excerpt) -> RelPath.parse(p)?.let { ContextItem(it, excerpt, "retrieved for the task") } }
        return ContextBundle(items)
    }
}

class HostWorkspaceRoots(private val host: AgentsHost) : WorkspaceRoots {
    override fun projectRoot(projectId: String): ProjectFileSystem = host.realProjectFileSystem(projectId) ?: throw UnsupportedProjectException()
    override fun createScratch(label: String): Scratch { val (id, fs) = host.createScratch(label); return Scratch(id, fs) }
    override fun destroyScratch(id: String) = host.destroyScratch(id)
}

class CasCatalog(private val cas: CasStore) : SnapshotCatalog {
    override fun manifest(id: com.anvex.agents.core.ports.SnapshotId) = cas.manifest(id)
}

class SystemClock : ClockPort { override fun nowMillis(): Long = System.currentTimeMillis() }

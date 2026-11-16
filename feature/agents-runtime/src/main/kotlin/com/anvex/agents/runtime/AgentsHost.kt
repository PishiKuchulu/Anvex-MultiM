package com.anvex.agents.runtime

import com.anvex.agents.core.ports.PriceInfo
import com.anvex.core.ai.ProviderClient
import com.anvex.core.engine.ProjectFileSystem

/** A provider the user can pick for an agent. [ref] is the host provider id (never a key). */
data class HostProvider(val ref: String, val displayName: String, val models: List<String>)

/**
 * Everything the module needs from the host app; implemented ONCE in the app (integration-templates/AnvexAgentsHostImpl.kt).
 * The module never sees credentials: [providerClient] returns a client that was configured by the host.
 */
interface AgentsHost {
    fun listProviders(): List<HostProvider>
    /** Null when the provider is unknown or has no usable credential. */
    fun providerClient(providerRef: String): ProviderClient?
    /** Null = price unknown (tokens are still tracked, cost is not). */
    fun priceFor(providerRef: String, model: String): PriceInfo?
    /** Host SensitivePaths. */
    fun isSensitive(relativePath: String): Boolean
    /** Real-path file system of the project, or null for a SAF-backed project (unsupported in v1, ADR-001/ADR-027). */
    fun realProjectFileSystem(projectId: String): ProjectFileSystem?
    /** A real-path file system rooted in app-private storage for the module (sub-directory name, e.g. "cas"). */
    fun agentsStorage(subdir: String): ProjectFileSystem
    /** Creates an empty app-private directory for an agent fork; returns its id and file system. */
    fun createScratch(label: String): Pair<String, ProjectFileSystem>
    fun destroyScratch(id: String)
    /** Relevant (path, excerpt) pairs for a task, size-bounded by the host's code index. Empty is acceptable. */
    fun retrieveContext(projectId: String, query: String, maxChars: Int): List<Pair<String, String>>
}

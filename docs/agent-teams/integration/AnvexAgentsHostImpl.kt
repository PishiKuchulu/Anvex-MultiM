package com.anvex.app.agents   // lives in the APP module at integration time (milestone M6)

import com.anvex.agents.core.ports.PriceInfo
import com.anvex.agents.runtime.AgentsHost
import com.anvex.agents.runtime.HostProvider
import com.anvex.core.ai.ProviderClient
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.engine.ProjectFileSystem
import java.io.File
import java.nio.file.Files

/**
 * UNVERIFIED TEMPLATE (never compiled). Binds the module's [AgentsHost] to Anvex. Every capability is injected as a function so that
 * the exact host classes are bound in ONE place at integration. How to bind each (host facts verified in Anvex 4.15.2 sources):
 *
 *  - listProviders / clientFor / priceFor: the app's provider repository. clientFor builds a ProviderClient with
 *    ProviderClientFactory.create(ProviderRuntimeConfig(config, apiKey)) where apiKey is decrypted by the host's secure store. The module never sees it.
 *    priceFor maps the provider/model price fields (per million tokens) to PriceInfo; return null when unknown.
 *  - projectFs: ProjectRepository's file-system accessor for the project, but return null when the project has a workspaceUri
 *    (SAF-backed): Agent Teams refuses those (ADR-001, ADR-027) and the UI shows the message.
 *  - isSensitive: com.anvex.core.engine.SensitivePaths.
 *  - contextFor: the host code index (CodebaseIndexer / SymbolIndexManager); an empty list is acceptable.
 *  - agentsRoot: File(context.filesDir, "agents") (app-private; holds the CAS and the agent forks).
 */
class AnvexAgentsHostImpl(
    private val providers: () -> List<HostProvider>,
    private val clientFor: (providerRef: String) -> ProviderClient?,
    private val price: (providerRef: String, model: String) -> PriceInfo?,
    private val projectFs: (projectId: String) -> ProjectFileSystem?,
    private val sensitive: (relativePath: String) -> Boolean,
    private val contextFor: (projectId: String, query: String, maxChars: Int) -> List<Pair<String, String>>,
    private val agentsRoot: File,
) : AgentsHost {
    private var counter = 0L

    override fun listProviders() = providers()
    override fun providerClient(providerRef: String) = clientFor(providerRef)
    override fun priceFor(providerRef: String, model: String) = price(providerRef, model)
    override fun isSensitive(relativePath: String) = sensitive(relativePath)
    override fun realProjectFileSystem(projectId: String) = projectFs(projectId)
    override fun agentsStorage(subdir: String): ProjectFileSystem = RealProjectFileSystem(File(agentsRoot, subdir.filter { it.isLetterOrDigit() || it == '-' || it == '_' }))
    override fun retrieveContext(projectId: String, query: String, maxChars: Int) = contextFor(projectId, query, maxChars)

    @Synchronized override fun createScratch(label: String): Pair<String, ProjectFileSystem> {
        val id = "s${System.currentTimeMillis()}-${++counter}"
        return id to RealProjectFileSystem(File(agentsRoot, "scratch/$id"))
    }

    override fun destroyScratch(id: String) { if (id.all { it.isLetterOrDigit() || it == '-' }) deleteNoFollow(File(agentsRoot, "scratch/$id")) }

    /** Never follows symlinks (a planted link must not make the cleanup delete files outside the scratch directory). */
    private fun deleteNoFollow(f: File) {
        if (Files.isSymbolicLink(f.toPath())) { f.delete(); return }
        if (f.isDirectory) f.listFiles()?.forEach { deleteNoFollow(it) }
        f.delete()
    }
}

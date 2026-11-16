package com.anvex.agents.fs

import com.anvex.core.engine.ProjectFileSystem

data class Scratch(val id: String, val fs: ProjectFileSystem)

/**
 * Supplies real-path file systems (ADR-001). The composition root implements this on RealProjectFileSystem and
 * must never hand a URI-backed implementation to the engine; this module cannot tell the difference, which is
 * why the contract is stated here and enforced at the composition root (acceptance saf_rejected).
 */
interface WorkspaceRoots {
    /** The integration workspace of a project. */
    fun projectRoot(projectId: String): ProjectFileSystem
    /** A fresh, empty, isolated directory for an agent fork. */
    fun createScratch(label: String): Scratch
    /** Deletes the scratch directory completely (never follows symlinks). */
    fun destroyScratch(id: String)
}

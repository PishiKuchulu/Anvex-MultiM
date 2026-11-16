package com.anvex.agents.fs

import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.ports.SensitivePathPort
import com.anvex.agents.core.ports.SensitiveVerdict
import com.anvex.core.android.fs.RealProjectFileSystem
import java.io.File
import java.nio.file.Files

class TempRoots(private val base: File) : WorkspaceRoots {
    private var n = 0
    override fun projectRoot(projectId: String) = RealProjectFileSystem(File(base, "projects/$projectId"))
    override fun createScratch(label: String): Scratch { val id = "s${++n}"; return Scratch(id, RealProjectFileSystem(File(base, "scratch/$id"))) }
    override fun destroyScratch(id: String) { deleteNoFollow(File(base, "scratch/$id")) }
}

/** Deletes a tree without following symlinks (tests plant links that point outside). */
fun deleteNoFollow(f: File) {
    if (Files.isSymbolicLink(f.toPath())) { f.delete(); return }
    if (f.isDirectory) f.listFiles()?.forEach { deleteNoFollow(it) }
    f.delete()
}

class TempEnv(maxSnapshotFileBytes: Long = com.anvex.agents.core.changeset.Limits.MAX_SNAPSHOT_FILE_BYTES) : AutoCloseable {
    val base: File = Files.createTempDirectory("agents-fs-").toFile()
    val roots = TempRoots(base)
    val casDir = File(base, "cas")
    val cas = CasStore(RealProjectFileSystem(casDir))
    val provider = RealWorkspaceProvider(roots, cas)
    val limit = maxSnapshotFileBytes
    fun project(id: String = "p1"): RealWorkspaceFileSystem = provider.open(id) as RealWorkspaceFileSystem
    fun projectDir(id: String = "p1") = File(base, "projects/$id")
    fun scratchDirs(): List<String> = File(base, "scratch").list()?.sorted() ?: emptyList()
    fun blobFiles(): List<File> = File(casDir, "blobs").walkTopDown().filter { it.isFile }.toList()
    override fun close() { deleteNoFollow(base) }
}

inline fun <T> withEnv(block: (TempEnv) -> T): T = TempEnv().use(block)

class FsSensitive : SensitivePathPort {
    override fun classify(path: RelPath) = if (path.segments.any { it == ".env" || it == ".git" || it == "local.properties" }) SensitiveVerdict.Sensitive else SensitiveVerdict.NotSensitive
}

fun rel(s: String) = RelPath.of(s)

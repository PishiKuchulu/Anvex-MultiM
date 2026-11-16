package com.anvex.agents.fs

import com.anvex.agents.core.changeset.ChangeSet
import com.anvex.agents.core.changeset.ChangeSetDeriver
import com.anvex.agents.core.changeset.FileChange
import com.anvex.agents.core.changeset.IgnoreRules
import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.Grant
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.integrator.ApplyReport
import com.anvex.agents.core.integrator.Integrator
import com.anvex.agents.core.merge.ConflictKind
import com.anvex.agents.core.path.RelPath
import com.anvex.agents.core.policy.PolicyContext
import com.anvex.agents.core.policy.PolicyEngine
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.SnapshotId
import com.anvex.agents.core.ports.WorkspaceFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Full pipeline on the REAL file system: seed -> snapshot -> fork -> agent edits -> derive -> validate -> policy -> merge -> apply. */
class EndToEndTest {
    private val lines = (1..40).joinToString("") { "line $it\n" }
    private val dev = AgentProfile(AgentId("dev"), "dev", "role", "prov", "model", grant = Grant(write = listOf("**")))

    private class Rig(val env: TempEnv, files: Map<String, String>) {
        val sens = FsSensitive()
        val integrator = Integrator(PolicyEngine(sens), sens, env.cas)
        val project = env.project()
        val base: SnapshotId
        val manifest: Manifest
        init { files.forEach { (p, t) -> project.writeBytes(rel(p), t.toByteArray()) }; base = project.createSnapshot(); manifest = env.cas.manifest(base)!! }
        fun fork(label: String): RealWorkspaceFileSystem = env.provider.fork(project, base, label) as RealWorkspaceFileSystem
        fun changeSet(fork: RealWorkspaceFileSystem, agent: String = "dev") = ChangeSet(TaskId("t-$agent"), AgentId(agent), base, ChangeSetDeriver.derive(manifest, fork, IgnoreRules.DEFAULT))
        fun apply(cs: ChangeSet, ctx: PolicyContext, ws: WorkspaceFileSystem = project) = integrator.apply(ws, cs, manifest, ctx)
        fun state(ws: WorkspaceFileSystem = project) = ws.walk().filter { !IgnoreRules.DEFAULT.isIgnored(it.path) }.associate { it.path.value to Digests.sha256(ws.readBytes(it.path)!!).hex }
        fun text(p: String) = String(project.readBytes(rel(p))!!)
    }
    private fun edit(text: String, vararg c: Pair<Int, String>): String { val l = text.split("\n").toMutableList(); for ((i, v) in c) l[i - 1] = v; return l.joinToString("\n") }
    private fun ctx(claims: List<String> = emptyList(), approved: Set<RelPath> = emptySet()) = PolicyContext(dev, claims, approved)
    private inline fun rig(files: Map<String, String>, block: (Rig) -> Unit) = withEnv { env -> block(Rig(env, files)) }

    @Test fun twoAgentsEditingDifferentPartsBothLandWithoutLosingAnything() = rig(mapOf("shared.kt" to lines, "other.kt" to "other\n")) { r ->
        val a = r.fork("a"); val b = r.fork("b")
        a.writeBytes(rel("shared.kt"), edit(lines, 3 to "A edit 3").toByteArray()); a.writeBytes(rel("a-new.kt"), "new from a\n".toByteArray())
        b.writeBytes(rel("shared.kt"), edit(lines, 30 to "B edit 30").toByteArray()); b.writeBytes(rel("other.kt"), "other changed by b\n".toByteArray())
        val ra = r.apply(r.changeSet(a, "agent-a"), ctx()); assertTrue("$ra", ra is ApplyReport.Applied)
        val rb = r.apply(r.changeSet(b, "agent-b"), ctx()); assertTrue("$rb", rb is ApplyReport.Applied); assertEquals(1, (rb as ApplyReport.Applied).merged)
        assertEquals(edit(lines, 3 to "A edit 3", 30 to "B edit 30"), r.text("shared.kt")); assertEquals("new from a\n", r.text("a-new.kt")); assertEquals("other changed by b\n", r.text("other.kt"))
    }

    @Test fun overlappingEditsLeaveTheProjectUntouchedByTheSecondAgent() = rig(mapOf("shared.kt" to lines, "other.kt" to "other\n")) { r ->
        val a = r.fork("a"); val b = r.fork("b")
        a.writeBytes(rel("shared.kt"), edit(lines, 10 to "A wins?").toByteArray()); b.writeBytes(rel("shared.kt"), edit(lines, 10 to "B wins?").toByteArray()); b.writeBytes(rel("other.kt"), "b also edits other\n".toByteArray())
        assertTrue(r.apply(r.changeSet(a, "agent-a"), ctx()) is ApplyReport.Applied)
        val afterA = r.state(); val rb = r.apply(r.changeSet(b, "agent-b"), ctx())
        assertTrue("$rb", rb is ApplyReport.Conflicts); assertEquals(ConflictKind.OVERLAP, (rb as ApplyReport.Conflicts).items.single().kind)
        assertEquals("a conflict writes NOTHING, not even the non-conflicting file", afterA, r.state()); assertEquals(edit(lines, 10 to "A wins?"), r.text("shared.kt"))
    }

    @Test fun deleteVersusEditAndBinaryConflictsOnRealFiles() = rig(mapOf("x.txt" to "x\n", "img.bin" to "\u0000\u0001\u0002")) { r ->
        val a = r.fork("a"); val b = r.fork("b")
        a.writeBytes(rel("x.txt"), "x edited\n".toByteArray()); a.writeBytes(rel("img.bin"), byteArrayOf(0, 1, 9)); b.delete(rel("x.txt")); b.writeBytes(rel("img.bin"), byteArrayOf(0, 9, 2))
        assertTrue(r.apply(r.changeSet(a, "agent-a"), ctx()) is ApplyReport.Applied)
        val before = r.state(); val rb = r.apply(r.changeSet(b, "agent-b"), ctx()) as ApplyReport.Conflicts
        assertEquals(setOf(ConflictKind.DELETE_EDIT, ConflictKind.BINARY), rb.items.map { it.kind }.toSet()); assertEquals(before, r.state())
    }

    @Test fun crlfFilesKeepTheirLineEndingsThroughAMerge() {
        val crlf = (1..20).joinToString("") { "row $it\r\n" }
        rig(mapOf("win.txt" to crlf)) { r ->
            val a = r.fork("a"); val b = r.fork("b")
            a.writeBytes(rel("win.txt"), crlf.replace("row 2\r\n", "ROW 2\r\n").toByteArray()); b.writeBytes(rel("win.txt"), crlf.replace("row 18\r\n", "ROW 18\r\n").toByteArray())
            r.apply(r.changeSet(a, "agent-a"), ctx()); r.apply(r.changeSet(b, "agent-b"), ctx())
            assertEquals(crlf.replace("row 2\r\n", "ROW 2\r\n").replace("row 18\r\n", "ROW 18\r\n"), r.text("win.txt")); assertFalse(r.text("win.txt").contains("\n") && r.text("win.txt").replace("\r\n", "").contains("\n"))
        }
    }

    @Test fun userRollbackRestoresExactlyThePreApplyBytes() = rig(mapOf("shared.kt" to lines, "other.kt" to "other\n")) { r ->
        val f = r.fork("a"); f.writeBytes(rel("shared.kt"), edit(lines, 5 to "X").toByteArray()); f.delete(rel("other.kt")); f.writeBytes(rel("brand/new.kt"), "n".toByteArray())
        val before = r.state(); val applied = r.apply(r.changeSet(f), ctx()) as ApplyReport.Applied
        assertNotEquals(before, r.state()); r.integrator.rollback(r.project, applied.rollback); assertEquals(before, r.state())
    }

    // ---------------------------------------------------------------- acceptance atomic_apply on the real file system
    private class FaultyWs(val d: WorkspaceFileSystem, val failAt: Int, val lie: Boolean = false) : WorkspaceFileSystem by d {
        var n = 0
        override fun writeBytes(path: RelPath, bytes: ByteArray) { n++; if (failAt == n) throw IllegalStateException("disk full (injected)"); d.writeBytes(path, if (lie) bytes + byteArrayOf(7) else bytes) }
        override fun delete(path: RelPath): Boolean { n++; if (failAt == n) throw IllegalStateException("io error (injected)"); return d.delete(path) }
    }

    @Test fun aFailureAtEveryStepRestoresTheRealProjectByteForByte() {
        val files = mapOf("shared.kt" to lines, "other.kt" to "other\n", "gone.kt" to "gone\n", "dir/inner.kt" to "inner\n")
        var steps = 0
        for (k in 0..6) rig(files) { r ->
            val f = r.fork("a"); f.writeBytes(rel("shared.kt"), edit(lines, 2 to "S").toByteArray()); f.writeBytes(rel("other.kt"), "O\n".toByteArray()); f.delete(rel("gone.kt")); f.writeBytes(rel("new/a.kt"), "a".toByteArray()); f.writeBytes(rel("new/b.kt"), "b".toByteArray())
            val cs = r.changeSet(f); val before = r.state()
            if (k == 0) { val probe = FaultyWs(r.project, 0); assertTrue(r.apply(cs, ctx(), probe) is ApplyReport.Applied); steps = probe.n; assertTrue("steps=$steps", steps >= 5) }
            else if (k <= steps) {
                val res = r.apply(cs, ctx(), FaultyWs(r.project, k)); assertTrue("k=$k: $res", res is ApplyReport.Rejected); assertFalse((res as ApplyReport.Rejected).rollbackFailed)
                assertEquals("failure at step $k must leave the project exactly as before", before, r.state())
            }
        }
    }

    @Test fun aLyingDiskIsCaughtByReadBackVerificationOnTheRealFileSystem() = rig(mapOf("a.txt" to "a\n", "b.txt" to "b\n")) { r ->
        val f = r.fork("a"); f.writeBytes(rel("a.txt"), "a2\n".toByteArray()); val before = r.state()
        val res = r.apply(r.changeSet(f), ctx(), FaultyWs(r.project, 0, lie = true)); assertTrue("$res", res is ApplyReport.Rejected); assertEquals(before, r.state())
    }

    // ---------------------------------------------------------------- untrusted output (acceptance cs_validation / ownership)
    @Test fun anAgentCannotSmuggleSensitiveFilesIntoTheProject() = rig(mapOf("a.txt" to "a\n")) { r ->
        val f = r.fork("evil"); f.writeBytes(rel(".env"), "API_KEY=1\n".toByteArray()); f.writeBytes(rel("sub/.git/config"), "[core]\n".toByteArray()); f.writeBytes(rel("a.txt"), "a2\n".toByteArray())
        val before = r.state(); val res = r.apply(r.changeSet(f), ctx())
        assertTrue("$res", res is ApplyReport.Rejected); assertEquals(before, r.state()); assertFalse(r.project.exists(rel(".env")))
    }

    @Test fun symlinksPlantedInAForkAreNeverCarriedIntoTheProject() = rig(mapOf("a.txt" to "a\n")) { r ->
        val secret = File(r.env.base, "host-secret.txt").also { it.writeText("TOP-SECRET") }
        val f = r.fork("evil"); val root = File(r.env.base, "scratch/${r.env.scratchDirs().single()}")
        Files.createSymbolicLink(File(root, "innocent.txt").toPath(), secret.toPath()); f.writeBytes(rel("a.txt"), "a2\n".toByteArray())
        val cs = r.changeSet(f); assertEquals(listOf("a.txt"), cs.changes.map { it.path.value })
        assertTrue(r.apply(cs, ctx()) is ApplyReport.Applied); assertFalse(r.project.exists(rel("innocent.txt")))
        assertTrue(r.state().values.none { it == Digests.sha256("TOP-SECRET".toByteArray()).hex })
    }

    @Test fun buildOutputInAForkIsIgnoredAndNeverIntegrated() = rig(mapOf("a.txt" to "a\n")) { r ->
        val f = r.fork("a"); f.writeBytes(rel("build/classes/X.class"), "x".toByteArray()); f.writeBytes(rel("m/node_modules/q/i.js"), "x".toByteArray()); f.writeBytes(rel("a.txt"), "a2\n".toByteArray())
        assertEquals(listOf("a.txt"), r.changeSet(f).changes.map { it.path.value })
    }

    @Test fun editsOutsideTheTaskClaimEscalateWithoutWriting() = rig(mapOf("src/a.kt" to "a\n", "build.gradle.kts" to "g\n")) { r ->
        val f = r.fork("a"); f.writeBytes(rel("src/a.kt"), "a2\n".toByteArray()); f.writeBytes(rel("build.gradle.kts"), "g2\n".toByteArray())
        val cs = r.changeSet(f); val before = r.state()
        val esc = r.apply(cs, ctx(claims = listOf("src/**"))); assertTrue("$esc", esc is ApplyReport.Escalated); assertEquals(before, r.state())
        val ok = r.apply(cs, ctx(claims = listOf("src/**"), approved = setOf(rel("build.gradle.kts")))); assertTrue("$ok", ok is ApplyReport.Applied); assertEquals("g2\n", r.text("build.gradle.kts"))
    }

    @Test fun aForgedBaseDigestIsRejectedEvenWhenEverythingElseLooksValid() = rig(mapOf("a.txt" to "a\n", "b.txt" to "b\n")) { r ->
        val f = r.fork("a"); f.writeBytes(rel("a.txt"), "a2\n".toByteArray())
        val honest = r.changeSet(f).changes.single() as FileChange.Modify
        val forged = ChangeSet(TaskId("t"), AgentId("dev"), r.base, listOf(FileChange.Modify(honest.path, Digests.sha256("b\n".toByteArray()), honest.bytes)))
        val res = r.apply(forged, ctx()); assertTrue("$res", res is ApplyReport.Rejected); assertEquals("a\n", r.text("a.txt"))
    }

    // ---------------------------------------------------------------- scale smoke
    @Test fun aThreeHundredFileProjectRunsThroughTheWholePipelineQuickly() {
        val files = (1..300).associate { "m${it % 12}/f$it.kt" to (1..60).joinToString("") { l -> "val v${it}_$l = $l\n" } }
        rig(files) { r ->
            val t0 = System.nanoTime(); val a = r.fork("a"); val b = r.fork("b")
            for (i in 1..300 step 7) a.writeBytes(rel("m${i % 12}/f$i.kt"), (files.getValue("m${i % 12}/f$i.kt").replace("val v${i}_1 = 1", "val v${i}_1 = 111")).toByteArray())
            for (i in 2..300 step 7) b.writeBytes(rel("m${i % 12}/f$i.kt"), (files.getValue("m${i % 12}/f$i.kt").replace("val v${i}_60 = 60", "val v${i}_60 = 666")).toByteArray())
            val ra = r.apply(r.changeSet(a, "agent-a"), ctx()); val rb = r.apply(r.changeSet(b, "agent-b"), ctx()); val ms = (System.nanoTime() - t0) / 1_000_000
            println("300-file pipeline (2 forks, 2 change sets, apply): $ms ms")
            assertTrue("$ra", ra is ApplyReport.Applied); assertTrue("$rb", rb is ApplyReport.Applied); assertTrue("took $ms ms", ms < 60_000)
        }
    }
}

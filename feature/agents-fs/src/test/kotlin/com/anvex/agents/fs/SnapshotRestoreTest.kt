package com.anvex.agents.fs

import com.anvex.agents.core.ports.SnapshotId
import com.anvex.core.android.fs.RealProjectFileSystem
import com.anvex.core.engine.ProjectFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class SnapshotRestoreTest {
    private fun fails(expected: Class<out Throwable>, block: () -> Unit) {
        try { block() } catch (t: Throwable) { if (expected.isInstance(t)) return; throw AssertionError("expected ${expected.simpleName} but got $t") }
        throw AssertionError("expected ${expected.simpleName}")
    }
    private fun state(ws: RealWorkspaceFileSystem) = ws.walk().filter { !com.anvex.agents.core.changeset.IgnoreRules.DEFAULT.isIgnored(it.path) }.associate { it.path.value to ws.digest(it.path)!!.hex }

    private fun randomTree(ws: RealWorkspaceFileSystem, rnd: Random, n: Int) {
        val names = listOf("src", "lib", "docs", "a b", "ünï", "x.y", "deep/er/est", ".hidden")
        for (i in 0 until n) {
            val dir = names[rnd.nextInt(names.size)]; val bytes = ByteArray(rnd.nextInt(6000)).also { rnd.nextBytes(it) }
            val content = if (rnd.nextBoolean()) bytes else String(CharArray(rnd.nextInt(400)) { (32 + rnd.nextInt(90)).toChar() }).toByteArray()
            ws.writeBytes(rel("$dir/file$i.${if (rnd.nextBoolean()) "bin" else "txt"}"), content)
        }
        ws.writeBytes(rel("empty.txt"), ByteArray(0))
    }

    /** Acceptance cas_restore: byte-exact restore after arbitrary damage. */
    @Test fun restoreIsByteExactAfterHeavyDamage() = withEnv { env ->
        val rnd = Random(99); val ws = env.project(); randomTree(ws, rnd, 150)
        val before = state(ws); val id = ws.createSnapshot()
        // damage: modify, delete, add, replace a file by a directory and a directory by a file
        for (p in ws.walk().map { it.path }.shuffled(rnd).take(60)) when (rnd.nextInt(3)) { 0 -> ws.writeBytes(p, "damaged".toByteArray()); 1 -> ws.delete(p); else -> ws.writeBytes(p, ByteArray(rnd.nextInt(100))) }
        repeat(15) { ws.writeBytes(rel("junk/j$it/z.txt"), "z".toByteArray()) }
        val victim = ws.walk().first { it.path.value.startsWith("src/") }.path; ws.delete(victim); ws.writeBytes(rel(victim.value + "/inside.txt"), "now a dir".toByteArray())
        assertNotEquals(before, state(ws))
        ws.restoreSnapshot(id)
        assertEquals(before, state(ws))
        for ((p, h) in before) assertEquals(h, ws.digest(rel(p))!!.hex)
        assertEquals("restore must be idempotent", id, ws.createSnapshot())
    }

    @Test fun snapshotIdDependsOnlyOnTrackedContent() = withEnv { env ->
        val ws = env.project(); ws.writeBytes(rel("a.txt"), "a".toByteArray()); ws.writeBytes(rel("b/c.txt"), "c".toByteArray())
        val id1 = ws.createSnapshot()
        ws.writeBytes(rel("build/out.bin"), "junk".toByteArray()); ws.writeBytes(rel(".git/HEAD"), "ref".toByteArray()); File(env.projectDir(), "emptydir").mkdirs()
        assertEquals("ignored files and empty directories are not part of a snapshot", id1, ws.createSnapshot())
        ws.writeBytes(rel("a.txt"), "A".toByteArray()); assertNotEquals(id1, ws.createSnapshot())
        ws.writeBytes(rel("a.txt"), "a".toByteArray()); assertEquals("same content, same id", id1, ws.createSnapshot())
    }

    @Test fun restoreLeavesIgnoredAreasAlone() = withEnv { env ->
        val ws = env.project(); ws.writeBytes(rel("keep.txt"), "k".toByteArray()); val id = ws.createSnapshot()
        ws.writeBytes(rel("build/cache.bin"), "cache".toByteArray()); ws.writeBytes(rel(".git/index"), "idx".toByteArray()); ws.writeBytes(rel("extra.txt"), "x".toByteArray())
        ws.restoreSnapshot(id)
        assertFalse(ws.exists(rel("extra.txt"))); assertEquals("cache", String(ws.readBytes(rel("build/cache.bin"))!!)); assertEquals("idx", String(ws.readBytes(rel(".git/index"))!!))
    }

    /** A corrupt or missing blob must abort BEFORE any modification (no half-restored workspace). */
    @Test fun corruptBlobAbortsRestoreWithoutTouchingTheWorkspace() = withEnv { env ->
        val ws = env.project(); ws.writeBytes(rel("a.txt"), "aaa".toByteArray()); ws.writeBytes(rel("b.txt"), "bbb".toByteArray()); ws.writeBytes(rel("c.txt"), "ccc".toByteArray())
        val id = ws.createSnapshot()
        ws.writeBytes(rel("a.txt"), "changed-a".toByteArray()); ws.delete(rel("c.txt")); ws.writeBytes(rel("new.txt"), "n".toByteArray())
        val damaged = state(ws)
        val m = env.cas.manifest(id)!!; val victim = m.entries.getValue(rel("b.txt")).digest.hex
        File(env.casDir, "blobs/${victim.substring(0, 2)}/$victim").writeText("EVIL")
        fails(CorruptBlobException::class.java) { ws.restoreSnapshot(id) }
        assertEquals("workspace must be exactly as before the failed restore", damaged, state(ws))
        File(env.casDir, "blobs/${victim.substring(0, 2)}/$victim").delete()
        fails(CorruptBlobException::class.java) { ws.restoreSnapshot(id) }
        assertEquals(damaged, state(ws))
    }

    @Test fun unknownSnapshotsAreRejected() = withEnv { env ->
        val ws = env.project(); fails(SnapshotNotFoundException::class.java) { ws.restoreSnapshot(SnapshotId("a".repeat(64))) }
        fails(IllegalArgumentException::class.java) { ws.restoreSnapshot(SnapshotId("../../x")) }
    }

    @Test fun snapshotSizeLimitIsExactAndFailsClosed() = withEnv { env ->
        val ws = RealWorkspaceFileSystem("tiny", env.roots.projectRoot("tiny"), env.cas, maxSnapshotFileBytes = 10)
        ws.writeBytes(rel("ten.bin"), ByteArray(10)); ws.createSnapshot()
        ws.writeBytes(rel("eleven.bin"), ByteArray(11)); fails(UnsupportedEntryException::class.java) { ws.createSnapshot() }
    }

    @Test fun unchangedFilesAreNotRewrittenOnRestore() = withEnv { env ->
        class Counting(val d: ProjectFileSystem) : ProjectFileSystem by d { var writes = 0
            override fun writeFileBytes(relativePath: String, bytes: ByteArray) { writes++; d.writeFileBytes(relativePath, bytes) } }
        val real = RealProjectFileSystem(File(env.base, "counted")); val counting = Counting(real)
        val ws = RealWorkspaceFileSystem("counted", counting, env.cas)
        for (i in 1..20) ws.writeBytes(rel("f$i.txt"), "content $i".toByteArray()); val id = ws.createSnapshot()
        counting.writes = 0; ws.restoreSnapshot(id); assertEquals("identical state: no writes", 0, counting.writes)
        ws.writeBytes(rel("f3.txt"), "different".toByteArray()); ws.writeBytes(rel("f4.txt"), ByteArray(7)); counting.writes = 0; ws.restoreSnapshot(id)
        assertEquals("only the two damaged files are rewritten", 2, counting.writes)
    }

    @Test fun anEmptyWorkspaceHasAStableEmptySnapshot() = withEnv { env ->
        val ws = env.project(); val id = ws.createSnapshot(); assertEquals(com.anvex.agents.core.changeset.Manifest.EMPTY.snapshotId(), id)
        ws.writeBytes(rel("x"), "x".toByteArray()); ws.restoreSnapshot(id); assertTrue(ws.walk().isEmpty())
    }
}

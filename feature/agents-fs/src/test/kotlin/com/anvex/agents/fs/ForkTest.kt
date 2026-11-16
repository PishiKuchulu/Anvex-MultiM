package com.anvex.agents.fs

import com.anvex.agents.core.ports.SnapshotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkTest {
    private fun fails(expected: Class<out Throwable>, block: () -> Unit) {
        try { block() } catch (t: Throwable) { if (expected.isInstance(t)) return; throw AssertionError("expected ${expected.simpleName} but got $t") }
        throw AssertionError("expected ${expected.simpleName}")
    }
    private fun seeded(env: TempEnv): Pair<RealWorkspaceFileSystem, SnapshotId> {
        val p = env.project(); p.writeBytes(rel("a.txt"), "A".toByteArray()); p.writeBytes(rel("src/b.txt"), "B".toByteArray()); p.writeBytes(rel("bin"), byteArrayOf(0, 1, 2))
        return p to p.createSnapshot()
    }

    @Test fun forkReproducesTheSnapshotExactly() = withEnv { env ->
        val (p, base) = seeded(env); val f = env.provider.fork(p, base, "agent-a")
        assertEquals(p.walk().map { it.path to p.digest(it.path) }, f.walk().map { it.path to f.digest(it.path) }); assertEquals(base, f.createSnapshot())
    }

    /** Acceptance fork_isolation. */
    @Test fun writesInOneForkNeverReachTheSourceOrOtherForks() = withEnv { env ->
        val (p, base) = seeded(env); val a = env.provider.fork(p, base, "a"); val b = env.provider.fork(p, base, "b")
        a.writeBytes(rel("a.txt"), "changed by a".toByteArray()); a.writeBytes(rel("only-a.txt"), "x".toByteArray()); a.delete(rel("src/b.txt"))
        assertEquals("A", String(p.readBytes(rel("a.txt"))!!)); assertFalse(p.exists(rel("only-a.txt"))); assertTrue(p.exists(rel("src/b.txt")))
        assertEquals("A", String(b.readBytes(rel("a.txt"))!!)); assertFalse(b.exists(rel("only-a.txt"))); assertTrue(b.exists(rel("src/b.txt")))
        p.writeBytes(rel("later.txt"), "after fork".toByteArray()); p.writeBytes(rel("a.txt"), "source moved on".toByteArray())
        assertFalse("source changes after the fork do not leak in", a.exists(rel("later.txt"))); assertEquals("A", String(b.readBytes(rel("a.txt"))!!))
        assertEquals("two scratch directories, one per fork", 2, env.scratchDirs().size)
    }

    @Test fun forkContentLivesInASeparateDirectoryTree() = withEnv { env ->
        val (p, base) = seeded(env); val f = env.provider.fork(p, base, "a") as RealWorkspaceFileSystem
        f.projectFileSystem().writeFile("via-mount.txt", "m")
        assertFalse(p.exists(rel("via-mount.txt"))); assertEquals(1, env.scratchDirs().size)
    }

    @Test fun disposeRemovesTheDirectoryAndReleasesTheSnapshot() = withEnv { env ->
        val (p, base) = seeded(env); val f = env.provider.fork(p, base, "a")
        assertTrue(env.cas.retainedSnapshots().contains(base))
        env.provider.dispose(f); assertEquals(emptyList<String>(), env.scratchDirs())
        assertTrue("the project workspace still retains the base snapshot", env.cas.retainedSnapshots().contains(base))
        env.cas.releaseAll("proj-p1"); val r = env.cas.gc(); assertTrue("nothing retains the snapshot any more", r.blobsDeleted > 0 && env.blobFiles().isEmpty())
    }

    @Test fun forkingAnUnknownSnapshotLeavesNoTraces() = withEnv { env ->
        val (p, _) = seeded(env)
        fails(SnapshotNotFoundException::class.java) { env.provider.fork(p, SnapshotId("f".repeat(64)), "x") }
        assertEquals(emptyList<String>(), env.scratchDirs()); assertTrue(env.cas.retainedSnapshots().none { it.value == "f".repeat(64) })
    }

    @Test fun forkFromACorruptedSnapshotFailsAndCleansUp() = withEnv { env ->
        val (p, base) = seeded(env); val victim = env.cas.manifest(base)!!.entries.values.first().digest.hex
        java.io.File(env.casDir, "blobs/${victim.substring(0, 2)}/$victim").writeText("EVIL")
        fails(CorruptBlobException::class.java) { env.provider.fork(p, base, "x") }
        assertEquals(emptyList<String>(), env.scratchDirs())
    }

    @Test fun onlyForksOfThisProviderCanBeDisposed() = withEnv { env ->
        val (p, _) = seeded(env)
        fails(IllegalArgumentException::class.java) { env.provider.dispose(p) }       // the integration workspace is never deleted
        val other = TempEnv().use { e2 -> e2.project("z") }
        fails(IllegalArgumentException::class.java) { env.provider.dispose(other) }
        assertTrue(p.exists(rel("a.txt")))
        fails(IllegalArgumentException::class.java) { env.provider.fork(object : com.anvex.agents.core.ports.WorkspaceFileSystem by p {}, SnapshotId("a".repeat(64)), "x") }
    }

    @Test fun manyForksAreIndependent() = withEnv { env ->
        val (p, base) = seeded(env); val forks = (1..8).map { env.provider.fork(p, base, "f$it") }
        forks.forEachIndexed { i, f -> f.writeBytes(rel("a.txt"), "fork $i".toByteArray()) }
        forks.forEachIndexed { i, f -> assertEquals("fork $i", String(f.readBytes(rel("a.txt"))!!)) }
        assertEquals("A", String(p.readBytes(rel("a.txt"))!!)); forks.forEach { env.provider.dispose(it) }; assertEquals(emptyList<String>(), env.scratchDirs())
        assertNull(p.readBytes(rel("nothing")))
    }
}

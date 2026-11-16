package com.anvex.agents.fs

import com.anvex.agents.core.changeset.Manifest
import com.anvex.agents.core.changeset.ManifestEntry
import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.SnapshotId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CasStoreTest {
    private fun fails(expected: Class<out Throwable>, block: () -> Unit) {
        try { block() } catch (t: Throwable) { if (expected.isInstance(t)) return; throw AssertionError("expected ${expected.simpleName} but got $t") }
        throw AssertionError("expected ${expected.simpleName}")
    }
    private fun blobFile(env: TempEnv, hex: String) = File(env.casDir, "blobs/${hex.substring(0, 2)}/$hex")

    @Test fun blobsAreStoredOnceAndReadBack() = withEnv { env ->
        val d1 = env.cas.putBlob("same".toByteArray()); val d2 = env.cas.putBlob("same".toByteArray()); val d3 = env.cas.putBlob("other".toByteArray())
        assertEquals(d1, d2); assertEquals(2, env.blobFiles().size)
        assertArrayEquals("same".toByteArray(), env.cas.get(d1)); assertTrue(env.cas.hasBlob(d3)); assertNull(env.cas.get(Digests.sha256("absent".toByteArray())))
        assertEquals(Digests.sha256(ByteArray(0)), env.cas.putBlob(ByteArray(0))); assertArrayEquals(ByteArray(0), env.cas.get(Digests.sha256(ByteArray(0))))
    }

    @Test fun aCorruptedBlobIsDetectedOnReadAndRepairedOnReput() = withEnv { env ->
        val d = env.cas.putBlob("precious".toByteArray())
        blobFile(env, d.hex).writeText("tampered!")
        fails(CorruptBlobException::class.java) { env.cas.get(d) }
        assertEquals(d, env.cas.putBlob("precious".toByteArray())); assertArrayEquals("precious".toByteArray(), env.cas.get(d))
    }

    @Test fun aTruncatedBlobIsDetected() = withEnv { env ->
        val d = env.cas.putBlob("0123456789".toByteArray()); blobFile(env, d.hex).writeBytes("01234".toByteArray())
        fails(CorruptBlobException::class.java) { env.cas.get(d) }
    }

    @Test fun manifestsAreVerifiedAgainstTheirId() = withEnv { env ->
        val m = Manifest(mapOf(rel("a.txt") to ManifestEntry(env.cas.putBlob("a".toByteArray()), 1)))
        val id = env.cas.putManifest(m); assertEquals(m, env.cas.manifest(id)); assertNull(env.cas.manifest(SnapshotId(Digests.sha256("zzz".toByteArray()).hex)))
        val f = File(env.casDir, "manifests/${id.value}")
        f.writeText(f.readText().replace("a.txt", "b.txt"))
        fails(CorruptBlobException::class.java) { env.cas.manifest(id) }
        assertEquals("re-putting repairs it", id, env.cas.putManifest(m)); assertEquals(m, env.cas.manifest(id))
    }

    @Test fun idsAndOwnersCannotCarryPathTraversal() = withEnv { env ->
        val m = Manifest(emptyMap()); val id = env.cas.putManifest(m)
        for (bad in listOf("../../etc/passwd", "a/b", "", "XYZ", "A".repeat(64), "0".repeat(63))) fails(IllegalArgumentException::class.java) { env.cas.manifest(SnapshotId(bad)) }
        for (bad in listOf("..", ".", "a/b", "", "../x", "-x", " x", "x".repeat(65))) fails(IllegalArgumentException::class.java) { env.cas.retain(bad, id) }
        env.cas.retain("ok-1.2_x", id)
    }

    @Test fun retainNeedsAnExistingSnapshot() = withEnv { env ->
        fails(SnapshotNotFoundException::class.java) { env.cas.retain("w", SnapshotId("0".repeat(64))) }
    }

    @Test fun gcKeepsSharedBlobsAndDeletesOrphans() = withEnv { env ->
        val shared = env.cas.putBlob("shared".toByteArray()); val onlyA = env.cas.putBlob("only-a".toByteArray()); val onlyB = env.cas.putBlob("only-b".toByteArray())
        val a = env.cas.putManifest(Manifest(mapOf(rel("s") to ManifestEntry(shared, 6), rel("a") to ManifestEntry(onlyA, 6))))
        val b = env.cas.putManifest(Manifest(mapOf(rel("s") to ManifestEntry(shared, 6), rel("b") to ManifestEntry(onlyB, 6))))
        env.cas.putBlob("orphan".toByteArray())
        env.cas.retain("wa", a); env.cas.retain("wb", b)
        var r = env.cas.gc(); assertEquals(1, r.blobsDeleted); assertEquals(3, r.blobsKept); assertEquals(0, r.manifestsDeleted)
        env.cas.releaseAll("wa"); r = env.cas.gc()
        assertEquals("only-a goes; shared stays for b", 1, r.blobsDeleted); assertEquals(1, r.manifestsDeleted); assertTrue(env.cas.hasBlob(shared)); assertFalse(env.cas.hasBlob(onlyA)); assertTrue(env.cas.hasBlob(onlyB))
        assertEquals(listOf(b), env.cas.retainedSnapshots())
        env.cas.release("wb", b); r = env.cas.gc(); assertEquals(0, env.blobFiles().size); assertEquals(emptyList<SnapshotId>(), env.cas.retainedSnapshots())
    }

    @Test fun gcRefusesToRunWhenALiveManifestIsCorruptAndDeletesNothing() = withEnv { env ->
        val d = env.cas.putBlob("x".toByteArray()); val id = env.cas.putManifest(Manifest(mapOf(rel("x") to ManifestEntry(d, 1)))); env.cas.retain("w", id)
        env.cas.putBlob("orphan".toByteArray()); val before = env.blobFiles().size
        File(env.casDir, "manifests/${id.value}").writeText("garbage")
        fails(IllegalStateException::class.java) { env.cas.gc() }
        assertEquals("nothing may be deleted when the live set is unknown", before, env.blobFiles().size)
    }

    @Test fun releaseIsIdempotent() = withEnv { env ->
        val id = env.cas.putManifest(Manifest(emptyMap())); env.cas.retain("w", id); env.cas.release("w", id); env.cas.release("w", id); env.cas.releaseAll("w"); env.cas.releaseAll("never")
        assertEquals(emptyList<SnapshotId>(), env.cas.retainedSnapshots())
    }

    @Test fun concurrentSnapshotsAndGcNeverLoseLiveBlobs() = withEnv { env ->
        val ws = env.project(); for (i in 1..30) ws.writeBytes(rel("f/$i.txt"), "content $i".toByteArray())
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val t1 = Thread { try { repeat(25) { ws.writeBytes(rel("f/1.txt"), "v$it".toByteArray()); ws.createSnapshot() } } catch (e: Throwable) { errors += e } }
        val t2 = Thread { try { repeat(60) { env.cas.gc() } } catch (e: Throwable) { errors += e } }
        t1.start(); t2.start(); t1.join(); t2.join()
        assertTrue("errors: $errors", errors.isEmpty())
        for (id in env.cas.retainedSnapshots()) for (e in env.cas.manifest(id)!!.entries.values) assertTrue("blob of a retained snapshot was collected", env.cas.get(e.digest) != null)
    }
}

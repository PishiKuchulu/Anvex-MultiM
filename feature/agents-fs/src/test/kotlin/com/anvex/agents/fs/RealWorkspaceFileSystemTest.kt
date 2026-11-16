package com.anvex.agents.fs

import com.anvex.agents.core.ports.Digests
import com.anvex.agents.core.ports.EntryInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RealWorkspaceFileSystemTest {
    @Test fun basicOperations() = withEnv { env ->
        val ws = env.project()
        assertNull(ws.readBytes(rel("nope"))); assertFalse(ws.exists(rel("nope")))
        ws.writeBytes(rel("a/b/c.txt"), "hello".toByteArray()); ws.writeBytes(rel("z.txt"), byteArrayOf(0, 1, 2, -1))
        assertTrue(ws.exists(rel("a/b/c.txt"))); assertTrue("directories exist too", ws.exists(rel("a/b")))
        assertArrayEquals("hello".toByteArray(), ws.readBytes(rel("a/b/c.txt"))); assertArrayEquals(byteArrayOf(0, 1, 2, -1), ws.readBytes(rel("z.txt")))
        assertEquals(Digests.sha256("hello".toByteArray()), ws.digest(rel("a/b/c.txt"))); assertNull(ws.digest(rel("none")))
        assertEquals(listOf("a/b/c.txt", "z.txt"), ws.walk().map { it.path.value }); assertEquals(listOf(5L, 4L), ws.walk().map { it.size })
        assertTrue(ws.delete(rel("z.txt"))); assertFalse(ws.delete(rel("z.txt"))); assertFalse("never deletes directories", ws.delete(rel("a")))
        assertTrue(ws.exists(rel("a/b/c.txt")))
    }

    @Test fun listShowsDirectoriesWithNegativeSize() = withEnv { env ->
        val ws = env.project(); ws.writeBytes(rel("src/Main.kt"), "m".toByteArray()); ws.writeBytes(rel("src/util/U.kt"), "uu".toByteArray()); ws.writeBytes(rel("README.md"), "r".toByteArray())
        assertEquals(listOf<Pair<String, Long>>("README.md" to 1L, "src" to -1L), ws.list(null).map { it.path.value to it.size })
        assertEquals(listOf<Pair<String, Long>>("src/Main.kt" to 1L, "src/util" to -1L), ws.list(rel("src")).map { it.path.value to it.size })
        assertEquals(emptyList<EntryInfo>(), ws.list(rel("missing")))
    }

    @Test fun directoriesAreNotReadableOrWritableAsFiles() = withEnv { env ->
        val ws = env.project(); ws.writeBytes(rel("d/x"), "x".toByteArray())
        for (f in listOf({ ws.readBytes(rel("d")) }, { ws.writeBytes(rel("d"), ByteArray(1)) })) try { f(); throw AssertionError("expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }

    @Test fun overwriteIsAtomicAndLeavesNoTemporaryFiles() = withEnv { env ->
        val ws = env.project(); repeat(20) { ws.writeBytes(rel("f.txt"), "v$it".toByteArray()) }
        assertEquals("v19", String(ws.readBytes(rel("f.txt"))!!)); assertEquals(listOf("f.txt"), env.projectDir().list()!!.toList())
    }

    @Test fun symlinksAreNeverListedNeverFollowedOutsideAndNeverWrittenThrough() = withEnv { env ->
        val outside = File(env.base, "outside"); outside.mkdirs(); File(outside, "secret.txt").writeText("TOP-SECRET")
        val ws = env.project(); ws.writeBytes(rel("real.txt"), "real".toByteArray())
        val root = env.projectDir()
        Files.createSymbolicLink(File(root, "link-dir").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "link-file.txt").toPath(), File(outside, "secret.txt").toPath())
        assertEquals("symlinks are not reported", listOf("real.txt"), ws.walk().map { it.path.value })
        for (probe in listOf({ ws.readBytes(rel("link-file.txt")) }, { ws.readBytes(rel("link-dir/secret.txt")) }, { ws.digest(rel("link-dir/secret.txt")) })) {
            val r = try { probe() } catch (_: SecurityException) { null }
            assertTrue("the outside secret must never be returned", r == null)
        }
        for (write in listOf({ ws.writeBytes(rel("link-dir/new.txt"), "pwn".toByteArray()) }, { ws.writeBytes(rel("link-file.txt"), "pwn".toByteArray()) })) try { write() } catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
        assertEquals("TOP-SECRET", File(outside, "secret.txt").readText()); assertFalse(File(outside, "new.txt").exists())
        assertFalse("snapshots do not include links", env.cas.let { ws.createSnapshot(); true } && env.blobFiles().any { String(it.readBytes()) == "TOP-SECRET" })
        assertTrue("deleting a link removes only the link", { ws.projectFileSystem().delete("link-dir", true) }() && File(outside, "secret.txt").exists())
    }

    @Test fun weirdFileNamesFailClosedExceptInsideIgnoredAreas() = withEnv { env ->
        val ws = env.project(); val root = env.projectDir()
        ws.writeBytes(rel("ok.txt"), "ok".toByteArray())
        File(root, "build/out").mkdirs(); File(root, "build/out/bad\nname.bin").writeText("junk")       // ignored area: tolerated
        assertEquals(listOf("ok.txt"), ws.walk().map { it.path.value })
        File(root, "src").mkdirs(); File(root, "src/ev\u0007il.kt").writeText("x")
        try { ws.walk(); throw AssertionError("expected UnsupportedEntryException") } catch (e: UnsupportedEntryException) { assertFalse("message must not echo control characters", e.message!!.any { it.code < 0x20 }) }
        try { ws.createSnapshot(); throw AssertionError("expected UnsupportedEntryException") } catch (_: UnsupportedEntryException) { }
    }

    @Test fun projectFileSystemMountIsTheSameHardenedDelegate() = withEnv { env ->
        val ws = env.project(); ws.projectFileSystem().writeFile("via-mount.txt", "m")
        assertEquals("m", String(ws.readBytes(rel("via-mount.txt"))!!)); assertNotNull(ws.walk().singleOrNull { it.path.value == "via-mount.txt" })
        try { ws.projectFileSystem().writeFile("../escape.txt", "x"); throw AssertionError("host sanitizer must reject traversal") } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidWorkspaceIdsAreRejected() = withEnv { env ->
        for (id in listOf("", "..", ".x", "a/b", "a b", "x".repeat(65))) try { RealWorkspaceFileSystem(id, env.roots.projectRoot("q"), env.cas); throw AssertionError("accepted '$id'") } catch (_: IllegalArgumentException) { }
    }
}

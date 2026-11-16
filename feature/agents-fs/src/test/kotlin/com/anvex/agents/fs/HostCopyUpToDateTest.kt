package com.anvex.agents.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tests of this module run against a COPY of the host RealProjectFileSystem (see build.gradle.kts). This test fails when the
 * copy and the real host file differ, so the tests can never silently validate an outdated implementation.
 */
class HostCopyUpToDateTest {
    @Test fun theTestCopyEqualsTheHostFile() {
        val hostPath = System.getProperty("agents.host.realfs")
        assertTrue("system property agents.host.realfs must point to the host RealProjectFileSystem.kt", hostPath != null && File(hostPath).isFile)
        val copy = File("src/test/kotlin/com/anvex/core/android/fs/RealProjectFileSystem.kt").takeIf { it.isFile }
            ?: File(System.getProperty("agents.fs.main"), "../../test/kotlin/com/anvex/core/android/fs/RealProjectFileSystem.kt")
        assertEquals("update the copy: cp <host file> ${copy.path}", File(hostPath).readText().replace("\r\n", "\n"), copy.readText().replace("\r\n", "\n"))
    }
}

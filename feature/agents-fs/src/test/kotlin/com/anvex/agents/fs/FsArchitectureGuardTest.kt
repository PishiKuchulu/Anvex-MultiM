package com.anvex.agents.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ADR-001/ADR-004 for agents-fs: files are reached only through the injected ProjectFileSystem; no File, Uri, DocumentFile, ContentResolver. */
class FsArchitectureGuardTest {
    companion object {
        private val ALLOWED = listOf("com.anvex.agents.", "com.anvex.core.engine.ProjectFileSystem", "java.util.concurrent.locks.ReentrantLock")
        private val FORBIDDEN = listOf(Regex("""\bjava\.io\b"""), Regex("""\bjava\.nio\b"""), Regex("""\bjava\.net\b"""), Regex("""\bandroid\."""), Regex("""\bandroidx\."""),
            Regex("""\bkotlinx\."""), Regex("""\bcom\.anvex\.core\.android\b"""), Regex("""\bcom\.anvex\.app\b"""), Regex("""\bPathSanitizer\b"""),
            Regex("""\b(File|Files|Uri|URI|URL|DocumentFile|ContentResolver|Context|RealProjectFileSystem|SafProjectFileSystem|ChatSession)\b"""))
        fun strip(src: String) = src.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")
        fun scan(name: String, source: String): List<String> {
            val code = strip(source); val out = ArrayList<String>()
            for (line in code.lines()) { val t = line.trim(); if (t.startsWith("import ")) { val i = t.removePrefix("import ").trim(); if (!i.startsWith("kotlin.") && ALLOWED.none { i.startsWith(it) }) out += "$name: forbidden import '$i'" } }
            for (rx in FORBIDDEN) rx.find(code)?.let { out += "$name: forbidden token '${it.value}'" }
            return out
        }
        fun mainDir(): File = listOfNotNull(System.getProperty("agents.fs.main"), "feature/agents-fs/src/main/kotlin", "src/main/kotlin").map { File(it) }.firstOrNull { it.isDirectory } ?: error("cannot locate agents-fs sources; set -Dagents.fs.main")
    }

    @Test fun agentsFsSourcesAreClean() {
        val files = mainDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList(); assertTrue("scanned ${files.size}", files.size >= 5)
        val v = files.flatMap { scan(it.name, it.readText()) }; assertEquals(v.joinToString("\n"), emptyList<String>(), v)
    }

    @Test fun guardDetectsDeliberateViolations() {
        for (bad in listOf("import java.io.File\nclass X(f: File)", "import android.net.Uri\nval u: Uri? = null", "import com.anvex.core.android.fs.RealProjectFileSystem\nclass Y", "val p = java.nio.file.Paths.get(\"/x\")", "import com.anvex.core.engine.PathSanitizer\nclass Z", "class W(val d: DocumentFile)"))
            assertTrue("should flag: $bad", scan("Bad.kt", bad).isNotEmpty())
        assertTrue(scan("Ok.kt", "import com.anvex.core.engine.ProjectFileSystem\nimport java.util.concurrent.locks.ReentrantLock\n// java.io.File\nclass Ok").isEmpty())
    }
}

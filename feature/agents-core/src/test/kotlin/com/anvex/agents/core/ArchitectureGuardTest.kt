package com.anvex.agents.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Acceptance arch_guard (ADR-001, ADR-004, ADR-009): agents-core must not touch java.io.File, java.nio.file,
 * Uri, DocumentFile, ContentResolver, Android or Anvex host classes. Tests may use File; main sources may not.
 */
class ArchitectureGuardTest {
    companion object {
        private val ALLOWED_IMPORT_PREFIXES = listOf("com.anvex.agents.core.", "java.security.")
        private val FORBIDDEN_TOKENS = listOf(
            Regex("""\bjava\.io\b"""), Regex("""\bjava\.nio\b"""), Regex("""\bjava\.net\b"""), Regex("""\bandroid\.""") , Regex("""\bandroidx\."""),
            Regex("""\bkotlinx\."""), Regex("""\bcom\.anvex\.core\b"""), Regex("""\bcom\.anvex\.app\b"""),
            Regex("""\b(File|Files|Uri|URI|URL|DocumentFile|ContentResolver|Context|ProjectFileSystem|ChatSession)\b"""),
        )

        fun stripComments(src: String): String = src.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")

        /** Returns human readable violations for one source file's text. */
        fun scan(name: String, source: String): List<String> {
            val code = stripComments(source)
            val out = ArrayList<String>()
            for (line in code.lines()) {
                val t = line.trim()
                if (t.startsWith("import ")) {
                    val imp = t.removePrefix("import ").trim()
                    if (!imp.startsWith("kotlin.") && ALLOWED_IMPORT_PREFIXES.none { imp.startsWith(it) }) out += "$name: forbidden import '$imp'"
                }
            }
            for (rx in FORBIDDEN_TOKENS) rx.find(code)?.let { out += "$name: forbidden token '${it.value}'" }
            return out
        }

        fun mainSourceDir(): File {
            val prop = System.getProperty("agents.core.main")
            val candidates = listOfNotNull(prop, "feature/agents-core/src/main/kotlin", "src/main/kotlin", "../../feature/agents-core/src/main/kotlin")
            return candidates.map { File(it) }.firstOrNull { it.isDirectory } ?: error("cannot locate agents-core main sources; set -Dagents.core.main")
        }
    }

    @Test fun agentsCoreSourcesAreClean() {
        val files = mainSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scanned ${files.size} files", files.size >= 10)
        val violations = files.flatMap { scan(it.name, it.readText()) }
        assertEquals("architecture violations:\n" + violations.joinToString("\n"), emptyList<String>(), violations)
    }

    /** Mutation sanity: the guard must fail on deliberate violations (otherwise it proves nothing). */
    @Test fun guardDetectsDeliberateViolations() {
        val bad = mapOf(
            "import java.io.File\nclass X(val f: File)" to 2,
            "import android.net.Uri\nval u: Uri? = null" to 2,
            "import androidx.documentfile.provider.DocumentFile\nclass D(val d: DocumentFile)" to 2,
            "val r = android.content.ContentResolver::class" to 2,
            "import com.anvex.core.engine.ProjectFileSystem\nclass P" to 2,
            "import java.nio.file.Paths\nval p = Paths.get(\"/x\")" to 2,
            "import kotlinx.coroutines.runBlocking" to 2,
            "fun f(x: java.io.File) = x" to 2,
        )
        for ((src, minimum) in bad) assertTrue("should flag: $src", scan("Bad.kt", src).size >= minimum - 1 && scan("Bad.kt", src).isNotEmpty())
        assertTrue("comments are ignored", scan("Ok.kt", "// java.io.File\n/* android.net.Uri */\nclass Ok").isEmpty())
        assertTrue("allowed imports pass", scan("Ok.kt", "import java.security.MessageDigest\nimport com.anvex.agents.core.path.RelPath\nimport kotlin.math.max\nclass Ok").isEmpty())
    }
}

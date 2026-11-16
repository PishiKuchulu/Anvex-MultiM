package com.anvex.agents.core.assurance

import com.anvex.agents.core.domain.CredentialGuard
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.json.Json
import com.anvex.agents.core.json.JsonArray
import com.anvex.agents.core.json.JsonNull
import com.anvex.agents.core.json.JsonNumber
import com.anvex.agents.core.json.JsonObject
import com.anvex.agents.core.json.JsonParseException
import com.anvex.agents.core.json.JsonString
import com.anvex.agents.core.path.RelPath

data class ReviewFinding(val file: RelPath?, val line: Int?, val text: String)
data class ReviewVerdictData(val task: TaskId, val approve: Boolean, val findings: List<ReviewFinding>)

sealed interface VerdictParse {
    data class Valid(val verdict: ReviewVerdictData) : VerdictParse
    data class Invalid(val reasons: List<String>) : VerdictParse
}

/**
 * Parses the reviewer agent's structured verdict (untrusted model output). Free text inside is informational only:
 * it is length-limited and scrubbed of credentials and never influences a decision. Required shape:
 * {"task":"<id>","decision":"approve"|"reject","findings":[{"file":"a/b.kt","line":12,"text":"..."}]}
 * A rejection must carry at least one actionable finding (specification: corrections need actionable explanations).
 */
object VerdictParser {
    const val MAX_FINDINGS = 50
    const val MAX_TEXT = 2000

    fun parse(json: String, expectedTask: TaskId): VerdictParse {
        val root = try { Json.parse(json) } catch (e: JsonParseException) { return VerdictParse.Invalid(listOf("not valid JSON: ${e.message}")) }
        if (root !is JsonObject) return VerdictParse.Invalid(listOf("verdict must be a JSON object"))
        val problems = ArrayList<String>()
        val extra = root.fields.keys - setOf("task", "decision", "findings")
        if (extra.isNotEmpty()) problems += "unknown field(s): ${extra.sorted().joinToString { it.take(40) }}"
        val task = (root.fields["task"] as? JsonString)?.value
        if (task == null) problems += "missing task" else if (task != expectedTask.value) problems += "verdict is for another task"
        val decision = (root.fields["decision"] as? JsonString)?.value
        if (decision != "approve" && decision != "reject") problems += "decision must be \"approve\" or \"reject\""
        val findings = ArrayList<ReviewFinding>()
        when (val f = root.fields["findings"]) {
            null, JsonNull -> Unit
            is JsonArray -> {
                if (f.items.size > MAX_FINDINGS) problems += "too many findings"
                for ((i, item) in f.items.take(MAX_FINDINGS).withIndex()) {
                    if (item !is JsonObject) { problems += "finding $i is not an object"; continue }
                    val file = (item.fields["file"] as? JsonString)?.value?.let { RelPath.parse(it) ?: run { problems += "finding $i has an invalid file path"; null } }
                    val line = (item.fields["line"] as? JsonNumber)?.toLongOrNull()?.takeIf { it in 1..10_000_000 }?.toInt()
                    if (item.fields["line"] != null && item.fields["line"] != JsonNull && line == null) problems += "finding $i has an invalid line"
                    val text = (item.fields["text"] as? JsonString)?.value
                    if (text.isNullOrBlank()) problems += "finding $i has no text" else findings += ReviewFinding(file, line, CredentialGuard.scrub(text).take(MAX_TEXT))
                }
            }
            else -> problems += "findings must be an array"
        }
        if (decision == "reject" && findings.isEmpty() && problems.isEmpty()) problems += "a rejection needs at least one actionable finding"
        if (problems.isNotEmpty()) return VerdictParse.Invalid(problems)
        return VerdictParse.Valid(ReviewVerdictData(expectedTask, decision == "approve", findings))
    }
}

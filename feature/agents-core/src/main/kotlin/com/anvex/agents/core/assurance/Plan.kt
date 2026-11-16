package com.anvex.agents.core.assurance

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.CredentialGuard
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.json.Json
import com.anvex.agents.core.json.JsonArray
import com.anvex.agents.core.json.JsonBool
import com.anvex.agents.core.json.JsonNumber
import com.anvex.agents.core.json.JsonObject
import com.anvex.agents.core.json.JsonParseException
import com.anvex.agents.core.json.JsonString
import com.anvex.agents.core.path.Glob
import com.anvex.agents.core.ports.ContentDigest
import com.anvex.agents.core.ports.Digests

/** Token proving that the USER approved exactly one plan (bound to the plan's digest). */
class UserApproval(val planDigest: ContentDigest)

/**
 * A validated task graph proposed by a manager agent. It is inert: it cannot become tasks without a [UserApproval]
 * for this exact plan, and it can never switch off mandatory review (the manager has no authority to weaken governance).
 */
class PlanProposal internal constructor(private val specs: List<TaskSpec>, val digest: ContentDigest) {
    val tasks: List<TaskSpec> get() = specs
    fun toTaskSpecs(approval: UserApproval): List<TaskSpec> {
        require(approval.planDigest == digest) { "the approval belongs to a different plan" }
        return specs
    }
}

sealed interface PlanParse {
    data class Valid(val plan: PlanProposal) : PlanParse
    data class Invalid(val reasons: List<String>) : PlanParse
}

/**
 * Parses and validates a manager's plan (untrusted). Shape:
 * {"tasks":[{"id":"t1","title":"..","acceptance":"..","deps":["t0"],"claims":["app/&lt;glob&gt;"],"assignees":["agent-id"],"priority":1}]}
 * Dependencies must point to EARLIER tasks (acyclic by construction), assignees must be known agents, claims valid
 * globs. A `requiresReview` field is accepted only as true: `false` makes the whole plan invalid.
 */
object PlanParser {
    const val MAX_TASKS = 50
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")

    fun parse(json: String, knownAgents: Set<AgentId>): PlanParse {
        val root = try { Json.parse(json) } catch (e: JsonParseException) { return PlanParse.Invalid(listOf("not valid JSON: ${e.message}")) }
        if (root !is JsonObject || root.fields.keys != setOf("tasks") || root.fields["tasks"] !is JsonArray) return PlanParse.Invalid(listOf("plan must be {\"tasks\":[...]}"))
        val items = (root.fields["tasks"] as JsonArray).items
        if (items.isEmpty()) return PlanParse.Invalid(listOf("plan has no tasks"))
        if (items.size > MAX_TASKS) return PlanParse.Invalid(listOf("plan has more than $MAX_TASKS tasks"))
        val problems = ArrayList<String>(); val specs = ArrayList<TaskSpec>(); val seen = HashSet<String>()
        val allowed = setOf("id", "title", "acceptance", "deps", "claims", "assignees", "priority", "requiresReview")
        for ((i, item) in items.withIndex()) {
            fun bad(m: String) { problems += "task $i: $m" }
            if (item !is JsonObject) { bad("not an object"); continue }
            (item.fields.keys - allowed).takeIf { it.isNotEmpty() }?.let { bad("unknown field(s): ${it.sorted().joinToString { k -> k.take(30) }}") }
            val id = (item.fields["id"] as? JsonString)?.value
            if (id == null || !ID.matches(id)) { bad("invalid id"); continue }
            if (!seen.add(id)) { bad("duplicate id"); continue }
            val title = (item.fields["title"] as? JsonString)?.value?.trim()
            val acc = (item.fields["acceptance"] as? JsonString)?.value?.trim()
            if (title.isNullOrEmpty() || title.length > 200) bad("title missing or longer than 200 characters")
            if (acc.isNullOrEmpty() || acc.length > 2000) bad("acceptance missing or longer than 2000 characters")
            if ((title != null && CredentialGuard.looksLikeSecretValue(title)) || (acc != null && CredentialGuard.looksLikeSecretValue(acc))) bad("text contains a credential-like value")
            fun strings(key: String, max: Int): List<String>? {
                val v = item.fields[key] ?: return emptyList()
                if (v !is JsonArray || v.items.size > max || v.items.any { it !is JsonString }) { bad("$key must be an array of at most $max strings"); return null }
                return v.items.map { (it as JsonString).value }
            }
            val deps = strings("deps", 20); val claims = strings("claims", 20); val assignees = strings("assignees", 5)
            if (deps != null) for (d in deps) if (d !in seen || d == id) bad("dependency '${d.take(40)}' does not refer to an earlier task")
            if (claims != null) for (c in claims) if (!Glob.isValid(c)) bad("invalid claim pattern '${c.take(60)}'")
            if (assignees != null) {
                if (assignees.isEmpty()) bad("no assignee")
                for (a in assignees) if (AgentIdOrNull(a) !in knownAgents) bad("unknown agent '${a.take(40)}'")
                if (assignees.toSet().size != assignees.size) bad("duplicate assignee")
            }
            val prio = (item.fields["priority"] as? JsonNumber)?.toLongOrNull()
            if (item.fields["priority"] != null && (prio == null || prio !in -100..100)) bad("priority must be an integer between -100 and 100")
            when (val rr = item.fields["requiresReview"]) { null -> Unit; is JsonBool -> if (!rr.value) bad("a plan may not disable mandatory review"); else -> bad("requiresReview must be a boolean") }
            if (problems.isEmpty() && title != null && acc != null && deps != null && claims != null && assignees != null)
                specs += TaskSpec(TaskId(id), title, acc, deps.map { TaskId(it) }.toSet(), claims, assignees.map { AgentId(it) }, (prio ?: 0).toInt(), requiresReview = true)
        }
        if (problems.isNotEmpty()) return PlanParse.Invalid(problems.distinct())
        val digest = Digests.sha256(Json.write(JsonArray(specs.map { s ->
            JsonObject(linkedMapOf("id" to JsonString(s.id.value), "title" to JsonString(s.title), "acceptance" to JsonString(s.acceptance),
                "deps" to JsonArray(s.deps.map { JsonString(it.value) }.sortedBy { it.value }), "claims" to JsonArray(s.claims.map { JsonString(it) }),
                "assignees" to JsonArray(s.assignees.map { JsonString(it.value) }), "priority" to JsonNumber(s.priority.toString())))
        })).toByteArray(Charsets.UTF_8))
        return PlanParse.Valid(PlanProposal(specs, digest))
    }

    private fun AgentIdOrNull(s: String): AgentId? = if (s.isBlank()) null else AgentId(s)
}

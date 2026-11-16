package com.anvex.agents.core.events

import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.json.Json
import com.anvex.agents.core.json.JsonArray
import com.anvex.agents.core.json.JsonBool
import com.anvex.agents.core.json.JsonNull
import com.anvex.agents.core.json.JsonNumber
import com.anvex.agents.core.json.JsonObject
import com.anvex.agents.core.json.JsonString
import com.anvex.agents.core.json.JsonValue

class EventCodecException(message: String) : RuntimeException(message)

/**
 * Canonical, versioned JSON form of [SchedulerEvent]. It is what the event log hashes and what persistence stores, so
 * the hash chain does not depend on data-class toString. Decoding is strict (exact key sets, known types) because
 * stored events are read back from a database and treated as untrusted until the chain verifies.
 */
object EventCodec {
    fun encode(e: SchedulerEvent): String = Json.write(toJson(e))

    fun decode(text: String): SchedulerEvent {
        val root = try { Json.parse(text) } catch (x: com.anvex.agents.core.json.JsonParseException) { throw EventCodecException("not JSON: ${x.message}") }
        return fromJson(root as? JsonObject ?: throw EventCodecException("event must be an object"))
    }

    private fun n(v: Long) = JsonNumber(v.toString())
    private fun s(v: String) = JsonString(v)
    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    private fun toJson(e: SchedulerEvent): JsonObject = when (e) {
        is SchedulerEvent.TaskAdded -> obj("t" to s("TaskAdded"), "at" to n(e.at), "spec" to spec(e.spec))
        is SchedulerEvent.Kick -> obj("t" to s("Kick"), "at" to n(e.at))
        is SchedulerEvent.AgentStarted -> obj("t" to s("AgentStarted"), "at" to n(e.at), "task" to s(e.task.value), "agent" to s(e.agent.value))
        is SchedulerEvent.AgentFinished -> obj("t" to s("AgentFinished"), "at" to n(e.at), "task" to s(e.task.value), "agent" to s(e.agent.value))
        is SchedulerEvent.AgentFailed -> obj("t" to s("AgentFailed"), "at" to n(e.at), "task" to s(e.task.value), "agent" to s(e.agent.value), "kind" to s(e.kind.name), "reason" to s(e.reason))
        is SchedulerEvent.UsageReported -> obj("t" to s("UsageReported"), "at" to n(e.at), "task" to (e.task?.let { s(it.value) } ?: JsonNull), "agent" to s(e.agent.value),
            "in" to n(e.inputTokens), "out" to n(e.outputTokens), "cost" to (e.costUsd?.let { JsonNumber(it.toString()) } ?: JsonNull))
        is SchedulerEvent.ReviewVerdict -> obj("t" to s("ReviewVerdict"), "at" to n(e.at), "task" to s(e.task.value), "reviewer" to reviewer(e.reviewer), "approved" to JsonBool(e.approved), "channel" to s(e.channel.name))
        is SchedulerEvent.UserChoseResult -> obj("t" to s("UserChoseResult"), "at" to n(e.at), "task" to s(e.task.value), "agent" to s(e.agent.value))
        is SchedulerEvent.ApplyResult -> obj("t" to s("ApplyResult"), "at" to n(e.at), "task" to s(e.task.value), "outcome" to outcome(e.outcome))
        is SchedulerEvent.UserDecision -> obj("t" to s("UserDecision"), "at" to n(e.at), "task" to s(e.task.value), "action" to s(e.action.name))
        is SchedulerEvent.PauseAgent -> obj("t" to s("PauseAgent"), "at" to n(e.at), "agent" to s(e.agent.value))
        is SchedulerEvent.ResumeAgent -> obj("t" to s("ResumeAgent"), "at" to n(e.at), "agent" to s(e.agent.value))
        is SchedulerEvent.StopAgent -> obj("t" to s("StopAgent"), "at" to n(e.at), "agent" to s(e.agent.value))
        is SchedulerEvent.CancelTask -> obj("t" to s("CancelTask"), "at" to n(e.at), "task" to s(e.task.value))
        is SchedulerEvent.CancelAll -> obj("t" to s("CancelAll"), "at" to n(e.at))
        is SchedulerEvent.RetryTask -> obj("t" to s("RetryTask"), "at" to n(e.at), "task" to s(e.task.value))
        is SchedulerEvent.Requeue -> obj("t" to s("Requeue"), "at" to n(e.at), "task" to s(e.task.value), "reason" to s(e.reason))
        is SchedulerEvent.SetMaxConcurrent -> obj("t" to s("SetMaxConcurrent"), "at" to n(e.at), "value" to n(e.value.toLong()))
        is SchedulerEvent.RaiseBudget -> obj("t" to s("RaiseBudget"), "at" to n(e.at), "maxTokens" to (e.limits.maxTokens?.let { n(it) } ?: JsonNull), "maxCostUsd" to (e.limits.maxCostUsd?.let { JsonNumber(it.toString()) } ?: JsonNull))
    }

    private fun spec(t: TaskSpec) = obj("id" to s(t.id.value), "title" to s(t.title), "acceptance" to s(t.acceptance), "deps" to JsonArray(t.deps.map { s(it.value) }.sortedBy { it.value }),
        "claims" to JsonArray(t.claims.map { s(it) }), "assignees" to JsonArray(t.assignees.map { s(it.value) }), "priority" to n(t.priority.toLong()), "requiresReview" to JsonBool(t.requiresReview))
    private fun reviewer(r: Reviewer) = when (r) { is Reviewer.User -> obj("type" to s("user")); is Reviewer.Agent -> obj("type" to s("agent"), "id" to s(r.id.value)) }
    private fun outcome(o: ApplyOutcome) = when (o) {
        is ApplyOutcome.Applied -> obj("type" to s("applied")); is ApplyOutcome.Escalated -> obj("type" to s("escalated"))
        is ApplyOutcome.Conflicts -> obj("type" to s("conflicts"), "count" to n(o.count.toLong())); is ApplyOutcome.Rejected -> obj("type" to s("rejected"), "reason" to s(o.reason))
    }

    // ------------------------------------------------------------------ decoding
    private fun bad(m: String): Nothing = throw EventCodecException(m)
    private fun JsonObject.exactly(vararg keys: String): JsonObject { if (fields.keys != keys.toSet()) bad("unexpected fields ${fields.keys.sorted()} (expected ${keys.sorted()})"); return this }
    private fun JsonObject.str(k: String): String = (fields[k] as? JsonString)?.value ?: bad("'$k' must be a string")
    private fun JsonObject.lng(k: String): Long = (fields[k] as? JsonNumber)?.toLongOrNull() ?: bad("'$k' must be an integer")
    private fun JsonObject.bool(k: String): Boolean = (fields[k] as? JsonBool)?.value ?: bad("'$k' must be a boolean")
    private fun JsonObject.opt(k: String): JsonValue? = fields[k]?.takeIf { it != JsonNull }
    private fun JsonObject.strs(k: String): List<String> = ((fields[k] as? JsonArray) ?: bad("'$k' must be an array")).items.map { (it as? JsonString)?.value ?: bad("'$k' must contain strings") }
    private inline fun <T> guard(block: () -> T): T = try { block() } catch (x: IllegalArgumentException) { throw EventCodecException("invalid value: ${x.message}") }
    private inline fun <reified E : Enum<E>> enum(name: String): E = enumValues<E>().firstOrNull { it.name == name } ?: bad("unknown ${E::class.simpleName} '$name'")

    private fun fromJson(o: JsonObject): SchedulerEvent {
        val type = o.str("t"); val at = o.lng("at")
        fun task() = TaskId(o.str("task"))
        fun agent() = AgentId(o.str("agent"))
        return guard {
            when (type) {
                "TaskAdded" -> { o.exactly("t", "at", "spec"); SchedulerEvent.TaskAdded(at, decodeSpec(o.fields["spec"] as? JsonObject ?: bad("spec must be an object"))) }
                "Kick" -> { o.exactly("t", "at"); SchedulerEvent.Kick(at) }
                "AgentStarted" -> { o.exactly("t", "at", "task", "agent"); SchedulerEvent.AgentStarted(at, task(), agent()) }
                "AgentFinished" -> { o.exactly("t", "at", "task", "agent"); SchedulerEvent.AgentFinished(at, task(), agent()) }
                "AgentFailed" -> { o.exactly("t", "at", "task", "agent", "kind", "reason"); SchedulerEvent.AgentFailed(at, task(), agent(), enum<FailureKind>(o.str("kind")), o.str("reason")) }
                "UsageReported" -> { o.exactly("t", "at", "task", "agent", "in", "out", "cost")
                    SchedulerEvent.UsageReported(at, o.opt("task")?.let { TaskId((it as? JsonString)?.value ?: bad("task must be a string")) }, agent(), o.lng("in"), o.lng("out"),
                        o.opt("cost")?.let { (it as? JsonNumber)?.toDouble() ?: bad("cost must be a number") }) }
                "ReviewVerdict" -> { o.exactly("t", "at", "task", "reviewer", "approved", "channel"); SchedulerEvent.ReviewVerdict(at, task(), decodeReviewer(o.fields["reviewer"] as? JsonObject ?: bad("reviewer must be an object")), o.bool("approved"), enum<VerdictChannel>(o.str("channel"))) }
                "UserChoseResult" -> { o.exactly("t", "at", "task", "agent"); SchedulerEvent.UserChoseResult(at, task(), agent()) }
                "ApplyResult" -> { o.exactly("t", "at", "task", "outcome"); SchedulerEvent.ApplyResult(at, task(), decodeOutcome(o.fields["outcome"] as? JsonObject ?: bad("outcome must be an object"))) }
                "UserDecision" -> { o.exactly("t", "at", "task", "action"); SchedulerEvent.UserDecision(at, task(), enum<UserAction>(o.str("action"))) }
                "PauseAgent" -> { o.exactly("t", "at", "agent"); SchedulerEvent.PauseAgent(at, agent()) }
                "ResumeAgent" -> { o.exactly("t", "at", "agent"); SchedulerEvent.ResumeAgent(at, agent()) }
                "StopAgent" -> { o.exactly("t", "at", "agent"); SchedulerEvent.StopAgent(at, agent()) }
                "CancelTask" -> { o.exactly("t", "at", "task"); SchedulerEvent.CancelTask(at, task()) }
                "CancelAll" -> { o.exactly("t", "at"); SchedulerEvent.CancelAll(at) }
                "RetryTask" -> { o.exactly("t", "at", "task"); SchedulerEvent.RetryTask(at, task()) }
                "Requeue" -> { o.exactly("t", "at", "task", "reason"); SchedulerEvent.Requeue(at, task(), o.str("reason")) }
                "SetMaxConcurrent" -> { o.exactly("t", "at", "value"); SchedulerEvent.SetMaxConcurrent(at, o.lng("value").also { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) bad("value out of range") }.toInt()) }
                "RaiseBudget" -> { o.exactly("t", "at", "maxTokens", "maxCostUsd")
                    SchedulerEvent.RaiseBudget(at, BudgetLimits(o.opt("maxTokens")?.let { (it as? JsonNumber)?.toLongOrNull() ?: bad("maxTokens must be an integer") }, o.opt("maxCostUsd")?.let { (it as? JsonNumber)?.toDouble() ?: bad("maxCostUsd must be a number") })) }
                else -> bad("unknown event type '${type.take(40)}'")
            }
        }
    }

    private fun decodeSpec(o: JsonObject): TaskSpec {
        o.exactly("id", "title", "acceptance", "deps", "claims", "assignees", "priority", "requiresReview")
        val prio = o.lng("priority"); if (prio !in Int.MIN_VALUE..Int.MAX_VALUE) bad("priority out of range")
        return TaskSpec(TaskId(o.str("id")), o.str("title"), o.str("acceptance"), o.strs("deps").map { TaskId(it) }.toSet(), o.strs("claims"), o.strs("assignees").map { AgentId(it) }, prio.toInt(), o.bool("requiresReview"))
    }
    private fun decodeReviewer(o: JsonObject): Reviewer = when (o.str("type")) {
        "user" -> { o.exactly("type"); Reviewer.User }
        "agent" -> { o.exactly("type", "id"); Reviewer.Agent(AgentId(o.str("id"))) }
        else -> bad("unknown reviewer type")
    }
    private fun decodeOutcome(o: JsonObject): ApplyOutcome = when (o.str("type")) {
        "applied" -> { o.exactly("type"); ApplyOutcome.Applied }
        "escalated" -> { o.exactly("type"); ApplyOutcome.Escalated }
        "conflicts" -> { o.exactly("type", "count"); ApplyOutcome.Conflicts(o.lng("count").also { if (it !in 0..Int.MAX_VALUE) bad("count out of range") }.toInt()) }
        "rejected" -> { o.exactly("type", "reason"); ApplyOutcome.Rejected(o.str("reason")) }
        else -> bad("unknown outcome type")
    }
}

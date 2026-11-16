package com.anvex.agents.core.domain

import com.anvex.agents.core.json.Json
import com.anvex.agents.core.json.JsonArray
import com.anvex.agents.core.json.JsonBool
import com.anvex.agents.core.json.JsonNull
import com.anvex.agents.core.json.JsonNumber
import com.anvex.agents.core.json.JsonObject
import com.anvex.agents.core.json.JsonParseException
import com.anvex.agents.core.json.JsonString
import com.anvex.agents.core.json.JsonValue

class ProfileCodecException(message: String) : RuntimeException(message)

/** Strict JSON form of [AgentProfile] for persistence. Stored data is untrusted: decoding re-runs every profile validation. */
object ProfileCodec {
    fun encode(p: AgentProfile): String = Json.write(JsonObject(linkedMapOf(
        "id" to JsonString(p.id.value), "name" to JsonString(p.name), "role" to JsonString(p.role), "providerRef" to JsonString(p.providerRef), "model" to JsonString(p.model),
        "temperature" to (p.temperature?.let { JsonNumber(it.toString()) } ?: JsonNull),
        "params" to JsonObject(LinkedHashMap<String, JsonValue>().also { m -> p.params.toSortedMap().forEach { (k, v) -> m[k] = JsonString(v) } }),
        "grant" to JsonObject(linkedMapOf("read" to strs(p.grant.read), "write" to strs(p.grant.write), "execute" to JsonBool(p.grant.execute), "approve" to JsonBool(p.grant.approve))),
        "limits" to JsonObject(linkedMapOf("maxTurns" to JsonNumber(p.limits.maxTurns.toString()), "maxTokens" to JsonNumber(p.limits.maxTokens.toString()), "timeoutMs" to JsonNumber(p.limits.timeoutMs.toString()))),
        "capabilities" to strs(p.capabilities.sorted()),
    )))

    fun decode(text: String): AgentProfile {
        try {
            val o = Json.parse(text) as? JsonObject ?: bad("profile must be an object")
            exactly(o, "id", "name", "role", "providerRef", "model", "temperature", "params", "grant", "limits", "capabilities")
            val g = o.fields["grant"] as? JsonObject ?: bad("grant must be an object"); exactly(g, "read", "write", "execute", "approve")
            val l = o.fields["limits"] as? JsonObject ?: bad("limits must be an object"); exactly(l, "maxTurns", "maxTokens", "timeoutMs")
            val params = (o.fields["params"] as? JsonObject ?: bad("params must be an object")).fields.mapValues { (it.value as? JsonString)?.value ?: bad("params values must be strings") }
            val temp = o.fields["temperature"].let { if (it == JsonNull) null else (it as? JsonNumber)?.toDouble() ?: bad("temperature must be a number or null") }
            return AgentProfile(
                AgentId(str(o, "id")), str(o, "name"), str(o, "role"), str(o, "providerRef"), str(o, "model"), temp, params,
                Grant(strList(g, "read"), strList(g, "write"), bool(g, "execute"), bool(g, "approve")),
                AgentLimits(int(l, "maxTurns"), lng(l, "maxTokens"), lng(l, "timeoutMs")), strList(o, "capabilities").toSet(),
            )
        } catch (e: JsonParseException) { bad("not JSON: ${e.message}") } catch (e: IllegalArgumentException) { bad("invalid profile: ${e.message}") }
    }

    private fun strs(l: List<String>) = JsonArray(l.map { JsonString(it) })
    private fun bad(m: String): Nothing = throw ProfileCodecException(m)
    private fun exactly(o: JsonObject, vararg k: String) { if (o.fields.keys != k.toSet()) bad("unexpected fields ${o.fields.keys.sorted()}") }
    private fun str(o: JsonObject, k: String) = (o.fields[k] as? JsonString)?.value ?: bad("'$k' must be a string")
    private fun bool(o: JsonObject, k: String) = (o.fields[k] as? JsonBool)?.value ?: bad("'$k' must be a boolean")
    private fun lng(o: JsonObject, k: String) = (o.fields[k] as? JsonNumber)?.toLongOrNull() ?: bad("'$k' must be an integer")
    private fun int(o: JsonObject, k: String): Int { val v = lng(o, k); if (v !in Int.MIN_VALUE..Int.MAX_VALUE) bad("'$k' out of range"); return v.toInt() }
    private fun strList(o: JsonObject, k: String) = ((o.fields[k] as? JsonArray) ?: bad("'$k' must be an array")).items.map { (it as? JsonString)?.value ?: bad("'$k' must contain strings") }
}

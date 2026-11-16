package com.anvex.agents.core

import com.anvex.agents.core.assurance.ModelText
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentLimits
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.Grant
import com.anvex.agents.core.domain.ProfileCodec
import com.anvex.agents.core.domain.ProfileCodecException
import com.anvex.agents.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceCodecsTest {
    private val full = AgentProfile(AgentId("dev-1"), "Dev ünï", "Implement tasks.\nBe careful \"quoted\".", "prov-1", "model-x", 0.2, mapOf("top_p" to "0.9", "max_tokens" to "4000"),
        Grant(listOf("src/**", "docs/**"), listOf("src/**"), execute = true, approve = false), AgentLimits(12, 50_000, 120_000), setOf("code", "kotlin"))

    @Test fun profilesRoundTrip() {
        assertEquals(full, ProfileCodec.decode(ProfileCodec.encode(full)))
        val minimal = AgentProfile(AgentId("a"), "A", "r", "p", "m"); assertEquals(minimal, ProfileCodec.decode(ProfileCodec.encode(minimal)))
        assertEquals("canonical output is stable", ProfileCodec.encode(full), ProfileCodec.encode(ProfileCodec.decode(ProfileCodec.encode(full))))
    }

    @Test fun decodingStoredProfilesIsStrictAndRevalidates() {
        val good = ProfileCodec.encode(full)
        val bad = listOf("", "[]", "{}", good.replace("\"id\":\"dev-1\"", "\"id\":\"\""), good.replace("\"temperature\":0.2", "\"temperature\":9"), good.replace("\"maxTurns\":12", "\"maxTurns\":0"),
            good.replace("\"top_p\":\"0.9\"", "\"apiKey\":\"x\""), good.replace("\"top_p\":\"0.9\"", "\"note\":\"sk-abcdefghijklmnopqrstuvwxyz\""), good.dropLast(1) + ",\"admin\":true}",
            good.replace("\"read\":[\"src/**\",\"docs/**\"]", "\"read\":[\"../etc\"]"), good.replace("\"execute\":true", "\"execute\":\"yes\""), good.replace("\"maxTokens\":50000", "\"maxTokens\":5.5"))
        for (b in bad) try { ProfileCodec.decode(b); throw AssertionError("accepted: ${b.take(80)}") } catch (_: ProfileCodecException) { }
    }

    @Test fun jsonIsExtractedFromTypicalModelText() {
        assertEquals("""{"a":1}""", ModelText.extractJsonObject("""{"a":1}"""))
        assertEquals("""{"task":"t","decision":"approve"}""", ModelText.extractJsonObject("Here is my verdict:\n```json\n{\"task\":\"t\",\"decision\":\"approve\"}\n```\nThanks!"))
        assertEquals("""{"a":{"b":[1,2,{"c":"}"}]}}""", ModelText.extractJsonObject("""prefix {"a":{"b":[1,2,{"c":"}"}]}} suffix {"x":1}"""))
        assertEquals("""{"s":"quote \" and brace {"}""", ModelText.extractJsonObject("""text {"s":"quote \" and brace {"} tail"""))
        assertEquals("""{"in":"fence"}""", ModelText.extractJsonObject("noise {\"outside\":true}\n```JSON \n{\"in\":\"fence\"}\n```"))
        assertEquals("""{"unterminated fence":1}""", ModelText.extractJsonObject("```json\n{\"unterminated fence\":1}\n"))
    }

    @Test fun noBalancedObjectMeansNull() {
        for (t in listOf("", "no json here", "{", "{\"a\":1", "} {", "```json\n```", "{\"s\":\"unclosed}")) assertNull("text: $t", ModelText.extractJsonObject(t))
        assertNull(ModelText.extractJsonObject("{".repeat(10) + "x"))
        assertNull("over the size limit", ModelText.extractJsonObject("{\"a\":\"" + "x".repeat(ModelText.MAX_LENGTH) + "\"}"))
    }

    @Test fun extractedTextStillHasToPassTheStrictParser() {
        val extracted = ModelText.extractJsonObject("""leading {"a":1,"a":2} trailing""")!!
        try { Json.parse(extracted); throw AssertionError("duplicate keys must still be rejected") } catch (_: com.anvex.agents.core.json.JsonParseException) { }
        assertTrue(ModelText.extractJsonObject("""{'a':1}""") != null)   // extraction is only a locator, not a validator
    }
}

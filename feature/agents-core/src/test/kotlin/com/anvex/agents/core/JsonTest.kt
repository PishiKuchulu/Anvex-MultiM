package com.anvex.agents.core

import com.anvex.agents.core.json.Json
import com.anvex.agents.core.json.JsonArray
import com.anvex.agents.core.json.JsonBool
import com.anvex.agents.core.json.JsonNull
import com.anvex.agents.core.json.JsonNumber
import com.anvex.agents.core.json.JsonObject
import com.anvex.agents.core.json.JsonParseException
import com.anvex.agents.core.json.JsonString
import com.anvex.agents.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class JsonTest {
    private fun bad(text: String, why: String = text.take(30)) { try { Json.parse(text); throw AssertionError("accepted invalid JSON: $why") } catch (_: JsonParseException) { } }

    @Test fun validDocuments() {
        assertEquals(JsonNull, Json.parse("null")); assertEquals(JsonBool(true), Json.parse(" true ")); assertEquals(JsonBool(false), Json.parse("false"))
        assertEquals(JsonNumber("-0"), Json.parse("-0")); assertEquals(JsonNumber("1.5e10"), Json.parse("1.5e10")); assertEquals(JsonNumber("0.25"), Json.parse("0.25")); assertEquals(JsonNumber("-1E+2"), Json.parse("-1E+2"))
        assertEquals(JsonArray(emptyList()), Json.parse("[]")); assertEquals(JsonObject(emptyMap()), Json.parse("{}"))
        assertEquals(JsonObject(linkedMapOf("a" to JsonArray(listOf(JsonNumber("1"), JsonString("x"), JsonNull)), "b" to JsonObject(linkedMapOf("c" to JsonBool(true))))), Json.parse("\t{ \"a\" : [ 1 ,\"x\",null ] ,\n\"b\":{\"c\":true}}\r\n"))
        assertEquals("a\"b\\c/d\b\u000c\n\r\tÿ😀", (Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u00ff\\ud83d\\ude00\"") as JsonString).value)
        assertEquals("日本語 😀", (Json.parse("\"日本語 😀\"") as JsonString).value)
    }

    @Test fun invalidDocuments() {
        for (t in listOf("", " ", "nul", "tru", "nulll", "True", "01", "+1", "1.", ".5", "1e", "1e+", "-", "--1", "0x10", "[1,]", "[,1]", "[1 2]", "[", "]", "{", "}", "{\"a\":1,}", "{a:1}", "{'a':1}", "{\"a\" 1}", "{\"a\":}", "{1:2}",
            "\"abc", "\"a\nb\"", "\"a\tb\"", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800\\u0041\"", "\"\\ud800x\"", "NaN", "Infinity", "-Infinity", "[1]x", "1 2", "// c\n1", "/* c */ 1", "#1", "{\"a\":1}{\"b\":2}",
            "\u00A01", "\uFEFF1", "{\"a\":1,\"a\":2}", "\"\uD800\"", "[\"\uDC00\"]", "\"\\u00e9\\u\""))
            bad(t)
    }

    @Test fun nestingDepthBoundaryIsExact() {
        fun nested(n: Int) = "[".repeat(n) + "]".repeat(n)
        Json.parse(nested(33)); bad(nested(34), "34 levels"); bad("{\"a\":".repeat(40) + "1" + "}".repeat(40), "deep objects")
    }

    @Test fun entryCountBoundaryIsExact() {
        Json.parse("[" + (1..20_000).joinToString(",") { "0" } + "]"); bad("[" + (1..20_001).joinToString(",") { "0" } + "]", "20001 entries")
    }

    @Test fun lengthLimit() { bad("\"" + "a".repeat(Json.MAX_LENGTH) + "\"", "over MAX_LENGTH"); Json.parse("\"" + "a".repeat(Json.MAX_LENGTH - 2) + "\"") }

    @Test fun duplicateKeysAreRejectedEvenWhenValuesMatch() { bad("{\"k\":1,\"k\":1}"); bad("{\"a\":{\"k\":1,\"k\":2}}") }

    @Test fun numbersConvert() {
        assertEquals(12L, (Json.parse("12") as JsonNumber).toLongOrNull()); assertNull((Json.parse("1.0") as JsonNumber).toLongOrNull()); assertNull((Json.parse("1e2") as JsonNumber).toLongOrNull())
        assertNull((Json.parse("99999999999999999999") as JsonNumber).toLongOrNull()); assertEquals(1.5, (Json.parse("1.5") as JsonNumber).toDouble(), 0.0)
    }

    private fun randomValue(rnd: Random, depth: Int): JsonValue = when (if (depth > 3) rnd.nextInt(5) else rnd.nextInt(7)) {
        0 -> JsonNull; 1 -> JsonBool(rnd.nextBoolean()); 2 -> JsonNumber(listOf("0", "-1", "42", "3.14", "-2.5e-3", "1E10", "100000000000000000000")[rnd.nextInt(7)])
        3, 4 -> JsonString(randomString(rnd))
        5 -> JsonArray((0 until rnd.nextInt(4)).map { randomValue(rnd, depth + 1) })
        else -> JsonObject(LinkedHashMap<String, JsonValue>().also { m -> repeat(rnd.nextInt(4)) { m["k${rnd.nextInt(6)}" + safePrefix(randomString(rnd), 3)] = randomValue(rnd, depth + 1) } })
    }
    /** Prefix that never cuts a surrogate pair in half. */
    private fun safePrefix(s: String, n: Int): String { var t = s.take(n); if (t.isNotEmpty() && Character.isHighSurrogate(t.last())) t = t.dropLast(1); return t }
    private fun randomString(rnd: Random): String { val sb = StringBuilder(); repeat(rnd.nextInt(12)) { when (rnd.nextInt(6)) { 0 -> sb.append((rnd.nextInt(0x20)).toChar()); 1 -> sb.appendCodePoint(0x1F600 + rnd.nextInt(50)); 2 -> sb.append("\"\\/"[rnd.nextInt(3)]); 3 -> sb.append((0x3040 + rnd.nextInt(80)).toChar()); else -> sb.append((32 + rnd.nextInt(95)).toChar()) } }; return sb.toString() }

    @Test fun writeThenParseIsTheIdentity() {
        val rnd = Random(12); repeat(3000) { val v = randomValue(rnd, 0); assertEquals(v, Json.parse(Json.write(v))) }
    }

    /** Mutated documents must either parse (and then be stable) or fail with JsonParseException: never any other exception. */
    @Test fun fuzzedInputNeverThrowsAnythingElse() {
        val noise = "{}[]\",:\\ \nu0-.eE\u0000\uD800x"
        val rnd = Random(31); var ok = 0; var rejected = 0
        repeat(40_000) {
            val doc = Json.write(randomValue(rnd, 0)).toCharArray()
            repeat(1 + rnd.nextInt(4)) { if (doc.isNotEmpty()) doc[rnd.nextInt(doc.size)] = noise[rnd.nextInt(noise.length)] }
            val text = String(doc)
            try { val v = Json.parse(text); ok++; assertEquals(v, Json.parse(Json.write(v))) } catch (_: JsonParseException) { rejected++ }
        }
        assertTrue("both outcomes exercised ok=$ok rejected=$rejected", ok > 500 && rejected > 5000)
    }
}

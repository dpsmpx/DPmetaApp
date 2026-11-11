package com.dpsmpx.dpai.core.serialization

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class JsonTest {
    private val parser = JsonParser()

    private fun ok(text: String): JsonValue = assertIs<Outcome.Success<JsonValue>>(parser.parse(text)).value

    private fun code(text: String, p: JsonParser = parser): DiagCode =
        assertIs<Outcome.Failure>(p.parse(text)).errors.single().code

    @Test
    fun parsesValues() {
        assertEquals(JsonObject.of("a" to json(1), "b" to JsonArray(listOf(JsonBool(true), JsonNull))), ok("""{"a":1,"b":[true,null]}"""))
        assertEquals(json("é\n\"\u0001😀"), ok("\"\\u00e9\\n\\\"\\u0001\\ud83d\\ude00\""))
        assertEquals(json("😀"), ok("\"😀\""))
        assertEquals(JsonNumber("-0.5e+3"), ok(" -0.5e+3 "))
    }

    @Test
    fun strictnessRejectsWhatLenientParsersAccept() {
        for (bad in listOf(
            "{\"a\":1,}", "[1,]", "{a:1}", "'x'", "01", "1.", ".5", "+1", "NaN", "Infinity", "[1 2]",
            "{\"a\" 1}", "\"\t\"", "\"\\x\"", "\"\\ud83d\"", "\"\\ude00\"", "tru", "nul", "{} {}", "// c\n1",
            "\"abc",
        )) {
            assertEquals(DiagCode.JSON_SYNTAX, code(bad), bad)
        }
    }

    @Test
    fun duplicateKeyIsAnErrorWithPath() {
        val f = assertIs<Outcome.Failure>(parser.parse("""{"a":{"b":1,"b":2}}"""))
        assertEquals(DiagCode.JSON_DUPLICATE_KEY, f.errors.single().code)
        assertEquals("$.a.b", f.errors.single().subject)
    }

    @Test
    fun duplicatePathKeepsArrayIndicesAndEscapedFieldNames() {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        val text = File(root, "tests/data/json/nested-duplicate-path.json").readText()
        val failure = assertIs<Outcome.Failure>(parser.parse(text))
        assertEquals(DiagCode.JSON_DUPLICATE_KEY, failure.errors.single().code)
        assertEquals("$[\"a.b\"][1][\"quote\\\"key\"].x", failure.errors.single().subject)
    }

    @Test
    fun largeArrayUnderLongFieldDoesNotCopyPathForEveryItem() {
        val key = "field".repeat(30_000)
        val text = "{\"$key\":[" + List(100_000) { "0" }.joinToString(",") + "]}"
        val start = Clock.SYSTEM.monotonicNanos()
        val value = ok(text) as JsonObject
        val elapsed = Clock.SYSTEM.monotonicNanos() - start
        assertEquals(100_000, (value.fields[key] as JsonArray).items.size)
        assertTrue(elapsed < 2_000_000_000L, "разбор занял ${elapsed / 1_000_000} мс")
    }

    @Test
    fun limitsAreEnforcedBeforeAndDuringParse() {
        val small = JsonParser(JsonParser.Limits(maxChars = 10, maxDepth = 3))
        assertEquals(DiagCode.JSON_TOO_LARGE, code("[1,2,3,4,5,6]", small))
        assertEquals(DiagCode.JSON_TOO_DEEP, code("[[[[1]]]]", small))
        assertEquals(DiagCode.JSON_EMPTY, code("   "))
        assertEquals(DiagCode.JSON_NUMBER_OUT_OF_RANGE, code("1e99999"))
    }

    @Test
    fun errorNamesPlaceButDoesNotQuoteInput() {
        val secret = "hunter2-" + "x".repeat(10)
        val f = assertIs<Outcome.Failure>(parser.parse("{\n  \"k\": $secret\n}"))
        val d = f.errors.single()
        assertEquals("строка 2, столбец 8", d.subject)
        assertFalse(d.render().contains(secret))
    }

    @Test
    fun numbersCompareByValueAndConvertExactly() {
        assertEquals(JsonNumber("1"), JsonNumber("1.0"))
        assertEquals(JsonNumber("100"), JsonNumber("1e2"))
        assertEquals(5L, JsonNumber("5.00").toLongExact())
        assertNull(JsonNumber("5.5").toLongExact())
        assertNull(JsonNumber("1e30").toLongExact())
    }

    @Test
    fun fieldsReportPathsAndUnknownFields() {
        val diags = DiagnosticCollector()
        val root = JsonFields.root(ok("""{"name":"x","count":"7","extra":true,"nested":{"id":5}}"""), diags)!!
        assertEquals("x", root.string("name"))
        assertNull(root.long("count"))
        assertNull(root.string("missing"))
        assertEquals(5L, root.obj("nested")!!.long("id"))
        root.finish()
        assertEquals(
            listOf(DiagCode.FIELD_TYPE to "$.count", DiagCode.FIELD_MISSING to "$.missing", DiagCode.FIELD_UNKNOWN to "$.extra"),
            diags.all.map { it.code to it.subject },
        )
    }

    @Test
    fun formatHeaderVersions() {
        fun read(text: String): Pair<Int?, List<DiagCode>> {
            val diags = DiagnosticCollector()
            val f = JsonFields.root(ok(text), diags)!!
            return FormatHeader.read(f, "dpai.test", 2..3, diags) to diags.all.map { it.code }
        }
        assertEquals(3 to emptyList(), read("""{"format":"dpai.test","version":3}"""))
        assertEquals(null to listOf(DiagCode.FORMAT_VERSION_NEWER), read("""{"format":"dpai.test","version":4}"""))
        assertEquals(null to listOf(DiagCode.FORMAT_VERSION_UNSUPPORTED), read("""{"format":"dpai.test","version":1}"""))
        assertEquals(null to listOf(DiagCode.FORMAT_VERSION_MISSING), read("""{"format":"dpai.test"}"""))
        assertEquals(null to listOf(DiagCode.FORMAT_MISMATCH), read("""{"format":"other","version":3}"""))
    }

    @Test
    fun pathQuotesUnusualKeys() {
        assertEquals("$.a", JsonPath.field("$", "a"))
        assertEquals("$[\"a b\"]", JsonPath.field("$", "a b"))
        assertEquals("$.a[2]", JsonPath.index("$.a", 2))
    }
}

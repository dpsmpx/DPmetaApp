package com.dpsmpx.dpai.core.serialization

import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import com.dpsmpx.dpai.testkit.map
import kotlin.test.Test
import kotlin.test.assertEquals

class CanonicalJsonTest {
    @Test
    fun integerFactoriesKeepTheCanonicalBytesAndExactLimits() {
        val values = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1_000_000_000L, -1L, 0L, 1L, 1_000_000_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (value in values) {
            val parsed = JsonNumber(value.toString())
            val number = JsonNumber.of(value)
            assertEquals(parsed.canonical, number.canonical)
            assertEquals(parsed, number)
            assertEquals(parsed.hashCode(), number.hashCode())
            assertEquals(value, number.toLongExact())
            assertEquals(value, parsed.toLongExact())
        }
        for (literal in listOf("-0", "1.0", "1e2", "9223372036854775808", "-9223372036854775809", "1.5", "1e9999", "1e-9999")) {
            val number = JsonNumber(literal)
            val expected = try { number.toBigDecimal().longValueExact() } catch (_: ArithmeticException) { null }
            assertEquals(expected, number.toLongExact(), literal)
        }
    }

    @Test
    fun escapedRunsKeepControlsUnicodeAndLongUnescapedText() {
        val text = "a".repeat(8192) + (0..31).map(Int::toChar).joinToString("") + "\\\"😀é" + "z".repeat(8192)
        val encoded = CanonicalJson.write(JsonString(text))
        assertEquals(JsonString(text), JsonParser().parse(encoded).valueOrNull())
        assertEquals(encoded, CanonicalJson.write(JsonParser().parse(encoded).valueOrNull()!!))
        for (code in listOf(0, 1, 7, 11, 14, 31)) {
            assertEquals("\"\\u00" + "0123456789abcdef"[code shr 4] + "0123456789abcdef"[code and 15] + "\"", CanonicalJson.write(JsonString(code.toChar().toString())))
        }
    }

    @Test
    fun keysSortedNoWhitespaceMinimalEscapes() {
        val v = JsonParser().parse("""{ "b" : [1.50, "é\u0007"], "a": {"z": true, "A": null}, "€": -0 }""").valueOrNull()!!
        assertEquals("""{"a":{"A":null,"z":true},"b":[1.5,"é\u0007"],"€":0}""", CanonicalJson.write(v))
    }

    @Test
    fun numbersHaveOneSpelling() {
        val cases = mapOf(
            "1.0" to "1", "1e2" to "100", "-0.0" to "0", "0.000001" to "0.000001", "1e-7" to "1E-7",
            "123456789012345678901234" to "123456789012345678901234", "1e22" to "1E+22", "10000000000000000000000" to "1E+22", "1.5e21" to "1.5E+21", "12.3400" to "12.34",
        )
        for ((input, want) in cases) assertEquals(want, JsonNumber(input).canonical, input)
    }

    @Test
    fun keyOrderIsUtf16CodeUnits() {
        // U+FF61 > U+1F600 по единицам UTF-16 (суррогат D83D < FF61), как в RFC 8785.
        val v = JsonObject.of("\uFF61" to json(1), "😀" to json(2), "a" to json(3))
        assertEquals("{\"a\":3,\"😀\":2,\"\uFF61\":1}", CanonicalJson.write(v))
    }

    /** Свойство: разбор канонического вида даёт равное значение, повторная запись — ту же строку. */
    @Test
    fun canonicalFormRoundTrips() {
        Property.forAll(JsonGen.value(4)) { v ->
            val text = CanonicalJson.write(v)
            val back = JsonParser().parse(text).valueOrNull()
            assertEquals(v, back, text)
            assertEquals(text, CanonicalJson.write(back!!))
        }
    }

    /** Свойство: порядок полей не влияет на канонический вид. */
    @Test
    fun fieldOrderDoesNotMatter() {
        Property.forAll(JsonGen.obj(3)) { o ->
            val reversed = JsonObject(LinkedHashMap(o.fields.entries.reversed().associate { it.key to it.value }))
            assertEquals(CanonicalJson.write(o), CanonicalJson.write(reversed))
            assertEquals(o, reversed)
        }
    }
}

/** Генераторы значений JSON для свойств. */
object JsonGen {
    // Части строк — целые кодовые точки: суррогатная пара не разрезается.
    private val chars = listOf("a", "Z", "0", "9", " ", "_", "-", "\"", "\\", "/", "\n", "\t", "\u0001", "é", "€", "😀")
    private val keyChars = listOf("a", "b", "x", "_", "A", "Z", "€", "😀")

    private fun text(parts: List<String>, length: IntRange): Gen<String> =
        Gen.list(Gen.element(parts), length).map { it.joinToString("") }

    val number: Gen<JsonNumber> = Gen.oneOf(
        Gen.long(-1_000_000L..1_000_000L).map { JsonNumber.of(it) },
        Gen.long(-99_999L..99_999L).map { JsonNumber("$it.${Math.floorMod(it, 1000)}") },
        Gen.int(-30..30).map { JsonNumber("7.25e$it") },
    )

    private val leaf: Gen<JsonValue> = Gen.oneOf(
        Gen.constant(JsonNull),
        Gen.bool().map { JsonBool(it) },
        number,
        text(chars, 0..8).map { JsonString(it) },
    )

    fun value(depth: Int): Gen<JsonValue> =
        if (depth <= 0) leaf else Gen.oneOf(leaf, leaf, array(depth - 1), obj(depth - 1))

    fun array(depth: Int): Gen<JsonValue> = Gen.list(value(depth), 0..4).map { JsonArray(it) }

    fun obj(depth: Int): Gen<JsonObject> = Gen { r ->
        val n = r.nextInt(5)
        val fields = LinkedHashMap<String, JsonValue>()
        repeat(n) { fields[text(keyChars, 1..4).next(r)] = value(depth).next(r) }
        JsonObject(fields)
    }
}

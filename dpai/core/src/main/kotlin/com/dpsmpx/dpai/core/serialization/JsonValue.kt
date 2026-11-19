package com.dpsmpx.dpai.core.serialization

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Значение JSON (D-09). Объект хранит порядок полей для показа, но
 * сравнивается как отображение: порядок ключей не влияет ни на равенство,
 * ни на хеш канонического вида.
 */
sealed interface JsonValue

object JsonNull : JsonValue {
    override fun toString(): String = "null"
}

data class JsonBool(val value: Boolean) : JsonValue {
    override fun toString(): String = value.toString()
}

data class JsonString(val value: String) : JsonValue {
    override fun toString(): String = CanonicalJson.write(this)
}

/**
 * Число в исходной записи. Равенство — по канонической записи
 * ([CanonicalJson]): `1`, `1.0` и `1e0` — одно число.
 */
class JsonNumber private constructor(val literal: String, private val knownCanonical: String?) : JsonValue {
    constructor(literal: String) : this(literal, integerCanonical(literal))

    init {
        if (knownCanonical == null) require(GRAMMAR.matches(literal) && literal.length <= 1000) { "не число JSON" }
    }

    // Строка Long уже канонична: для счётчиков/времени не нужны Matcher,
    // BigDecimal и отдельный Lazy. Произвольный ввод проверяется как прежде.
    private val normalized = if (knownCanonical == null) lazy { canonicalNumber(BigDecimal(literal)) } else null
    val canonical: String get() = knownCanonical ?: normalized!!.value

    fun toBigDecimal(): BigDecimal = BigDecimal(literal)

    /**
     * Точное целое в пределах Long; дробное или слишком большое — `null`.
     * `BigDecimal.longValueExact`, а не `BigInteger.longValueExact`: второго
     * в Android 11 нет (`check_android_api.py`).
     */
    fun toLongExact(): Long? {
        literal.toLongOrNull()?.let { return it }
        return try {
            toBigDecimal().stripTrailingZeros().let { if (it.scale() > 0) null else it.longValueExact() }
        } catch (_: ArithmeticException) {
            null
        }
    }

    override fun equals(other: Any?): Boolean = other is JsonNumber && canonical == other.canonical

    override fun hashCode(): Int = canonical.hashCode()

    override fun toString(): String = canonical

    companion object {
        private fun integerCanonical(literal: String): String? {
            val value = literal.toLongOrNull() ?: return null
            val canonical = value.toString()
            // toLongOrNull принимает +1 и ведущие нули, JSON — нет.
            // Только точное совпадение (либо допустимый -0) обходит общую грамматику.
            return canonical.takeIf { literal == it || literal == "-0" }
        }

        fun of(v: Long): JsonNumber = v.toString().let { JsonNumber(it, it) }

        fun of(v: Int): JsonNumber = of(v.toLong())

        fun of(v: BigInteger): JsonNumber = JsonNumber(v.toString())

        private val GRAMMAR = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?0*[0-9]{1,4})?")
        private val PLAIN_MIN = BigDecimal("1e-6")
        private val PLAIN_MAX = BigDecimal("1e21")

        internal fun canonicalNumber(v: BigDecimal): String {
            if (v.signum() == 0) return "0"
            val s = v.stripTrailingZeros()
            val abs = s.abs()
            return if (abs >= PLAIN_MIN && abs < PLAIN_MAX) s.toPlainString() else s.toString()
        }
    }
}

data class JsonArray(val items: List<JsonValue>) : JsonValue {
    override fun toString(): String = CanonicalJson.write(this)
}

data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]

    override fun toString(): String = CanonicalJson.write(this)

    companion object {
        fun of(vararg pairs: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*pairs))
    }
}

fun json(value: String): JsonString = JsonString(value)

fun json(value: Long): JsonNumber = JsonNumber.of(value)

fun json(value: Int): JsonNumber = JsonNumber.of(value)

fun json(value: Boolean): JsonBool = JsonBool(value)

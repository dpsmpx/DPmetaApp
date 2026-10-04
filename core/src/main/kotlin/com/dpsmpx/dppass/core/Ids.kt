package com.dpsmpx.dppass.core

/**
 * Идентификаторы доменных объектов. Формат один для всех: строчные
 * латинские буквы, цифры, `.`, `_`, `-`; до 64 символов; начинается с
 * буквы или цифры. Устойчивы между версиями конфигурации — на них
 * ссылаются правила, кандидаты, история активаций.
 */
object Ids {
    private val PATTERN = Regex("""[a-z0-9][a-z0-9._-]{0,63}""")

    fun isValid(value: String): Boolean = PATTERN.matches(value)
}

@JvmInline
value class ProfileId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class SubscriptionId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class RuleId(val value: String) {
    override fun toString(): String = value
}

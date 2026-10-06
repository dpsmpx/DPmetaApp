package com.dpsmpx.dppass.build

import java.security.SecureRandom

/**
 * Личность личной сборки DPpass (D-47): случайные `applicationId` и ярлык
 * приложения, не называющие ни «VPN», ни «DPpass». Android закрепляет
 * `applicationId` при сборке, поэтому каждая сборка получает свежую
 * личность, а каждая переустановка — новое приложение.
 *
 * Пространство имён кода (`com.dpsmpx.dppass`) не меняется — меняется
 * только устанавливаемая личность (D-47). Значение берётся из зерна:
 * одинаковое зерно даёт одинаковую личность (воспроизводимость), иначе —
 * `SecureRandom`.
 *
 * Здесь только Kotlin stdlib и `java.security`: объект проверяется
 * `IdentityTest` без Android и AGP.
 */
object AppIdentity {
    // Константы — раньше STABLE: в объекте поля инициализируются по порядку,
    // а конструктор Identity читает VALID_APP_ID.
    private val VALID_APP_ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    /** Слова ярлыка — нейтральные, не бренды и не чужие названия (D-01, D-20). */
    private val LABELS = listOf(
        "Atlas", "Ledger", "Memo", "Almanac", "Compass", "Harbor", "Lumen",
        "Cardinal", "Drift", "Ember", "Nimbus", "Quill", "Verdant", "Beacon",
        "Cobalt", "Juniper", "Marble", "Opal", "Cedar", "Slate", "Haven",
        "Meadow", "Pebble", "Lantern", "Willow", "Aster", "Flint", "Grove",
    )

    /** Запрещённые подстроки в случайном ярлыке и пакете: имя не выдаёт назначение. */
    private val FORBIDDEN = listOf("vpn", "proxy", "tunnel", "dppass", "xray")

    private const val CONSONANTS = "bcdfghjklmnprstvwz"
    private const val VOWELS = "aeiou"

    data class Identity(val applicationId: String, val label: String) {
        init {
            require(VALID_APP_ID.matches(applicationId)) { "неверный applicationId: $applicationId" }
        }
    }

    /** Стабильная личность отладочной сборки и проверок (детерминизм). */
    val STABLE: Identity = Identity("com.dpsmpx.dppass", "DPpass")

    /**
     * Личность по зерну: [seed] = null — случайная (`SecureRandom`); иначе
     * детерминированная, одно и то же зерно даёт ту же личность.
     */
    fun random(seed: Long? = null): Identity {
        val rnd = if (seed == null) SecureRandom() else java.util.Random(seed)
        val id = "com." + segment(rnd) + "." + segment(rnd)
        val label = LABELS[rnd.nextInt(LABELS.size)]
        val lower = (id + " " + label).lowercase()
        check(FORBIDDEN.none { it in lower }) { "личность выдаёт назначение: $id / $label" }
        return Identity(id, label)
    }

    /**
     * Зерно из строки свойства сборки `dppass.identitySeed`: пусто — null
     * (случайно), иначе длинный хеш строки (воспроизводимо).
     */
    fun seedOf(property: String?): Long? {
        val s = property?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var h = 1125899906842597L
        for (c in s) h = 31 * h + c.code
        return h
    }

    // Сегмент пакета: чередование согласная/гласная — похоже на слово, валидный
    // идентификатор Java (начинается с буквы, только [a-z]). Длина 5–8.
    private fun segment(rnd: java.util.Random): String {
        val len = 5 + rnd.nextInt(4)
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val set = if (i % 2 == 0) CONSONANTS else VOWELS
            sb.append(set[rnd.nextInt(set.length)])
        }
        return sb.toString()
    }
}

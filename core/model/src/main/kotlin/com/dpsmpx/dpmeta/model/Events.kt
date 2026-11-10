package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.clock.Timestamp

/**
 * Каталог событий (ТЗ, §8.1, F-51; D-85) и правила тревог (F-37, F-52; D-85) —
 * типы предметной области: разбор — `:core:formats`, движок — `:core:notify`.
 */

/** Откуда приходит событие (§8.1): С — синхронизация, М — мгновенный канал, Л — слушатель, П — приложение. */
enum class EventSource(val code: String) {
    SYNC("sync"),
    PUSH("push"),
    LISTENER("listener"),
    APP("app"),
    ;

    companion object {
        fun of(code: String): EventSource? = entries.firstOrNull { it.code == code }
    }
}

/** Вид канала уведомлений Android (F-38): у каждого своя важность, звук и вибрация. */
enum class AlertChannel(val code: String, val title: String) {
    CLAUDE("claude", "Claude"),
    DEV("dev", "Разработка"),
    SOCIAL("social", "Соцсети"),
    SITE("site", "Сайт"),
    QUESTIONS("questions", "Вопросы"),
    SYNC("sync", "Синхронизация"),
    ;

    companion object {
        fun of(code: String): AlertChannel? = entries.firstOrNull { it.code == code }
    }
}

/** Тип события каталога: тип, подпись, источники, канал по умолчанию, текстовые и числовые поля для условий. */
data class EventType(
    val type: String,
    val title: String,
    val sources: Set<EventSource>,
    val channel: AlertChannel,
    val fields: List<String>,
    val numbers: List<String>,
)

/** Каталог событий в порядке файла. */
data class EventCatalog(val schema: Int, val types: List<EventType>) {
    private val byType = types.associateBy { it.type }

    fun type(type: String): EventType? = byType[type]

    /** Типы под маской правила: точный тип или `часть.*` (все типы с этим началом). */
    fun matching(on: String): List<EventType> =
        if (on.endsWith(".*")) on.removeSuffix("*").let { prefix -> types.filter { it.type.startsWith(prefix) } } else listOfNotNull(byType[on])
}

/**
 * Событие: тип каталога, время, проект (если есть), текст для показа и условий
 * `contains`/`regex`, поля и числа для условий; [key] — предмет (номер PR,
 * ветка): у одного предмета — одно уведомление, обновляемое.
 */
data class Event(
    val type: String,
    val at: Timestamp,
    val project: String?,
    val text: String,
    val fields: Map<String, String> = emptyMap(),
    val numbers: Map<String, Double> = emptyMap(),
    val key: String? = null,
)

/** Что сделать с событием (F-52). */
enum class AlertAction(val code: String) {
    NOTIFY("notify"),
    FEED("feed"),
    DIGEST("digest"),
    SILENT("silent"),
    ;

    companion object {
        fun of(code: String): AlertAction? = entries.firstOrNull { it.code == code }
    }
}

/** Важность уведомления (F-38). */
enum class AlertImportance(val code: String) {
    LOW("low"),
    DEFAULT("default"),
    HIGH("high"),
    ;

    companion object {
        fun of(code: String): AlertImportance? = entries.firstOrNull { it.code == code }
    }
}

/** Числовое условие: поле из `numbers` типа, нижняя и/или верхняя граница включительно. */
data class NumberCondition(val field: String, val atLeast: Double?, val atMost: Double?)

/** Антидребезг: не больше [limit] уведомлений за [windowMinutes] по правилу и проекту. */
data class Debounce(val limit: Int, val windowMinutes: Int)

/**
 * Правило тревоги (D-85): на что ([on] — тип или маска `часть.*`), условия,
 * действие, канал (`null` — канал типа), важность, дни недели (пусто — все),
 * антидребезг и тихие часы ([quiet] — `false`: звучит и в них).
 */
data class AlertRule(
    val id: String,
    val title: String,
    val on: String,
    val action: AlertAction,
    val channel: AlertChannel?,
    val importance: AlertImportance,
    val enabled: Boolean,
    val project: String?,
    val contains: String?,
    val regex: Regex?,
    val number: NumberCondition?,
    val days: Set<java.time.DayOfWeek>,
    val debounce: Debounce?,
    val quiet: Boolean,
)

/** Правила тревог в порядке проверки: решает первое подходящее включённое (D-85). */
data class AlertRules(val schema: Int, val rules: List<AlertRule>) {
    fun byId(id: String): AlertRule? = rules.firstOrNull { it.id == id }
}

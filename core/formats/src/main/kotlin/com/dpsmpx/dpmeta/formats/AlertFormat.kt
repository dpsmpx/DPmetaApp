package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpmeta.model.AlertAction
import com.dpsmpx.dpmeta.model.AlertChannel
import com.dpsmpx.dpmeta.model.AlertImportance
import com.dpsmpx.dpmeta.model.AlertRule
import com.dpsmpx.dpmeta.model.AlertPattern
import com.dpsmpx.dpmeta.model.AlertRules
import com.dpsmpx.dpmeta.model.Debounce
import com.dpsmpx.dpmeta.model.EventCatalog
import com.dpsmpx.dpmeta.model.EventSource
import com.dpsmpx.dpmeta.model.EventType
import com.dpsmpx.dpmeta.model.NumberCondition
import java.time.DayOfWeek

/**
 * Строгий разбор `config/events.toml` — каталога событий (ТЗ, §8.1, F-51; D-85):
 * другая схема — `FORMAT_VERSION_UNSUPPORTED`, неизвестный ключ — `FIELD_UNKNOWN`,
 * нет ключа или типов — `FIELD_MISSING`, не тот тип — `FIELD_TYPE`, остальное —
 * `VALUE_INVALID` с местом: повтор типа, неизвестный источник или канал, поле и
 * число с одним именем, управляющий знак.
 */
object EventCatalogFormat {
    const val SCHEMA = 1

    private val TOP = setOf("schema", "event")
    private val EVENT = setOf("type", "title", "source", "channel", "fields", "numbers")

    /** Тип — латиницей, не меньше двух частей через точку: `pr.opened`, `claude.turn.done`. */
    val TYPE = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
    val FIELD = Regex("[a-z][a-z0-9_]{0,31}")

    fun parse(text: String, source: String = "events.toml"): Outcome<EventCatalog> {
        val list = when (val t = Doc.top(text, source, TOP, SCHEMA, "event")) {
            is Outcome.Failure -> return t
            is Outcome.Success -> t.value
        }
        val seen = HashSet<String>()
        val types = list.mapIndexed { i, v ->
            val at = "$source:event[$i]"
            val obj = v as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "событие — не таблица", at)
            val e = when (val r = event(obj, at)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            if (!seen.add(e.type)) return Doc.invalid("тип «${e.type}» — уже был", "$at.type")
            e
        }
        return Outcome.success(EventCatalog(SCHEMA, types))
    }

    private fun event(obj: JsonObject, at: String): Outcome<EventType> {
        val f = Doc(obj, at)
        f.unknown(EVENT)?.let { return it }
        val type = f.text("type", 64).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return f.missing("type")
        if (!TYPE.matches(type)) return Doc.invalid("тип — латиницей, части через точку: pr.opened", "$at.type")
        val title = f.text("title", 80).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return f.missing("title")
        val sourceCodes = f.strings("source", 4, 16).let { if (it is Outcome.Failure) return it else it.valueOrNull()!! }
        if (sourceCodes.isEmpty()) return f.missing("source")
        val sources = sourceCodes.map { EventSource.of(it) ?: return Doc.invalid("источник «$it» — sync, push, listener или app", "$at.source") }.toSet()
        if (sources.size != sourceCodes.size) return Doc.invalid("источник повторён", "$at.source")
        val channelCode = f.text("channel", 16).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return f.missing("channel")
        val channel = AlertChannel.of(channelCode) ?: return Doc.invalid("канал — ${AlertChannel.entries.joinToString(", ") { it.code }}", "$at.channel")
        val fields = f.names("fields").let { if (it is Outcome.Failure) return it else it.valueOrNull()!! }
        val numbers = f.names("numbers").let { if (it is Outcome.Failure) return it else it.valueOrNull()!! }
        (fields intersect numbers.toSet()).firstOrNull()?.let { return Doc.invalid("«$it» — и текстовое поле, и число", "$at.numbers") }
        return Outcome.success(EventType(type, title, sources, channel, fields, numbers))
    }
}

/**
 * Строгий разбор `config/alerts.toml` — правил тревог (F-37, F-52; D-85) — против
 * каталога [EventCatalog]: ошибки файла — как у каталога; правило о неизвестном
 * типе или маске без типов, о поле не из `numbers` его типов, с неверным `regex` —
 * `RULE_INVALID` с местом. Порядок правил — порядок проверки.
 */
object AlertFormat {
    const val SCHEMA = 1
    const val MAX_RULES = 200
    const val MAX_REGEX = AlertPattern.MAX_LENGTH

    private val TOP = setOf("schema", "rule")
    private val RULE = setOf(
        "id", "title", "on", "action", "channel", "importance", "enabled", "project", "contains", "regex",
        "field", "at_least", "at_most", "days", "limit", "window", "quiet",
    )
    private val ID = Regex("[a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)*")
    private val ON = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*(\\.\\*)?")
    private val PROJECT = Regex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}")
    private val DAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )

    /** Канонический текст редактора; все поля и порядок правил сохраняются. */
    fun write(rules: AlertRules): Outcome<String> {
        val result = buildString {
            append("schema = ").append(rules.schema).append('\n')
            fun text(key: String, value: String) { append(key).append(" = ").append(CanonicalJson.write(JsonString(value))).append('\n') }
            for (rule in rules.rules) {
                append("\n[[rule]]\n")
                text("id", rule.id); text("title", rule.title); text("on", rule.on); text("action", rule.action.code)
                rule.channel?.let { text("channel", it.code) }
                text("importance", rule.importance.code)
                append("enabled = ").append(rule.enabled).append('\n')
                rule.project?.let { text("project", it) }
                rule.contains?.let { text("contains", it) }
                rule.regex?.let { text("regex", it.pattern) }
                rule.number?.let { n ->
                    text("field", n.field)
                    // Подмножество TOML принимает Int64, а числовой движок хранит Double.
                    for ((key, value) in listOf("at_least" to n.atLeast, "at_most" to n.atMost)) if (value != null) {
                        if (!value.isFinite() || value.toLong().toDouble() != value) return Outcome.failure(DiagCode.VALUE_INVALID, "границы правила TOML должны быть целыми Int64", key)
                        append(key).append(" = ").append(value.toLong()).append('\n')
                    }
                }
                if (rule.days.isNotEmpty()) append("days = ").append(rule.days.sortedBy { it.value }.joinToString(", ", "[", "]") { day -> CanonicalJson.write(JsonString(DAYS.entries.first { it.value == day }.key)) }).append('\n')
                rule.debounce?.let { append("limit = ").append(it.limit).append("\nwindow = ").append(it.windowMinutes).append('\n') }
                append("quiet = ").append(rule.quiet).append('\n')
            }
        }
        return Outcome.success(result)
    }

    fun parse(text: String, catalog: EventCatalog, source: String = "alerts.toml"): Outcome<AlertRules> {
        val list = when (val t = Doc.top(text, source, TOP, SCHEMA, "rule")) {
            is Outcome.Failure -> return t
            is Outcome.Success -> t.value
        }
        if (list.size > MAX_RULES) return Doc.invalid("правил — не больше $MAX_RULES", "$source:rule")
        val seen = HashSet<String>()
        val rules = list.mapIndexed { i, v ->
            val at = "$source:rule[$i]"
            val obj = v as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "правило — не таблица", at)
            val r = when (val p = rule(obj, catalog, at)) {
                is Outcome.Failure -> return p
                is Outcome.Success -> p.value
            }
            if (!seen.add(r.id)) return Doc.invalid("id «${r.id}» — уже был", "$at.id")
            r
        }
        return Outcome.success(AlertRules(SCHEMA, rules))
    }

    private fun rule(obj: JsonObject, catalog: EventCatalog, at: String): Outcome<AlertRule> {
        val f = Doc(obj, at)
        f.unknown(RULE)?.let { return it }
        val id = f.text("id", 64).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return f.missing("id")
        if (!ID.matches(id)) return Doc.invalid("id — строчные латинские буквы, цифры, «_-», части через точку", "$at.id")
        val title = f.text("title", 120).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return f.missing("title")
        val on = f.text("on", 64).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return f.missing("on")
        if (!ON.matches(on)) return Doc.invalid("on — тип события или маска «часть.*»", "$at.on")
        val types = catalog.matching(on)
        if (types.isEmpty()) return Outcome.failure(DiagCode.RULE_INVALID, "в каталоге событий нет «$on»", "$at.on")
        val actionCode = f.text("action", 16).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return f.missing("action")
        val action = AlertAction.of(actionCode) ?: return Doc.invalid("действие — notify, feed, digest или silent", "$at.action")
        val channel = f.text("channel", 16).let { if (it is Outcome.Failure) return it else it.valueOrNull() }?.let { c ->
            AlertChannel.of(c) ?: return Doc.invalid("канал — ${AlertChannel.entries.joinToString(", ") { it.code }}", "$at.channel")
        }
        val importance = f.text("importance", 16).let { if (it is Outcome.Failure) return it else it.valueOrNull() }?.let { c ->
            AlertImportance.of(c) ?: return Doc.invalid("важность — low, default или high", "$at.importance")
        } ?: AlertImportance.DEFAULT
        val enabled = f.bool("enabled").let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: true
        val quiet = f.bool("quiet").let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: true
        val project = f.text("project", 64).let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        if (project != null && !PROJECT.matches(project)) return Doc.invalid("проект — имя из реестра", "$at.project")
        val contains = f.text("contains", 200).let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val regexText = f.text("regex", MAX_REGEX).let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val regex = regexText?.let {
            when (val compiled = AlertPattern.compile(it, "$at.regex")) {
                is Outcome.Failure -> return compiled
                is Outcome.Success -> compiled.value
            }
        }
        val field = f.text("field", 32).let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val atLeast = f.number("at_least").let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val atMost = f.number("at_most").let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val number = when {
            field == null && atLeast == null && atMost == null -> null
            field == null -> return f.missing("field")
            atLeast == null && atMost == null -> return Doc.invalid("у числа — at_least и/или at_most", "$at.field")
            types.any { field !in it.numbers } -> return Outcome.failure(DiagCode.RULE_INVALID, "«$field» — не число у ${types.first { t -> field !in t.numbers }.type}", "$at.field")
            atLeast != null && atMost != null && atLeast > atMost -> return Doc.invalid("at_least больше at_most", "$at.at_least")
            else -> NumberCondition(field, atLeast, atMost)
        }
        val dayCodes = f.strings("days", 7, 3).let { if (it is Outcome.Failure) return it else it.valueOrNull()!! }
        val days = dayCodes.map { DAYS[it] ?: return Doc.invalid("день «$it» — mon, tue, wed, thu, fri, sat, sun", "$at.days") }.toSet()
        val limit = f.int("limit").let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val window = f.int("window").let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val debounce = when {
            limit == null && window == null -> null
            limit == null || window == null -> return Doc.invalid("антидребезг — limit и window вместе", "$at.${if (limit == null) "limit" else "window"}")
            limit !in 1..100 -> return Doc.invalid("limit — 1…100", "$at.limit")
            window !in 1..10_080 -> return Doc.invalid("window — 1…10080 минут (неделя)", "$at.window")
            else -> Debounce(limit, window)
        }
        return Outcome.success(AlertRule(id, title, on, action, channel, importance, enabled, project, contains, regex, number, days, debounce, quiet))
    }
}

/** Ключи таблицы TOML с местом — общие проверки разборов каталога и правил. */
internal class Doc(private val obj: JsonObject, private val at: String) {
    fun unknown(allowed: Set<String>): Outcome.Failure? =
        obj.fields.keys.firstOrNull { it !in allowed }?.let { Outcome.failure(DiagCode.FIELD_UNKNOWN, "неизвестный ключ «$it»", "$at.$it") }

    fun missing(key: String): Outcome.Failure = Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа $key", "$at.$key")

    private fun typeError(key: String): Outcome.Failure = Outcome.failure(DiagCode.FIELD_TYPE, "неверный тип ключа $key", "$at.$key")

    /** Строка: нет или пустая — `null`; длиннее [max] или с управляющим знаком — отказ. */
    fun text(key: String, max: Int): Outcome<String?> {
        val raw = obj[key] ?: return Outcome.success(null)
        val v = (raw as? JsonString)?.value ?: return typeError(key)
        if (v.isBlank()) return Outcome.success(null)
        if (v.length > max) return invalid("$key — не длиннее $max знаков", "$at.$key")
        if (v.any { it.isISOControl() }) return invalid("$key — без управляющих знаков", "$at.$key")
        return Outcome.success(v)
    }

    fun bool(key: String): Outcome<Boolean?> {
        val raw = obj[key] ?: return Outcome.success(null)
        return Outcome.success((raw as? JsonBool)?.value ?: return typeError(key))
    }

    fun number(key: String): Outcome<Double?> {
        val raw = obj[key] ?: return Outcome.success(null)
        val n = raw as? JsonNumber ?: return typeError(key)
        return Outcome.success(n.toBigDecimal().toDouble())
    }

    fun int(key: String): Outcome<Int?> {
        val raw = obj[key] ?: return Outcome.success(null)
        val n = (raw as? JsonNumber)?.toLongExact() ?: return typeError(key)
        if (n !in Int.MIN_VALUE..Int.MAX_VALUE) return invalid("$key — вне предела", "$at.$key")
        return Outcome.success(n.toInt())
    }

    fun strings(key: String, maxItems: Int, maxLength: Int): Outcome<List<String>> {
        val v = obj[key] ?: return Outcome.success(emptyList())
        val arr = v as? JsonArray ?: return typeError(key)
        if (arr.items.size > maxItems) return invalid("$key — не больше $maxItems", "$at.$key")
        return Outcome.success(
            arr.items.mapIndexed { i, item ->
                val s = (item as? JsonString)?.value ?: return typeError("$key[$i]")
                if (s.length > maxLength || s.any { it.isISOControl() }) return invalid("$key[$i] — до $maxLength знаков, без управляющих", "$at.$key[$i]")
                s
            },
        )
    }

    /** Имена полей: латиницей, без повторов, не больше 16. */
    fun names(key: String): Outcome<List<String>> {
        val list = when (val r = strings(key, 16, 32)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        list.firstOrNull { !EventCatalogFormat.FIELD.matches(it) }?.let { return invalid("поле «$it» — латиницей: строчные, цифры, «_»", "$at.$key") }
        if (list.toSet().size != list.size) return invalid("$key — с повтором", "$at.$key")
        return Outcome.success(list)
    }

    companion object {
        fun invalid(message: String, at: String): Outcome.Failure = Outcome.failure(DiagCode.VALUE_INVALID, message, at)

        /** Верх документа: известные ключи, `schema`, непустой массив таблиц [list]. */
        fun top(text: String, source: String, allowed: Set<String>, schemaVersion: Int, list: String): Outcome<List<JsonValue>> {
            val doc = when (val t = Toml.parse(text, source)) {
                is Outcome.Failure -> return t
                is Outcome.Success -> t.value
            }
            val d = Doc(doc, source)
            d.unknown(allowed)?.let { return it }
            val schemaValue = doc["schema"] ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа schema", "$source:schema")
            val schema = (schemaValue as? JsonNumber)?.toLongExact() ?: return Outcome.failure(DiagCode.FIELD_TYPE, "неверный тип ключа schema", "$source:schema")
            if (schema != schemaVersion.toLong()) {
                return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "схема $schema не поддержана (поддержана $schemaVersion)", "$source:schema")
            }
            val items = doc[list] ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа $list", "$source:$list")
            if (items !is JsonArray) return Outcome.failure(DiagCode.FIELD_TYPE, "неверный тип ключа $list", "$source:$list")
            if (items.items.isEmpty()) return Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа $list", "$source:$list")
            return Outcome.success(items.items)
        }
    }
}

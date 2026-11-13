package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Шаблон поручения (ТЗ, F-23): данные, а не код — `config/briefs/<ключ>.md` DPmetaApp.
 * Текст поручения уходит сессии Claude полем `text` запуска маршрута (F-22) и
 * показывается автору до отправки. Места `{{имя}}` в теле — встроенные ([BUILT_IN]:
 * их заполняет пульт) и поля шаблона (их вводит автор).
 */
data class Brief(val key: String, val title: String, val fields: List<Field>, val body: String) {
    /** Поле, которое вводит автор: [multiline] — многострочное (ответ, вопрос). */
    data class Field(val name: String, val label: String, val multiline: Boolean)

    /** Поля без значения: отправлять поручение с пустым местом нельзя. */
    fun missing(values: Map<String, String>): List<Field> = fields.filter { values[it.name].isNullOrBlank() }

    /**
     * Текст поручения: места заменяются за один проход — значение с `{{…}}` внутри
     * не раскрывается второй раз. Однострочное поле — в одну строку. Нет значения
     * или текст длиннее [BriefFormat.MAX_TEXT] — отказ.
     */
    fun render(builtIn: Map<String, String>, values: Map<String, String>): Outcome<String> {
        missing(values).firstOrNull()?.let { return Outcome.failure(DiagCode.FIELD_MISSING, "не заполнено: ${it.label}", "brief:$key.${it.name}") }
        val absent = BriefFormat.BUILT_IN.filter { builtIn[it].isNullOrBlank() && "{{$it}}" in body }
        if (absent.isNotEmpty()) return Outcome.failure(DiagCode.FIELD_MISSING, "пульт не знает: ${absent.joinToString()}", "brief:$key")
        val byName = fields.associateBy { it.name }
        val out = BriefFormat.PLACE.replace(body) { m ->
            val name = m.groupValues[1]
            val f = byName[name]
            val v = if (f != null) values.getValue(name).trim() else builtIn.getValue(name).trim()
            if (f != null && !f.multiline) v.replace(Regex("\\s*\n\\s*"), " ") else v
        }.trim() + "\n"
        return if (out.length > BriefFormat.MAX_TEXT) {
            Outcome.failure(DiagCode.VALUE_INVALID, "поручение длиннее ${BriefFormat.MAX_TEXT} знаков", "brief:$key")
        } else {
            Outcome.success(out)
        }
    }
}

/**
 * Строгий разбор шаблона: шапка TOML между строками `+++` — `schema = 1`, `title`,
 * `[[field]]` (`name`, `label`, `multiline`); тело — Markdown. Другая схема —
 * `FORMAT_VERSION_UNSUPPORTED`; нет шапки — `FORMAT_UNRECOGNIZED`; неизвестный
 * ключ — `FIELD_UNKNOWN`; место не встроенное и не поле, поле без места, имя
 * поля не по правилу или повтор — `VALUE_INVALID`.
 */
object BriefFormat {
    const val SCHEMA = 1

    /** Предел поручения — предел поля `text` запуска маршрута (`DP_AI_Orcestrator:D-116`). */
    const val MAX_TEXT = 65_536

    /** Встроенные места: проект реестра, репозиторий `владелец/имя`, основная ветка. */
    val BUILT_IN: Set<String> = setOf("project", "repo", "base")

    /** Ключ шаблона — имя файла без `.md`. */
    val KEY = Regex("[a-z0-9][a-z0-9-]{0,63}")

    internal val PLACE = Regex("\\{\\{([a-z][a-z0-9_]{0,31})}}")
    private val NAME = Regex("[a-z][a-z0-9_]{0,31}")
    private val TOP = setOf("schema", "title", "field")
    private val FIELD = setOf("name", "label", "multiline")
    private const val FENCE = "+++"
    private const val MAX_LABEL = 80

    fun parse(text: String, key: String, source: String = "$key.md"): Outcome<Brief> {
        if (!KEY.matches(key)) return Outcome.failure(DiagCode.VALUE_INVALID, "ключ шаблона — строчная латиница, цифры и «-»", source)
        val lines = text.replace("\r\n", "\n").split('\n')
        if (lines.firstOrNull() != FENCE) return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "шаблон начинается строкой $FENCE", "$source:1")
        val end = lines.drop(1).indexOf(FENCE).takeIf { it >= 0 }?.plus(1)
            ?: return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "шапка не закрыта строкой $FENCE", source)
        val doc = when (val t = Toml.parse(lines.subList(1, end).joinToString("\n"), source)) {
            is Outcome.Failure -> return t
            is Outcome.Success -> t.value
        }
        unknown(doc, TOP, source)?.let { return it }
        val schema = (doc["schema"] as? JsonNumber)?.toLongExact() ?: return missing("schema", source)
        if (schema != SCHEMA.toLong()) {
            return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "схема $schema не поддержана (поддержана $SCHEMA)", "$source:schema")
        }
        val title = (doc["title"] as? JsonString)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return missing("title", source)
        val list = when (val f = doc["field"]) {
            null -> emptyList()
            is JsonArray -> f.items
            else -> return Outcome.failure(DiagCode.FIELD_TYPE, "field — массив таблиц [[field]]", "$source.field")
        }
        val fields = list.mapIndexed { i, v ->
            val at = "$source:field[$i]"
            val obj = v as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "поле — не таблица", at)
            when (val r = field(obj, at)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        }
        fields.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            return Outcome.failure(DiagCode.VALUE_INVALID, "поле «$it» повторяется", "$source.field")
        }
        val body = lines.drop(end + 1).joinToString("\n").trim()
        if (body.isEmpty()) return Outcome.failure(DiagCode.FIELD_MISSING, "у шаблона нет текста", "$source:body")
        val used = PLACE.findAll(body).map { it.groupValues[1] }.toSet()
        val stray = PLACE.replace(body, "")
        if ("{{" in stray || "}}" in stray) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "место подстановки — {{имя}}: строчная латиница, цифры и _", "$source:body")
        }
        val names = fields.map { it.name }.toSet()
        (used - names - BUILT_IN).firstOrNull()?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "место {{$it}} — не поле и не встроенное", "$source:body") }
        (names - used).firstOrNull()?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "поле «$it» в тексте не используется", "$source:field") }
        return Outcome.success(Brief(key, title, fields, body))
    }

    private fun field(obj: JsonObject, at: String): Outcome<Brief.Field> {
        unknown(obj, FIELD, at)?.let { return it }
        val name = (obj["name"] as? JsonString)?.value ?: return missing("name", at)
        if (!NAME.matches(name) || name in BUILT_IN) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "имя поля — строчная латиница, цифры и _, не ${BUILT_IN.joinToString()}", "$at.name")
        }
        val label = (obj["label"] as? JsonString)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return missing("label", at)
        if (label.length > MAX_LABEL) return Outcome.failure(DiagCode.VALUE_INVALID, "подпись поля — до $MAX_LABEL знаков", "$at.label")
        val multiline = when (val m = obj["multiline"]) {
            null -> false
            is JsonBool -> m.value
            else -> return Outcome.failure(DiagCode.FIELD_TYPE, "multiline — true или false", "$at.multiline")
        }
        return Outcome.success(Brief.Field(name, label, multiline))
    }

    private fun unknown(obj: JsonObject, allowed: Set<String>, at: String): Outcome.Failure? =
        obj.fields.keys.firstOrNull { it !in allowed }?.let { Outcome.failure(DiagCode.FIELD_UNKNOWN, "неизвестный ключ «$it»", "$at.$it") }

    private fun missing(key: String, at: String) = Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа $key", "$at.$key")
}

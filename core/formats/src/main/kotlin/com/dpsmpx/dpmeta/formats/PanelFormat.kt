package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Панель проекта (ТЗ, F-10; ответ автора 4 ноября 2026): что проект пишет о
 * себе, — пункты из файлов его репозитория, каждый разбирается уже готовым
 * разборщиком. Файл — `config/projects/<Проект>.toml` DPmetaApp, `schema = 1`.
 * Пункты читаются при открытии карточки, как документы F-09: бюджет
 * синхронизации (A-02) они не тратят и в состоянии не хранятся.
 */
data class Panel(val project: String, val items: List<Item>) {
    enum class Kind(val code: String) {
        /** Блоки состояния README ([StatusBlocks]); `block` — один блок по имени. */
        README_BLOCK("readme-block"),

        /** Этапы ROADMAP ([Roadmap]). */
        ROADMAP("roadmap"),

        /** Журнал решений ([DecisionLog]); `id` — шаблон номера, обязателен. */
        DECISIONS("decisions"),

        /** `TODO.md` ([TodoList]). */
        TODO("todo"),

        /** Число записей каталога `path` по маске `glob` — файлов и подкаталогов (по умолчанию — всех). */
        COUNT("count"),

        /** Документ целиком — переход в просмотр F-09. */
        DOCUMENT("document"),
        ;

        companion object {
            fun of(code: String): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    data class Item(val title: String, val kind: Kind, val path: String, val glob: String?, val id: String?, val block: String?) {
        private val mask: Regex? by lazy { glob?.let(::globRegex) }

        /** Имя файла подходит под маску [glob]: `*` — любые знаки, `?` — один; без маски — любое. */
        fun matches(name: String): Boolean = mask?.matches(name) ?: true
    }

    companion object {
        internal fun globRegex(glob: String): Regex =
            Regex(glob.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } })
    }
}

/**
 * Строгий разбор панели — отказ с местом: другая схема — `FORMAT_VERSION_UNSUPPORTED`,
 * неизвестный ключ — `FIELD_UNKNOWN`, нет ключа или пунктов — `FIELD_MISSING`, не строка —
 * `FIELD_TYPE`, неизвестный вид, путь с «..» или от корня, ключ не к своему виду — `VALUE_INVALID`.
 */
object PanelFormat {
    const val SCHEMA = 1

    private val TOP = setOf("schema", "project", "item")
    private val ITEM = setOf("title", "kind", "path", "glob", "id", "block")
    private val GLOB = Regex("[A-Za-z0-9._*?-]{1,64}")

    fun parse(text: String, source: String = "panel.toml"): Outcome<Panel> {
        val doc = when (val t = Toml.parse(text, source)) {
            is Outcome.Failure -> return t
            is Outcome.Success -> t.value
        }
        unknown(doc, TOP, source)?.let { return it }
        val schema = (doc["schema"] as? JsonNumber)?.toLongExact()
            ?: return missing("schema", source)
        if (schema != SCHEMA.toLong()) {
            return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "схема $schema не поддержана (поддержана $SCHEMA)", "$source:schema")
        }
        val project = str(doc, "project") ?: return missing("project", source)
        val list = doc["item"] as? JsonArray ?: return missing("item", source)
        val items = list.items.mapIndexed { i, v ->
            val at = "$source:item[$i]"
            val obj = v as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "пункт — не таблица", at)
            when (val r = item(obj, at)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        }
        return Outcome.success(Panel(project, items))
    }

    private fun item(obj: JsonObject, at: String): Outcome<Panel.Item> {
        unknown(obj, ITEM, at)?.let { return it }
        for (key in ITEM) if (obj[key] != null && obj[key] !is JsonString) return typeError(key, at)
        val title = str(obj, "title") ?: return missing("title", at)
        val kindText = str(obj, "kind") ?: return missing("kind", at)
        val kind = Panel.Kind.of(kindText) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "неизвестный вид «$kindText»", "$at.kind")
        val path = str(obj, "path") ?: return missing("path", at)
        if (path.startsWith("/") || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "путь — относительный, без «..» и пустых частей", "$at.path")
        }
        val glob = str(obj, "glob")
        val id = str(obj, "id")
        val block = str(obj, "block")
        if (glob != null && kind != Panel.Kind.COUNT) return Outcome.failure(DiagCode.VALUE_INVALID, "glob — только у вида count", "$at.glob")
        if (glob != null && !GLOB.matches(glob)) return Outcome.failure(DiagCode.VALUE_INVALID, "маска — имя файла: буквы, цифры, «._-*?»", "$at.glob")
        if (id != null && kind != Panel.Kind.DECISIONS) return Outcome.failure(DiagCode.VALUE_INVALID, "id — только у вида decisions", "$at.id")
        if (id == null && kind == Panel.Kind.DECISIONS) return missing("id", at)
        if (id != null && runCatching { Regex(id) }.isFailure) return Outcome.failure(DiagCode.VALUE_INVALID, "id — не регулярное выражение", "$at.id")
        if (block != null && kind != Panel.Kind.README_BLOCK) return Outcome.failure(DiagCode.VALUE_INVALID, "block — только у вида readme-block", "$at.block")
        return Outcome.success(Panel.Item(title, kind, path, glob, id, block))
    }

    private fun str(obj: JsonObject, key: String): String? = (obj[key] as? JsonString)?.value?.takeIf { it.isNotBlank() }

    private fun unknown(obj: JsonObject, allowed: Set<String>, at: String): Outcome.Failure? =
        obj.fields.keys.firstOrNull { it !in allowed }?.let { Outcome.failure(DiagCode.FIELD_UNKNOWN, "неизвестный ключ «$it»", "$at.$it") }

    private fun missing(key: String, at: String) = Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа $key", "$at.$key")

    private fun typeError(key: String, at: String) = Outcome.failure(DiagCode.FIELD_TYPE, "неверный тип ключа $key", "$at.$key")
}

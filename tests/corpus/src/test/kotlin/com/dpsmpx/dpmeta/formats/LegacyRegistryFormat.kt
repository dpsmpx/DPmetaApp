package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.model.IdSource
import com.dpsmpx.dpmeta.model.Place
import com.dpsmpx.dpmeta.model.ProjectEntry
import com.dpsmpx.dpai.core.git.RepoRef
import com.dpsmpx.dpmeta.model.Registry

/**
 * Реестр DPmeta `tools/ecosystem.toml`, `schema = 1` (ТЗ, F-01, I-01; D-05).
 *
 * Строго, с перечнем ключей: другая схема, неизвестный ключ, неизвестное
 * место — `REGISTRY_SCHEMA_UNSUPPORTED` с путём к ключу; приложение тогда
 * работает на последнем распознанном реестре (F-01) — это решает вызывающий.
 * `dir` — каталог подсистемы в DPmetaApp (D-46, `DPmeta:M-16`): одно имя
 * каталога, без «/» и «..».
 */
object RegistryFormat {
    const val SCHEMA = 1

    private val TOP = setOf("schema", "repo")
    private val REPO = setOf("name", "slug", "clone", "place", "aliases", "names", "decisions", "defs", "dir")
    private val DIR = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val ID_SOURCE = setOf("path", "id")

    fun parse(text: String, source: String = "ecosystem.toml"): Outcome<Registry> {
        val doc = when (val t = Toml.parse(text, source)) {
            is Outcome.Failure -> return t
            is Outcome.Success -> t.value
        }
        unknown(doc, TOP, source)?.let { return it }
        val schema = (doc["schema"] as? JsonNumber)?.toLongExact()
            ?: return unsupported("нет целого ключа schema", "$source:schema")
        if (schema != SCHEMA.toLong()) return unsupported("схема $schema не поддержана (поддержана $SCHEMA)", "$source:schema")
        val repos = doc["repo"] as? JsonArray ?: return unsupported("нет записей [[repo]]", "$source:repo")
        val projects = mutableListOf<ProjectEntry>()
        val seen = HashSet<String>()
        for ((i, item) in repos.items.withIndex()) {
            val at = "$source:repo[$i]"
            val obj = item as? JsonObject ?: return unsupported("запись — не таблица", at)
            val entry = when (val e = entry(obj, at)) {
                is Outcome.Failure -> return e
                is Outcome.Success -> e.value
            }
            if (!seen.add(entry.name)) return Outcome.failure(DiagCode.VALUE_INVALID, "проект «${entry.name}» записан второй раз", at)
            projects += entry
        }
        // Подсистема с каталогом живёт в клоне строки управления (D-46, DPmeta:M-16) — без неё неизвестно, в чьём.
        val sub = projects.indexOfFirst { it.dir != null }
        if (sub >= 0 && projects.none { it.place == Place.CONTROL }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "у подсистем есть dir, а строки управления (place = \"control\") нет", "$source:repo[$sub].dir")
        }
        return Outcome.success(Registry(SCHEMA, projects))
    }

    private fun entry(obj: JsonObject, at: String): Outcome<ProjectEntry> {
        unknown(obj, REPO, at)?.let { return it }
        val name = str(obj, "name") ?: return missing("name", at)
        val slugText = str(obj, "slug") ?: return missing("slug", at)
        val slug = when (val s = RepoRef.parse(slugText)) {
            is Outcome.Failure -> return Outcome.failure(DiagCode.VALUE_INVALID, "slug — не «владелец/имя»", "$at.slug")
            is Outcome.Success -> s.value
        }
        val placeText = str(obj, "place") ?: return missing("place", at)
        val place = Place.of(placeText) ?: return unsupported("неизвестное место «$placeText»", "$at.place")
        val clone = obj["clone"]?.let { (it as? JsonString)?.value ?: return typeError("clone", at) }
        val dir = obj["dir"]?.let { (it as? JsonString)?.value ?: return typeError("dir", at) }
        if (dir != null && !DIR.matches(dir)) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "каталог подсистемы — одно имя строчными, без «/» и «..»", "$at.dir")
        }
        val aliases = strings(obj, "aliases") ?: return typeError("aliases", at)
        val names = strings(obj, "names") ?: return typeError("names", at)
        val decisions = obj["decisions"]?.let { v ->
            when (val d = idSource(v, "$at.decisions")) {
                is Outcome.Failure -> return d
                is Outcome.Success -> d.value
            }
        }
        val defs = when (val v = obj["defs"]) {
            null -> emptyList()
            is JsonArray -> v.items.mapIndexed { i, d ->
                when (val s = idSource(d, "$at.defs[$i]")) {
                    is Outcome.Failure -> return s
                    is Outcome.Success -> s.value
                }
            }
            else -> return typeError("defs", at)
        }
        return Outcome.success(ProjectEntry(name, slug, clone, place, aliases, names, decisions, defs, dir))
    }

    private fun idSource(v: JsonValue, at: String): Outcome<IdSource> {
        val obj = v as? JsonObject ?: return unsupported("ожидалась таблица { path, id }", at)
        unknown(obj, ID_SOURCE, at)?.let { return it }
        val path = (obj["path"] as? JsonString)?.value ?: return missing("path", at)
        val id = (obj["id"] as? JsonString)?.value ?: return missing("id", at)
        if (path.startsWith("/") || path.split('/').any { it == ".." || it.isEmpty() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "путь — относительный, без «..»", "$at.path")
        }
        val source = IdSource(path, id)
        return when (val r = source.regex()) {
            is Outcome.Failure -> Outcome.Failure(r.diagnostics)
            is Outcome.Success -> Outcome.success(source)
        }
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonString)?.value?.takeIf { it.isNotBlank() }

    /** Необязательный массив строк: нет — пусто; не массив строк — null. */
    private fun strings(obj: JsonObject, key: String): List<String>? = when (val v = obj[key]) {
        null -> emptyList()
        is JsonArray -> v.items.map { (it as? JsonString)?.value ?: return null }
        else -> null
    }

    private fun unknown(obj: JsonObject, allowed: Set<String>, at: String): Outcome.Failure? =
        obj.fields.keys.firstOrNull { it !in allowed }?.let { unsupported("неизвестный ключ «$it»", "$at.$it") }

    private fun unsupported(message: String, at: String) = Outcome.failure(DiagCode.REGISTRY_SCHEMA_UNSUPPORTED, message, at)

    private fun missing(key: String, at: String) = Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа $key", "$at.$key")

    private fun typeError(key: String, at: String) = Outcome.failure(DiagCode.FIELD_TYPE, "неверный тип ключа $key", "$at.$key")
}

package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Место проекта в экосистеме (ТЗ, F-02; `DPmeta:M-10`, D-26, D-46). Код —
 * значение `place` реестра DPmeta.
 */
enum class Place(val code: String, val title: String) {
    CONTROL("control", "управление"),
    CORE("core", "ядро"),
    SYNC("sync", "слой синхронизации"),
    SUPPORT("support", "поддерживающий слой"),
    PERIPHERY("periphery", "периферия"),
    ARCHIVE("archive", "архив"),
    ;

    /** Архив не показывается (D-26). */
    val shown: Boolean get() = this != ARCHIVE

    companion object {
        fun of(code: String): Place? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Где в репозитории определены номера: путь файла и шаблон номера —
 * регулярное выражение из реестра (`decisions`, `defs`).
 */
data class IdSource(val path: String, val pattern: String) {
    /** Шаблон собран; неверный шаблон — `VALUE_INVALID`, а не исключение. */
    fun regex(): Outcome<Regex> = try {
        Outcome.success(Regex(pattern))
    } catch (e: IllegalArgumentException) {
        Outcome.failure(DiagCode.VALUE_INVALID, "шаблон номера не собирается: ${e.message?.lineSequence()?.firstOrNull()}", pattern)
    }

    fun toJson(): JsonObject = JsonObject.of("path" to JsonString(path), "id" to JsonString(pattern))
}

/** Строка реестра DPmeta (ТЗ, F-01): один проект экосистемы. */
data class ProjectEntry(
    val name: String,
    val slug: RepoRef,
    val clone: String?,
    val place: Place,
    val aliases: List<String>,
    val names: List<String>,
    val decisions: IdSource?,
    val defs: List<IdSource>,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "name" to JsonString(name),
            "slug" to JsonString(slug.toString()),
            "place" to JsonString(place.code),
        ).apply {
            clone?.let { put("clone", JsonString(it)) }
            if (aliases.isNotEmpty()) put("aliases", JsonArray(aliases.map(::JsonString)))
            if (names.isNotEmpty()) put("names", JsonArray(names.map(::JsonString)))
            decisions?.let { put("decisions", it.toJson()) }
            if (defs.isNotEmpty()) put("defs", JsonArray(defs.map { it.toJson() }))
        },
    )
}

/** Реестр проектов — порядок записей реестра сохраняется (порядок показа). */
data class Registry(val schema: Int, val projects: List<ProjectEntry>) {
    fun byName(name: String): ProjectEntry? = projects.firstOrNull { it.name == name }

    /** Проекты текущей системы: архив не показывается (D-26). */
    val shown: List<ProjectEntry> get() = projects.filter { it.place.shown }

    fun toJson(): JsonObject = JsonObject.of(
        "schema" to JsonNumber.of(schema),
        "projects" to JsonArray(projects.map { it.toJson() }),
    )
}

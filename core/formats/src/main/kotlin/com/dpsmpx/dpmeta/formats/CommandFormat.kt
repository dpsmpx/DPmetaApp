package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Команда реестра (ТЗ, F-40, приложение Б; D-82): что, где и с каким уровнем
 * идёт в Linux-среде приложения. [project] — имя из реестра DPmeta или [ANY];
 * [workdir] — каталог внутри проекта (`null` — корень); [path] — абсолютный путь
 * гостя или путь от [workdir]; [args] — как есть, без оболочки.
 */
data class CommandSpec(
    val id: String,
    val title: String,
    val project: String,
    val workdir: String?,
    val path: String,
    val args: List<String>,
    val plan: Plan,
    val level: Int,
    val packages: List<String>,
    val expect: String?,
    val timeoutMinutes: Int,
) {
    /** План — для моста Termux (F-41); в Linux-среде запуск один (D-82). */
    enum class Plan(val code: String) {
        FOREGROUND("foreground"),
        BACKGROUND("background"),
        ;

        companion object {
            fun of(code: String): Plan? = entries.firstOrNull { it.code == code }
        }
    }

    /** Проект выбирается на экране — из проектов со своим клоном. */
    val anyProject: Boolean get() = project == ANY

    /** Программа — из системы гостя, а не из клона. */
    val absolute: Boolean get() = path.startsWith("/")

    /** Командная строка для экрана: программа и аргументы через пробел, аргумент с пробелом — в кавычках. */
    val line: String get() = (listOf(path) + args).joinToString(" ") { if (it.isEmpty() || it.any(Char::isWhitespace)) "«$it»" else it }

    companion object {
        const val ANY: String = "*"
    }
}

/** Реестр команд — порядок файла сохраняется (порядок показа). */
data class CommandRegistry(val schema: Int, val commands: List<CommandSpec>) {
    fun byId(id: String): CommandSpec? = commands.firstOrNull { it.id == id }
}

/**
 * Строгий разбор `config/commands.toml` — отказ с местом (D-82): другая схема —
 * `FORMAT_VERSION_UNSUPPORTED`, неизвестный ключ — `FIELD_UNKNOWN`, нет ключа или
 * команд — `FIELD_MISSING`, не тот тип — `FIELD_TYPE`, остальное — `VALUE_INVALID`:
 * повтор id, путь с «..», программа гостя не из `/bin`, `/usr/bin`, `/usr/local/bin`,
 * уровень не 0 и не 1 (уровней 2 и 3 в реестре нет), имя пакета не Alpine, предел
 * времени вне 1…30 минут, управляющий знак в строке.
 */
object CommandFormat {
    const val SCHEMA = 1

    /** Предел времени по умолчанию, минуты. */
    const val DEFAULT_TIMEOUT = 10
    const val MAX_TIMEOUT = 30
    const val MAX_ARGS = 32
    const val MAX_ARG = 512
    const val MAX_PACKAGES = 16

    private val TOP = setOf("schema", "command")
    private val COMMAND = setOf("id", "title", "project", "workdir", "path", "args", "plan", "level", "packages", "expect", "timeout")
    private val ID = Regex("[a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)*")
    private val PROJECT = Regex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}")
    private val PATH_PART = Regex("[A-Za-z0-9._+-]+")

    /** Имя пакета Alpine — как у `apk` подсистемы «AI» (`LinuxEnvironment`). */
    private val PACKAGE = Regex("[a-z0-9][a-z0-9+._-]{0,63}")

    /** Каталоги программ гостя: команды из системы Alpine, а не из чужих каталогов. */
    val GUEST_BIN: List<String> = listOf("/bin/", "/usr/bin/", "/usr/local/bin/")

    fun parse(text: String, source: String = "commands.toml"): Outcome<CommandRegistry> {
        val doc = when (val t = Toml.parse(text, source)) {
            is Outcome.Failure -> return t
            is Outcome.Success -> t.value
        }
        unknown(doc, TOP, source)?.let { return it }
        val schemaValue = doc["schema"] ?: return missing("schema", source)
        val schema = (schemaValue as? JsonNumber)?.toLongExact() ?: return typeError("schema", source)
        if (schema != SCHEMA.toLong()) {
            return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "схема $schema не поддержана (поддержана $SCHEMA)", "$source:schema")
        }
        val list = doc["command"] ?: return missing("command", source)
        if (list !is JsonArray) return typeError("command", source)
        if (list.items.isEmpty()) return missing("command", source)
        val seen = HashSet<String>()
        val commands = list.items.mapIndexed { i, v ->
            val at = "$source:command[$i]"
            val obj = v as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "команда — не таблица", at)
            val c = when (val r = command(obj, at)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            if (!seen.add(c.id)) return Outcome.failure(DiagCode.VALUE_INVALID, "id «${c.id}» — уже был", "$at.id")
            c
        }
        return Outcome.success(CommandRegistry(SCHEMA, commands))
    }

    private fun command(obj: JsonObject, at: String): Outcome<CommandSpec> {
        unknown(obj, COMMAND, at)?.let { return it }
        for (key in listOf("id", "title", "project", "workdir", "path", "plan", "expect")) {
            if (obj[key] != null && obj[key] !is JsonString) return typeError(key, at)
        }
        val id = text(obj, "id", at, 64).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return missing("id", at)
        if (!ID.matches(id)) return invalid("id — строчные латинские буквы, цифры, «_-», части через точку", "$at.id")
        val title = text(obj, "title", at, 80).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return missing("title", at)
        val project = text(obj, "project", at, 64).let { if (it is Outcome.Failure) return it else it.valueOrNull() } ?: return missing("project", at)
        if (project != CommandSpec.ANY && !PROJECT.matches(project)) return invalid("проект — имя из реестра или «*»", "$at.project")
        val workdir = (obj["workdir"] as? JsonString)?.value
        if (workdir != null && !relative(workdir)) return invalid("workdir — каталог внутри проекта: относительный, без «..» и пустых частей", "$at.workdir")
        val path = (obj["path"] as? JsonString)?.value ?: return missing("path", at)
        if (path.startsWith("/")) {
            if (GUEST_BIN.none { path.startsWith(it) } || !relative(path.removePrefix("/"))) {
                return invalid("программа гостя — из ${GUEST_BIN.joinToString(", ")}, без «..»", "$at.path")
            }
        } else if (!relative(path)) {
            return invalid("path — путь от workdir без «..» или абсолютный путь гостя", "$at.path")
        }
        val args = strings(obj, "args", at, MAX_ARGS, MAX_ARG).let { if (it is Outcome.Failure) return it else it.valueOrNull()!! }
        val planText = (obj["plan"] as? JsonString)?.value ?: return missing("plan", at)
        val plan = CommandSpec.Plan.of(planText) ?: return invalid("план — foreground или background", "$at.plan")
        val levelValue = obj["level"] ?: return missing("level", at)
        val level = (levelValue as? JsonNumber)?.toLongExact() ?: return typeError("level", at)
        if (level != 0L && level != 1L) return invalid("уровень — 0 (чтение) или 1 (правка рабочего дерева клона); уровней 2 и 3 в реестре нет", "$at.level")
        val packages = strings(obj, "packages", at, MAX_PACKAGES, 64).let { if (it is Outcome.Failure) return it else it.valueOrNull()!! }
        packages.firstOrNull { !PACKAGE.matches(it) }?.let { return invalid("«$it» — не имя пакета Alpine: строчные буквы, цифры, «+._-»", "$at.packages") }
        val expect = text(obj, "expect", at, 200).let { if (it is Outcome.Failure) return it else it.valueOrNull() }
        val timeout = when (val t = obj["timeout"]) {
            null -> DEFAULT_TIMEOUT.toLong()
            else -> (t as? JsonNumber)?.toLongExact() ?: return typeError("timeout", at)
        }
        if (timeout !in 1..MAX_TIMEOUT) return invalid("предел времени — 1…$MAX_TIMEOUT минут", "$at.timeout")
        return Outcome.success(CommandSpec(id, title, project, workdir, path, args, plan, level.toInt(), packages, expect, timeout.toInt()))
    }

    private fun relative(p: String): Boolean =
        p.isNotEmpty() && !p.startsWith("/") && p.split('/').all { it.isNotEmpty() && it != "." && it != ".." && PATH_PART.matches(it) }

    /** Строка ключа: пустая — как нет; длиннее [max] или с управляющим знаком — отказ. */
    private fun text(obj: JsonObject, key: String, at: String, max: Int): Outcome<String?> {
        val v = (obj[key] as? JsonString)?.value?.takeIf { it.isNotBlank() } ?: return Outcome.success(null)
        if (v.length > max) return invalid("$key — не длиннее $max знаков", "$at.$key")
        if (v.any { it.isISOControl() }) return invalid("$key — без управляющих знаков", "$at.$key")
        return Outcome.success(v)
    }

    private fun strings(obj: JsonObject, key: String, at: String, maxItems: Int, maxLength: Int): Outcome<List<String>> {
        val v = obj[key] ?: return Outcome.success(emptyList())
        val arr = v as? JsonArray ?: return typeError(key, at)
        if (arr.items.size > maxItems) return invalid("$key — не больше $maxItems", "$at.$key")
        return Outcome.success(
            arr.items.mapIndexed { i, item ->
                val s = (item as? JsonString)?.value ?: return typeError("$key[$i]", at)
                if (s.length > maxLength || s.any { it.isISOControl() }) return invalid("$key[$i] — до $maxLength знаков, без управляющих", "$at.$key[$i]")
                s
            },
        )
    }

    private fun unknown(obj: JsonObject, allowed: Set<String>, at: String): Outcome.Failure? =
        obj.fields.keys.firstOrNull { it !in allowed }?.let { Outcome.failure(DiagCode.FIELD_UNKNOWN, "неизвестный ключ «$it»", "$at.$it") }

    private fun missing(key: String, at: String) = Outcome.failure(DiagCode.FIELD_MISSING, "нет ключа $key", "$at.$key")

    private fun typeError(key: String, at: String) = Outcome.failure(DiagCode.FIELD_TYPE, "неверный тип ключа $key", "$at.$key")

    private fun invalid(message: String, at: String) = Outcome.failure(DiagCode.VALUE_INVALID, message, at)
}

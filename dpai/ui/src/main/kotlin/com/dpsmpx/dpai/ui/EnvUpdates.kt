package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.AlpineVersion

/**
 * Проверка обновлений окружения (D-197; `DPmetaApp:D-171`, шаг 7): пакеты Alpine — по выводу
 * `apk version -l '<'` после `apk update` (так отвечает сам apk; формат сверен с apk-tools
 * 3.0.8: заголовок `Installed: Available:`, строки `имя-версия < новая`), компоненты — по
 * версиям этой сборки DP_IDE ([AppComponents.Component.outdated], без сети). Итог хранится
 * (`dpai.env-updates` v1) и показывается на экране; ставит только человек.
 */
object EnvUpdates {
    const val FORMAT = "dpai.env-updates"
    const val VERSION = 1

    data class Update(val name: String, val installed: String, val available: String)

    data class Result(val at: Long, val packages: List<Update>, val components: List<Update>) {
        val count: Int get() = packages.size + components.size
    }

    /** Разбор вывода `apk version -l '<'`: имя и версию разделяет база ([inv]); неразборчивое — пропуск. */
    fun parse(lines: List<String>, inv: AlpinePackages.Inventory): List<Update> {
        val byFull = inv.packages.associateBy { "${it.name}-${it.version}" }
        return lines.mapNotNull { raw ->
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 3 || parts[1] != "<") return@mapNotNull null
            val p = byFull[parts[0]] ?: return@mapNotNull null
            val next = parts[2].takeIf(AlpineVersion::valid) ?: return@mapNotNull null
            Update(p.name, p.version, next)
        }.distinctBy { it.name }.sortedBy { it.name }
    }

    fun components(list: List<AppComponents.Component>): List<Update> =
        list.filter { it.outdated }.map { Update(it.title, it.version.orEmpty(), it.expected.orEmpty()) }

    /** Текст уведомления; обновлений нет — `null` (уведомления нет). */
    fun notice(r: Result): String? {
        if (r.count == 0) return null
        return buildString {
            if (r.packages.isNotEmpty()) append("Пакетов Alpine: ${r.packages.size} — " + r.packages.take(MAX_LISTED).joinToString { "${it.name} ${it.installed} → ${it.available}" } + more(r.packages.size))
            if (r.components.isNotEmpty()) {
                if (isNotEmpty()) append(". ")
                append("Компонентов: ${r.components.size} — " + r.components.take(MAX_LISTED).joinToString { "${it.name} → ${it.available}" })
            }
            append(". Ставите вы: «Окружение».")
        }
    }

    /** Уведомлять ли снова: набор обновлений не тот, о котором уже говорили. */
    fun changed(now: Result, last: Result?): Boolean =
        now.count > 0 && (last == null || now.packages.map { it.name to it.available }.toSet() != last.packages.map { it.name to it.available }.toSet() ||
            now.components.map { it.name to it.available } != last.components.map { it.name to it.available })

    fun encode(r: Result): String = CanonicalJson.write(
        JsonObject.of(
            "format" to JsonString(FORMAT), "version" to JsonNumber.of(VERSION), "at" to JsonNumber.of(r.at),
            "packages" to JsonArray(r.packages.map(::item)), "components" to JsonArray(r.components.map(::item)),
        ),
    )

    fun decode(text: String): Outcome<Result> {
        val o = (JsonParser().parse(text) as? Outcome.Success)?.value as? JsonObject ?: return Outcome.failure(DiagCode.VALUE_INVALID, "итог проверки обновлений не разобран")
        if ((o["format"] as? JsonString)?.value != FORMAT || (o["version"] as? JsonNumber)?.toLongExact() != VERSION.toLong()) return Outcome.failure(DiagCode.FORMAT_MISMATCH, "итог проверки обновлений другого формата")
        val at = (o["at"] as? JsonNumber)?.toLongExact() ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет времени проверки")
        fun list(k: String) = (o[k] as? JsonArray)?.items?.mapNotNull { v ->
            val x = v as? JsonObject ?: return@mapNotNull null
            Update((x["name"] as? JsonString)?.value ?: return@mapNotNull null, (x["installed"] as? JsonString)?.value.orEmpty(), (x["available"] as? JsonString)?.value.orEmpty())
        }.orEmpty()
        return Outcome.success(Result(at, list("packages"), list("components")))
    }

    private fun item(u: Update) = JsonObject.of("name" to JsonString(u.name), "installed" to JsonString(u.installed), "available" to JsonString(u.available))

    private fun more(n: Int): String = if (n > MAX_LISTED) " и ещё ${n - MAX_LISTED}" else ""

    private const val MAX_LISTED = 5
}

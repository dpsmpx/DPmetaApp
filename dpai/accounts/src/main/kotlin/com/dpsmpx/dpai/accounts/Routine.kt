package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.net.URI
import java.net.URISyntaxException

/**
 * Маршрут Claude Code — отдельный вид аккаунта (D-116, `DPmetaApp:D-08`,
 * `DPmetaApp:D-44`): облачная сессия запускается вызовом `/fire` своего
 * маршрута по его токену. Токена здесь нет: его держит хранилище ключей
 * оболочки (Android Keystore) под [id] маршрута (D-14).
 *
 * [trigger] — id маршрута (`trig_…`); [subscription] — подписка, в которой
 * маршрут создан (для подписи на экране, `null` — не указана); [project] —
 * ключ проекта экосистемы, для которого маршрут заведён; [brief] — шаблон
 * поручения по умолчанию (`DPmetaApp:config/briefs/<brief>.md`).
 */
data class Routine(
    val id: AccountId,
    val name: String,
    val trigger: String,
    val subscription: SubscriptionId?,
    val project: String?,
    val brief: String?,
) {
    /** Адрес API-триггера маршрута: только `api.anthropic.com` и только свой `trig_…`. */
    val fireUrl: String get() = Routines.FIRE_BASE + trigger + "/fire"
}

/** Проверка полей маршрута: что сохраняется, то и годится для запуска. */
object Routines {
    const val MAX_NAME = 64
    const val MAX_INPUT = 512

    /** Адрес API-триггеров маршрутов (`docs/research/ROUTINES_FIRE.md`). */
    const val FIRE_BASE = "https://api.anthropic.com/v1/claude_code/routines/"

    private val TRIGGER = Regex("trig_[A-Za-z0-9]{1,64}")
    private val KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * id маршрута из того, что вставил человек: адрес `/fire` целиком (как
     * его показывает claude.ai) или сам `trig_…`. Иной хост, путь или схема —
     * `null`: токен маршрута не уйдёт на чужой адрес.
     */
    fun trigger(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.length > MAX_INPUT) return null
        if (TRIGGER.matches(s)) return s
        val u = try {
            URI(s)
        } catch (_: URISyntaxException) {
            return null
        }
        if (u.scheme?.lowercase() != "https" || u.host?.lowercase() != "api.anthropic.com") return null
        if (u.userInfo != null || u.port != -1 || u.rawQuery != null || u.rawFragment != null) return null
        val path = u.rawPath ?: return null
        val prefix = "/v1/claude_code/routines/"
        if (!path.startsWith(prefix) || !path.endsWith("/fire")) return null
        return path.removePrefix(prefix).removeSuffix("/fire").takeIf { TRIGGER.matches(it) }
    }

    fun form(
        id: AccountId,
        name: String,
        triggerInput: String,
        subscription: SubscriptionId?,
        project: String?,
        brief: String?,
    ): Outcome<Routine> {
        val n = name.trim()
        val p = project?.trim()?.ifEmpty { null }
        val b = brief?.trim()?.ifEmpty { null }
        val t = trigger(triggerInput)
        return when {
            n.isEmpty() || n.length > MAX_NAME || n.any { it.isISOControl() } ->
                Outcome.failure(DiagCode.VALUE_INVALID, "имя маршрута — 1…$MAX_NAME знаков без управляющих", "routine")
            t == null ->
                Outcome.failure(DiagCode.VALUE_INVALID, "нужен адрес /fire маршрута (https://api.anthropic.com/v1/claude_code/routines/trig_…/fire) или его id trig_…", "routine")
            p != null && !KEY.matches(p) ->
                Outcome.failure(DiagCode.VALUE_INVALID, "проект — ключ до 64 знаков: латиница, цифры и ._-", "routine")
            b != null && !KEY.matches(b) ->
                Outcome.failure(DiagCode.VALUE_INVALID, "шаблон — ключ до 64 знаков: латиница, цифры и ._-", "routine")
            else -> Outcome.success(Routine(id, n, t, subscription, p, b))
        }
    }
}

/** Маршруты приложения — документ `dpai.routines` (`docs/DATA_MODEL.md`); токенов в нём нет. */
object RoutineBook {
    const val FORMAT = "dpai.routines"
    const val VERSION = 1
    const val MAX_ROUTINES = 64

    fun toJson(routines: List<Routine>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "routines" to JsonArray(
                routines.map { r ->
                    JsonObject(
                        linkedMapOf<String, JsonValue>(
                            "id" to JsonString(r.id.value),
                            "name" to JsonString(r.name),
                            "trigger" to JsonString(r.trigger),
                        ).also { m ->
                            r.subscription?.let { m["subscription"] = JsonString(it.value) }
                            r.project?.let { m["project"] = JsonString(it) }
                            r.brief?.let { m["brief"] = JsonString(it) }
                        },
                    )
                },
            ),
        ),
    )

    /** Чтение строгое (D-09), как у профилей: находка — с путём, документ с находкой не читается целиком. */
    fun fromJson(value: JsonValue): Outcome<List<Routine>> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("routines")?.items.orEmpty()
        f.finish()
        if (items.size > MAX_ROUTINES) d.error(DiagCode.VALUE_INVALID, "маршрутов больше $MAX_ROUTINES", "$.routines")
        val out = items.take(MAX_ROUTINES).mapIndexedNotNull { i, item ->
            val at = "$.routines[$i]"
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", at) }
            val r = JsonFields(obj, at, d)
            val id = r.string("id")?.let { IdKinds.ACCOUNT.parse(it).valueOrNull() }
            val name = r.string("name")
            val trigger = r.string("trigger")
            val subText = r.optString("subscription")
            val sub = subText?.let { IdKinds.SUBSCRIPTION.parse(it).valueOrNull() }
            val project = r.optString("project")
            val brief = r.optString("brief")
            r.finish()
            if (id == null || name == null || trigger == null || (subText != null && sub == null)) {
                d.error(DiagCode.VALUE_INVALID, "маршрут неполон или id не того вида", at)
                return@mapIndexedNotNull null
            }
            when (val v = Routines.form(id, name, trigger, sub, project, brief)) {
                is Outcome.Success -> v.value.takeIf { it.trigger == trigger }
                    ?: null.also { d.error(DiagCode.VALUE_INVALID, "trigger — только id trig_…", "$at.trigger") }
                is Outcome.Failure -> null.also { v.errors.forEach { e -> d.error(e.code, e.message, at) } }
            }
        }
        out.groupBy { it.id }.filterValues { it.size > 1 }.keys
            .forEach { d.error(DiagCode.VALUE_INVALID, "id маршрута повторяется: ${it.value}", "$.routines") }
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out, d.all)
    }
}

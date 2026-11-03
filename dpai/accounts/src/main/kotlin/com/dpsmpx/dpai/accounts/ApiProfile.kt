package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.net.URI
import java.net.URISyntaxException

/**
 * Провайдер API-профиля (D-98). [fixedBaseUrl] — адрес API провайдера с
 * версией пути; у OpenAI-совместимого его задаёт человек (OpenAI,
 * OpenRouter, DeepSeek, Mistral, свой сервер).
 */
enum class ProviderKind(val key: String, val fixedBaseUrl: String?) {
    ANTHROPIC("anthropic", "https://api.anthropic.com/v1"),
    OPENAI_COMPATIBLE("openai-compatible", null),
    GEMINI("gemini", "https://generativelanguage.googleapis.com/v1beta"),
    ;

    companion object {
        fun byKey(key: String): ProviderKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * API-профиль — отдельный вид аккаунта, не слот (D-98, `DPmetaApp:D-44`):
 * ходы Claude Code идут по подписке (D-29), профиль — для чата с моделью
 * по ключу. Ключа здесь нет: его держит хранилище ключей оболочки
 * (Android Keystore), документ профиля — только ссылка по [id] (D-14).
 *
 * [customHostAllowed] — человек отдельной отметкой разрешил адрес своего
 * сервера, которого нет во встроенном списке (`ProviderHosts`).
 * [maxOutputTokens] — предел длины ответа; `null` — по умолчанию провайдера.
 */
data class ApiProfile(
    val id: AccountId,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val maxOutputTokens: Int?,
    val customHostAllowed: Boolean,
) {
    /** Хост [baseUrl] в нижнем регистре, IPv6 — без скобок (как сверяет список адресов); адрес проверен при создании. */
    val host: String get() = URI(baseUrl).host.lowercase().removeSurrounding("[", "]")
}

/** Готовые адреса OpenAI-совместимых провайдеров (D-98): выбор вместо ручного ввода. */
object ApiPresets {
    data class Preset(val name: String, val baseUrl: String)

    val OPENAI_COMPATIBLE: List<Preset> = listOf(
        Preset("OpenAI", "https://api.openai.com/v1"),
        Preset("OpenRouter", "https://openrouter.ai/api/v1"),
        Preset("DeepSeek", "https://api.deepseek.com/v1"),
        Preset("Mistral", "https://api.mistral.ai/v1"),
    )
}

/** Проверка полей профиля: что сохраняется, то и годится для запроса. */
object ApiProfiles {
    const val MAX_NAME = 64
    const val MAX_URL = 256
    const val MAX_MODEL = 128
    const val MAX_OUTPUT_TOKENS = 1_000_000

    /** Модель: латиница, цифры и `._:/@-` — так пишут имена OpenAI, OpenRouter, Gemini и Ollama. */
    private val MODEL = Regex("[A-Za-z0-9._:/@+-]*")

    fun form(
        id: AccountId,
        name: String,
        kind: ProviderKind,
        baseUrl: String,
        model: String,
        maxOutputTokens: Int?,
        customHostAllowed: Boolean,
    ): Outcome<ApiProfile> {
        val n = name.trim()
        val m = model.trim()
        val url = (kind.fixedBaseUrl ?: baseUrl.trim()).trimEnd('/')
        return when {
            n.isEmpty() || n.length > MAX_NAME || n.any { it.isISOControl() } ->
                Outcome.failure(DiagCode.VALUE_INVALID, "имя профиля — 1…$MAX_NAME знаков без управляющих", "profile")
            m.length > MAX_MODEL || !MODEL.matches(m) ->
                Outcome.failure(DiagCode.VALUE_INVALID, "модель — до $MAX_MODEL знаков: латиница, цифры и ._:/@+-", "profile")
            maxOutputTokens != null && maxOutputTokens !in 1..MAX_OUTPUT_TOKENS ->
                Outcome.failure(DiagCode.VALUE_INVALID, "предел ответа — 1…$MAX_OUTPUT_TOKENS токенов", "profile")
            else -> baseUrl(url).let { bad ->
                if (bad != null) {
                    Outcome.failure(DiagCode.VALUE_INVALID, bad, "profile")
                } else {
                    // У Anthropic и Gemini адрес свой и встроенный: отметка своего адреса не нужна.
                    Outcome.success(ApiProfile(id, n, kind, url, m, maxOutputTokens, customHostAllowed && kind.fixedBaseUrl == null))
                }
            }
        }
    }

    /** Почему адрес API не годится; `null` — годится. Только http(s), хост, без входа, запроса и якоря. */
    fun baseUrl(url: String): String? {
        if (url.isEmpty() || url.length > MAX_URL) return "адрес API — 1…$MAX_URL знаков"
        val u = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "адрес API не разбирается"
        }
        val scheme = u.scheme?.lowercase()
        return when {
            scheme != "https" && scheme != "http" -> "адрес API — https:// (http:// — только своя сеть)"
            u.host.isNullOrEmpty() -> "в адресе API нет хоста"
            u.userInfo != null -> "имя и пароль в адресе запрещены — ключ вводится отдельно"
            u.rawQuery != null || u.rawFragment != null -> "адрес API — без ?запроса и #якоря"
            else -> null
        }
    }
}

/** Профили приложения — документ `dpai.api-profiles` (`docs/DATA_MODEL.md`); ключей в нём нет. */
object ApiProfileBook {
    const val FORMAT = "dpai.api-profiles"
    const val VERSION = 1
    const val MAX_PROFILES = 64

    fun toJson(profiles: List<ApiProfile>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "profiles" to JsonArray(
                profiles.map { p ->
                    JsonObject(
                        linkedMapOf<String, JsonValue>(
                            "id" to JsonString(p.id.value),
                            "name" to JsonString(p.name),
                            "provider" to JsonString(p.kind.key),
                            "baseUrl" to JsonString(p.baseUrl),
                            "model" to JsonString(p.model),
                            "customHost" to JsonBool(p.customHostAllowed),
                        ).also { m -> p.maxOutputTokens?.let { m["maxOutputTokens"] = JsonNumber.of(it) } },
                    )
                },
            ),
        ),
    )

    /**
     * Чтение строгое (D-09): неизвестное поле, чужой провайдер, недопустимое
     * значение — находка с путём, а не профиль «похожий» (D-05); документ с
     * находкой не читается целиком.
     */
    fun fromJson(value: JsonValue): Outcome<List<ApiProfile>> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("profiles")?.items.orEmpty()
        f.finish()
        if (items.size > MAX_PROFILES) d.error(DiagCode.VALUE_INVALID, "профилей больше $MAX_PROFILES", "$.profiles")
        val out = items.take(MAX_PROFILES).mapIndexedNotNull { i, item ->
            val at = "$.profiles[$i]"
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", at) }
            val p = JsonFields(obj, at, d)
            val id = p.string("id")?.let { IdKinds.ACCOUNT.parse(it).valueOrNull() }
            val name = p.string("name")
            val kind = p.string("provider")?.let(ProviderKind::byKey)
            val baseUrl = p.string("baseUrl")
            val model = p.string("model")
            val custom = p.boolean("customHost")
            val max = p.optLong("maxOutputTokens")
            p.finish()
            if (id == null || name == null || kind == null || baseUrl == null || model == null || custom == null) {
                d.error(DiagCode.VALUE_INVALID, "профиль неполон, id не аккаунта или провайдер неизвестен", at)
                return@mapIndexedNotNull null
            }
            if (kind.fixedBaseUrl != null && baseUrl != kind.fixedBaseUrl) {
                d.error(DiagCode.VALUE_INVALID, "адрес ${kind.key} — только встроенный", "$at.baseUrl")
                return@mapIndexedNotNull null
            }
            val maxInt = max?.let { if (it in 1..ApiProfiles.MAX_OUTPUT_TOKENS.toLong()) it.toInt() else -1 }
            when (val r = ApiProfiles.form(id, name, kind, baseUrl, model, maxInt, custom)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> null.also { r.errors.forEach { e -> d.error(e.code, e.message, at) } }
            }
        }
        val ids = out.groupBy { it.id }.filterValues { it.size > 1 }.keys
        ids.forEach { d.error(DiagCode.VALUE_INVALID, "id профиля повторяется: ${it.value}", "$.profiles") }
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out, d.all)
    }
}

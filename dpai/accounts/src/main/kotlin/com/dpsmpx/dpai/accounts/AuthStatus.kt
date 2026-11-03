package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.security.AccountIdentity

/**
 * Что Claude Code сообщает о входе слота (`claude auth status --json`,
 * `docs/research/CLAUDE_CODE_CLI.md`, §4): вошёл ли, каким способом, какой
 * аккаунт. Токенов в выводе нет; email сразу превращается в отпечаток и
 * дальше не хранится (D-30).
 */
data class AuthStatus(
    val loggedIn: Boolean,
    val authMethod: String,
    val apiProvider: String?,
    val identity: ContentHash?,
    val plan: String?,
) {
    /** Вход формой Claude у первого провайдера — единственный разрешённый у Claude (D-29). */
    val isClaudeLogin: Boolean get() = loggedIn && authMethod == CLAUDE_AI && (apiProvider == null || apiProvider == FIRST_PARTY)

    /** Разрешённый вход исполнителя (D-100): Claude — форма Claude, Codex — ChatGPT, Gemini — аккаунт Google. */
    fun isAllowed(executor: Executor): Boolean = when (executor) {
        Executor.CLAUDE -> isClaudeLogin
        Executor.CODEX -> loggedIn && authMethod == CHATGPT
        Executor.GEMINI -> loggedIn && authMethod == GOOGLE
    }

    companion object {
        const val CLAUDE_AI = "claude.ai"
        const val FIRST_PARTY = "firstParty"

        /** Способ входа Codex аккаунтом ChatGPT (`codex login status`: «Logged in using ChatGPT»). */
        const val CHATGPT = "chatgpt"

        /** Способ входа Gemini аккаунтом Google (`security.auth.selectedType`). */
        const val GOOGLE = "oauth-personal"

        fun allowedPhrase(executor: Executor): String = when (executor) {
            Executor.CLAUDE -> "вход формой Claude"
            Executor.CODEX -> "вход аккаунтом ChatGPT"
            Executor.GEMINI -> "вход аккаунтом Google"
        }

        /**
         * Вывод `codex login status` (`docs/research/CODEX_CLI.md`, §2): «Logged
         * in using ChatGPT» — вход ChatGPT, «Logged in using …» — иной способ
         * (ключ API, токен, Bedrock), «Not logged in» — входа нет. Иное —
         * отказ разбора, а не догадка (D-05). Аккаунт Codex не называет.
         */
        fun fromCodexStatus(text: String): Outcome<AuthStatus> {
            val line = text.lines().map { it.trim() }.lastOrNull { it.startsWith("Logged in using") || it.startsWith("Not logged in") }
                ?: return Outcome.failure(DiagCode.CLAUDE_AUTH_STATUS_INVALID, "codex login status: нет строки о входе", "codex")
            return Outcome.success(
                when {
                    line.startsWith("Not logged in") -> AuthStatus(false, "none", null, null, null)
                    line.removePrefix("Logged in using").trim().equals("ChatGPT", ignoreCase = true) -> AuthStatus(true, CHATGPT, null, null, null)
                    else -> AuthStatus(true, line.removePrefix("Logged in using").trim().lowercase().replace(' ', '-'), null, null, null)
                },
            )
        }

        /**
         * Проба входа Gemini (`docs/research/GEMINI_CLI.md`, §2): строки
         * `selectedType=<способ>` (из `settings.json`) и `creds=yes|no` (есть
         * ли файл входа; сам файл не читается, D-04). Вход аккаунтом Google —
         * `oauth-personal` и файл на месте; иной способ — как сообщён.
         */
        fun fromGeminiProbe(text: String): Outcome<AuthStatus> {
            val kv = text.lines().mapNotNull { l -> l.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim().trim('"') } }.toMap()
            val creds = kv["creds"] ?: return Outcome.failure(DiagCode.CLAUDE_AUTH_STATUS_INVALID, "проба входа Gemini: нет строки creds", "gemini")
            val type = kv["selectedType"].orEmpty()
            return Outcome.success(
                when {
                    type.isEmpty() -> AuthStatus(false, "none", null, null, null)
                    type == GOOGLE -> AuthStatus(creds == "yes", GOOGLE, null, null, null)
                    else -> AuthStatus(true, type, null, null, null)
                },
            )
        }

        /**
         * Чтение вывода провайдера — терпимое: нужны `loggedIn` и
         * `authMethod`, остальные поля необязательны, незнакомые
         * пропускаются (формат чужой и растёт; строгость D-09 — для своих
         * форматов).
         */
        fun fromClaudeJson(value: JsonValue): Outcome<AuthStatus> {
            val o = value as? JsonObject ?: return Outcome.failure(DiagCode.FIELD_TYPE, "ожидался объект", "$")
            val loggedIn = (o["loggedIn"] as? JsonBool)?.value
                ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет loggedIn", "$.loggedIn")
            val method = (o["authMethod"] as? JsonString)?.value
                ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет authMethod", "$.authMethod")
            fun str(k: String) = (o[k] as? JsonString)?.value?.takeIf { it.isNotBlank() }
            return Outcome.success(
                AuthStatus(loggedIn, method, str("apiProvider"), fingerprint(str("orgId"), str("email")), str("subscriptionType")),
            )
        }

        /** Чтение `dpai.auth-status` (строго, D-09); `invalid` — `CLAUDE_AUTH_STATUS_INVALID`. */
        fun fromAgentJson(value: JsonValue): Outcome<AuthStatus> {
            val d = DiagnosticCollector()
            val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, AccountIdentity.FORMAT, 1..AccountIdentity.VERSION, d) ?: return Outcome.Failure(d.all)
            if ((f.raw("invalid", required = false) as? JsonBool)?.value == true) {
                f.finish()
                return Outcome.failure(DiagCode.CLAUDE_AUTH_STATUS_INVALID, "claude auth status: не JSON входа (текст на target не сохранён)", "$")
            }
            val loggedIn = f.boolean("loggedIn")
            val method = f.string("authMethod")
            val provider = f.optString("apiProvider")
            val plan = f.optString("plan")
            val identity = f.optString("identity")?.let { h ->
                ContentHash.parse(h).valueOrNull() ?: null.also { d.error(DiagCode.VALUE_INVALID, "неверный отпечаток", "$.identity") }
            }
            f.finish()
            if (d.hasErrors || loggedIn == null || method == null) return Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "статус входа неполон", "$")) })
            return Outcome.success(AuthStatus(loggedIn, method, provider, identity, plan))
        }

        /** Отпечаток аккаунта — `AccountIdentity.fingerprint` (одна каноническая форма с агентом). */
        fun fingerprint(orgId: String?, email: String?): ContentHash? = AccountIdentity.fingerprint(orgId, email)
    }
}

package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.ContentHash
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
    /** Вход формой Claude у первого провайдера — единственный разрешённый (D-29). */
    val isClaudeLogin: Boolean get() = loggedIn && authMethod == CLAUDE_AI && (apiProvider == null || apiProvider == FIRST_PARTY)

    companion object {
        const val CLAUDE_AI = "claude.ai"
        const val FIRST_PARTY = "firstParty"

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

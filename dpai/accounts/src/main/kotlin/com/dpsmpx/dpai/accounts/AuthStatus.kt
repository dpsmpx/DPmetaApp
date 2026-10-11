package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

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

        /** Отпечаток аккаунта: хеш организации и email в нижнем регистре; нет обоих — неизвестен. */
        fun fingerprint(orgId: String?, email: String?): ContentHash? {
            if (orgId == null && email == null) return null
            val canonical = "dpai.account-identity\n" + (orgId ?: "") + "\n" + (email?.trim()?.lowercase() ?: "")
            return Hashing.sha256(canonical)
        }
    }
}

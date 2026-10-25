package com.dpsmpx.dpai.security

import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Аккаунт Claude без личных данных (D-30, D-69): отпечаток вместо email и
 * организации. Здесь — потому что свод нужен и агенту на target (он не
 * пишет вывод `claude auth status` на диск), и модулю аккаунтов, и mock
 * агента: у всех — одна каноническая форма.
 */
object AccountIdentity {
    const val FORMAT = "dpai.auth-status"
    const val VERSION = 1

    /** Отпечаток аккаунта: хеш организации и email в нижнем регистре; нет обоих — неизвестен. */
    fun fingerprint(orgId: String?, email: String?): ContentHash? {
        if (orgId == null && email == null) return null
        val canonical = "dpai.account-identity\n" + (orgId ?: "") + "\n" + (email?.trim()?.lowercase() ?: "")
        return Hashing.sha256(canonical)
    }

    /**
     * Вывод `claude auth status --json` → `dpai.auth-status` v1: вход,
     * способ, провайдер, план и отпечаток — без email и организации. Не
     * разобралось (нет `loggedIn` или `authMethod`) — только `invalid`, без
     * исходного текста: в нём мог быть email. Чтение вывода провайдера —
     * терпимое: формат чужой и растёт (`docs/research/CLAUDE_CODE_CLI.md`, §4).
     */
    fun summarize(claudeStdout: String): JsonObject {
        val out = linkedMapOf<String, JsonValue>("format" to JsonString(FORMAT), "version" to JsonNumber.of(VERSION))
        val o = JsonParser().parse(claudeStdout).valueOrNull() as? JsonObject
        val loggedIn = (o?.get("loggedIn") as? JsonBool)?.value
        val method = (o?.get("authMethod") as? JsonString)?.value
        if (o == null || loggedIn == null || method == null) return JsonObject(out.apply { put("invalid", JsonBool(true)) })
        fun str(k: String) = (o[k] as? JsonString)?.value?.takeIf { it.isNotBlank() }
        out["loggedIn"] = JsonBool(loggedIn)
        out["authMethod"] = JsonString(method)
        str("apiProvider")?.let { out["apiProvider"] = JsonString(it) }
        str("subscriptionType")?.let { out["plan"] = JsonString(it) }
        fingerprint(str("orgId"), str("email"))?.let { out["identity"] = JsonString(it.toString()) }
        return JsonObject(out)
    }
}

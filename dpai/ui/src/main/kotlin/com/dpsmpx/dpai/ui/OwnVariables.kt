package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.linux.GuestSecrets

/**
 * Свои переменные окружения автора (D-193; `DPmetaApp:D-171`, шаг 3) — не секреты: для
 * терминала, ходов ИИ и ручных профилей в Linux приложения. Хранятся в данных приложения
 * (`dpai.env-vars` v1), гостю не видны файлом. Проверка — как у окружения профилей
 * ([ManualVars.envProblem]) и строже: секретное имя или значение, похожее на ключ
 * ([Redactor]), — отказ: секрет — в «Секретах» с «давать ИИ»; переменные, которыми
 * управляет приложение, загрузчик, оболочка, Git или язык вывода, — отказ: они меняли бы
 * работу самого приложения (разбор вывода Git, вход слотов), а не только программ автора.
 */
object OwnVariables {
    const val FORMAT = "dpai.env-vars"
    const val VERSION = 1
    const val MAX = 64

    data class Var(val name: String, val value: String)

    private val RESERVED = setOf(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD", "TERM", "COLORTERM", "TMPDIR", "IFS", "ENV", "BASH_ENV",
        "PS1", "PS2", "PS4", "HOSTNAME", "LANG", "LANGUAGE", "ANDROID_HOME", "ANDROID_SDK_ROOT",
    )
    private val RESERVED_PREFIXES = listOf("LD_", "LC_", "PROOT_", "ADB_", "DPAI_", GuestSecrets.PREFIX, "CLAUDE_", "CODEX_", "GEMINI_CLI_", "GIT_", "SSH_", "NPM_CONFIG_", "_")

    /** Почему переменную нельзя сохранить; `null` — можно. */
    fun problem(name: String, value: String): String? {
        ManualVars.envProblem(name, value)?.let { return it }
        return when {
            !GuestSecrets.NAME.matches(name) -> "имя — латиница в верхнем регистре, цифры и «_», до 64 знаков"
            name in GuestSecrets.MANAGED || name in GuestSecrets.SLOT_OVERRIDING -> "«$name» — переменная приложения или входа слотов ИИ"
            name in RESERVED || RESERVED_PREFIXES.any { name.startsWith(it) } -> "«$name» меняет работу оболочки, загрузчика, Git или приложения — её задаёт приложение"
            Redactor.STANDARD.redact(value) != value -> "значение «$name» похоже на ключ или пароль — секрет добавьте в «Секретах»"
            else -> null
        }
    }

    /** Проверить весь список: имена не повторяются, переменных не больше [MAX]. */
    fun check(vars: List<Var>): Outcome<List<Var>> {
        if (vars.size > MAX) return Outcome.failure(DiagCode.VALUE_INVALID, "своих переменных — не больше $MAX")
        val seen = HashSet<String>()
        for (v in vars) {
            problem(v.name, v.value)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, v.name) }
            if (!seen.add(v.name)) return Outcome.failure(DiagCode.VALUE_INVALID, "«${v.name}» дважды", v.name)
        }
        return Outcome.success(vars)
    }

    fun encode(vars: List<Var>): String = CanonicalJson.write(
        JsonObject.of(
            "format" to JsonString(FORMAT), "version" to JsonNumber.of(VERSION),
            "vars" to JsonArray(vars.map { JsonObject.of("name" to JsonString(it.name), "value" to JsonString(it.value)) }),
        ),
    )

    /** Разбор; чужой формат или версия — отказ; строка, что больше не проходит проверку, — пропуск. */
    fun decode(text: String): Outcome<List<Var>> {
        val root = (JsonParser().parse(text) as? Outcome.Success)?.value as? JsonObject
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "файл своих переменных не разобран")
        if ((root["format"] as? JsonString)?.value != FORMAT) return Outcome.failure(DiagCode.FORMAT_MISMATCH, "файл своих переменных другого формата")
        if ((root["version"] as? JsonNumber)?.toLongExact() != VERSION.toLong()) return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "версия файла своих переменных не поддерживается")
        val items = (root["vars"] as? JsonArray)?.items.orEmpty()
        val out = items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val n = (o["name"] as? JsonString)?.value ?: return@mapNotNull null
            val v = (o["value"] as? JsonString)?.value ?: return@mapNotNull null
            Var(n, v).takeIf { problem(n, v) == null }
        }.distinctBy { it.name }.take(MAX)
        return Outcome.success(out)
    }

    /**
     * Окружение процесса гостя: [base] (окружение гостя или профиля) сильнее своих
     * переменных — профиль может их переопределить; свои — сильнее ничего.
     */
    fun merge(own: List<Var>, base: Map<String, String>): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            for (v in own) put(v.name, v.value)
            putAll(base)
        }
}

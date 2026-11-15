package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Команда создания ключа выпуска (`DPmetaApp:D-158`): поля формы приложения —
 * в готовую команду `keytool` для Termux/Linux или PowerShell, в буфер обмена.
 * Приложение эти данные не хранит. Пароль — в переменной окружения только для
 * `keytool` (`-storepass:env`), а не в аргументах процесса; история оболочки на время
 * команды выключена. Раскрытие пароля — только здесь (правило `secret-reveal`).
 */
object ReleaseKeyCommand {
    enum class Shell { POSIX, POWERSHELL }

    data class Form(
        val name: String,
        val organization: String,
        val country: String,
        val alias: String,
        val file: String,
        val years: Int,
        val password: Secret,
    )

    private val TEXT = Regex("[\\p{L}\\p{N} ._-]{1,64}")
    private val COUNTRY = Regex("[A-Z]{2}")
    private val ALIAS = Regex("[a-z0-9_-]{1,32}")
    private val FILE = Regex("[A-Za-z0-9._-]{1,64}\\.(p12|jks|keystore)")
    const val MIN_PASSWORD = 12
    const val DEFAULT_YEARS = 30

    /** Что не так в форме; `null` — можно собирать команду. */
    fun problem(f: Form): String? = when {
        !TEXT.matches(f.name.trim()) -> "имя — буквы, цифры, пробел, точка, дефис, до 64 знаков"
        f.organization.isNotBlank() && !TEXT.matches(f.organization.trim()) -> "организация — буквы, цифры, пробел, точка, дефис, до 64 знаков"
        !COUNTRY.matches(f.country.trim()) -> "страна — две латинские заглавные буквы, например RU"
        !ALIAS.matches(f.alias.trim()) -> "имя ключа — строчные латинские, цифры, «_» и «-»"
        !FILE.matches(f.file.trim()) -> "файл — латиница, цифры, «._-», окончание .p12, .jks или .keystore"
        f.years !in 1..50 -> "срок — от 1 до 50 лет (Google Play для обновлений просит не меньше 25)"
        f.password.reveal().length < MIN_PASSWORD -> "пароль — не короче $MIN_PASSWORD знаков"
        // Тот же файл потом импортируется в приложение: .p12 надёжно читается только с паролем ASCII.
        !f.password.reveal().all { it in ' '..'~' } -> "пароль — латиница, цифры, пробел и знаки, без кириллицы"
        else -> null
    }

    /** Команда для [shell]; файл — в домашней папке. */
    fun build(f: Form, shell: Shell): Outcome<String> {
        problem(f)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it) }
        val dname = listOfNotNull("CN=${f.name.trim()}", f.organization.trim().takeIf { it.isNotEmpty() }?.let { "O=$it" }, "C=${f.country.trim()}").joinToString(", ")
        val days = f.years * 365 + f.years / 4
        val args = "-genkeypair -v -storetype PKCS12 -keyalg RSA -keysize 4096 -validity $days -alias ${f.alias.trim()}"
        val pass = f.password.reveal()
        return Outcome.success(
            when (shell) {
                Shell.POSIX ->
                    "set +o history; DPMETA_KS_PASS=${posix(pass)} keytool $args -keystore ~/${f.file.trim()} " +
                        "-dname ${posix(dname)} -storepass:env DPMETA_KS_PASS -keypass:env DPMETA_KS_PASS; set -o history"
                Shell.POWERSHELL ->
                    "\$env:DPMETA_KS_PASS = ${ps(pass)}; keytool $args -keystore \"\$HOME\\${f.file.trim()}\" " +
                        "-dname ${ps(dname)} -storepass:env DPMETA_KS_PASS -keypass:env DPMETA_KS_PASS; Remove-Item Env:DPMETA_KS_PASS"
            },
        )
    }

    /** Строка в одинарных кавычках для sh: `'` — как `'\''`. */
    fun posix(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Строка в одинарных кавычках PowerShell: `'` удваивается. */
    fun ps(s: String): String = "'" + s.replace("'", "''") + "'"
}

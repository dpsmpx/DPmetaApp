package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Команда создания ключа выпуска (`DPmetaApp:D-158`, `DPmetaApp:D-160`): поля формы
 * приложения — в готовую команду `keytool` для Termux/Linux или PowerShell, в буфер
 * обмена. Приложение эти данные не хранит. Termux/bash/zsh: пароль — в переменной
 * окружения только для `keytool` (`-storepass:env`), а не в аргументах процесса; строка
 * начинается с пробела (`ignorespace`/`hist_ignore_space`) и убирает себя из истории сама:
 * bash — `history -d -1`, zsh — история сеанса не сохраняется (там `history -d` только
 * печатает список). PowerShell сохраняет историю строк сам (PSReadLine), поэтому туда
 * пароль не встраивается: его спрашивает `keytool`. Раскрытие пароля — только здесь
 * (правило `secret-reveal`).
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
        // PowerShell: пароль спросит keytool — поле пароля не нужно (проверка PR #190).
        val check = if (shell == Shell.POWERSHELL) f.copy(password = Secret("x".repeat(MIN_PASSWORD))) else f
        problem(check)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it) }
        val dname = listOfNotNull("CN=${f.name.trim()}", f.organization.trim().takeIf { it.isNotEmpty() }?.let { "O=$it" }, "C=${f.country.trim()}").joinToString(", ")
        val days = f.years * 365 + f.years / 4
        val args = "-genkeypair -v -storetype PKCS12 -keyalg RSA -keysize 4096 -validity $days -alias ${f.alias.trim()}"
        val pass = f.password.reveal()
        return Outcome.success(
            when (shell) {
                // Bash заносит строку в историю до исполнения: «set +o history» в той же строке не помогает
                // (проверка PR #190). Пробел в начале — не в историю при ignorespace/hist_ignore_space; иначе
                // bash удаляет строку сам, zsh (его «history -d» только печатает) — не сохраняет историю сеанса.
                Shell.POSIX ->
                    " DPMETA_KS_PASS=${posix(pass)} keytool $args -keystore ~/${f.file.trim()} " +
                        "-dname ${posix(dname)} -storepass:env DPMETA_KS_PASS -keypass:env DPMETA_KS_PASS; " +
                        "if [ -n \"\${ZSH_VERSION-}\" ]; then unset HISTFILE; else history -d -1 2>/dev/null || unset HISTFILE; fi"
                // PSReadLine пишет каждую строку в свой файл истории — пароль в строку не идёт, его спросит keytool.
                Shell.POWERSHELL ->
                    "keytool $args -keystore \"\$HOME\\${f.file.trim()}\" -dname ${ps(dname)}"
            },
        )
    }

    /** Строка в одинарных кавычках для sh: `'` — как `'\''`. */
    fun posix(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Строка в одинарных кавычках PowerShell: `'` удваивается. */
    fun ps(s: String): String = "'" + s.replace("'", "''") + "'"
}

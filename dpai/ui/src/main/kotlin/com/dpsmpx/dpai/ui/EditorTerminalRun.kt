package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Явная команда пользователя в новой PTY; имена файлов никогда не становятся shell-кодом (D-138). */
object EditorTerminalRun {
    val COMMAND_KEYS = setOf("cpp", "python", "kotlin", "kts", "other")
    fun commandKey(path: String): String = if (path.endsWith(".kts", ignoreCase = true)) "kts" else when (RunLanguage.of(path)) {
        RunLanguage.CPP -> "cpp"
        RunLanguage.PYTHON -> "python"
        RunLanguage.KOTLIN -> "kotlin"
        RunLanguage.OTHER -> "other"
    }
    /** Старые узнаваемые шаблоны не применяются к другому языку; неизвестная общая команда остаётся пользовательской. */
    fun legacyCommandKey(command: String): String? = when {
        command.contains("DPAI_CXX_TOOLCHAIN") || command.contains("clang++") || command.contains("g++") -> "cpp"
        command.contains("DPAI_PYTHON") || Regex("\\bpython[0-9.]*\\b").containsMatchIn(command) -> "python"
        command.contains("kotlinc") && command.contains("-script") -> "kts"
        command.contains("kotlinc") || command.contains("gradlew") -> "kotlin"
        else -> null
    }
    fun defaultCommand(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "py", "pyw" -> PythonRuntime.defaultCommand
        "kt" -> KotlinRuntime.defaultCommand(script = false)
        "kts" -> KotlinRuntime.defaultCommand(script = true)
        "sh" -> "sh \"\$DPAI_FILE\""
        "cpp", "cc", "cxx", "c++" -> CppDependencies.singleFileCommand(CppDependencies.Report(0, emptyList(), emptyList()))
        else -> "" // Команда настоящего Gradle/многофайлового проекта задаётся пользователем.
    }
    /**
     * argv запуска в новой PTY: перейти в [root], выполнить [command] через `/bin/sh -c`,
     * напечатать код выхода и остаться в оболочке. [file] — путь открытого файла
     * (`DPAI_FILE`); у запуска программы проекта (D-155) файла нет — `null`.
     */
    fun argv(file: String?, root: String, command: String): Outcome<List<String>> {
        if ((file != null && (!file.startsWith('/') || file.any { it.code < 32 })) || !root.startsWith('/') || root.any { it.code < 32 } || command.isBlank() || command.length > 4096 || '\u0000' in command) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "Нужны путь в Linux, рабочая папка и команда запуска")
        }
        val script = """
            cd "${'$'}1" || exit
            DPAI_ROOT="${'$'}1"; DPAI_FILE="${'$'}2"; export DPAI_ROOT DPAI_FILE
            /bin/sh -c "${'$'}3"
            DPAI_RUN_STATUS=${'$'}?
            printf '\n[Код выхода: %s]\n' "${'$'}DPAI_RUN_STATUS"
            exec /bin/sh -l
        """.trimIndent()
        return Outcome.success(listOf("/bin/sh", "-c", script, "dpai-run", root, file.orEmpty(), command))
    }

    /**
     * Запуск программы на Windows-target (`DPmetaApp:D-128`; D-163): PowerShell в
     * папке [dir] — команда [command] как есть, затем `[Код выхода: N]`, и вкладка
     * остаётся PowerShell в той же папке (`-NoExit`). Сценарий идёт
     * `-EncodedCommand` (UTF-16LE в base64): по SSH Windows не передаются `$`,
     * `%`, кавычки (`RemoteShell`, A-06), а base64 — передаётся. Вывод — UTF-8.
     * Вся строка короче предела cmd.exe ([CMD_LIMIT]).
     */
    fun windowsArgv(dir: String, command: String): Outcome<List<String>> {
        val d = dir.trim()
        if (!WINDOWS_DIR.matches(d) || d.length > MAX_WINDOWS_DIR || d.any { it.code < 32 || it == '\u007f' }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "Нужна папка Windows вида C:\\проект (до $MAX_WINDOWS_DIR знаков)")
        }
        if (command.isBlank() || command.length > MAX_WINDOWS_COMMAND || command.any { it.code < 32 && it != '\t' || it == '\u007f' }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "Команда для Windows — одна строка до $MAX_WINDOWS_COMMAND знаков")
        }
        val q = quotePs(d)
        val script = listOf(
            "[Console]::OutputEncoding = [Text.Encoding]::UTF8",
            "\$OutputEncoding = [Text.Encoding]::UTF8",
            "try { Set-Location -LiteralPath $q -ErrorAction Stop } catch { [Console]::Out.Write(\"Папка не найдена: \$(\$_.Exception.Message)`n[Код выхода: 1]`n\"); return }",
            "\$env:DPAI_ROOT = $q",
            "\$global:LASTEXITCODE = \$null",
            command,
            "\$dpaiOk = \$?",
            "\$dpaiCode = if (\$null -ne \$LASTEXITCODE) { \$LASTEXITCODE } elseif (\$dpaiOk) { 0 } else { 1 }",
            "[Console]::Out.Write(\"`n[Код выхода: \$dpaiCode]`n\")",
        ).joinToString("\n")
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return Outcome.success(listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NoExit", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded))
    }

    /** Строка PowerShell в одинарных кавычках: кавычка (и типографские, которые PowerShell тоже считает кавычками) — удвоена. */
    internal fun quotePs(text: String): String =
        "'" + text.map { if (it in PS_QUOTES) "$it$it" else it.toString() }.joinToString("") + "'"

    private val WINDOWS_DIR = Regex("^[A-Za-z]:[\\\\/].*")
    private const val PS_QUOTES = "'\u2018\u2019\u201A\u201B"
    const val MAX_WINDOWS_DIR = 260
    const val MAX_WINDOWS_COMMAND = 1500

    /** Предел строки команды cmd.exe — оболочки SSH Windows по умолчанию. */
    const val CMD_LIMIT = 8191

}

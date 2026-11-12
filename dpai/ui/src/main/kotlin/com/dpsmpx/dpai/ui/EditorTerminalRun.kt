package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Явная команда пользователя в новой PTY; имена файлов никогда не становятся shell-кодом (D-138). */
object EditorTerminalRun {
    fun defaultCommand(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "py", "pyw" -> "python3 \"\$DPAI_FILE\""
        "kts" -> "kotlinc -script \"\$DPAI_FILE\""
        "sh" -> "sh \"\$DPAI_FILE\""
        else -> "" // Флаги и исполняемый файл настоящего C++/Gradle-проекта задаёт пользователь.
    }
    fun argv(file: String, root: String, command: String): Outcome<List<String>> {
        if (!file.startsWith('/') || !root.startsWith('/') || file.any { it.code < 32 } || root.any { it.code < 32 } || command.isBlank() || command.length > 4096 || '\u0000' in command) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "Нужны путь файла в Linux, рабочая папка и команда запуска")
        }
        val script = """
            cd "${'$'}1" || exit
            DPAI_FILE="${'$'}2"; export DPAI_FILE
            /bin/sh -c "${'$'}3"
            DPAI_RUN_STATUS=${'$'}?
            printf '\n[Код выхода: %s]\n' "${'$'}DPAI_RUN_STATUS"
            exec /bin/sh -l
        """.trimIndent()
        return Outcome.success(listOf("/bin/sh", "-c", script, "dpai-run", root, file, command))
    }
}

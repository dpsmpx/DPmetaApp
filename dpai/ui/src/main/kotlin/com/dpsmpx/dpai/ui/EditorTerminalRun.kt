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

}

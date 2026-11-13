package com.dpsmpx.dpai.ui

enum class RunLanguage(val title: String) {
    CPP("C++"), PYTHON("Python"), KOTLIN("Kotlin"), OTHER("команды");
    companion object {
        fun of(file: String): RunLanguage = when (file.substringAfterLast('.').lowercase()) {
            "cpp", "cc", "cxx", "c++", "c" -> CPP
            "py", "pyw", "pyi" -> PYTHON
            "kt", "kts" -> KOTLIN
            else -> OTHER
        }
    }
}

/** Данные ошибки не становятся аргументами менеджера пакетов: предложения берутся из каталогов (D-141). */
data class RunDiagnosis(
    val language: RunLanguage,
    val exitCode: Int,
    val advice: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val pip: List<String> = emptyList(),
    val maven: List<String> = emptyList(),
    val unknown: List<String> = emptyList(),
    val environmentProblem: Boolean = false,
    val projectCommand: Boolean = false,
    val cpp: CppDependencies.Report? = null,
    /** Места в коде из вывода запуска (D-153): ошибки компилятора и трассировки — с переходом в редактор. */
    val problems: BuildProblemList = BuildProblemList(emptyList(), false),
) {
    val useful: Boolean get() = exitCode != 0
    fun withFailure(output: String): RunDiagnosis {
        val next = RunDiagnostics.analyze(language, output, 1)
        return copy(advice = (advice + next.advice).distinct(), packages = (packages + next.packages).distinct(),
            pip = (pip + next.pip).distinct(), maven = (maven + next.maven).distinct(), unknown = (unknown + next.unknown).distinct(),
            environmentProblem = environmentProblem || next.environmentProblem, projectCommand = projectCommand || next.projectCommand,
            cpp = cpp?.withFailure(output))
    }
}

object RunDiagnostics {
    fun analyze(language: RunLanguage, output: String, exitCode: Int): RunDiagnosis {
        if (exitCode == 0) return RunDiagnosis(language, exitCode)
        val text = CppDependencies.plain(output.takeLast(EditorRunOutput.MAX_BYTES))
        return when (language) {
            RunLanguage.CPP -> {
                val r = CppDependencies.analyze(text, exitCode)
                RunDiagnosis(language, exitCode, r.advice, r.packages, unknown = r.unknownHeaders,
                    environmentProblem = r.issues.any { it in setOf(CppDependencies.Issue.TOOLCHAIN, CppDependencies.Issue.PACKAGE_WRITE, CppDependencies.Issue.EXECUTION_PERMISSION) }, cpp = r)
            }
            RunLanguage.PYTHON -> PythonRuntime.diagnose(text, exitCode)
            RunLanguage.KOTLIN -> KotlinRuntime.diagnose(text, exitCode)
            RunLanguage.OTHER -> RunDiagnosis(language, exitCode, common(text).ifEmpty { listOf("Команда завершилась с кодом $exitCode. Проверьте вывод терминала и команду запуска.") })
        }
    }
    fun common(text: String): List<String> = buildList {
        if (text.contains("No space left on device", true)) add(CppDependencies.Issue.DISK_FULL.advice)
        if (text.contains("failed to extract") || text.contains("failed to write database") || text.contains("DPAI_PACKAGE_WRITE:")) add(CppDependencies.Issue.PACKAGE_WRITE.advice)
        else if (text.contains("Permission denied", true) || text.contains("Read-only file system", true)) add("Нет доступа к файлу или каталогу. Проверьте права; окружения и временные файлы должны находиться во внутренней памяти Linux.")
        if (Regex("(?:Could not resolve host|Temporary failure in name resolution|UnknownHostException|Network is unreachable|CERTIFICATE_VERIFY_FAILED|PKIX path building failed)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            add("Не удалось подключиться к репозиторию. Проверьте интернет/VPN, DNS, дату и сертификаты; повторите действие после исправления подключения.")
        }
    }
}

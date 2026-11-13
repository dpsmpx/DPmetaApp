package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

object PythonRuntime {
    val packages = listOf("python3", "py3-pip")
    val modules = mapOf("requests" to "requests", "httpx" to "httpx", "numpy" to "numpy", "pandas" to "pandas", "PIL" to "Pillow",
        "yaml" to "PyYAML", "bs4" to "beautifulsoup4", "rich" to "rich", "pytest" to "pytest", "flask" to "Flask",
        "django" to "Django", "dotenv" to "python-dotenv", "aiohttp" to "aiohttp", "pydantic" to "pydantic", "colorama" to "colorama")
    private val requirement = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}(?:==[A-Za-z0-9][A-Za-z0-9.!+_-]{0,59})?")
    private val missing = Regex("(?:ModuleNotFoundError:|ImportError:)\\s*No module named ['\"]([A-Za-z_][A-Za-z0-9_.]{0,159})['\"]")
    private val requirePython = "command -v python3 >/dev/null 2>&1 || { printf 'DPAI_PYTHON_TOOLCHAIN: python3 not found\\n'; exit 127; }"
    private val selection = ProjectRuntime.context + "\nDPAI_VENV=\"\$DPAI_PROJECT_HOME/python\"\n" +
        "DPAI_PY=python3\nif test -d \"\$DPAI_VENV\"; then\n" +
        "test -x \"\$DPAI_VENV/bin/python\" || { printf 'DPAI_PYTHON_VENV: broken interpreter\\n'; exit 126; }\nDPAI_PY=\"\$DPAI_VENV/bin/python\"\nfi"
    val defaultCommand: String = requirePython + "\n" + selection + "\nexec \"\$DPAI_PY\" \"\$DPAI_FILE\""

    /**
     * Адаптер debugpy для отладки (D-159): тот же интерпретатор, что у запуска — окружение
     * проекта, если оно есть, иначе `python3`; программа идёт тем же интерпретатором.
     */
    fun debugAdapterCommand(root: String, file: String): List<String> =
        ProjectRuntime.args(root, file, requirePython + "\n" + selection + "\nexec \"\$DPAI_PY\" -m debugpy.adapter")
    private val prepare = requirePython + "\n" + ProjectRuntime.context + """

        DPAI_VENV="${'$'}DPAI_PROJECT_HOME/python"
        mkdir -p "${'$'}DPAI_PROJECT_HOME" || exit
        if test -x "${'$'}DPAI_VENV/bin/python"; then
            python3 -m venv --upgrade "${'$'}DPAI_VENV"
        else
            python3 -m venv "${'$'}DPAI_VENV"
        fi
        DPAI_VENV_STATUS=${'$'}?
        test "${'$'}DPAI_VENV_STATUS" = 0 || { printf 'DPAI_PYTHON_VENV: creation failed\n'; exit "${'$'}DPAI_VENV_STATUS"; }
        DPAI_PY="${'$'}DPAI_VENV/bin/python"
    """.trimIndent().let { "\n$it" }
    fun prepareCommand(root: String, file: String): List<String> = ProjectRuntime.args(root, file, prepare + "\n\"\$DPAI_PY\" -c 'import sys,pip; print(sys.executable); print(\"DPAI_PYTHON_OK\")'")
    fun install(root: String, file: String, requirements: List<String>): Outcome<List<String>> {
        if (!ProjectRuntime.validPaths(root, file) || requirements.isEmpty() || requirements.size > 50 || requirements.any { !requirement.matches(it) }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "Нужны пути Linux и имена PyPI: пакет или пакет==версия, не больше 50; URL и ключи pip не принимаются")
        }
        return Outcome.success(ProjectRuntime.args(root, file, prepare + "\n\"\$DPAI_PY\" -m pip --isolated --disable-pip-version-check install --index-url https://pypi.org/simple -- \"\$@\"", requirements))
    }
    fun probe(root: String, file: String): List<String> = ProjectRuntime.args(root, file, requirePython + "\n" + selection + """

        if test "${'$'}DPAI_PY" = python3; then
            mkdir -p "${'$'}DPAI_PROJECT_HOME" || exit
            DPAI_CHECK=${'$'}(mktemp -d "${'$'}DPAI_PROJECT_HOME/python-check.XXXXXX") || exit
            trap 'rm -rf "${'$'}DPAI_CHECK"' EXIT
            python3 -m venv "${'$'}DPAI_CHECK" || { printf 'DPAI_PYTHON_VENV: creation failed\n'; exit 1; }
            DPAI_PY="${'$'}DPAI_CHECK/bin/python"
        fi
        "${'$'}DPAI_PY" -c 'import ssl,sqlite3,pip,sys; print(sys.executable); print("DPAI_PYTHON_OK")'
    """.trimIndent().let { "\n$it" })
    fun diagnose(text: String, code: Int): RunDiagnosis {
        val apk = linkedSetOf<String>()
        val pip = linkedSetOf<String>()
        val unknown = linkedSetOf<String>()
        val advice = RunDiagnostics.common(text).toMutableList()
        var env = false
        val absent = missing.findAll(text).map { it.groupValues[1].substringBefore('.') }.distinct().toList()
        if (text.contains("DPAI_PYTHON_TOOLCHAIN:") || text.contains("DPAI_PYTHON_VENV:") || Regex("python3?: (?:not found|command not found)").containsMatchIn(text) || absent.any { it in setOf("pip", "venv", "ensurepip", "ssl", "_ssl", "sqlite3", "_sqlite3") }) {
            apk += packages; env = true
            advice += "Нужны исправные Python 3, pip и venv. Установите пакеты, подготовьте внутреннее окружение; после частичного сбоя используйте восстановление."
        }
        for (module in absent.filter { it !in setOf("pip", "venv", "ensurepip", "ssl", "_ssl", "sqlite3", "_sqlite3") }) {
            modules[module]?.let { pip += it } ?: run { unknown += module }
        }
        if (pip.isNotEmpty()) advice += "Модули устанавливаются из PyPI в отдельное окружение этого проекта. Следующий запуск шаблоном Python 3 использует его; системный Python и .venv проекта не меняются."
        if (unknown.isNotEmpty()) advice += "Неизвестный модуль может быть локальным файлом или иметь другое имя в PyPI. Проверьте import/путь/интерпретатор либо укажите точное имя дистрибутива вручную."
        if (text.contains("externally-managed-environment")) { env = true; advice += "Системный Python защищён PEP 668. Подготовьте внутреннее окружение и ставьте модули туда; --break-system-packages не нужен." }
        if (text.contains("Python.h") && (text.contains("not found") || text.contains("No such file"))) { apk += listOf("python3-dev", "build-base"); advice += "Для сборки Python-модуля из исходников нужны заголовки Python и C/C++-тулчейн." }
        if (text.contains("can't find Rust compiler") || text.contains("Cargo, the Rust package manager, is not installed")) { apk += listOf("rust", "cargo"); advice += "Этот модуль требует сборки на Rust: установите rust/cargo либо выберите совместимую готовую версию пакета." }
        if (text.contains("No matching distribution found") || text.contains("Requires-Python")) advice += "Нет совместимой версии пакета для выбранных Python/Alpine arm64. Проверьте версию Python и ограничения пакета; можно явно указать пакет==версия."
        if (Regex("(?:SyntaxError|IndentationError|TabError):").containsMatchIn(text)) advice += "Исправьте указанную строку Python: синтаксис или отступы."
        if (text.contains("FileNotFoundError:")) advice += "Проверьте путь данных и рабочую папку запуска. Относительные пути считаются от выбранной папки IDE."
        if (text.contains("ImportError:") && pip.isEmpty() && unknown.isEmpty()) advice += "Проверьте версию библиотеки, имя импортируемого объекта и циклические импорты; переустановка не исправляет несовместимый API автоматически."
        if (text.contains("no display name") || text.contains("could not connect to display")) advice += "Графической библиотеке нужен графический сервер. Консоль IDE предоставляет терминал."
        if (advice.isEmpty() && apk.isEmpty()) advice += "Python завершился с кодом $code. Причина и строка показаны в traceback; проверьте код и входные данные."
        return RunDiagnosis(RunLanguage.PYTHON, code, advice.distinct(), apk.toList(), pip.toList(), unknown = unknown.toList(), environmentProblem = env || apk.isNotEmpty())
    }
}

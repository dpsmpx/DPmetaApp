package com.dpsmpx.dpai.execution.project

/** Рецепт явного запуска: версии разрешает штатный менеджер, не IDE. */
object DependencyTools {
    private val script: String by lazy {
        checkNotNull(DependencyTools::class.java.getResourceAsStream("/com/dpsmpx/dpai/execution/project_dependencies.py")).bufferedReader().use { it.readText() }
    }
    enum class Manager(val code: String) { GRADLE("gradle"), PYTHON("python"), CONAN("conan") }
    enum class Mode(val code: String) { REPORT("report"), PIN("pin"), RESTORE("restore") }
    fun command(manager: Manager, mode: Mode, python: String = "python3"): List<String> {
        require(python.isNotBlank() && python.none { it.isISOControl() })
        require(mode != Mode.RESTORE || manager == Manager.PYTHON)
        return listOf(python, "-I", "-c", script, manager.code, mode.code)
    }
}

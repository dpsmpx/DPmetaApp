package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.files.FileTree

/** Один подбор начального профиля для API и каталога (D-215); сохранённый профиль имеет приоритет. */
object ProjectProfileDefaults {
    fun default(tree: FileTree, kind: ManualProfileKind): ManualProfile? {
        if (kind !in setOf(ManualProfileKind.CHECK, ManualProfileKind.BUILD)) return null
        val root = tree.list("").valueOrNull()?.map { it.name }?.toSet() ?: return null
        if ("CMakeLists.txt" in root) {
            val steps = listOf(ManualStep("Конфигурация CMake", listOf("cmake", "-S", ".", "-B", "build", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"), 600),
                ManualStep("Сборка CMake", listOf("cmake", "--build", "build"), 1800)) +
                if (kind == ManualProfileKind.CHECK) listOf(ManualStep("Тесты CTest", listOf("ctest", "--test-dir", "build", "--output-on-failure"), 1800)) else emptyList()
            return ManualProfile(kind, steps)
        }
        if ("Makefile" in root) {
            val make = tree.read("Makefile", 256 * 1024).valueOrNull()?.toString(Charsets.UTF_8).orEmpty()
            val test = Regex("(?m)^(check|test)\\s*:").find(make)?.groupValues?.get(1)
            if (kind == ManualProfileKind.CHECK && test == null) return null
            return ManualProfile(kind, listOf(ManualStep("Make", listOfNotNull("make", if (kind == ManualProfileKind.CHECK) test else null), 1800)))
        }
        val argv = when {
            "gradlew" in root -> listOf("sh", "gradlew", if (kind == ManualProfileKind.CHECK) "check" else "assemble")
            "gradlew.bat" in root -> listOf("cmd.exe", "/c", "gradlew.bat", if (kind == ManualProfileKind.CHECK) "check" else "assemble")
            "pyproject.toml" in root || "requirements.txt" in root || root.any { it.endsWith(".py") } -> if (kind == ManualProfileKind.CHECK) listOf("python3", "-m", "unittest", "discover") else listOf("python3", "-m", "compileall", "-q", ".")
            else -> return null
        }
        return ManualProfile(kind, listOf(ManualStep(if (kind == ManualProfileKind.CHECK) "Проверка проекта" else "Сборка проекта", argv, 1800)))
    }
}

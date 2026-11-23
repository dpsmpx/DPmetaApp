package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.execution.target.TargetPlatform

/** Один подбор начального профиля для API и каталога (D-215); сохранённый профиль имеет приоритет. */
object ProjectProfileDefaults {
    fun default(tree: FileTree, kind: ManualProfileKind, platform: TargetPlatform = TargetPlatform.POSIX): ManualProfile? {
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
            platform == TargetPlatform.WINDOWS && "gradlew.bat" in root -> listOf("cmd.exe", "/c", "gradlew.bat", if (kind == ManualProfileKind.CHECK) "check" else "assemble")
            "gradlew" in root -> listOf("sh", "gradlew", if (kind == ManualProfileKind.CHECK) "check" else "assemble")
            "gradlew.bat" in root -> listOf("cmd.exe", "/c", "gradlew.bat", if (kind == ManualProfileKind.CHECK) "check" else "assemble")
            "pyproject.toml" in root || "requirements.txt" in root || root.any { it.endsWith(".py") } -> {
                val python = if (platform == TargetPlatform.WINDOWS) "python" else "python3"
                if (kind == ManualProfileKind.CHECK) listOf(python, "-m", "unittest", "discover") else listOf(python, "-m", "compileall", "-q", ".")
            }
            else -> return null
        }
        // An Android project can have the same `gradlew` wrapper as a JVM-only
        // project; the AndroidManifest is the distinguishing marker. The saved
        // profile remains authoritative when the author has customized it.
        val android = ("gradlew" in root || "gradlew.bat" in root) &&
            listOf("app/src/main", "src/main").any { dir ->
                tree.list(dir).valueOrNull()?.any { it.name == "AndroidManifest.xml" && !it.dir } == true
            }
        return ManualProfile(kind, listOf(ManualStep(if (kind == ManualProfileKind.CHECK) "Проверка проекта" else "Сборка проекта", argv, 1800)),
            needs = if (android) setOf(ManualNeed.ANDROID_SDK) else emptySet())
    }
}

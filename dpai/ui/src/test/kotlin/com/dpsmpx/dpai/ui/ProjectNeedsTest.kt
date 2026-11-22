package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.ApkIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Совместимость окружения с проектом (D-195). */
class ProjectNeedsTest {
    private val inv = AlpinePackages.parse(
        """
        P:cmake
        V:4.1.2-r0
        p:cmd:cmake=4.1.2-r0 cmd:ctest=4.1.2-r0

        P:make
        V:4.4.1-r4
        p:cmd:make=4.4.1-r4
        """.trimIndent(),
        "cmake make\n", null, "3.24.3",
    )

    private val index = ApkIndex.parse(
        """
        P:g++
        V:15.2.0-r5
        p:cmd:c++=15.2.0-r5 cmd:g++=15.2.0-r5

        P:java-common
        V:0.5-r0
        p:cmd:java=0.5-r0

        P:openjdk21-jdk
        V:21.0.9_p10-r0

        P:gcc-go
        V:15.2.0-r5
        p:cmd:go=15.2.0-r5

        P:go
        V:1.26.0-r0
        p:cmd:go=1.26.0-r0

        P:shellcheck
        V:0.11.0-r0
        p:cmd:shellcheck=0.11.0-r0
        """.trimIndent(),
    )

    @Test
    fun cmakeProjectNeedsCompiler() {
        val c = ProjectNeeds.check("local/hello", ProjectNeeds.fromFiles(setOf("CMakeLists.txt", "README.md")), inv, index, emptyList())
        assertEquals(listOf("c++"), c.missing.map { it.command })
        assertEquals("g++", c.missing.single().install)
        assertEquals("cmake", c.needs.first { it.command == "cmake" }.found)
        assertEquals(listOf("g++"), ProjectNeeds.installs(c))
        assertEquals("нет: c++ (пакет g++)", ProjectNeeds.text(c))
    }

    @Test
    fun installedPackagesNeverClaimThatTheToolsActuallyRun() {
        val c = ProjectNeeds.check("o/p", listOf("cmake" to "CMakeLists.txt"), inv, index, emptyList())
        assertTrue(c.ok)
        assertTrue("запуск инструментов не проверен" in ProjectNeeds.text(c))
    }

    @Test
    fun suggestionsPreferOwnChoiceThenIndex() {
        assertEquals("openjdk21-jdk", ProjectNeeds.suggest("java", index), "не java-common из индекса")
        assertEquals("go", ProjectNeeds.suggest("go", index), "не gcc-go")
        assertEquals("shellcheck", ProjectNeeds.suggest("shellcheck", index), "редкая команда — из индекса")
        assertNull(ProjectNeeds.suggest("nonexistent-tool", index))
        assertEquals("g++", ProjectNeeds.suggest("c++", null), "индекса нет — свой выбор")
        assertEquals("py3-pytest", ProjectNeeds.suggest("pytest", index), "индекс в кеше не у всех репозиториев — свой выбор всё равно")
    }

    @Test
    fun profileStepsAndSdk() {
        val profile = ManualProfile(ManualProfileKind.CHECK, listOf(
            ManualStep("Тесты", listOf("pytest", "-q"), 300),
            ManualStep("Своё", listOf("./run-tests.sh"), 300),
            ManualStep("Оболочка", listOf("/bin/sh", "-c", "echo"), 60),
            ManualStep("Сборка", listOf("/usr/bin/cmake", "--build", "build"), 600),
        ))
        val wanted = ProjectNeeds.fromProfile(ManualProfileKind.CHECK, profile)
        assertEquals(listOf("pytest", "cmake"), wanted.map { it.first }, "скрипт проекта и оболочка — не пакеты apk")
        val c = ProjectNeeds.check("o/p", wanted, inv, index, listOf("platforms;android-36"))
        assertTrue("Android SDK: нет platforms;android-36" in ProjectNeeds.text(c))
        assertEquals("py3-pytest", c.missing.single().install)
    }

    @Test
    fun removalWarnsAboutProjectCommands() {
        val c = ProjectNeeds.check("local/hello", ProjectNeeds.fromFiles(setOf("CMakeLists.txt")), inv, index, emptyList())
        val gone = AlpinePackages.removal(inv, "cmake").gone
        assertEquals(listOf("local/hello: cmake (cmake)"), ProjectNeeds.removalWarnings(gone, listOf(c)))
    }
}

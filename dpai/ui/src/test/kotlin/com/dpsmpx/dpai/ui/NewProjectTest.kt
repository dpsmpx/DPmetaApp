package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.ui.NewProject.Template
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewProjectTest {
    private fun spec(t: Template, name: String = "hello-app", pkg: String = NewProject.defaultPackage(name)) =
        (NewProject.form(t, name, pkg) as Outcome.Success).value

    private val wrapper = NewProject.GradleWrapper("#!/bin/sh\n".toByteArray(), "@echo off\r\n".toByteArray(), byteArrayOf(0x50, 0x4b), "distributionUrl=x\n".toByteArray())

    @Test
    fun nameAndPackageAreChecked() {
        assertIs<Outcome.Success<*>>(NewProject.form(Template.CPP_CMAKE, "my-game2", ""))
        for (bad in listOf("", "My", "1app", "a--b", "a-", "-a", "a_b", "a b", "a".repeat(41), "проект")) {
            assertIs<Outcome.Failure>(NewProject.form(Template.PYTHON_PACKAGE, bad, ""), bad)
        }
        assertIs<Outcome.Failure>(NewProject.form(Template.CPP_CMAKE, "int", ""), "слово C++ — пространство имён не собрать")
        assertIs<Outcome.Failure>(NewProject.form(Template.PYTHON_PACKAGE, "json", ""), "модуль стандартной библиотеки подменился бы")
        assertIs<Outcome.Failure>(NewProject.form(Template.KOTLIN_CONSOLE, "app", "local.fun"), "слово Kotlin в пакете")
        assertIs<Outcome.Failure>(NewProject.form(Template.KOTLIN_CONSOLE, "app", "Local.App"), "заглавные в пакете")
        assertIs<Outcome.Failure>(NewProject.form(Template.KOTLIN_ANDROID, "app", "app"), "applicationId — не меньше двух частей")
        assertEquals("local.hello_app", NewProject.defaultPackage("hello-app"))
        assertEquals("hello_app", spec(Template.PYTHON_PACKAGE).pkg, "у Python пакет — модуль из имени")
    }

    @Test
    fun identityIsOptionalButValid() {
        assertNull((NewProject.identity(" ", "") as Outcome.Success).value)
        assertEquals(NewProject.GitIdentity("Аня", "anya@example.org"), (NewProject.identity("Аня", "anya@example.org") as Outcome.Success).value)
        assertIs<Outcome.Failure>(NewProject.identity("Аня", ""))
        assertIs<Outcome.Failure>(NewProject.identity("", "a@b"))
        assertIs<Outcome.Failure>(NewProject.identity("A <x>", "a@b"))
    }

    @Test
    fun androidOnlyOnPc() {
        assertNotNull(NewProject.unavailable(Template.KOTLIN_ANDROID, inApp = true, termux = false))
        assertNotNull(NewProject.unavailable(Template.KOTLIN_ANDROID, inApp = false, termux = true))
        assertNull(NewProject.unavailable(Template.KOTLIN_ANDROID, inApp = false, termux = false))
        assertNull(NewProject.unavailable(Template.CPP_CMAKE, inApp = true, termux = false))
    }

    @Test
    fun pathsAreUnderHome() {
        assertEquals("/root/projects/local/game", NewProject.root("/root", "", "game"))
        assertEquals("/root/projects/local/.trees/game", NewProject.worktreeRoot("/root/", "projects/local/", "game"))
        assertEquals("C:/Users/u/code/game", NewProject.root("C:/Users/u", "code", "game"))
        assertNull(NewProject.parentProblem("work/2026"))
        for (bad in listOf("../x", "a//b", "a/./b", "C:/x", "a\\b")) assertNotNull(NewProject.parentProblem(bad), bad)
    }

    @Test
    fun everyTemplateHasItsFiles() {
        val expected = mapOf(
            Template.CPP_CMAKE to listOf("CMakeLists.txt", "src/main.cpp", "src/greeting.cpp", "src/greeting.hpp", "tests/greeting_test.cpp"),
            Template.KOTLIN_CONSOLE to listOf("src/main/kotlin/local/hello_app/Main.kt", "src/test/kotlin/local/hello_app/GreetingTests.kt"),
            Template.KOTLIN_GRADLE to listOf("build.gradle.kts", "settings.gradle.kts", "src/test/kotlin/local/hello_app/GreetingTest.kt", "gradlew", "gradle/wrapper/gradle-wrapper.jar"),
            Template.KOTLIN_ANDROID to listOf("app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "app/src/main/kotlin/local/hello_app/MainActivity.kt", "gradlew.bat"),
            Template.PYTHON_PACKAGE to listOf("pyproject.toml", "src/hello_app/__main__.py", "tests/test_greeting.py"),
        )
        for (t in Template.entries) {
            val files = (NewProject.files(spec(t), wrapper) as Outcome.Success).value
            val paths = files.map { it.path }
            assertEquals(paths.distinct(), paths, "повторов нет: $t")
            for (p in expected.getValue(t) + listOf("README.md", ".gitignore")) assertTrue(p in paths, "$t: нет $p")
            for (f in files) {
                assertTrue(FileTreePaths.valid(f.path), "$t: путь ${f.path}")
                if (!f.path.endsWith(".jar") && !f.path.endsWith(".bat")) assertFalse('\r' in f.text, "$t: CRLF в ${f.path}")
            }
            assertEquals(listOf("gradlew").takeIf { t == Template.KOTLIN_GRADLE || t == Template.KOTLIN_ANDROID } ?: emptyList(), files.filter { it.executable }.map { it.path })
        }
        assertIs<Outcome.Failure>(NewProject.files(spec(Template.KOTLIN_GRADLE), null), "Gradle-шаблон без wrapper не создаётся")
        val cmake = (NewProject.files(spec(Template.CPP_CMAKE), null) as Outcome.Success).value.first { it.path == "CMakeLists.txt" }.text
        assertTrue("namespace" !in cmake && "project(hello-app LANGUAGES CXX)" in cmake && "add_executable(hello-app src/main.cpp)" in cmake, cmake)
        val main = (NewProject.files(spec(Template.KOTLIN_CONSOLE), null) as Outcome.Success).value.first { it.path.endsWith("Greeting.kt") }.text
        assertTrue("package local.hello_app" in main && "\"Привет, \$name!\"" in main, main)
    }

    /** Версии Gradle-шаблонов — те же, с которыми собирается само приложение. */
    @Test
    fun gradleVersionsMatchTheCatalog() {
        val catalog = File("../gradle/libs.versions.toml").takeIf { it.isFile } ?: File("dpai/gradle/libs.versions.toml")
        val versions = catalog.readLines().mapNotNull { Regex("^([a-z0-9-]+) = \"([^\"]+)\"").find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()
        assertEquals(versions["kotlin"], NewProject.KOTLIN_VERSION)
        assertEquals(versions["agp"], NewProject.AGP_VERSION)
        assertEquals(versions["junit"], NewProject.JUNIT_VERSION)
        assertEquals(versions["junit4"], NewProject.JUNIT4_VERSION)
        assertEquals(versions["compose-bom"], NewProject.COMPOSE_BOM)
        assertEquals(versions["activity-compose"], NewProject.ACTIVITY_COMPOSE)
    }

    @Test
    fun commandsAreValidProfilesOnBothPlatforms() {
        for (t in Template.entries) for (platform in listOf(TargetPlatform.POSIX, TargetPlatform.WINDOWS)) for (inApp in listOf(true, false)) {
            if (platform == TargetPlatform.WINDOWS && inApp) continue
            val c = NewProject.commands(spec(t), platform, inApp)
            c.build?.let { assertTrue(it.valid && it.kind == ManualProfileKind.BUILD, "$t $platform: сборка") }
            assertTrue(c.check.valid && c.check.kind == ManualProfileKind.CHECK, "$t $platform: проверка")
            c.run?.let { assertTrue(it.valid, "$t $platform: запуск") }
            assertEquals(t == Template.KOTLIN_ANDROID, c.run == null, "у Android-приложения запуск — установкой APK")
            // Проверка задач ИИ проходит форму проекта без искажений.
            val text = assertNotNull(NewProject.checkText(c.verify), "$t $platform")
            assertEquals(c.verify, ProjectForm.words(text), "$t $platform: $text")
            if (platform == TargetPlatform.WINDOWS) assertTrue(c.check.steps.none { it.command.first() == "/bin/sh" }, "$t: на Windows нет sh")
        }
        val kotlinc = NewProject.commands(spec(Template.KOTLIN_CONSOLE), TargetPlatform.POSIX, inApp = true).build!!.steps.single().command[2]
        assertTrue("/kotlin/${KotlinRuntime.VERSION}/kotlinc/bin/kotlinc" in kotlinc, "в Linux приложения — свой kotlinc")
        assertTrue("/kotlin/" !in NewProject.commands(spec(Template.KOTLIN_CONSOLE), TargetPlatform.POSIX, inApp = false).build!!.steps.single().command[2])
        val python = NewProject.commands(spec(Template.PYTHON_PACKAGE), TargetPlatform.POSIX, inApp = true).run!!
        assertEquals(RunProfile("python3 -m hello_app", "src", buildFirst = false), python)
        assertEquals(listOf("build/hello-app"), NewProject.commands(spec(Template.CPP_CMAKE), TargetPlatform.POSIX, true).build!!.artifacts)
        assertEquals(listOf("app/build/outputs/apk/debug/*.apk"), NewProject.commands(spec(Template.KOTLIN_ANDROID), TargetPlatform.POSIX, false).build!!.artifacts)
    }

    @Test
    fun checkTextQuotesOnlyWhatItMust() {
        assertEquals("python3 -m unittest", NewProject.checkText(listOf("python3", "-m", "unittest")))
        assertEquals("/bin/sh -c 'echo \"a b\"'", NewProject.checkText(listOf("/bin/sh", "-c", "echo \"a b\"")))
        assertEquals("cmd.exe /c \"it's\"", NewProject.checkText(listOf("cmd.exe", "/c", "it's")))
        assertNull(NewProject.checkText(listOf("\"'")))
    }

    @Test
    fun gitInitPassesValuesAsArgumentsNotCode() {
        val argv = NewProject.gitInit(TargetPlatform.POSIX, "/root/projects/local/a b", NewProject.GitIdentity("O'Brien \$(rm)", "o@b.c"))
        assertEquals(listOf("/bin/sh", "-c"), argv.take(2))
        assertEquals(listOf("dpai-new-project", "/root/projects/local/a b", "O'Brien \$(rm)", "o@b.c", NewProject.FIRST_COMMIT), argv.drop(3))
        assertFalse("rm" in argv[2], "значения — аргументами, не текстом сценария")
        assertTrue("refs/heads/main" in argv[2] && NewProject.OK_MARKER in argv[2])
        val win = NewProject.gitInit(TargetPlatform.WINDOWS, "C:/Users/u/projects/local/app", NewProject.GitIdentity("O'Brien", "o@b.c"))
        val script = Base64.getDecoder().decode(win.last()).toString(Charsets.UTF_16LE)
        assertTrue("'O''Brien'" in script && "Set-Location -LiteralPath 'C:/Users/u/projects/local/app'" in script, script)
        assertTrue("update-index', '--chmod=+x', 'gradlew'" in script, "право запуска gradlew — и в индекс")
    }

    @Test
    fun probeAndProblemsInWords() {
        assertEquals(listOf("cmake"), NewProject.missing(listOf("DPAI_MISSING cmake", NewProject.OK_MARKER)))
        assertEquals(emptyList(), NewProject.missing(listOf(NewProject.OK_MARKER)))
        assertNull(NewProject.missing(listOf("sh: not found")), "проверка не дошла до конца")
        val inApp = NewProject.probe(Template.KOTLIN_CONSOLE, TargetPlatform.POSIX, inApp = true)[2]
        assertTrue("kotlinc/bin/kotlinc" in inApp && "command -v 'java'" in inApp, inApp)
        assertTrue("автора" in NewProject.gitProblem("*** Please tell me who you are."))
        assertTrue("cmake" in NewProject.alpinePackages(Template.CPP_CMAKE))
        assertEquals("git", NewProject.alpinePackages(Template.PYTHON_PACKAGE).first())
    }
}

/** Пути файлов шаблона — те же правила, что у деревьев файлов приложения. */
private object FileTreePaths {
    fun valid(path: String): Boolean = com.dpsmpx.dpai.files.FileNames.pathValid(path)
}

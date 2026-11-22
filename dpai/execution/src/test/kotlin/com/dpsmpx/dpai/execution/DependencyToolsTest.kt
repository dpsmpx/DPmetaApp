package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.execution.project.DependencyTools
import com.dpsmpx.dpai.execution.project.DependencyTools.Manager
import com.dpsmpx.dpai.execution.project.DependencyTools.Mode
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class DependencyToolsTest {
    private fun run(dir: File, argv: List<String>): Pair<Int, String> {
        val result = LocalProcessHost().run(ProcessSpec(argv, workDir = dir.path), 5.minutes).valueOrNull()!!
        return result.exitCode to (result.stdout + result.stderr).joinToString("\n")
    }
    private fun enabled() = assumeTrue(System.getProperty("dpai.test.dependencies") == "true", "нативные менеджеры не включены")
    private fun ok(result: Pair<Int, String>) = assertEquals(0, result.first, result.second)
    private fun tool(dir: File, manager: Manager, mode: Mode, python: String = "python3") = run(dir, DependencyTools.command(manager, mode, python))

    @Test fun recipesFitManualProfileAndNeverRequireShellInterpolation() {
        for (m in Manager.entries) for (mode in Mode.entries) if (mode != Mode.RESTORE || m == Manager.PYTHON) {
            val command = DependencyTools.command(m, mode, "/path with spaces/python")
            assertEquals("/path with spaces/python", command.first())
            assertTrue(command.sumOf { it.length } < 16384)
            assertEquals(listOf(m.code, mode.code), command.takeLast(2))
        }
    }

    @Test fun pythonSnapshotRestoreAndConflictRefuseToReplaceAGoodLock(@TempDir dir: File) {
        enabled()
        ok(run(dir, listOf("python3", "-m", "venv", ".venv")))
        val py = File(dir, ".venv/bin/python").path
        ok(tool(dir, Manager.PYTHON, Mode.PIN, py))
        val lock = File(dir, "requirements.dp-ide.lock")
        val original = lock.readText()
        assertTrue(original.contains("pip=="))
        assertTrue(File(dir, ".dp-ide/dependencies/python-report.json").isFile)
        ok(tool(dir, Manager.PYTHON, Mode.RESTORE, py))
        val site = run(dir, listOf(py, "-c", "import sysconfig; print(sysconfig.get_path('purelib'))"))
        ok(site)
        val linked = File(site.second.trim(), "linked_sample-1.0.dist-info").apply { mkdirs() }
        File(linked, "METADATA").writeText("Metadata-Version: 2.1\nName: linked-sample\nVersion: 1.0\n")
        File(linked, "direct_url.json").writeText("""{"url":"file:///source","dir_info":{"editable":true}}""")
        val direct = tool(dir, Manager.PYTHON, Mode.PIN, py)
        assertTrue(direct.first != 0 && "linked-sample" in direct.second, direct.second)
        assertEquals(original, lock.readText())
        linked.deleteRecursively()
        val wheel = File(dir, "broken_sample-1.0-py3-none-any.whl")
        ZipOutputStream(wheel.outputStream()).use { zip ->
            for ((name, text) in mapOf(
                "broken_sample-1.0.dist-info/METADATA" to "Metadata-Version: 2.1\nName: broken-sample\nVersion: 1.0\nRequires-Dist: absent-sample>=9\n",
                "broken_sample-1.0.dist-info/WHEEL" to "Wheel-Version: 1.0\nGenerator: IDE-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
                "broken_sample-1.0.dist-info/RECORD" to "",
            )) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        ok(run(dir, listOf(py, "-m", "pip", "install", "--no-index", "--no-deps", wheel.path)))
        assertTrue(tool(dir, Manager.PYTHON, Mode.PIN, py).first != 0)
        assertEquals(original, lock.readText())
    }

    @Test fun reportCannotWriteThroughProjectSymlink(@TempDir dir: File, @TempDir outside: File) {
        enabled()
        java.nio.file.Files.createSymbolicLink(File(dir, ".dp-ide").toPath(), outside.toPath())
        assertTrue(tool(dir, Manager.PYTHON, Mode.REPORT).first != 0)
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test fun conanLockCannotWriteThroughProjectSymlink(@TempDir dir: File, @TempDir outside: File) {
        enabled()
        File(dir, "conanfile.txt").writeText("[requires]\n")
        val lock = File(outside, "protected.lock").apply { writeText("{ \"version\": \"0.5\", \"requires\": [] }\n") }
        val before = lock.readBytes()
        java.nio.file.Files.createSymbolicLink(File(dir, "conan.lock").toPath(), lock.toPath())
        val result = tool(dir, Manager.CONAN, Mode.PIN)
        // Причина обязательна: отсутствие Conan в PATH не должно давать ложный успех теста.
        assertTrue(result.first != 0 && "ссылк" in result.second, result.second)
        assertTrue(before.contentEquals(lock.readBytes()))
        assertTrue(java.nio.file.Files.isSymbolicLink(File(dir, "conan.lock").toPath()))
    }

    @Test fun gradleGraphIncludesTransitiveVersionAndRegularBuildHonorsLock(@TempDir dir: File) {
        enabled()
        val repo = File(checkNotNull(System.getProperty("dpai.repoRoot")))
        for (path in listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            File(dir, path).apply { parentFile.mkdirs(); File(repo, path).copyTo(this) }
        }
        File(dir, "settings.gradle").writeText("rootProject.name='dependency-live'\n")
        fun artifact(name: String, dependencies: String, version: String = "1.0") {
            val folder = File(dir, "maven/org/sample/$name/$version").apply { mkdirs() }
            File(folder, "$name-$version.pom").writeText("<project><modelVersion>4.0.0</modelVersion><groupId>org.sample</groupId><artifactId>$name</artifactId><version>$version</version>$dependencies</project>")
            ZipOutputStream(File(folder, "$name-$version.jar").outputStream()).close()
        }
        artifact("leaf", "")
        artifact("entry", "<dependencies><dependency><groupId>org.sample</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency></dependencies>")
        File(dir, "build.gradle").writeText("plugins { id 'java' }\nrepositories { maven { url = uri('maven') } }\ndependencies { implementation 'org.sample:entry:1.0' }\n")
        ok(tool(dir, Manager.GRADLE, Mode.PIN))
        val report = File(dir, ".dp-ide/dependencies/gradle-report.json").readText()
        assertTrue(report.contains("org.sample:leaf:1.0"))
        assertTrue(report.contains("requested"))
        assertTrue(File(dir, "gradle.lockfile").readText().contains("org.sample:leaf:1.0"))
        assertTrue(File(dir, "build.gradle").readText().contains("apply from:"))
        ok(run(dir, listOf("sh", "gradlew", "dependencies", "--configuration", "runtimeClasspath", "--offline", "--no-daemon")))
        File(dir, "src/main/java/Main.java").apply { parentFile.mkdirs(); writeText("class Main {}\n") }
        ok(run(dir, listOf("sh", "gradlew", "compileJava", "--offline", "--no-daemon")))
        artifact("entry", "", "2.0")
        val build = File(dir, "build.gradle")
        build.writeText(build.readText().replace("org.sample:entry:1.0", "org.sample:entry:2.0"))
        val refused = run(dir, listOf("sh", "gradlew", "compileJava", "--offline", "--no-daemon"))
        assertTrue(refused.first != 0, refused.second)
        assertTrue("lock" in refused.second.lowercase() || "strictly 1.0" in refused.second, refused.second)
        assertFalse(File(dir, "requirements.dp-ide.lock").exists())
    }
}

package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PythonRuntimeTest {
    @TempDir lateinit var dir: Path
    @Test fun importsMapToDistributionNamesWithoutGuessingUnknownModules() {
        val report = RunDiagnostics.analyze(RunLanguage.PYTHON, "ModuleNotFoundError: No module named 'PIL'\nModuleNotFoundError: No module named 'yaml'\nModuleNotFoundError: No module named 'my_project.internal'", 1)
        assertEquals(listOf("Pillow", "PyYAML"), report.pip)
        assertEquals(listOf("my_project"), report.unknown)
    }
    @Test fun pipAndVenvFailuresRequireEnvironmentRatherThanInstallingPipFromPypi() {
        for (text in listOf("ModuleNotFoundError: No module named 'ensurepip'", "DPAI_PYTHON_TOOLCHAIN: python3 not found", "DPAI_PYTHON_VENV: creation failed", "externally-managed-environment")) {
            val report = PythonRuntime.diagnose(text, 1)
            assertTrue(report.environmentProblem)
            assertTrue(report.pip.isEmpty())
        }
    }
    @Test fun syntaxAndMissingDataHaveAdviceWithoutPackageInstallation() {
        for (text in listOf("IndentationError: unexpected indent", "SyntaxError: invalid syntax", "FileNotFoundError: missing input.json")) {
            val r = PythonRuntime.diagnose(text, 1)
            assertTrue(r.advice.isNotEmpty())
            assertTrue(r.packages.isEmpty() && r.pip.isEmpty())
        }
    }
    @Test fun buildAndVersionFailuresAreExplained() {
        assertEquals(listOf("python3-dev", "build-base"), PythonRuntime.diagnose("fatal error: Python.h: No such file or directory", 1).packages)
        assertEquals(listOf("rust", "cargo"), PythonRuntime.diagnose("can't find Rust compiler", 1).packages)
        assertTrue(PythonRuntime.diagnose("No matching distribution found", 1).advice.any { "совместимой" in it })
    }
    @Test fun installerRejectsOptionsUrlsAndInvalidVersions() {
        for (name in listOf("--index-url", "https://example.invalid/pkg", "x;touch injected", "a[extra]", "a==", "a>=1", "a==1\nother")) assertIs<Outcome.Failure>(PythonRuntime.install("/root", "/root/a.py", listOf(name)))
        val plan = PythonRuntime.install("/root", "/root/a.py", listOf("requests==2.32.5")).valueOrNull()!!
        assertEquals("requests==2.32.5", plan.last())
        assertTrue(plan[2].contains("--isolated"))
        assertFalse(plan[2].contains("--break-system-packages"))
    }
    @Test fun realPythonReceivesStdinAndFilenameIsData() {
        val file = dir.resolve("test ' \$(touch injected).py")
        Files.writeString(file, "print('INPUT:' + input())\n")
        val command = EditorTerminalRun.argv(file.toString(), dir.toString(), PythonRuntime.defaultCommand).valueOrNull()!!
        val result = LocalProcessHost().run(ProcessSpec(command, environment = System.getenv() + ("DPAI_STUDIO_HOME" to dir.resolve("studio").toString()), stdin = "hello\n"), 20.seconds).valueOrNull()!!
        assertTrue(result.stdout.any { it == "INPUT:hello" }, result.toString())
        assertTrue(result.stdout.any { it.contains("[Код выхода: 0]") })
        assertFalse(Files.exists(dir.resolve("injected")))
    }
    @Test fun realProbeCreatesOnlyInternalTemporaryVenv() {
        val file = dir.resolve("main.py")
        Files.writeString(file, "print('untouched')\n")
        val home = dir.resolve("studio")
        val result = LocalProcessHost().run(ProcessSpec(PythonRuntime.probe(dir.toString(), file.toString()), environment = System.getenv() + ("DPAI_STUDIO_HOME" to home.toString())), 40.seconds).valueOrNull()!!
        assertTrue(result.ok, result.toString())
        assertTrue("DPAI_PYTHON_OK" in result.stdout)
        assertFalse(Files.exists(dir.resolve(".venv")))
        assertEquals("print('untouched')\n", Files.readString(file))
    }
}

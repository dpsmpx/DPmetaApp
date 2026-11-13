package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorRunOutputTest {
    private val failure = "file.cpp:14:10: fatal error: 'ncurses.h' file not found\r\n1 error generated.\r\n[Код выхода: 1]\r\nlocalhost:~# "
    @Test fun statusAndUtf8MayBeSplitAtEveryByteAndPromptMayFollowInSamePacket() {
        val bytes = failure.toByteArray()
        for (split in 0..bytes.size) {
            val output = EditorRunOutput()
            val first = output.feed(bytes.copyOfRange(0, split))
            val report = first ?: output.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(listOf("ncurses-dev"), report?.packages, "разрыв $split")
        }
    }
    @Test fun reportIsProducedOnlyOnceAndInteractiveShellIsNotAnalyzed() {
        val output = EditorRunOutput()
        assertEquals(1, output.feed(failure.toByteArray())?.exitCode)
        assertNull(output.feed(failure.toByteArray()))
    }
    @Test fun largeOutputKeepsRecentDiagnosticsAndLongShellPromptDoesNotHideStatus() {
        val output = EditorRunOutput()
        assertNull(output.feed("x".repeat(EditorRunOutput.MAX_BYTES * 4).toByteArray()))
        val report = output.feed((failure + "prompt".repeat(500)).toByteArray())
        assertEquals(listOf("ncurses-dev"), report?.packages)
    }
    @Test fun runReportCarriesPlacesInCodeEvenOnSuccess() {
        val failed = EditorRunOutput().feed(failure.toByteArray())!!
        assertEquals(listOf(BuildProblem("file.cpp", 14, 10, ProblemSeverity.ERROR, "'ncurses.h' file not found")), failed.problems.items)
        val warned = EditorRunOutput("a.cpp").feed("a.cpp:3:7: warning: unused variable 'x'\n[Код выхода: 0]\n".toByteArray())!!
        assertEquals(0, warned.exitCode)
        assertEquals(1, warned.problems.warnings)
    }
    @Test fun noReportBeforeExitAndSuccessHasNoInstallProposal() {
        val output = EditorRunOutput()
        assertNull(output.feed("fatal error: 'ncurses.h' file not found\n".toByteArray()))
        assertTrue(output.feed("\n[Код выхода: 0]\n".toByteArray())?.packages?.isEmpty() == true)
    }
    @Test fun pythonAndKotlinOutputHaveTheirOwnSolutions() {
        val py = EditorRunOutput("a.py").feed("ModuleNotFoundError: No module named 'requests'\n[Код выхода: 1]\n".toByteArray())!!
        assertEquals(listOf("requests"), py.pip)
        assertTrue(py.packages.isEmpty())
        val kt = EditorRunOutput("a.kt").feed("DPAI_KOTLIN_TOOLCHAIN: not installed\n[Код выхода: 127]\n".toByteArray())!!
        assertEquals(KotlinRuntime.packages, kt.packages)
    }

    /** Код выхода Windows (D-163): со знаком и длинный; PowerShell шлёт \r\n. */
    @Test fun windowsExitCodesAreRead() {
        val crash = EditorRunOutput().feed("Unhandled exception\r\n[Код выхода: -1073741819]\r\nPS C:\\p> ".toByteArray())
        assertEquals(-1073741819, crash?.exitCode)
        assertEquals(0, EditorRunOutput().feed("done\r\n[Код выхода: 0]\r\n".toByteArray())?.exitCode)
    }
}

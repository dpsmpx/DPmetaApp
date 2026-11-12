package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import java.nio.file.Files
import java.nio.file.Path
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EditorTerminalRunTest {
    @TempDir lateinit var dir: Path
    @Test fun fileNameIsDataAndTerminalStartsInChosenDirectory() {
        val file = dir.resolve("odd ' \$(touch injected) ; code.py").toString()
        val command = "printf 'FILE:%s\\n' \"\$DPAI_FILE\"; pwd"
        val argv = EditorTerminalRun.argv(file, dir.toString(), command).valueOrNull()!!
        val result = LocalProcessHost().run(ProcessSpec(argv), 8.seconds).valueOrNull()!!
        val output = result.stdout.joinToString("\n")
        assertTrue(output.contains("FILE:$file"), output)
        assertTrue(output.contains(dir.toString()), output)
        assertTrue(output.contains("[Код выхода: 0]"), output)
        assertFalse(Files.exists(dir.resolve("injected")))
    }
    @Test fun terminalShowsFailedProgramExitAndLeavesARealShell() {
        val argv = EditorTerminalRun.argv(dir.resolve("file.py").toString(), dir.toString(), "exit 7").valueOrNull()!!
        val result = LocalProcessHost().run(ProcessSpec(argv), 8.seconds).valueOrNull()!!
        assertTrue(result.stdout.any { it.contains("[Код выхода: 7]") })
    }
    @Test fun programReceivesTerminalInputInsteadOfAClosedStdin() {
        val argv = EditorTerminalRun.argv(dir.resolve("file.py").toString(), dir.toString(), "read DPAI_INPUT; printf 'INPUT:%s\\n' \"\$DPAI_INPUT\"").valueOrNull()!!
        val result = LocalProcessHost().run(ProcessSpec(argv, stdin = "hello from terminal\n"), 8.seconds).valueOrNull()!!
        assertTrue(result.stdout.any { it.contains("INPUT:hello from terminal") })
    }
    @Test fun missingCommandOrInvalidGuestPathsAreNotExecutable() {
        for (args in listOf(Triple("relative.py", "/root", "python3"), Triple("/root/code.py", "relative", "python3"),
            Triple("/root/code.py", "/root", ""), Triple("/root/bad\n.py", "/root", "python3"),
            Triple("/root/code.py", "/root", "x".repeat(4097)))) {
            assertIs<Outcome.Failure>(EditorTerminalRun.argv(args.first, args.second, args.third))
        }
        assertIs<Outcome.Failure>(EditorTerminalRun.argv("/root/main.cpp", "/root", EditorTerminalRun.defaultCommand("main.cpp")))
    }
}

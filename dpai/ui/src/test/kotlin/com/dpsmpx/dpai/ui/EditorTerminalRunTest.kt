package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.close()
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(output.contains("FILE:$file"), output)
            assertTrue(output.contains(dir.toString()), output)
            assertTrue(output.contains("[Код выхода: 0]"), output)
            assertFalse(Files.exists(dir.resolve("injected")))
        } finally { process.destroyForcibly() }
    }
    @Test fun terminalShowsFailedProgramExitAndLeavesARealShell() {
        val argv = EditorTerminalRun.argv(dir.resolve("file.py").toString(), dir.toString(), "exit 7").valueOrNull()!!
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.close()
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS))
            assertTrue(process.inputStream.bufferedReader().readText().contains("[Код выхода: 7]"))
        } finally { process.destroyForcibly() }
    }
    @Test fun programReceivesTerminalInputInsteadOfAClosedStdin() {
        val argv = EditorTerminalRun.argv(dir.resolve("file.py").toString(), dir.toString(), "read DPAI_INPUT; printf 'INPUT:%s\\n' \"\$DPAI_INPUT\"").valueOrNull()!!
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.use { it.write("hello from terminal\n".toByteArray()) }
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS))
            assertTrue(process.inputStream.bufferedReader().readText().contains("INPUT:hello from terminal"))
        } finally { process.destroyForcibly() }
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

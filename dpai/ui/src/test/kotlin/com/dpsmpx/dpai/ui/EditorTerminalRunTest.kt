package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import java.nio.file.Files
import java.nio.file.Path
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val command = EditorTerminalRun.defaultCommand("main.cpp")
        assertIs<Outcome.Success<List<String>>>(EditorTerminalRun.argv("/root/storage/shared/main.cpp", "/root/storage/shared", command))
        assertTrue(command.contains("-fuse-ld=lld"))
        assertTrue(command.contains("mktemp /tmp/dpai-cpp.XXXXXX"))
    }
    @Test fun projectRunHasNoEditorFileAndStartsInTheChosenSubfolder() {
        val sub = Files.createDirectories(dir.resolve("build/bin"))
        val argv = EditorTerminalRun.argv(null, sub.toString(), "printf 'FILE:[%s]\\n' \"\$DPAI_FILE\"; pwd; exit 3").valueOrNull()!!
        val result = LocalProcessHost().run(ProcessSpec(argv), 8.seconds).valueOrNull()!!
        val output = result.stdout.joinToString("\n")
        assertTrue(output.contains("FILE:[]"), output)
        assertTrue(output.contains(sub.toString()), output)
        assertTrue(output.contains("[Код выхода: 3]"), output)
        assertIs<Outcome.Failure>(EditorTerminalRun.argv(null, "relative", "./app"))
    }

    /** Windows-target (D-163): сценарий PowerShell — base64, команда как есть, кавычки удвоены, строка короче предела cmd.exe. */
    @Test fun windowsRunIsEncodedPowerShellThatPassesSshAndCmdLimits() {
        val folder = "C:\\Users\\Мария O'Brien\u2019s\\proj/build"
        val command = "& \".\\app.exe\" --level 2 %PATH% \$env:X `n !x ^y"
        val argv = EditorTerminalRun.windowsArgv(folder, command).valueOrNull()!!
        assertEquals(listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NoExit", "-ExecutionPolicy", "Bypass", "-EncodedCommand"), argv.dropLast(1))
        val script = String(java.util.Base64.getDecoder().decode(argv.last()), Charsets.UTF_16LE)
        val lines = script.lines()
        assertTrue(lines.any { "Set-Location -LiteralPath 'C:\\Users\\Мария O''Brien\u2019\u2019s\\proj/build' -ErrorAction Stop" in it }, script)
        assertTrue(command in lines, "команда — своей строкой как есть: $script")
        assertTrue(lines.indexOf(command) < lines.indexOfFirst { "[Код выхода: \$dpaiCode]" in it }, script)
        assertTrue("UTF8" in lines.first(), script)
        // Строка пройдёт по SSH на Windows: base64 без $, %, кавычек.
        val joined = com.dpsmpx.dpai.execution.ssh.RemoteShell.join(com.dpsmpx.dpai.execution.target.TargetPlatform.WINDOWS, argv).valueOrNull()!!
        assertTrue(joined.length < EditorTerminalRun.CMD_LIMIT, joined.length.toString())

        val longest = EditorTerminalRun.windowsArgv("C:\\" + "п".repeat(EditorTerminalRun.MAX_WINDOWS_DIR - 3), "x".repeat(EditorTerminalRun.MAX_WINDOWS_COMMAND)).valueOrNull()!!
        val longJoined = com.dpsmpx.dpai.execution.ssh.RemoteShell.join(com.dpsmpx.dpai.execution.target.TargetPlatform.WINDOWS, longest).valueOrNull()!!
        assertTrue(longJoined.length < EditorTerminalRun.CMD_LIMIT, "самый длинный запуск — ${longJoined.length} знаков")

        for ((d, c) in listOf("proj" to "app.exe", "/home/x" to "app.exe", "C:\\p" to "", "C:\\p" to "a\nb", "C:\\p\nq" to "app.exe", "C:\\p" to "x".repeat(EditorTerminalRun.MAX_WINDOWS_COMMAND + 1))) {
            assertIs<Outcome.Failure>(EditorTerminalRun.windowsArgv(d, c), "$d | ${c.take(20)}")
        }
    }
}

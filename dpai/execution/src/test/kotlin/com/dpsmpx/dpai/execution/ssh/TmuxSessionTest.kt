package com.dpsmpx.dpai.execution.ssh

import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Сессия вкладки в tmux (D-111): команда — настоящим `sh` с поддельным `tmux` в `PATH`. */
class TmuxSessionTest {
    private val name = "dpai-d-t1-sh-1"

    /** Каталог PATH: только `echo`, `sh` и, если задан, поддельный `tmux` со сценарием [tmux]. */
    private fun bin(dir: File, tmux: String?): File {
        val bin = File(dir, "bin-${tmux?.hashCode() ?: 0}").apply { mkdirs() }
        val echo = listOf("/bin/echo", "/usr/bin/echo").first { File(it).canExecute() }
        java.nio.file.Files.createSymbolicLink(File(bin, "echo").toPath(), java.nio.file.Path.of(echo))
        java.nio.file.Files.createSymbolicLink(File(bin, "sh").toPath(), java.nio.file.Path.of("/bin/sh"))
        if (tmux != null) File(bin, "tmux").apply { writeText("#!/bin/sh\n$tmux\n"); setExecutable(true) }
        return bin
    }

    private fun run(cmd: List<String>, path: File): Pair<Int, String> {
        val r = LocalProcessHost().run(ProcessSpec(listOf("/bin/sh", "-c", cmd[2]), environment = mapOf("PATH" to path.path)), 30.seconds).valueOrNull()!!
        return r.exitCode to r.stdout.joinToString("\n")
    }

    // Поддельный tmux: печатает аргументы в скобках и выходит с кодом из TMUX_EXIT.
    private val echoArgs = "for a in \"\$@\"; do printf '[%s]' \"\$a\"; done; echo"

    @Test
    fun newAttachesWithTheWholeCommandOrFallsBack() {
        val cmd = TmuxSession.wrap(listOf("sh", "-lc", "exec echo 'привет мир'"), name).valueOrNull()!!
        assertEquals(listOf("sh", "-lc"), cmd.take(2))
        val dir = java.nio.file.Files.createTempDirectory("tmux").toFile()
        try {
            // Нет tmux: строка-подсказка и сама команда.
            val (_, bare) = run(cmd, bin(dir, null))
            assertTrue(bare.startsWith(TmuxSession.NO_TMUX) && bare.trimEnd().endsWith("привет мир"), bare)
            // tmux есть: -u, new -A -s имя и команда целиком — с оболочкой входа.
            val (code, with) = run(cmd, bin(dir, echoArgs))
            assertEquals(0, code)
            assertEquals("[-u][new][-A][-s][$name][sh -lc 'exec echo '\\''привет мир'\\''']", with.trim())
            // tmux не запустился (версия, права): строка и сама команда, а не закрытая вкладка.
            val (_, failed) = run(cmd, bin(dir, "exit 1"))
            assertTrue(failed.startsWith(TmuxSession.TMUX_FAILED) && failed.trimEnd().endsWith("привет мир"), failed)
        } finally {
            dir.deleteRecursively()
        }
        // Оболочка входа — без команды у tmux, запасной путь — оболочка пользователя.
        val shell = TmuxSession.wrap(emptyList(), name).valueOrNull()!![2]
        assertTrue("tmux -u new -A -s $name && exit 0;" in shell && shell.endsWith("exec \"\${SHELL:-/bin/sh}\" -l"), shell)
    }

    /**
     * tmux проработал дольше [TmuxSession.START_SECONDS] и кончился с ошибкой
     * (`kill-server`, сбой сервера): сессия была — вкладка закрывается с его
     * кодом, программа заново сама не запускается. Время — поддельным `date`.
     */
    @Test
    fun aCrashedSessionDoesNotStartTheProgramAgain() {
        val cmd = TmuxSession.wrap(listOf("sh", "-lc", "exec echo запущено"), name).valueOrNull()!!
        val dir = java.nio.file.Files.createTempDirectory("tmux").toFile()
        try {
            val bin = bin(dir, "exit 1")
            File(bin, "date").apply {
                writeText("#!/bin/sh\nf=${dir.path}/clock\nn=100; [ -f \"\$f\" ] && read n < \"\$f\"\necho \"\$n\"\necho \$((n+100)) > \"\$f\"\n")
                setExecutable(true)
            }
            val (code, out) = run(cmd, bin)
            assertEquals(1, code)
            assertTrue("запущено" !in out && TmuxSession.TMUX_FAILED !in out, out)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Автопереподключение CLI слота (D-111): только вход в живую сессию, иначе — код 75 без запуска программы. */
    @Test
    fun resumeOnlyNeverStartsTheProgramAgain() {
        val cmd = TmuxSession.wrap(listOf("sh", "-lc", "exec echo запущено"), name, resumeOnly = true).valueOrNull()!!
        val dir = java.nio.file.Files.createTempDirectory("tmux").toFile()
        try {
            val (noTmux, out1) = run(cmd, bin(dir, null))
            assertEquals(TmuxSession.NOT_RESUMED_CODE, noTmux)
            assertTrue("запущено" !in out1 && TmuxSession.NOT_RESUMED in out1, out1)
            // Сессии нет: has-session отказывает.
            val (gone, out2) = run(cmd, bin(dir, "[ \"\$1\" = has-session ] && exit 1; $echoArgs"))
            assertEquals(TmuxSession.NOT_RESUMED_CODE, gone)
            assertTrue("запущено" !in out2, out2)
            // Сессия есть — вход в неё по точному имени.
            val (ok, out3) = run(cmd, bin(dir, "[ \"\$1\" = has-session ] && exit 0; $echoArgs"))
            assertEquals(0, ok)
            assertEquals("[-u][attach][-t][=$name]", out3.trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun namesSocketsAndQuotesAreChecked() {
        assertFailsWith<IllegalArgumentException> { TmuxSession.wrap(emptyList(), "x; rm -rf /") }
        assertFailsWith<IllegalArgumentException> { TmuxSession.wrap(emptyList(), name, socket = "a b") }
        val cmd = TmuxSession.wrap(listOf("sh", "-lc", "exec env -u ANTHROPIC_API_KEY 'CLAUDE_CONFIG_DIR=/home/u/slot a' claude"), name, socket = name).valueOrNull()!!
        assertTrue("tmux -L $name -u new -A -s $name 'sh -lc '" in cmd[2], cmd[2])
        // Запасной путь — сразу команда: профиль оболочки входа не читается второй раз.
        assertTrue(cmd[2].endsWith("; exec env -u ANTHROPIC_API_KEY 'CLAUDE_CONFIG_DIR=/home/u/slot a' claude"), cmd[2])
        // Закрытие — только точное имя: иначе tmux берёт и сессию с тем же началом (dpai-…-1 и dpai-…-10).
        assertEquals("tmux kill-session -t =$name 2>/dev/null; true", TmuxSession.kill(name)[2])
        assertEquals("tmux -L $name kill-session -t =$name 2>/dev/null; true", TmuxSession.kill(name, name)[2])
    }
}

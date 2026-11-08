package com.dpsmpx.dpai.execution.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Сессия вкладки в tmux (D-111): команда — настоящим `sh` с поддельным `tmux` в `PATH`. */
class TmuxSessionTest {
    /**
     * Команда с tmux (D-111): есть tmux — `tmux new -A -s имя команда`; нет —
     * строка, как поставить, и прежняя команда. Проверка — настоящим `sh` с
     * поддельным `tmux` в `PATH`.
     */
    @Test
    fun wrapAttachesOrFallsBack() {
        val name = "dpai-t1-sh-1"
        val cmd = TmuxSession.wrap(listOf("sh", "-lc", "exec echo 'привет мир'"), name).valueOrNull()!!
        assertEquals(listOf("sh", "-lc"), cmd.take(2))
        val dir = java.nio.file.Files.createTempDirectory("tmux").toFile()
        try {
            fun run(path: String): String {
                val p = ProcessBuilder("/bin/sh", "-c", cmd[2]).redirectErrorStream(true)
                p.environment()["PATH"] = path
                val proc = p.start()
                val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                proc.waitFor()
                return out
            }
            // PATH — только свой каталог: настоящий tmux в системе тест не видит.
            val bin = java.io.File(dir, "bin").apply { mkdirs() }
            val echo = listOf("/bin/echo", "/usr/bin/echo").first { java.io.File(it).canExecute() }
            java.nio.file.Files.createSymbolicLink(java.io.File(bin, "echo").toPath(), java.nio.file.Path.of(echo))
            // Нет tmux: строка-подсказка и сама команда.
            val bare = run(bin.path)
            assertTrue(bare.startsWith(TmuxSession.NO_TMUX) && bare.trimEnd().endsWith("привет мир"), bare)
            // tmux есть: он получает new -A -s имя и команду одной строкой.
            java.io.File(dir, "tmux").apply { writeText("#!/bin/sh\nfor a in \"\$@\"; do printf '[%s]' \"\$a\"; done\n"); setExecutable(true) }
            val with = run("${dir.path}:${bin.path}")
            assertEquals("[new][-A][-s][$name][echo 'привет мир']", with.trim())
        } finally {
            dir.deleteRecursively()
        }
        // Оболочка входа — без команды у tmux, запасной путь — оболочка пользователя.
        val shell = TmuxSession.wrap(emptyList(), name).valueOrNull()!![2]
        assertTrue("tmux new -A -s $name; fi;" in shell && shell.endsWith("exec \"\${SHELL:-/bin/sh}\" -l"), shell)
    }

    @Test
    fun nameIsCheckedAndQuotesSurvive() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { TmuxSession.wrap(emptyList(), "x; rm -rf /") }
        val cmd = TmuxSession.wrap(listOf("sh", "-lc", "exec env -u ANTHROPIC_API_KEY 'CLAUDE_CONFIG_DIR=/home/u/slot a' claude"), "dpai-t-claude-1").valueOrNull()!!
        assertTrue("tmux new -A -s dpai-t-claude-1 'env -u ANTHROPIC_API_KEY '\\''CLAUDE_CONFIG_DIR=/home/u/slot a'\\'' claude'" in cmd[2], cmd[2])
        assertTrue(cmd[2].endsWith("exec env -u ANTHROPIC_API_KEY 'CLAUDE_CONFIG_DIR=/home/u/slot a' claude"), cmd[2])
    }
}

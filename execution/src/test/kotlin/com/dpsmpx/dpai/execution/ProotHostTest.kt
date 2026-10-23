package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.ProotHost
import com.dpsmpx.dpai.execution.linux.ProotLayout
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Команда proot Linux-среды в приложении (D-68): корень, каталоги ядра,
 * каталоги хоста, каталог и окружение гостя, правило «`apk` — без `/proc`».
 * Сам proot — aarch64 и запускается только на телефоне (`docs/DEVICE_CHECK.md`, §14).
 */
class ProotHostTest {
    private val layout = ProotLayout(
        proot = "/data/app/lib/arm64/libproot.so",
        loader = "/data/app/lib/arm64/libproot-loader.so",
        rootfs = "/data/user/0/app/files/linux/rootfs",
        tmp = "/data/user/0/app/cache/proot",
        binds = listOf(ProotLayout.Bind("/data/user/0/app/files/work", "/work")),
    )

    private class Recorder : ProcessHost {
        var spec: ProcessSpec? = null

        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            this.spec = spec
            return Outcome.failure(DiagCode.PROCESS_START_FAILED, "запись команды")
        }
    }

    private fun pairs(argv: List<String>, flag: String): List<String> = argv.indices.filter { argv[it] == flag }.map { argv[it + 1] }

    @Test
    fun commandRunsInTheGuestWithItsOwnEnvironment() {
        val rec = Recorder()
        ProotHost(rec, layout).start(ProcessSpec(listOf("git", "status"), workDir = "/work/repo", stdin = "ввод")) {}
        val spec = requireNotNull(rec.spec)
        val argv = spec.command
        assertEquals(listOf(layout.proot, "--kill-on-exit", "-0", "-r", layout.rootfs), argv.take(5))
        assertEquals(listOf("/dev", "/proc", "/sys", "/data/user/0/app/files/work:/work"), pairs(argv, "-b"))
        assertEquals(listOf("/work/repo"), pairs(argv, "-w"))
        assertEquals(listOf("git", "status"), argv.takeLast(2))
        assertNull(spec.workDir, "каталог гостя — через -w, а не каталог процесса хоста")
        assertEquals("ввод", spec.stdin)
        val env = requireNotNull(spec.environment)
        assertEquals(ProotHost.GUEST_ENVIRONMENT + mapOf("PROOT_LOADER" to layout.loader, "PROOT_TMP_DIR" to layout.tmp), env)
    }

    @Test
    fun guestEnvironmentIsTakenWhole() {
        val rec = Recorder()
        ProotHost(rec, layout).start(ProcessSpec(listOf("claude"), environment = mapOf("HOME" to "/slots/a", "PATH" to "/usr/bin"))) {}
        val spec = requireNotNull(rec.spec)
        assertEquals(mapOf("HOME" to "/slots/a", "PATH" to "/usr/bin", "PROOT_LOADER" to layout.loader, "PROOT_TMP_DIR" to layout.tmp), spec.environment)
        assertEquals(listOf(ProotHost.GUEST_HOME), pairs(spec.command, "-w"))
    }

    @Test
    fun apkRunsWithoutProc() {
        val host = ProotHost(Recorder(), layout)
        for (apk in listOf("apk", "/sbin/apk")) {
            val argv = host.wrap(ProcessSpec(listOf(apk, "add", "git"))).command
            assertFalse("/proc" in pairs(argv, "-b"), apk)
            assertTrue("/dev" in pairs(argv, "-b") && "/sys" in pairs(argv, "-b"), apk)
        }
        // Только сама программа apk: аргумент «apk» у другой программы /proc не убирает.
        assertTrue("/proc" in pairs(host.wrap(ProcessSpec(listOf("echo", "apk"))).command, "-b"))
        assertTrue("/proc" in pairs(host.wrap(ProcessSpec(listOf("/usr/bin/apk-tools-helper"))).command, "-b"))
    }
}

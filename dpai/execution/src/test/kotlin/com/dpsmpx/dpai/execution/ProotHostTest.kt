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
 * Команда proot Linux-среды в приложении (D-82): корень, узлы `/dev` и
 * `/proc` без `/sys` (D-93), каталоги хоста, каталог и окружение гостя,
 * правило «`apk` — без `/proc`».
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
        assertEquals(listOf(layout.proot, "--kill-on-exit", "--link2symlink", "-0", "-r", layout.rootfs), argv.take(6))
        val fd = listOf("/proc/self/fd:/dev/fd", "/proc/self/fd/0:/dev/stdin", "/proc/self/fd/1:/dev/stdout", "/proc/self/fd/2:/dev/stderr")
        assertEquals(ProotHost.DEVICES + "/proc" + fd + "/data/user/0/app/files/work:/work", pairs(argv, "-b"))
        // D-93: ни всего /dev (там /dev/binder — службы системы от имени приложения), ни /sys.
        assertTrue(pairs(argv, "-b").none { it == "/dev" || it.startsWith("/dev/binder") || it.startsWith("/sys") }, argv.toString())
        assertEquals(listOf("/work/repo"), pairs(argv, "-w"))
        assertEquals(listOf("git", "status"), argv.takeLast(2))
        assertNull(spec.workDir, "каталог гостя — через -w, а не каталог процесса хоста")
        assertEquals("ввод", spec.stdin)
        val env = requireNotNull(spec.environment)
        assertEquals(ProotHost.GUEST_ENVIRONMENT + mapOf("PROOT_LOADER" to layout.loader, "PROOT_TMP_DIR" to layout.tmp, "ADB_SERVER_SOCKET" to ProotHost.ADB_SERVER_SOCKET), env)
    }

    /** T23: сервер adb гостя — не на общем loopback телефона, а на сокете в данных приложения. */
    @Test
    fun adbServerListensOnAPrivateSocket() {
        val socket = ProotHost.ADB_SERVER_SOCKET
        assertTrue(socket.startsWith("localfilesystem:${ProotHost.GUEST_HOME}/"), socket)
        assertFalse(socket.contains("tcp:") || socket.contains("5037"), socket)
        // Путь на хосте — корень гостя плюс путь сокета — короче предела sun_path (108 байт с нулём).
        assertTrue(("/data/user/999/com.dpsmpx.dpai/files/linux/rootfs" + socket.removePrefix("localfilesystem:")).length < 108)
        val rec = Recorder()
        // Своё окружение команды (слот, профиль запуска) переменную не убирает и не подменяет.
        ProotHost(rec, layout).start(ProcessSpec(listOf("adb", "devices"), environment = mapOf("ADB_SERVER_SOCKET" to "tcp:localhost:5037"))) {}
        assertEquals(socket, requireNotNull(rec.spec).environment?.get("ADB_SERVER_SOCKET"))
    }

    @Test
    fun guestEnvironmentIsTakenWhole() {
        val rec = Recorder()
        ProotHost(rec, layout).start(ProcessSpec(listOf("claude"), environment = mapOf("HOME" to "/slots/a", "PATH" to "/usr/bin"))) {}
        val spec = requireNotNull(rec.spec)
        assertEquals(mapOf("HOME" to "/slots/a", "PATH" to "/usr/bin", "PROOT_LOADER" to layout.loader, "PROOT_TMP_DIR" to layout.tmp, "ADB_SERVER_SOCKET" to ProotHost.ADB_SERVER_SOCKET), spec.environment)
        assertEquals(listOf(ProotHost.GUEST_HOME), pairs(spec.command, "-w"))
    }

    @Test
    fun apkRunsWithoutProc() {
        val host = ProotHost(Recorder(), layout)
        for (apk in listOf("apk", "/sbin/apk")) {
            for (operation in listOf("add", "fix")) {
                val argv = host.wrap(ProcessSpec(listOf(apk, operation, "build-base"))).command
                assertFalse(pairs(argv, "-b").any { it.startsWith("/proc") }, apk)
                assertEquals(ProotHost.DEVICES, pairs(argv, "-b").take(ProotHost.DEVICES.size), apk)
            }
        }
        // Только сама программа apk: аргумент «apk» у другой программы /proc не убирает.
        assertTrue("/proc" in pairs(host.wrap(ProcessSpec(listOf("echo", "apk"))).command, "-b"))
        assertTrue("/proc" in pairs(host.wrap(ProcessSpec(listOf("/usr/bin/apk-tools-helper"))).command, "-b"))
    }

    @Test
    fun termuxAptRunsAsRealAndroidAppUidButAlpineApkKeepsFakeRoot() {
        val host = ProotHost(Recorder(), layout)
        val native = host.wrap(ProcessSpec(listOf(ProotHost.REAL_USER_MARKER, "/bin/sh", "-c", "apt-get update")))
        assertFalse("-0" in native.command, "Termux apt refuses fake root UID 0")
        assertEquals(listOf("/bin/sh", "-c", "apt-get update"), native.command.takeLast(3))
        assertFalse(ProotHost.REAL_USER_MARKER in native.command, "the wrapper marker must not reach the guest")
        assertTrue("/proc" in pairs(native.command, "-b"))
        assertTrue("-0" in host.wrap(ProcessSpec(listOf("apk", "add", "curl"))).command,
            "Alpine package maintenance must retain its established fake-root semantics")
    }
}

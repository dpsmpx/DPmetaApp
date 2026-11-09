package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.claude.ClaudeTurnAssembler
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.execution.linux.ProotHost
import com.dpsmpx.dpai.execution.linux.ProotLayout
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Linux-среда приложения на настоящем proot (D-75, D-82): тот же `ProotHost`, тот же
 * proot-static 5.4.0 и Alpine 3.24.3, что в APK, но для машины сборки — среду готовит
 * `tools/linux/host-env.sh` (`docs/TESTING.md`, «Слои»). Только явно:
 * `-Ddpai.test.proot=<каталог среды>`; без него тест пропускается.
 *
 * Проверяет то, чего не видно на подделке процессов: `apk` без `/proc`, узкий `/dev`
 * без `/sys` (D-93), Claude Code из npm в госте и разбор его потока — отказ входа в
 * пустом каталоге слота (D-27, D-29) даёт `CLAUDE_AUTH_REQUIRED`, без запроса к модели.
 */
class ProotLiveTest {
    private lateinit var proot: ProotHost

    @BeforeTest
    fun environment() {
        val root = System.getProperty("dpai.test.proot")
        assumeTrue(root != null, "dpai.test.proot не задан: среду готовит tools/linux/host-env.sh")
        val dir = File(root!!)
        val layout = ProotLayout(
            proot = dir.resolve("bin/proot").path,
            loader = dir.resolve("bin/loader").path,
            rootfs = dir.resolve("rootfs").path,
            tmp = dir.resolve("tmp").apply { mkdirs() }.path,
        )
        assumeTrue(File(layout.proot).canExecute() && File(layout.rootfs, "etc/alpine-release").isFile, "среда не подготовлена: $root")
        proot = ProotHost(LocalProcessHost(), layout)
    }

    private fun guest(vararg command: String, environment: Map<String, String>? = null): Completed {
        val r = proot.run(ProcessSpec(command.toList(), environment = environment), 120.seconds)
        return (r as? Outcome.Success)?.value ?: error("гость не запустился: $r")
    }

    @Test
    fun apkWorksWithoutProc() {
        val r = guest("apk", "info", "-e", "git", "nodejs", "npm")
        assertEquals(0, r.exitCode, r.stderr.joinToString("\n"))
        assertEquals(listOf("git", "nodejs", "npm"), r.stdout)
    }

    @Test
    fun guestSeesOnlyAllowedDevicesAndNoSys() {
        val dev = guest("/bin/ls", "/dev")
        assertEquals(0, dev.exitCode)
        val allowed = ProotHost.DEVICES.map { it.removePrefix("/dev/") }.toSet() + setOf("fd", "stdin", "stdout", "stderr")
        assertTrue(dev.stdout.all { it in allowed }, "лишнее в /dev: ${dev.stdout - allowed}")
        val sys = guest("/bin/sh", "-c", "ls /sys 2>/dev/null | wc -l")
        assertEquals(listOf("0"), sys.stdout.map { it.trim() })
    }

    @Test
    fun claudeCodeStartsInGuest() {
        val r = guest("claude", "--version")
        assertEquals(0, r.exitCode, r.stderr.joinToString("\n"))
        assertTrue(r.stdout.single().matches(Regex("""\d+\.\d+\.\d+ \(Claude Code\)""")), r.stdout.toString())
    }

    @Test
    fun turnWithEmptySlotIsAuthRequired() {
        val slot = "/root/.claude-slot-99"
        guest("/bin/sh", "-c", "rm -rf $slot && mkdir -p $slot")
        val env = ProotHost.GUEST_ENVIRONMENT + mapOf("CLAUDE_CONFIG_DIR" to slot)
        val r = guest("claude", "-p", "--verbose", "--output-format", "stream-json", "ответь одним словом", environment = env)
        val session = r.stdout.firstNotNullOf { Regex(""""session_id":"([0-9a-f-]{36})"""").find(it)?.groupValues?.get(1) }
        val a = ClaudeTurnAssembler(ProviderSessionId.parse(session).valueOrNull()!!)
        r.stdout.forEach { a.feed(it) }
        val turn = a.finish(r.exitCode, ExitReason.EXITED)
        assertEquals(TurnStatus.FAILED, turn.status, turn.diagnostics.toString())
        assertTrue(turn.diagnostics.any { it.code == DiagCode.CLAUDE_AUTH_REQUIRED }, turn.diagnostics.toString())
        assertTrue(turn.diagnostics.none { it.code == DiagCode.CLAUDE_STREAM_MALFORMED }, turn.diagnostics.toString())
    }
}

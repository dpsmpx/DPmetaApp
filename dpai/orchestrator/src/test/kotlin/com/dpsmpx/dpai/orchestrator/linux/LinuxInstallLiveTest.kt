package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.ProotHost
import com.dpsmpx.dpai.execution.linux.ProotLayout
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Рецепт кнопки «Поставить Claude Code» (D-75, шаг 5) на настоящем proot: те же шаги
 * [LinuxEnvironment.claudeInstall], тот же `ProotHost`, что в приложении, — среда машины
 * сборки из `tools/linux/host-env.sh` (`docs/TESTING.md`, «Слои»). Только явно:
 * `-Ddpai.test.proot=<каталог среды>`; ставит пакеты из сети.
 *
 * Прокси машины (облачная сессия) передаётся гостю — только здесь: в приложении гостю идёт
 * лишь [ProotHost.GUEST_ENVIRONMENT].
 */
class LinuxInstallLiveTest {
    @Test
    fun claudeInstallStepsLeaveWorkingClaude() {
        val root = System.getProperty("dpai.test.proot")
        assumeTrue(root != null, "dpai.test.proot не задан: среду готовит tools/linux/host-env.sh")
        val dir = File(root!!)
        val proot = ProotHost(
            LocalProcessHost(),
            ProotLayout(dir.resolve("bin/proot").path, dir.resolve("bin/loader").path, dir.resolve("rootfs").path, dir.resolve("tmp").apply { mkdirs() }.path),
        )
        val proxy = System.getenv("HTTPS_PROXY")?.let { mapOf("HTTPS_PROXY" to it, "https_proxy" to it, "http_proxy" to it, "npm_config_https_proxy" to it) }.orEmpty()
        val ca = if (File(dir, "rootfs/etc/ssl/host-ca.pem").isFile) mapOf("SSL_CERT_FILE" to "/etc/ssl/host-ca.pem", "NODE_EXTRA_CA_CERTS" to "/etc/ssl/host-ca.pem") else emptyMap()
        var last = emptyList<String>()
        for (step in LinuxEnvironment.claudeInstall()) {
            val r = proot.run(step.copy(environment = ProotHost.GUEST_ENVIRONMENT + proxy + ca), 15.minutes)
            val done = (r as? Outcome.Success)?.value ?: error("шаг ${step.command} не запустился: $r")
            assertEquals(0, done.exitCode, "${step.command}: ${(done.stdout + done.stderr).takeLast(20).joinToString("\n")}")
            last = done.stdout
        }
        assertTrue(last.last().matches(Regex("""\d+\.\d+\.\d+ \(Claude Code\)""")), last.toString())
    }
}

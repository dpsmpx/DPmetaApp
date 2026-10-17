package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Команда настройки ПК (D-52, D-56): ключ подставлен, чужое не подставляется; Linux — прогон в песочнице. */
class PcSetupTest {
    @TempDir
    lateinit var dir: Path

    private val key = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTY= dpai-android"

    @Test
    fun oneLineWithTheKeyFromTailscaleOnly() {
        val cmd = assertNotNull(PcSetup.command(key))
        assertTrue('\n' !in cmd && '\r' !in cmd, "одна строка")
        assertEquals(1, Regex.escape("from=\"100.64.0.0/10\" $key").toRegex().findAll(cmd).count())
        assertTrue("DPAI_KEY_LINE" !in cmd)
        assertTrue("PasswordAuthentication no" in cmd && "agent doctor" in cmd && "ssh-keygen -l" in cmd)
        assertTrue("; #" !in cmd, "комментарии шаблона не попадают в команду")
    }

    @Test
    fun noKeyOrForeignTextGivesNoCommand() {
        assertNull(PcSetup.command(null))
        assertNull(PcSetup.command("ssh-ed25519 AAAA other@host"))
        assertNull(PcSetup.command("ssh-ed25519 AAAA' ; Remove-Item C:\\ ; ' dpai-android"))
    }

    @Test
    fun linuxCommandCarriesTheKeyOnceAndForeignTextIsRefused() {
        val cmd = assertNotNull(PcSetup.linuxCommand(key))
        assertTrue('\n' !in cmd && '\r' !in cmd, "одна строка")
        assertEquals(1, Regex.escape(key).toRegex().findAll(cmd).count())
        assertTrue("DPAI_KEY_LINE" !in cmd && "Локаль UTF-8" !in cmd, "ключ подставлен, комментарии не попали")
        assertNull(PcSetup.linuxCommand(null))
        assertNull(PcSetup.linuxCommand("ssh-ed25519 AAAA' ; rm -rf ~ ; ' dpai-android"))
    }

    /**
     * Команда целиком — в песочнице: свой HOME, заглушки `sudo`, `apt-get`,
     * `id`, `tailscale`, `ssh-keygen`, настоящий архив агента. Проверяется
     * то, что на ПК не проверить из облака: ключ — только из Tailscale и
     * один, чужие ключи целы, агент — с локалью UTF-8, поля для «Добавить».
     */
    @Test
    fun linuxCommandRunsEndToEndInASandbox() {
        assumeTrue(listOf("/bin/bash", "/usr/bin/unzip", "/usr/bin/zip").all { File(it).exists() }, "нужны bash, zip и unzip")
        val home = Files.createDirectories(dir.resolve("home")).toFile()
        val bin = Files.createDirectories(dir.resolve("bin")).toFile()
        fun stub(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
        val log = File(dir.toFile(), "sudo.log")
        stub("sudo", "echo \"sudo $*\" >> '${log.path}'; exit 0")
        stub("apt-get", "exit 0")
        stub("id", "case \"$1\" in -u) echo 1000;; -un) echo tester;; *) echo tester;; esac")
        stub("tailscale", "echo 100.101.102.103")
        stub("ssh-keygen", "echo \"256 SHA256:stub host (ED25519)\"")
        stub("xdg-user-dir", "echo '${home.path}/Downloads'")
        File(home, ".ssh").mkdirs()
        File(home, ".ssh/authorized_keys").writeText("ssh-ed25519 AAAAother me@pc\nssh-ed25519 AAAAold dpai-android\n")
        File(home, "Downloads").mkdirs()
        // Архив — как у сборки (`distZip`): скрипт `dpai/bin/dpai` с правом исполнения.
        val pack = Files.createDirectories(dir.resolve("pack/dpai/bin")).resolve("dpai").toFile()
        pack.writeText("#!/bin/sh\necho \"agent ok: $* LANG=${'$'}LANG\"\n")
        pack.setExecutable(true)
        val zipped = LocalProcessHost().run(ProcessSpec(listOf("zip", "-qr", File(home, "Downloads/dpai-agent.zip").path, "dpai"), dir.resolve("pack").toString()), 1.minutes)
        assertEquals(0, zipped.valueOrNull()?.exitCode, zipped.toString())
        val env = HashMap(System.getenv()).apply {
            put("HOME", home.path)
            put("PATH", bin.path + ":" + (get("PATH") ?: "/usr/bin:/bin"))
            put("TMPDIR", dir.toString())
        }
        val r = LocalProcessHost().run(ProcessSpec(listOf("bash", "-c", PcSetup.linuxCommand(key)!!), environment = env), 2.minutes)
        val done = assertIs<Outcome.Success<com.dpsmpx.dpai.execution.process.Completed>>(r, r.diagnostics.toString()).value
        val out = (done.stdout + done.stderr).joinToString("\n")
        assertEquals(0, done.exitCode, out)
        val keys = File(home, ".ssh/authorized_keys").readLines()
        assertEquals(listOf("ssh-ed25519 AAAAother me@pc", "from=\"100.64.0.0/10\" $key"), keys, "чужой ключ цел, прежний ключ DP AI заменён")
        assertTrue("agent ok: agent doctor LANG=C.UTF-8" in out, out)
        assertTrue("Хост: 100.101.102.103" in out && "Команда агента dpai: ${home.path}/dpai-agent" in out && "SHA256:stub" in out, out)
        val sudo = log.readText()
        assertTrue("apt-get install" in sudo && "sshd -t" in sudo && "sudo -v" in sudo, sudo)
        // Ключи хоста — до проверки sshd -t: на свежей системе без них проверка падает.
        assertTrue(sudo.indexOf("ssh-keygen -A") in 0 until sudo.indexOf("sshd -t"), sudo)
        assertTrue(dir.toFile().listFiles()!!.none { it.name.startsWith("tmp.") }, "временный скрипт удалён")
    }
}

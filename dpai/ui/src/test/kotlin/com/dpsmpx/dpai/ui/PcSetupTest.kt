package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Команда настройки ПК (D-52): одна строка, ключ подставлен, чужое не подставляется. */
class PcSetupTest {
    private val key = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTY= dpai-android"

    @Test
    fun oneLineWithTheKeyFromPrivateNetsOnly() {
        val cmd = assertNotNull(PcSetup.command(key))
        assertTrue('\n' !in cmd && '\r' !in cmd, "одна строка")
        assertEquals(1, Regex.escape("from=\"${PcSetup.PRIVATE_NETS}\" $key").toRegex().findAll(cmd).count())
        assertTrue("DPAI_KEY_LINE" !in cmd && "DPAI_NETS" !in cmd)
        assertTrue("tailscale" !in cmd.lowercase(), "без Tailscale: у автора свой VPN (D-68)")
        assertTrue("192.168.0.0/16" in cmd && "Get-NetIPAddress" in cmd && "NetworkCategory" in cmd, "частные сети, адреса ПК и подсказка о «Частной» сети")
        assertTrue("PasswordAuthentication no" in cmd && "agent doctor" in cmd && "ssh-keygen -l" in cmd)
        assertTrue("; #" !in cmd, "комментарии шаблона не попадают в команду")
    }

    @Test
    fun noKeyOrForeignTextGivesNoCommand() {
        assertNull(PcSetup.command(null))
        assertNull(PcSetup.command("ssh-ed25519 AAAA other@host"))
        assertNull(PcSetup.command("ssh-ed25519 AAAA' ; Remove-Item C:\\ ; ' dpai-android"))
    }
}

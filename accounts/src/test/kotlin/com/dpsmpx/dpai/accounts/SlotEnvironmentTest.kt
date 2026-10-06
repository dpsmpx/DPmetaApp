package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlotEnvironmentTest {
    @Test
    fun overridingVariablesAreRemovedAndConfigDirSet() {
        val f = Fixture()
        val slotId = f.slot(f.subscription(), dir = "~/.claude-a")
        val slot = f.state.slots.getValue(slotId)
        val secret = "s" + "k-ant-" + "x".repeat(30)
        val base = mapOf(
            "PATH" to "/usr/bin",
            "ANTHROPIC_API_KEY" to secret,
            "CLAUDE_CODE_OAUTH_TOKEN" to secret,
            "ANTHROPIC_BASE_URL" to "https://proxy.example.org",
            "CLAUDE_CONFIG_DIR" to "~/.claude-b",
        )
        val r = SlotEnvironment.forSlot(base, slot)
        val env = r.valueOrNull()!!
        assertEquals(mapOf("CLAUDE_CONFIG_DIR" to "~/.claude-a", "PATH" to "/usr/bin"), env)
        assertEquals(listOf(DiagCode.ACCOUNT_ENVIRONMENT_SANITIZED, DiagCode.ACCOUNT_ENVIRONMENT_SANITIZED), r.warnings.map { it.code })
        assertTrue(r.warnings.first().message.contains("ANTHROPIC_API_KEY"))
        assertFalse(r.warnings.any { it.render().contains(secret) }, "значения не показываются")
    }

    @Test
    fun cleanEnvironmentHasNoWarnings() {
        val f = Fixture()
        val id = f.slot(f.subscription())
        val slot = f.state.slots.getValue(id)
        val r = SlotEnvironment.forSlot(mapOf("HOME" to "/home/u"), slot)
        assertTrue(r.warnings.isEmpty())
        assertEquals("~/.claude", r.valueOrNull()!!["CLAUDE_CONFIG_DIR"])
    }
}

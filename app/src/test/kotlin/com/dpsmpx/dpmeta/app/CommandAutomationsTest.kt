package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpmeta.app.screens.CommandAutomations
import com.dpsmpx.dpmeta.formats.CommandFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CommandAutomationsTest {
    private val text = """
        schema = 1
        [[command]]
        id = "repo.check"
        title = "Проверить"
        project = "*"
        path = "/usr/bin/git"
        args = ["status", "--short"]
        plan = "foreground"
        level = 0
        packages = ["git"]
        timeout = 2
    """.trimIndent()

    @Test fun adapterPreservesTheCommandAndItsExistingConfirmationRoute() {
        val registry = CommandFormat.parse(text).valueOrNull()!!
        val before = registry.commands.single()
        val described = CommandAutomations.describe(registry, "/root/projects/owner/repo", true).single()
        assertEquals("pult.repo.check", described.id)
        assertEquals(ActionLevel.READ, described.level)
        assertEquals(AutomationInvocation.OPEN_UI, described.invocation)
        assertEquals(120L, described.timeoutSeconds)
        assertEquals(before, registry.commands.single())
        assertEquals(described, CommandAutomations.fromText(text, "/root/projects/owner/repo", true).valueOrNull()!!.single())
        assertFalse(CanonicalJson.write(described.toJson()).contains("--short"))
        assertFalse(CommandAutomations.describe(registry, "/root/project", false).single().available)
    }

    @Test fun onlyAnArgumentChangeAlreadyChangesTheRevision() {
        val a = CommandAutomations.describe(CommandFormat.parse(text).valueOrNull()!!, "/root/projects/o/r", true).single()
        val b = CommandAutomations.describe(CommandFormat.parse(text.replace("--short", "--porcelain")).valueOrNull()!!, "/root/projects/o/r", true).single()
        assertNotEquals(a.revision, b.revision)
    }
}

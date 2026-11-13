package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.diagnostics.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Секреты для ИИ (DPmetaApp:D-114; D-144): имена, копия DPMETA_ для слотов, окружение без пустых и дублей. */
class GuestSecretsTest {
    // Образцы значений собираются из частей: в репозитории нет похожих на ключи строк.
    private fun sample(tag: String) = Secret(listOf("sample", tag, "0123456789").joinToString("-"))

    @Test
    fun overridingVariablesGetSlotAliasOthersDoNot() {
        assertEquals("DPMETA_ANTHROPIC_API_KEY", GuestSecrets.alias("ANTHROPIC_API_KEY"))
        assertEquals("DPMETA_OPENAI_API_KEY", GuestSecrets.alias("OPENAI_API_KEY"))
        assertEquals("DPMETA_GEMINI_API_KEY", GuestSecrets.alias("GEMINI_API_KEY"))
        assertNull(GuestSecrets.alias("GH_TOKEN"))
        assertNull(GuestSecrets.alias("HF_TOKEN"))
        assertTrue(SlotEnvironment.OVERRIDING.none { it.startsWith(GuestSecrets.PREFIX) }, "копию слот Claude не удаляет")
    }

    @Test
    fun providerVariables() {
        assertEquals("ANTHROPIC_API_KEY", GuestSecrets.variableFor(ProviderKind.ANTHROPIC, "api.anthropic.com"))
        assertEquals("GEMINI_API_KEY", GuestSecrets.variableFor(ProviderKind.GEMINI, "generativelanguage.googleapis.com"))
        assertEquals("OPENROUTER_API_KEY", GuestSecrets.variableFor(ProviderKind.OPENAI_COMPATIBLE, "OpenRouter.ai"))
        assertEquals("OPENAI_API_KEY", GuestSecrets.variableFor(ProviderKind.OPENAI_COMPATIBLE, "127.0.0.1"))
    }

    @Test
    fun environmentHasStandardNameAndAliasSkipsEmptyAndDuplicates() {
        val env = GuestSecrets.environment(
            listOf(
                GuestSecrets.Issued("ANTHROPIC_API_KEY", sample("a")),
                GuestSecrets.Issued("ANTHROPIC_API_KEY", sample("b")),
                GuestSecrets.Issued("GH_TOKEN", sample("g")),
                GuestSecrets.Issued("HF_TOKEN", Secret("")),
                GuestSecrets.Issued("bad name", sample("x")),
            ),
        )
        assertEquals(setOf("ANTHROPIC_API_KEY", "DPMETA_ANTHROPIC_API_KEY", "GH_TOKEN"), env.keys)
        assertEquals(env["ANTHROPIC_API_KEY"], env["DPMETA_ANTHROPIC_API_KEY"])
        assertEquals(sample("a").reveal(), env["ANTHROPIC_API_KEY"], "у одной переменной — первый")
        // Слот Claude: стандартное имя удаляется, копия остаётся.
        val claude = env.filterKeys { it !in SlotEnvironment.OVERRIDING }
        assertFalse("ANTHROPIC_API_KEY" in claude)
        assertTrue("DPMETA_ANTHROPIC_API_KEY" in claude && "GH_TOKEN" in claude)
        assertEquals(listOf("GH_TOKEN"), GuestSecrets.names(listOf(GuestSecrets.Issued("GH_TOKEN", sample("g")), GuestSecrets.Issued("HF_TOKEN", Secret("")))))
    }

    @Test
    fun customNameIsAnOrdinaryVariableNotAManagedOrSystemOne() {
        assertEquals("MY_SERVICE_TOKEN", GuestSecrets.customName("  MY_SERVICE_TOKEN ").valueOrNull())
        assertEquals("S3_KEY2", GuestSecrets.customName("S3_KEY2").valueOrNull())
        for (bad in listOf("", "my_token", "1TOKEN", "_TOKEN", "A B", "A-B", "ТОКЕН", "A".repeat(65), "DPMETA_X", "GH_TOKEN", "ANTHROPIC_API_KEY",
            "PATH", "HOME", "LD_PRELOAD", "GIT_SSH_COMMAND", "PROOT_TMP_DIR", "CLAUDE_CONFIG_DIR", "NODE_OPTIONS", "PYTHONPATH", "BASH_ENV")) {
            assertTrue(GuestSecrets.customName(bad) is com.dpsmpx.dpai.core.result.Outcome.Failure, "«$bad» принят")
        }
        // Всё, что перекрывает вход слота, — управляется приложением и своим секретом не задаётся.
        for (v in GuestSecrets.SLOT_OVERRIDING) assertTrue(GuestSecrets.customName(v) is com.dpsmpx.dpai.core.result.Outcome.Failure, v)
    }
}

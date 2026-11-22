package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Инструкция «Настройка»: команды — одной строкой, ключ приложения подставлен, отметки — из состояния. */
class SetupGuideTest {
    private val key = "ecdsa-sha2-nistp256 AAAAE2VjZHNh dpai-android"

    @Test
    fun commandsAreSingleLineAndKeyIsSubstituted() {
        val all = SetupGuide.sections(SetupState(appKey = key, agentSha256 = "a".repeat(64)))
        val commands = all.flatMap { it.steps }.mapNotNull { it.command }
        assertTrue(commands.isNotEmpty())
        assertTrue(commands.none { '\n' in it }, "команда в несколько строк рвётся при вставке")
        assertTrue(all.single { it.id == "pc" }.steps.any { it.action == GuideAction.SAVE_AGENT })
        val pc = all.single { it.id == "pc" }.steps.mapNotNull { it.command }
        assertTrue(pc.any { "from=\"${PcSetup.PRIVATE_NETS}\" $key" in it }, "команда ПК — с ключом приложения")
        val noKey = SetupGuide.sections(SetupState()).single { it.id == "pc" }.steps
        assertTrue(noKey.none { it.command?.contains("authorized_keys") == true }, "без ключа — без команды ПК")
        val termux = all.single { it.id == "termux-ssh" }
        assertTrue(termux.steps.any { it.action == GuideAction.SAVE_AGENT })
        assertTrue(termux.steps.any { "127.0.0.1" in it.command.orEmpty() && "authorized_keys" in it.command.orEmpty() })
        assertTrue(SetupGuide.sections(SetupState()).single { it.id == "termux-ssh" }.steps.none { it.command?.contains("authorized_keys") == true })
    }

    @Test
    fun doneMarksFollowState() {
        val s = SetupState(notifications = true, hasTarget = true, loggedIn = false, hasProject = true, paired = false)
        val done = SetupGuide.sections(s).associate { it.id to it.done }
        assertEquals(true, done["phone"])
        assertEquals(true, done["target"])
        assertEquals(false, done["login"])
        assertEquals(false, done["cli"])
        assertEquals(null, done["pc-linux"], "шаги на чужом ПК без подтверждения не объявляются выполненными")
    }

    /** Выбор пути — первым разделом (AI:A-17): Linux в приложении — три шага с командой npm. */
    @Test
    fun pathChoiceComesFirst() {
        val all = SetupGuide.sections(SetupState())
        assertEquals("path", all.first().id)
        assertEquals(3, all.first().steps.size)
        assertTrue(all.first().steps.any { it.command == "npm i -g @anthropic-ai/claude-code" })
    }
}

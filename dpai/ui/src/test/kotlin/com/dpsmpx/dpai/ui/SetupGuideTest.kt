package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Инструкция «Настройка»: команды — одной строкой, ключ приложения подставлен, отметки — из состояния. */
class SetupGuideTest {
    private val key = "ecdsa-sha2-nistp256 AAAAE2VjZHNh dpai-android"

    @Test
    fun commandsAreSingleLineAndKeyIsSubstituted() {
        val all = SetupGuide.sections(SetupState(appKey = key))
        val commands = all.flatMap { it.steps }.mapNotNull { it.command }
        assertTrue(commands.isNotEmpty())
        assertTrue(commands.none { '\n' in it }, "команда в несколько строк рвётся при вставке")
        val keyLine = all.single { it.id == "key" }.steps.single().command!!
        assertTrue("echo 'from=\"127.0.0.1,::1\" $key' >> ~/.ssh/authorized_keys" in keyLine, keyLine)
        assertTrue(all.single { it.id == "agent" }.steps.any { it.action == GuideAction.SAVE_AGENT })
        val pc = all.single { it.id == "pc" }.steps.mapNotNull { it.command }
        assertTrue(pc.any { "from=\"${PcSetup.PRIVATE_NETS}\" $key" in it }, "команда ПК — с ключом приложения")
        val noKey = SetupGuide.sections(SetupState()).single { it.id == "pc" }.steps
        assertTrue(noKey.none { it.command?.contains("authorized_keys") == true }, "без ключа — без команды ПК")
    }

    @Test
    fun doneMarksFollowState() {
        val s = SetupState(notifications = true, hasTarget = true, loggedIn = false, hasProject = true, paired = false)
        val done = SetupGuide.sections(s).associate { it.id to it.done }
        assertEquals(true, done["phone"])
        assertEquals(true, done["target"])
        assertEquals(false, done["login"])
        assertEquals(false, done["cli"])
        assertEquals(null, done["termux"], "что приложение проверить не может — без отметки")
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

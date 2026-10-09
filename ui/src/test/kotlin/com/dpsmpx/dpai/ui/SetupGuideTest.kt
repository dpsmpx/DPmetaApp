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
}

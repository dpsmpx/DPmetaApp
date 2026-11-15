package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.app.ui.HelpLinks
import com.dpsmpx.dpmeta.app.ui.Intro
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Окно знакомства (D-150): о чём оно и как открыть его снова. */
class IntroTest {
    @Test
    fun pagesTellTheEssentials() {
        assertTrue(Intro.PAGES.size in 3..8, "страниц — немного")
        for (p in Intro.PAGES) {
            assertTrue(p.title.isNotBlank() && p.lines.isNotEmpty(), p.title)
            assertTrue(p.lines.all { it.length <= 240 }, "абзацы короткие: ${p.title}")
        }
        val all = Intro.PAGES.joinToString("\n") { it.title + "\n" + it.lines.joinToString("\n") }
        assertTrue("GitHub — по желанию" in all, "GitHub не обязателен")
        for (word in listOf("C++", "Kotlin", "Python", "Справки")) assertTrue(word in all, word)
        assertEquals("С чего начать", Intro.PAGES.last().title, "последняя страница — действия")
        assertEquals(Intro.Action.NEW_PROJECT, Intro.Action.entries.first(), "главное действие — первым")
    }

    @Test
    fun helpReopensIntroAndWizard() {
        val help = File("src/main/assets/help.md").readText()
        assertTrue("](app:${HelpLinks.INTRO})" in help, "справка открывает окно знакомства")
        assertTrue("](app:${HelpLinks.NEW_PROJECT})" in help, "справка открывает мастер")
        assertEquals(HelpLinks.INTRO, HelpLinks.section("app:intro"))
    }
}

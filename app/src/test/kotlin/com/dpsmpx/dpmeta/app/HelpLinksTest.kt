package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.app.ui.HelpLinks
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpLinksTest {
    private val help = File("src/main/assets/help.md").readText()
    private val links = Regex("""\]\(([^)\s]+)\)""").findAll(help).map { it.groupValues[1] }.toList()

    @Test
    fun linkParsing() {
        assertEquals("settings", HelpLinks.section("app:settings?q=токен"))
        assertEquals("токен", HelpLinks.query("app:settings?q=токен"))
        assertEquals("", HelpLinks.query("app:settings"))
        assertEquals("ide", HelpLinks.section("app:ide"))
        assertNull(HelpLinks.section("app:нет"))
        assertNull(HelpLinks.section("https://github.com"))
    }

    /** Каждая ссылка справки ведёт в существующий раздел приложения или на сайт по https — иначе касание ничего не делает. */
    @Test
    fun everyHelpLinkLeadsSomewhere() {
        assertTrue(links.size >= 10, "ссылки справки не найдены")
        for (link in links) {
            when {
                link.startsWith(HelpLinks.SCHEME) -> assertTrue(HelpLinks.section(link) != null, "нет раздела: $link")
                link.startsWith("#") -> Unit
                else -> assertTrue(link.startsWith("https://"), "не https: $link")
            }
        }
        // Каждый раздел приложения достижим из справки.
        assertEquals(HelpLinks.SECTIONS, links.mapNotNull { HelpLinks.section(it) }.toSet())
    }
}

package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpmeta.notify.AppNotifications
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Слушатель уведомлений (F-57; D-155): белый список, отброс сразу, поля события. */
class AppNotificationsTest {
    private val allowed = AppNotifications.DEFAULT_PACKAGES.toSet()
    private fun ev(pkg: String = AppNotifications.CLAUDE, title: String? = "Claude", text: String? = "Готово", ongoing: Boolean = false, summary: Boolean = false) =
        AppNotifications.event(pkg, title, text, 1_000, "0|com.anthropic.claude|7|null|10123", allowed, "com.dpsmpx.dpmeta", ongoing, summary)

    @Test
    fun whitelistedBecomesEventOthersAreDropped() {
        val e = assertNotNull(ev())
        assertEquals(AppNotifications.TYPE, e.type)
        assertEquals("Готово", e.text)
        assertEquals(mapOf("package" to AppNotifications.CLAUDE, "title" to "Claude"), e.fields)
        assertEquals(e.key, ev(text = "Другое")?.key, "тот же ключ системы — та же карточка")
        assertNull(ev(pkg = "org.telegram.messenger"), "вне списка")
        assertNull(ev(pkg = "com.dpsmpx.dpmeta"), "свои")
        assertNull(ev(ongoing = true), "постоянное")
        assertNull(ev(summary = true), "сводка группы")
        assertNull(ev(title = " ", text = null), "пустое")
    }

    @Test
    fun textIsCleanedAndBounded() {
        val e = assertNotNull(ev(title = "A\u0000\nB", text = "x".repeat(900)))
        assertEquals("A B", e.fields["title"])
        assertEquals(AppNotifications.MAX_TEXT, e.text.length)
        assertEquals("Только заголовок", assertNotNull(ev(title = "Только заголовок", text = "")).text)
    }

    @Test
    fun packageListParsing() {
        val p = AppNotifications.packages("com.anthropic.claude, org.telegram.messenger\nне пакет\ncom.anthropic.claude")
        assertEquals(listOf("com.anthropic.claude", "org.telegram.messenger"), p.ok)
        assertEquals(listOf("не", "пакет"), p.bad)
    }
}

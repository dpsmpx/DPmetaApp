package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Панель «AI» (D-112): снимок пунктов и то, что читает TalkBack. */
class AiTabsTest {
    @Test
    fun sixTabsWithoutLog() {
        assertEquals(listOf("Сводка", "Задачи", "Targets", "Запросы", "Модели", "Файлы"), AiTab.entries.map { it.title })
    }

    @Test
    fun requestsSayHowManyWait() {
        assertEquals("Запросы, ждут ответа: 2", AiTab.REQUESTS.spoken(2))
        assertEquals("Запросы", AiTab.REQUESTS.spoken(0))
        assertEquals("Задачи", AiTab.TASKS.spoken(5))
    }

    @Test
    fun unknownIndexFallsBackToSummary() {
        assertEquals(AiTab.FILES, AiTab.of(5))
        assertEquals(AiTab.SUMMARY, AiTab.of(6))
        assertEquals(AiTab.SUMMARY, AiTab.of(-1))
    }
}

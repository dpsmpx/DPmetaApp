package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Словарь интерфейса (D-88): на экране — слово, а не идентификатор.
 *
 * Проверка ловит именно то, ради чего словарь заведён: сырой код или
 * английское имя на экране. Новое состояние или код диагностики без
 * строки в [Words] выглядит в интерфейсе как `SOME_NEW_CODE` — тест
 * падает и требует добавить слово, а не оставить человека с кодом.
 */
class WordsTest {
    @Test
    fun everyDiagCodeHasAWordDifferentFromTheCode() {
        val missing = DiagCode.entries.filter { Words.reason(it.name) == it.name }
        assertTrue(missing.isEmpty(), "у кода диагностики нет слова в Words: ${missing.map { it.name }}")
    }

    @Test
    fun everyTaskStateHasAWord() {
        val missing = TaskState.entries.filter { Words.task(it.name) == it.name }
        assertTrue(missing.isEmpty(), "у состояния задачи нет слова: ${missing.map { it.name }}")
    }

    @Test
    fun everySessionStateHasAWord() {
        val missing = SessionState.entries.filter { Words.session(it.name) == it.name }
        assertTrue(missing.isEmpty(), "у состояния сессии нет слова: ${missing.map { it.name }}")
    }

    @Test
    fun everyAccountStateHasAWord() {
        val missing = AccountState.entries.filter { Words.account(it.name) == it.name }
        assertTrue(missing.isEmpty(), "у состояния слота нет слова: ${missing.map { it.name }}")
    }

    @Test
    fun everyPriorityAndTaskClassHasAWord() {
        val missingPriorities = Priority.entries.filter { Words.priority(it.name) == it.name }
        assertTrue(missingPriorities.isEmpty(), "у приоритета нет слова: ${missingPriorities.map { it.name }}")
        val missingClasses = TaskClass.entries.filter { Words.taskClass(it.name) == it.name }
        assertTrue(missingClasses.isEmpty(), "у класса задачи нет слова: ${missingClasses.map { it.name }}")
    }

    /**
     * D-89: всё, что раньше было сырым на сводке, в чате, листе задачи,
     * «Проверить» и выводе `dpai`, — со словом. Тест — здесь, в `ui`: он
     * видит перечисления всех модулей, а словарь — в ядре.
     */
    @Test
    fun everyScreenEnumHasAWord() {
        fun <E : Enum<E>> missing(values: Array<E>, word: (String) -> String) = values.filter { word(it.name) == it.name }.map { it.name }
        assertEquals(emptyList(), missing(com.dpsmpx.dpai.monitoring.SystemHealth.entries.toTypedArray(), Words::health), "здоровье системы")
        assertEquals(emptyList(), missing(com.dpsmpx.dpai.monitoring.DisplayState.entries.toTypedArray(), Words::display), "вид строки сессии")
        assertEquals(emptyList(), missing(com.dpsmpx.dpai.execution.claude.TurnStatus.entries.toTypedArray(), Words::turn), "итог хода")
        assertEquals(emptyList(), missing(com.dpsmpx.dpai.tasks.FindingSeverity.entries.toTypedArray(), Words::severity), "строгость находки ревью")
        assertEquals(emptyList(), missing(com.dpsmpx.dpai.execution.target.TargetType.entries.toTypedArray(), Words::targetType), "вид target")
        assertEquals(emptyList(), missing(com.dpsmpx.dpai.notifications.RequestKind.entries.toTypedArray(), Words::requestKind), "вид запроса")
        assertEquals(emptyList(), missing(com.dpsmpx.dpai.core.operation.OperationStatus.entries.toTypedArray(), Words::operation), "итог команды")
    }

    /** Длительность — по-русски, а не `Duration.toString()` («1m»). */
    @Test
    fun durationIsRussian() {
        assertEquals("45 с", Words.duration(kotlin.time.Duration.parse("45s")))
        assertEquals("1 мин", Words.duration(kotlin.time.Duration.parse("1m")))
        assertEquals("2 ч 5 мин", Words.duration(kotlin.time.Duration.parse("2h 5m")))
        assertEquals("3 ч", Words.duration(kotlin.time.Duration.parse("3h")))
        assertEquals("1 дн 2 ч", Words.duration(kotlin.time.Duration.parse("26h")))
    }

    /** Сами слова — по-русски и не идентификаторами (D-14). */
    @Test
    fun wordsAreRussianNotIdentifiers() {
        val state = Words.task("COMPLETION_UNVERIFIED")
        assertFalse(state.contains('_'), "слово состояния — не идентификатор: $state")
        assertTrue(state.any { it in 'а'..'я' || it in 'А'..'Я' }, "слово состояния — по-русски: $state")

        val reason = Words.reason("CLAUDE_RATE_LIMITED")
        assertFalse(reason.contains('_'), "слово причины — не идентификатор: $reason")
        assertTrue(reason.any { it in 'а'..'я' || it in 'А'..'Я' }, "слово причины — по-русски: $reason")
        // И оно объясняет, что делать, а не только называет: длина — признак фразы, а не кода.
        assertTrue(reason.length > 15, "слово причины объясняет, а не называет: $reason")
    }

    /** Неизвестное показывается как есть (D-13), а не пустой строкой и не «?». */
    @Test
    fun unknownIdentifierIsShownAsIs() {
        assertEquals("НЕИЗВЕСТНОЕ_СОСТОЯНИЕ", Words.task("НЕИЗВЕСТНОЕ_СОСТОЯНИЕ"))
        assertEquals("NEW_CODE", Words.reason("NEW_CODE"))
    }
}

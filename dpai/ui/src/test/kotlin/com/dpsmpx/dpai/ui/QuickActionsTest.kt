package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.contexts.ContextBundle
import com.dpsmpx.dpai.contexts.IncludedSource
import com.dpsmpx.dpai.contexts.OmittedSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Быстрые действия (ТЗ, §73) и окно «Контекст» сессии (ТЗ, §74). */
class QuickActionsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(73))

    private fun task(title: String, state: TaskState, held: Boolean = false, archived: Boolean = false): TaskRecord {
        clock.advance(1.minutes)
        return TaskRecord(ids.next(IdKinds.TASK), title, "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = state, held = held, archived = archived, updatedAt = clock.now())
    }

    @Test
    fun actionsFollowTaskStates() {
        val ready = task("готова", TaskState.READY)
        val running = task("идёт", TaskState.RUNNING)
        val paused = task("на паузе", TaskState.READY, held = true)
        val failed = task("упала", TaskState.FAILED)
        val blocked = task("ждёт ответа", TaskState.BLOCKED)
        val unverified = task("без проверки", TaskState.COMPLETION_UNVERIFIED)
        val done = task("готово", TaskState.DONE)
        val archived = task("в архиве", TaskState.FAILED, archived = true)
        // Отменённая на паузе: контур её не отпустит (`Tasks.hold`), «Продолжить» ей не предлагается.
        val cancelledHeld = task("отменена на паузе", TaskState.CANCELLED, held = true)
        val q = QuickActions.of(TasksState(listOf(ready, running, paused, failed, blocked, unverified, done, archived, cancelledHeld).associateBy { it.id }))
        assertEquals(setOf(ready.id, running.id, blocked.id), q.holdable.toSet(), "пауза — тем, кого можно приостановить")
        assertEquals(listOf(paused.id), q.resumable)
        // Ждут человека — новые первыми; задача в архиве не показывается.
        assertEquals(listOf("без проверки", "ждёт ответа", "упала"), q.waiting.map { it.title })
    }

    @Test
    fun bulkCommandsHaveOwnValidKeys() {
        val a = task("a", TaskState.READY)
        val b = task("b", TaskState.QUEUED)
        val q = QuickActions.of(TasksState(listOf(a, b).associateBy { it.id }))
        val key = ids.next(IdKinds.OPERATION).value
        val cs = q.holdAll(key)
        assertEquals(listOf(a.id, b.id).sortedBy { it.value }, cs.map { (it as Command.Hold).task })
        assertEquals(2, cs.mapNotNull { it.key }.toSet().size, "у каждой задачи свой ключ")
        assertTrue(cs.all { Command.KEY.matches(it.key!!) }, "ключ проходит правило команд")
        assertEquals(cs, q.holdAll(key), "повтор той же серии — те же ключи: контур их не удвоит")
    }

    @Test
    fun contextHidesSecretSourcesAndClipsLongText() {
        fun src(key: String, s: Sensitivity, text: String) = IncludedSource(key, SourceLevel.DOCUMENT, s, Origin.REPOSITORY, Hashing.sha256(text.toByteArray()), text)
        val token = "sk-ant-oat01-" + "y".repeat(40)
        val c = ContextBundle(
            ids.next(IdKinds.CONTEXT), 3, ids.next(IdKinds.TASK), ids.next(IdKinds.PROJECT), "добавить парсер", "разбор чисел",
            listOf(
                src("doc:README.md", Sensitivity.INTERNAL, "ключ $token в тексте"),
                src("env:prod", Sensitivity.SECRET, "PASSWORD=hunter2"),
                // Обычный текст: сплошные 40+ букв скрываются как возможный ключ (D-130).
                src("doc:long.md", Sensitivity.PUBLIC, "word ".repeat((ContextScreens.MAX_CHARS + 14) / 5).take(ContextScreens.MAX_CHARS + 10)),
            ),
            null, clock.now(),
            omitted = listOf(OmittedSource("doc:big.md", SourceLevel.DOCUMENT, 9000), OmittedSource("doc:copy.md", SourceLevel.DOCUMENT, 10, sameAs = "doc:README.md")),
        )
        val v = ContextScreens.view(c)
        assertEquals("Контекст задачи v3", v.title)
        assertTrue(token !in v.sources[0].text!!, "секрет в тексте источника скрыт (D-14)")
        assertNull(v.sources[1].text, "секретный источник — без текста (ТЗ, §65)")
        assertEquals("текст скрыт: секрет", v.sources[1].hidden)
        assertTrue(v.sources[2].text!!.endsWith("… ещё 10 знаков"))
        assertEquals(ContextScreens.MAX_CHARS + 10, v.sources[2].chars)
        assertEquals(listOf("doc:big.md — не вошёл по бюджету, ≈9000 ток.", "doc:copy.md — повтор doc:README.md"), v.omitted)
    }
}

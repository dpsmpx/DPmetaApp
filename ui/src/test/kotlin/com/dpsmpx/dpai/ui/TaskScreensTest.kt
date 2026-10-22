package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.FindingSeverity
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.ReviewFinding
import com.dpsmpx.dpai.tasks.ReviewRecord
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.ReviewVerdict
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Действия задачи по состоянию (D-42): экран не предлагает того, что оркестратор отклонит. */
class TaskScreensTest {
    private val clock = ManualClock()
    private val id = IdGenerator(clock, RandomSource.seeded(3)).next(IdKinds.TASK)
    private fun task(state: TaskState, held: Boolean = false) =
        TaskRecord(id, "задача", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = state, held = held)

    @Test
    fun actionsFollowState() {
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.RUN_FIRST, TaskAction.POLICY, TaskAction.SCOPE, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.RUNNING)), "идущей — без правки политики")
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.RUN_FIRST, TaskAction.POLICY, TaskAction.SCOPE, TaskAction.RESUME, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY, held = true)))
        assertTrue(TaskAction.REPLY in TaskScreens.actions(task(TaskState.BLOCKED)) && TaskAction.RETRY in TaskScreens.actions(task(TaskState.BLOCKED)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.VERIFY, TaskAction.WAIVE, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.COMPLETION_UNVERIFIED)))
        assertEquals(listOf(TaskAction.POLICY, TaskAction.RETRY, TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.FAILED)), "завершённой — без области")
        assertEquals(listOf(TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.DONE)))
        assertTrue(TaskAction.CANCEL.dangerous && TaskAction.WAIVE.dangerous && !TaskAction.HOLD.dangerous)
        assertEquals(id, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).id)
    }

    /** D-42, п. 1: лист задачи показывает область и зависимости; выбор зависимостей — без завершённых и самой задачи. */
    @Test
    fun scopeAndDependenciesInDetail() {
        val gen = IdGenerator(clock, RandomSource.seeded(4))
        val dep = TaskRecord(gen.next(IdKinds.TASK), "база", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.QUEUED)
        val done = TaskRecord(gen.next(IdKinds.TASK), "готовая", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.DONE)
        val t = task(TaskState.READY).copy(dependencies = setOf(dep.id), scope = setOf("src/a"))
        val all = listOf(t, dep, done).associateBy { it.id }
        val d = TaskScreens.detail(t, SessionsState.EMPTY, tasks = all)
        assertEquals(setOf("src/a"), d.scope)
        assertEquals(listOf(DependencyLine(dep.id, "база", "QUEUED")), d.dependencies)
        assertEquals(listOf(dep.id), TaskScreens.dependencyChoices(all, except = t.id).map { it.id })
    }

    /** D-59: лист задачи — роли из политики и итог каждой; нет итога — «ещё нет». */
    @Test
    fun reviewsInDetail() {
        val blocked = ReviewRecord(
            ReviewRole.SECURITY, ReviewVerdict.BLOCKED, 1, null,
            listOf(ReviewFinding(FindingSeverity.BLOCKING, "секрет в журнале", "src/Log.kt", 12, "ключ пишется целиком"), ReviewFinding(FindingSeverity.MINOR, "имя", null, null, null)),
            omitted = 2,
        )
        val t = task(TaskState.READY).copy(policy = TaskPolicy(reviews = setOf(ReviewRole.SECURITY, ReviewRole.CODE)), reviews = mapOf(ReviewRole.SECURITY to blocked))
        val d = TaskScreens.detail(t, SessionsState.EMPTY)
        assertEquals(listOf(ReviewRole.CODE, ReviewRole.SECURITY), d.reviews.map { it.role })
        assertEquals("ещё нет", d.reviews[0].verdict)
        val sec = d.reviews[1]
        assertEquals("нужно исправить", sec.verdict)
        assertEquals(Tone.WARNING, sec.tone)
        assertEquals(FindingLine(FindingSeverity.BLOCKING, "секрет в журнале", "src/Log.kt:12", "ключ пишется целиком"), sec.findings.first())
        assertEquals("не показано находок: 2", sec.note)
        val waived = TaskScreens.review(ReviewRole.CODE, ReviewRecord(ReviewRole.CODE, ReviewVerdict.WAIVED, 2, null, by = "автор", reason = "ревью не отвечает"))
        assertEquals("решил: автор · ревью не отвечает", waived.note)
        assertEquals(emptyList(), TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).reviews, "без ролей — без строк")
    }

    /** D-59: сессию ревью не продолжают — запрос и свежая сессия у последней рабочей; в строке сводки — роль. */
    @Test
    fun reviewSessionIsNotContinued() {
        val gen = IdGenerator(clock, RandomSource.seeded(5))
        fun session(review: String?): SessionRecord {
            clock.advance(1.minutes)
            return SessionRecord(
                gen.next(IdKinds.SESSION), "k", id, gen.next(IdKinds.ACCOUNT), gen.next(IdKinds.SUBSCRIPTION), gen.next(IdKinds.TARGET), "/c",
                com.dpsmpx.dpai.core.ids.ProviderSessionId.newUuid(RandomSource.seeded(6)), null, SessionState.COMPLETED, clock.now(), clock.now(), review = review,
            )
        }
        val work = session(null)
        val review = session("CODE")
        val sessions = SessionsState(listOf(work, review).associateBy { it.id })
        val tasks = TasksState(mapOf(id to task(TaskState.BLOCKED)))
        assertEquals(emptyList(), Screens.sessionActions(review.id, sessions, tasks), "ревью только читает")
        assertEquals(listOf(SessionAction.SEND, SessionAction.FRESH, SessionAction.REASSIGN), Screens.sessionActions(work.id, sessions, tasks), "продолжают работу, хотя ревью было позже")
        // «Перенести» (D-63) — у рабочей сессии незавершённой задачи, не на проверке.
        assertTrue(SessionAction.REASSIGN !in Screens.sessionActions(work.id, sessions, TasksState(mapOf(id to task(TaskState.VERIFIED)))))
        assertTrue(SessionAction.REASSIGN !in Screens.sessionActions(work.id, sessions, TasksState(mapOf(id to task(TaskState.DONE)))))
        assertEquals("ревью кода", Screens.reviewLabel("CODE"))
        assertEquals("ревью STYLE", Screens.reviewLabel("STYLE"))
    }
}

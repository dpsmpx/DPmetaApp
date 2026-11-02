package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Предотвращение конфликтов (ТЗ, §31): задачи с общим файлом идут по
 * очереди, с общим каталогом — параллельно с предупреждением, с
 * непересекающимися областями — параллельно; область неизвестна — без
 * проверки.
 */
class OverlapTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(31))
    private lateinit var o: Orchestrator
    private lateinit var runner: MockRunner

    private fun setUp() {
        val targets = List(4) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 1) }
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        for ((i, t) in targets.withIndex()) {
            val slot = ids.next(IdKinds.ACCOUNT)
            acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, t.id, "/c/$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        }
        runner = MockRunner.create(clock, RandomSource.seeded(32)) { _, _ -> MockScenario.SLOW_OUTPUT }
        val project = ids.next(IdKinds.PROJECT)
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        o = Orchestrator(
            clock, ids, RandomSource.seeded(33), (EventJournal.open(NioVfs(dir), "journal.log") as Outcome.Success).value, runner,
            ProjectConfig(project, null, dir.toString(), profile), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), acc,
        )
    }

    private fun running(t: TaskId) = o.sessions.sessions.values.any { it.task == t && !it.terminal }

    private fun finishAll() = repeat(20) {
        o.sessions.sessions.keys.forEach { s -> while (runner.advance(s)) continue }
        o.tick()
        clock.advance(1.minutes)
    }

    @Test
    fun sameFileSerializesDisjointRunsInParallel() {
        setUp()
        val a = o.addTask("a", "правка", scope = setOf("src/app/Main.kt")).valueOrNull()!!
        val b = o.addTask("b", "правка того же файла", scope = setOf("src/app/Main.kt", "src/app/Util.kt")).valueOrNull()!!
        val c = o.addTask("c", "документы", scope = setOf("manual/guide.md")).valueOrNull()!!
        val d = o.addTask("d", "область неизвестна").valueOrNull()!!
        o.tick()
        assertTrue(running(a) && running(c) && running(d), "непересекающиеся и неизвестные — параллельно")
        assertTrue(!running(b), "общий файл — b ждёт a")
        val waits = o.posted.filterIsInstance<NotificationDecision.Post>().map { it.notification }.filter { it.key == "task:${b.value}:overlap" }
        assertEquals(1, waits.size, "одно уведомление об ожидании, а не на каждом такте")
        o.tick()
        assertEquals(1, o.posted.filterIsInstance<NotificationDecision.Post>().count { it.notification.key == "task:${b.value}:overlap" })
        finishAll()
        assertTrue(listOf(a, b, c, d).all { o.tasks.tasks.getValue(it).state == TaskState.DONE }, listOf(a, b, c, d).map { o.tasks.tasks.getValue(it).state }.toString())
        assertEquals(emptyList(), o.invariants())
    }

    @Test
    fun sharedDirectoryRunsWithWarningCriticalFileWaits() {
        setUp()
        val a = o.addTask("a", "файл в каталоге", scope = setOf("src/app/A.kt", "build.gradle.kts")).valueOrNull()!!
        val b = o.addTask("b", "другой файл того же каталога", scope = setOf("src/app/B.kt")).valueOrNull()!!
        val c = o.addTask("c", "сборка", scope = setOf("build.gradle.kts")).valueOrNull()!!
        o.tick()
        assertTrue(running(a) && running(b), "общий каталог — параллельно")
        assertTrue(!running(c), "критичный файл сборки — ждёт")
        val keys = o.posted.filterIsInstance<NotificationDecision.Post>().map { it.notification.key to it.notification.title }
        // В заголовке — название задачи (аудит 1 ноября 2026, A-11): по уведомлению видно, о какой речь.
        assertTrue(("task:${b.value}:overlap" to "Возможный конфликт: b") in keys, keys.toString())
        assertTrue(("task:${c.value}:overlap" to "Задача ждёт — критичный файл: c") in keys, keys.toString())
        finishAll()
        assertEquals(TaskState.DONE, o.tasks.tasks.getValue(c).state)
    }
}

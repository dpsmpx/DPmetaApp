package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.commands.AcceptedProjection
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.Verifier
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Принятая команда — в журнале (D-94; аудит надёжности 2 ноября 2026,
 * A-2, A-3): пока такт занят, команда пишется `COMMAND_ACCEPTED`; процесс
 * умер до исполнения — после перезапуска она исполняется, и ровно один раз;
 * закрытый журнал (контур пересобран) записей не принимает.
 */
class AcceptedCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(941))
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(942)))
    private val targets = listOf(TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 1))
    private val project = ids.next(IdKinds.PROJECT)

    private lateinit var journal: EventJournal

    /** Контур поверх журнала на диске — «запуск приложения». */
    private fun boot(): Orchestrator {
        journal = (EventJournal.open(NioVfs(dir), "journal.log") as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(1), host) { _, _ -> MockScenario.SLOW_OUTPUT }
        return Orchestrator(
            clock, ids, RandomSource.seeded(943), journal, runner, ProjectConfig(project, null, dir.toString(), null), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), AccountsState.EMPTY,
        )
    }

    private fun types() = journal.records(journal.activeFrom).map { it.event.type }

    @Test
    fun acceptedCommandSurvivesRestartAndRunsOnce() {
        val first = boot()
        // Такт занят: команда принята в журнал, но не исполнена — и процесс умер.
        val f = assertIs<Outcome.Success<*>>(first.accept(Command.AddTask("из очереди", "цель")))
        assertTrue(EventType.COMMAND_ACCEPTED in types())
        assertEquals(1, first.accepted.pending.size)
        assertEquals(0, first.tasks.tasks.size, "ещё не исполнена")
        assertTrue(!(f.value as java.util.concurrent.CompletableFuture<*>).isDone)

        val second = boot()
        assertEquals(1, second.accepted.pending.size, "принятая команда — из журнала")
        assertEquals(1, second.inbox.size, "снова в очереди")
        assertEquals(1, second.runCommands())
        assertEquals(listOf("из очереди"), second.tasks.tasks.values.map { it.title })
        assertEquals(0, second.accepted.pending.size)
        assertTrue(EventType.COMMAND_SETTLED in types())
        // Ключом стал id операции: повтор того же ключа второго действия не делает (ТЗ, §5.4).
        val key = second.operations.byKey.keys.single()
        assertTrue(key.startsWith("op_"), key)

        val third = boot()
        assertEquals(0, third.inbox.size, "исполненная — второй раз не исполняется")
        assertEquals(1, third.tasks.tasks.size)
        assertEquals(OperationStatus.SUCCEEDED, third.operations.byKey.getValue(key).status)
    }

    @Test
    fun answerAndFactOfShowingAreNotRekeyed() {
        val o = boot()
        // Факт показа запроса — не решение человека: в журнал как принятая не пишется.
        assertIs<Outcome.Success<*>>(o.accept(Command.Delivered(ids.next(IdKinds.REQUEST), "notification")))
        assertTrue(EventType.COMMAND_ACCEPTED !in types())
        // Ответ на запрос — без ключа: повтор и так без действия (D-44).
        assertIs<Outcome.Success<*>>(o.accept(Command.AnswerRequest("dpai://answer/x/y?hash=sha256:00", null, "автор", "notification")))
        val a = o.accepted.pending.values.single()
        assertTrue("key" !in a.command.fields, a.command.toString())
        // Снимок — тот же порядок и содержимое.
        assertEquals(o.accepted, AcceptedProjection.decode(AcceptedProjection.encode(o.accepted)).valueOrNull())
    }

    /**
     * Аудит надёжности, A-4: журнал закрылся после отказа записи (кончилось
     * место) — контур не делает внешних действий: команды — отказ с причиной,
     * такт — ничего; оживляет его оболочка, открыв журнал заново.
     */
    @Test
    fun brokenJournalStopsActions() {
        var full = false
        val vfs = object : com.dpsmpx.dpai.storage.vfs.Vfs by NioVfs(dir) {
            override fun appendDurably(path: String, bytes: ByteArray) {
                if (full) throw java.io.IOException("No space left on device")
                NioVfs(dir).appendDurably(path, bytes)
            }
        }
        journal = (EventJournal.open(vfs, "journal.log") as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(1), host) { _, _ -> MockScenario.SLOW_OUTPUT }
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(944), journal, runner, ProjectConfig(project, null, dir.toString(), null), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), AccountsState.EMPTY,
        )
        val task = o.addTask("задача", "цель").valueOrNull()!!
        full = true
        runCatching { o.addTask("вторая", "цель") }
        assertTrue(o.storageBroken, "отказ записи закрыл журнал")
        full = false
        val r = o.execute(Command.Cancel(task))
        assertEquals(DiagCode.STORAGE_UNAVAILABLE, r.diagnostics.first().code, "действие — не без записи итога")
        o.tick()
        assertTrue(host.runIds().isEmpty(), "ни одного хода, пока журнал не открыт заново")
    }

    @Test
    fun closedJournalAcceptsNothing() {
        val o = boot()
        journal.close()
        val r = o.accept(Command.Hold(ids.next(IdKinds.TASK)))
        assertEquals(DiagCode.STORAGE_UNAVAILABLE, (r as Outcome.Failure).errors.first().code, "не принято — честно, без обещания")
        assertEquals(0, o.inbox.size)
        assertEquals(0, o.accepted.pending.size)
        assertEquals(0L, journal.position, "закрытый журнал не дописан")
    }
}

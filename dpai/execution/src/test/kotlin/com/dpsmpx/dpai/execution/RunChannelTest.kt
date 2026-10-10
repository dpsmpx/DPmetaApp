package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunEventSource
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.agent.RunStatus
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Канал событий запуска (критерий этапа 3): пропуск номера события
 * обнаруживается и ведёт к сверке; незакрытый пропуск — отказ, а не
 * применение через дыру (D-12; ТЗ, §56).
 */
class RunChannelTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(7))
    private val run: SessionId = ids.next(IdKinds.SESSION)
    private val events: List<EventEnvelope> = (1L..20L).map { n ->
        EventEnvelope(ids.next(IdKinds.EVENT), EventType.TARGET_PROCESS_OUTPUT, StreamRef.of(run), n, clock.now(), clock.now())
    }

    /** Агент, который отдаёт журнал; [drop] — какие номера теряются в ответе (обрыв, сбой канала). */
    private class FakeAgent(val run: SessionId, val journal: List<EventEnvelope>, var drop: (call: Int, seq: Long) -> Boolean = { _, _ -> false }) :
        RunEventSource {
        var calls = 0
        var written = journal.size

        override fun events(run: SessionId, after: Long, limit: Int): Outcome<AgentReply> {
            calls++
            val page = journal.take(written).filter { it.sequence > after }.take(limit).filterNot { drop(calls, it.sequence) }
            val status = RunStatus(run, RunState.RUNNING, lastSequence = written.toLong())
            return Outcome.success(AgentReply("events", ok = true, run = status, events = page))
        }
    }

    @Test
    fun deliversInOrderAcrossPolls() {
        val agent = FakeAgent(run, events).apply { written = 5 }
        val channel = RunChannel(agent, run)
        assertEquals((1L..5L).toList(), channel.poll().valueOrNull()!!.events.map { it.sequence })
        agent.written = 12
        assertEquals((6L..12L).toList(), channel.poll().valueOrNull()!!.events.map { it.sequence })
        assertEquals(0, channel.reconciliations)
    }

    @Test
    fun gapIsDetectedAndClosedByReconciliation() {
        // Первый ответ теряет №4: канал замечает пропуск и запрашивает с №4 заново.
        val agent = FakeAgent(run, events) { call, seq -> call == 1 && seq == 4L }
        val channel = RunChannel(agent, run)
        val r = channel.poll()
        assertIs<Outcome.Success<RunChannel.Batch>>(r)
        assertEquals((1L..20L).toList(), r.value.events.map { it.sequence })
        assertEquals(1, channel.reconciliations)
        assertTrue(r.warnings.any { it.code == DiagCode.EVENT_SEQUENCE_GAP })
    }

    @Test
    fun gapAtTheTailIsDetectedFromReportedSequence() {
        // Агент сообщает №20, а события 18…20 потерялись в первом ответе.
        val agent = FakeAgent(run, events) { call, seq -> call == 1 && seq >= 18L }
        val channel = RunChannel(agent, run)
        val r = channel.poll()
        assertEquals(20L, channel.lastAccepted)
        assertEquals(1, channel.reconciliations)
        assertEquals(20, r.valueOrNull()!!.events.size)
    }

    @Test
    fun unclosedGapRequiresReconciliationInsteadOfApplying() {
        val agent = FakeAgent(run, events) { _, seq -> seq == 6L }
        val channel = RunChannel(agent, run)
        // Принятые до дыры №1…5 отдаются, отказ сверки — предупреждением (A-2).
        val first = channel.poll()
        assertIs<Outcome.Success<RunChannel.Batch>>(first)
        assertEquals((1L..5L).toList(), first.value.events.map { it.sequence })
        assertTrue(first.warnings.any { it.code == DiagCode.RUN_RECONCILIATION_REQUIRED })
        // Дыра осталась — следующий опрос отказывает, ничего не приняв.
        val r = channel.poll()
        assertIs<Outcome.Failure>(r)
        val d = r.errors.single()
        assertEquals(DiagCode.RUN_RECONCILIATION_REQUIRED, d.code)
        assertEquals(DiagCode.EVENT_SEQUENCE_GAP, d.causes.single().code)
        assertEquals(5L, channel.lastAccepted, "через дыру ничего не применено")
    }

    @Test
    fun failureAfterAcceptedPageKeepsAcceptedEvents() {
        // A-2: первая страница принята, вторая сорвалась (обрыв SSH) — принятое
        // не пропадает, а следующий опрос продолжает с №11, а не после потерянных.
        var calls = 0
        val agent = FakeAgent(run, events)
        val channel = RunChannel(
            { r, after, limit ->
                calls++
                if (calls == 2) Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "обрыв", "target") else agent.events(r, after, limit)
            },
            run,
            pageSize = 10,
        )
        val first = channel.poll()
        assertIs<Outcome.Success<RunChannel.Batch>>(first)
        assertEquals((1L..10L).toList(), first.value.events.map { it.sequence })
        assertEquals(null, first.value.status, "состояние из сорванного опроса не отдаётся")
        assertTrue(first.warnings.any { it.code == DiagCode.SSH_CONNECTION_FAILED })
        assertEquals((11L..20L).toList(), channel.poll().valueOrNull()!!.events.map { it.sequence })
    }

    @Test
    fun failureBeforeAnythingAcceptedIsFailure() {
        val channel = RunChannel({ _, _, _ -> Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "обрыв", "target") }, run)
        assertIs<Outcome.Failure>(channel.poll())
        assertEquals(0L, channel.lastAccepted)
    }

    @Test
    fun sameNumberWithOtherEventIsConflict() {
        val forged = events.toMutableList()
        forged[2] = EventEnvelope(ids.next(IdKinds.EVENT), EventType.TARGET_PROCESS_OUTPUT, StreamRef.of(run), 3, clock.now(), clock.now())
        // Второй ответ несёт другое событие под уже принятым номером №3.
        val mixed = RunChannel(
            { _, after, _ -> Outcome.success(AgentReply("events", true, run = RunStatus(run, RunState.RUNNING, lastSequence = 5), events = if (after == 0L) events.take(5) else forged.take(5))) },
            run,
            pageSize = 5,
        )
        // Первые пять приняты и отдаются, конфликт — предупреждением (A-2);
        // следующий опрос упирается в конфликт, ничего не приняв.
        val first = mixed.poll()
        assertEquals((1L..5L).toList(), first.valueOrNull()!!.events.map { it.sequence })
        assertTrue(first.warnings.any { it.code == DiagCode.EVENT_SEQUENCE_CONFLICT })
        val r = mixed.poll()
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.EVENT_SEQUENCE_CONFLICT, r.errors.single().code)
    }

    @Test
    fun replyRoundTripsThroughJson() {
        val reply = AgentReply("events", true, run = RunStatus(run, RunState.RUNNING, pid = 42, lastSequence = 3), events = events.take(3))
        val parsed = AgentReply.parse(CanonicalJson.write(reply.toJson()))
        assertEquals(reply, parsed.valueOrNull())
        assertIs<Outcome.Failure>(AgentReply.parse("""{"format":"dpai.agent-reply","version":1,"command":"x","ok":true,"diagnostics":[],"extra":1}"""))
    }

    /** Свойство: при любых потерях в одном ответе канал отдаёт ровно 1…N по порядку. */
    @Test
    fun anySingleReplyLossIsRecovered() {
        Property.forAll(Gen.list(Gen.long(1L..20L), 0..6)) { lost ->
            val agent = FakeAgent(run, events) { call, seq -> call == 1 && seq in lost }
            val channel = RunChannel(agent, run)
            val r = channel.poll()
            assertEquals((1L..20L).toList(), r.valueOrNull()?.events?.map { it.sequence }, "потеряны $lost")
        }
    }
}

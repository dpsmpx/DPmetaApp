package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.FollowingEventSource
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunFollower
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.agent.RunStatus
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Поток событий хода вместо опроса (D-62, «Проверка»): события идущего
 * хода приходят потоком, а не вызовом агента на каждый опрос; обрыв —
 * опрос и переподключение с номера; агент без `follow` — опрос; пропуск
 * в потоке и молчание — опрос, ничего не теряется и не повторяется.
 */
class FollowingEventSourceTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(21))
    private val mock = MockAgentHost(clock, ids)
    private val agent = AgentClient(mock, listOf("dpai"), null, "target mock")

    private fun startRun(): SessionId = ids.next(IdKinds.SESSION).also { run ->
        assertTrue(agent.start(run, RunSpec(listOf("claude", "-p"))) is Outcome.Success)
    }

    private fun RunChannel.take(): List<EventEnvelope> = poll().valueOrNull()!!.events

    @Test
    fun streamReplacesPollingWhileTheTurnRuns() {
        val run = startRun()
        val source = FollowingEventSource(agent, clock)
        val channel = RunChannel(source, run)
        // Первый опрос — у агента; он показывает идущий ход и начинает поток с того же номера.
        assertEquals(listOf(1L), channel.take().map { it.sequence })
        assertEquals(1, mock.eventCalls)
        assertEquals(1, mock.followerCount())
        mock.output(run, "a")
        mock.output(run, "b")
        assertEquals(listOf(2L, 3L), channel.take().map { it.sequence })
        assertEquals(emptyList(), channel.take())
        mock.exit(run, 0)
        val last = channel.poll().valueOrNull()!!
        assertEquals(EventType.TARGET_PROCESS_EXITED, last.events.single().type)
        assertEquals(RunState.EXITED, last.status!!.state)
        assertEquals(1, mock.eventCalls, "весь ход — без опроса агента")
        assertEquals(0, mock.followerCount(), "поток кончился вместе с ходом")
        // Ход кончен и всё отдано: дальше опрос, а нового потока у законченного хода нет.
        assertEquals(emptyList(), channel.take())
        assertEquals(2, mock.eventCalls)
        assertEquals(1, source.started)
    }

    @Test
    fun agentWithoutFollowIsPolledAndProbedAgainLater() {
        mock.follows = false
        val run = startRun()
        val source = FollowingEventSource(agent, clock)
        val channel = RunChannel(source, run)
        channel.take()
        mock.output(run, "a")
        assertEquals(listOf(2L), channel.take().map { it.sequence })
        mock.output(run, "b")
        assertEquals(listOf(3L), channel.take().map { it.sequence })
        assertEquals(3, mock.eventCalls)
        assertEquals(1, source.started, "отказ UNSUPPORTED — новой пробы потока нет до срока")
        // Агент обновили; через срок пробы поток начинается.
        mock.follows = true
        clock.advance(FollowingEventSource.UNSUPPORTED_RETRY)
        channel.take()
        assertEquals(2, source.started)
        mock.output(run, "c")
        assertEquals(listOf(4L), channel.take().map { it.sequence })
        assertEquals(4, mock.eventCalls)
    }

    @Test
    fun dropFallsBackToPollingAndReconnectsFromTheLastAccepted() {
        val run = startRun()
        val source = FollowingEventSource(agent, clock)
        val channel = RunChannel(source, run)
        val got = channel.take().toMutableList()
        mock.output(run, "a")
        got += channel.take()
        // Обрыв канала SSH: события, записанные без потока, приходят опросом.
        mock.dropFollowers()
        mock.output(run, "b")
        val polledBefore = mock.eventCalls
        got += channel.take()
        assertEquals(polledBefore + 1, mock.eventCalls)
        assertEquals(1, source.started, "сразу после обрыва нового потока нет")
        clock.advance(FollowingEventSource.RETRY_AFTER)
        mock.output(run, "c")
        got += channel.take()
        assertEquals(2, source.started, "через паузу — новый поток с последнего принятого номера")
        val calls = mock.eventCalls
        mock.output(run, "d")
        mock.exit(run, 0)
        got += channel.take()
        assertEquals(calls, mock.eventCalls)
        assertEquals((1L..6L).toList(), got.map { it.sequence }, "номера подряд, без пропусков и повторов")
    }

    @Test
    fun newTurnDropsTheStreamOfTheOldOne() {
        val run = startRun()
        val source = FollowingEventSource(agent, clock)
        val channel = RunChannel(source, run)
        channel.take()
        assertEquals(1, mock.followerCount())
        source.reset(run)
        assertEquals(0, mock.followerCount(), "поток остановлен")
        mock.output(run, "a")
        val calls = mock.eventCalls
        assertEquals(listOf(2L), channel.take().map { it.sequence })
        assertEquals(calls + 1, mock.eventCalls, "после сброса — опрос")
        assertEquals(2, source.started)
    }

    @Test
    fun streamsPerTargetAreLimited() {
        val source = FollowingEventSource(agent, agent, clock, maxStreams = 2)
        val runs = List(3) { startRun() }
        runs.forEach { RunChannel(source, it).take() }
        assertEquals(2, mock.followerCount())
        assertEquals(2, source.streaming)
        // Ход с потоком кончился — место свободно для следующего.
        mock.exit(runs[0], 0)
        RunChannel(source, runs[2]).take()
        assertEquals(2, mock.followerCount())
        source.close()
        assertEquals(0, mock.followerCount(), "закрытый источник останавливает потоки")
    }

    /** Поток-подделка: строки шлёт тест; процесс жив, пока его не остановят. */
    private class FakeStream : RunningProcess {
        var stopped = false
        override val pid: Long? = null
        override val isAlive: Boolean get() = !stopped

        override fun awaitExit(timeout: Duration): Int? = if (stopped) 143 else null

        override fun stop(grace: Duration): StopReport {
            stopped = true
            return StopReport(143, false, emptyList())
        }
    }

    private fun envelope(run: SessionId, n: Long) =
        EventEnvelope(ids.next(IdKinds.EVENT), EventType.TARGET_PROCESS_OUTPUT, StreamRef.of(run), n, clock.now(), clock.now())

    @Test
    fun gapInTheStreamAndSilenceFallBackToPolling() {
        val run = startRun()
        mock.output(run, "a")
        var send: (Outcome<AgentReply>) -> Unit = {}
        val streams = mutableListOf<FakeStream>()
        val follower = RunFollower { _, _, onReply ->
            send = onReply
            Outcome.success(FakeStream().also { streams += it })
        }
        val source = FollowingEventSource(agent, follower, clock)
        val channel = RunChannel(source, run)
        assertEquals(listOf(1L, 2L), channel.take().map { it.sequence })
        // Поток прислал №4 без №3: из буфера не отвечать — опрос отдаёт №3 и №4 по порядку.
        mock.output(run, "b")
        mock.output(run, "c")
        send(Outcome.success(AgentReply("follow", ok = true, run = RunStatus(run, RunState.RUNNING, lastSequence = 4), events = listOf(envelope(run, 4)))))
        val calls = mock.eventCalls
        assertEquals(listOf(3L, 4L), channel.take().map { it.sequence })
        assertEquals(calls + 1, mock.eventCalls)
        // Новый поток после паузы молчит дольше срока — он мёртв: остановлен, ответ — опросом.
        clock.advance(FollowingEventSource.RETRY_AFTER)
        channel.take()
        assertEquals(2, streams.size)
        clock.advance(FollowingEventSource.STALE + FollowingEventSource.STALE)
        mock.output(run, "d")
        assertEquals(listOf(5L), channel.take().map { it.sequence })
        assertTrue(streams.last().stopped, "молчащий поток остановлен")
    }
}

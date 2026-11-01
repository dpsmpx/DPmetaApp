package com.dpsmpx.dpai.execution.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.RunningProcess
import java.util.TreeMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Поток событий идущих ходов вместо опроса (D-76, D-79; ТЗ, §56).
 *
 * Опрос `events` — вызов агента на каждый такт, а по SSH это запуск JVM на
 * target: ~1 с на телефоне (`docs/research/SSH_ON_ANDROID.md`). У идущего
 * хода здесь поток `follow`: агент сам присылает строки по мере записи
 * журнала, а канал запуска ([RunChannel]) получает события из буфера
 * потока без вызова агента. Из буфера — только когда в нём всё после
 * запрошенного номера: поток начат не позже этого номера, события пришли
 * подряд, последняя строка без `more` и номер последнего события в ней не
 * больше полученного. Иначе — опрос, как прежде; пропуск номера и сверку
 * по-прежнему решает канал.
 *
 * Поток начинается после опроса, показавшего идущий ход, — с того же
 * номера. Кончается он вместе с ходом или по сроку агента — следующий
 * опрос начнёт новый с номера; обрыв, отказ, нечитаемая строка, пропуск
 * номера, молчание дольше [STALE] — опрос, новый поток не раньше
 * [retryAfter]. Агент без `follow` (прежняя версия: код 2 без ответа, или
 * отказ `UNSUPPORTED`) — опрос, проба снова через [UNSUPPORTED_RETRY].
 * Потоков на target не больше [maxStreams]: каждый — канал SSH и JVM на
 * target; остальные ходы — опросом.
 *
 * Строки потока приходят из потока чтения, поэтому состояние — под замком,
 * а вызовы агента и остановка процессов — вне его: подделка агента в
 * тестах присылает строки из того же потока, что и вызов.
 */
class FollowingEventSource(
    private val poll: RunEventSource,
    private val follower: RunFollower,
    private val clock: Clock,
    private val maxStreams: Int = MAX_STREAMS,
    private val retryAfter: Duration = RETRY_AFTER,
) : RunEventSource, AutoCloseable {
    constructor(agent: AgentClient, clock: Clock) : this(agent, agent, clock)

    private class Stream(val run: SessionId, val from: Long, var lastLineAt: Long) {
        var process: RunningProcess? = null
        val events = TreeMap<Long, EventEnvelope>()

        /** Получено всё подряд до этого номера. */
        var complete: Long = from

        /** Состояние из последней строки без `more`: до неё в буфере всё. */
        var status: RunStatus? = null
        var lines: Int = 0

        /** Отказ, нечитаемая строка, чужой поток, пропуск или молчание: из буфера не отвечать. */
        var broken: Boolean = false

        /** Процесс потока вышел. */
        var exited: Boolean = false

        val live: Boolean get() = !broken && !exited
    }

    private val lock = Any()
    private val streams = HashMap<SessionId, Stream>()
    private var closed = false

    /** Монотонное время, раньше которого новый поток не начинается. */
    private var retryAt: Long? = null

    /** Ответов из буфера потока — без вызова агента. */
    var served: Int = 0
        private set

    /** Ответов опросом агента. */
    var polled: Int = 0
        private set

    /** Начатых потоков. */
    var started: Int = 0
        private set

    /** Идущих потоков. */
    val streaming: Int get() = synchronized(lock) { streams.values.count { it.live } }

    override fun events(run: SessionId, after: Long, limit: Int): Outcome<AgentReply> {
        sweep()
        synchronized(lock) {
            streams[run]?.let { s ->
                serve(s, after, limit)?.let {
                    served++
                    return Outcome.success(it)
                }
            }
            polled++
        }
        val reply = poll.events(run, after, limit)
        if (reply is Outcome.Success && reply.value.run?.finished == false) follow(run, after)
        return reply
    }

    /** Новый ход или остановка: буфер устарел — поток прочь, следующий ответ — опросом. */
    override fun reset(run: SessionId) {
        synchronized(lock) { streams.remove(run) }?.process?.stop(STOP_GRACE)
    }

    override fun close() {
        val all = synchronized(lock) {
            closed = true
            streams.values.toList().also { streams.clear() }
        }
        all.forEach { it.process?.stop(STOP_GRACE) }
    }

    /** Под замком. */
    private fun serve(s: Stream, after: Long, limit: Int): AgentReply? {
        val status = s.status ?: return null
        if (s.broken || after < s.from || after > s.complete || status.lastSequence > s.complete) return null
        // Вышедший поток отдаёт только полученное; дальше — опрос и, если ход идёт, новый поток.
        if (s.exited && after >= s.complete) {
            streams.remove(s.run)
            return null
        }
        // Принятое каналом (номера до [after]) больше не нужно.
        s.events.headMap(after, true).clear()
        val page = s.events.values.take(limit)
        return AgentReply("events", ok = true, run = status, events = page, more = s.events.size > page.size)
    }

    /**
     * Выход, молчание и отказ потоков: процессы останавливаются и коды
     * выхода читаются вне замка — ожидание дочитывания вывода не держит его.
     */
    private fun sweep() {
        val now = clock.monotonicNanos()
        val check = synchronized(lock) {
            for (s in streams.values) {
                if (s.live && now - s.lastLineAt > STALE.inWholeNanoseconds) {
                    s.broken = true
                    failed(now)
                }
            }
            // Вышедший поток давно не спрашивали — его ход больше не ведут: буфер прочь.
            streams.values.removeAll { it.exited && now - it.lastLineAt > KEEP_EXITED.inWholeNanoseconds }
            streams.values.filter { !it.exited && it.process != null }.map { it to it.process!! }
        }
        for ((s, p) in check) {
            if (s.broken && p.isAlive) p.stop(STOP_GRACE)
            if (p.isAlive) continue
            val code = p.awaitExit(Duration.ZERO) ?: continue
            synchronized(lock) {
                if (s.exited) return@synchronized
                s.exited = true
                when {
                    code == 0 || s.broken -> Unit
                    code == USAGE_EXIT && s.lines == 0 -> unsupported(now)
                    else -> {
                        s.broken = true
                        failed(now)
                    }
                }
            }
        }
    }

    /** Начать поток хода [run] с номера [after], если его нет и можно. */
    private fun follow(run: SessionId, after: Long) {
        val now = clock.monotonicNanos()
        val (s, old) = synchronized(lock) {
            if (closed) return
            val existing = streams[run]
            // Идущий поток, начатый не позже номера, — уже есть; начатый позже (канал начат заново
            // с меньшего номера) не ответит никогда — его сменяет новый.
            if (existing != null && existing.live && after >= existing.from) return
            if (retryAt?.let { now < it } == true) return
            if (streams.values.count { it.live } >= maxStreams) return
            val s = Stream(run, after, now)
            streams[run] = s
            started++
            s to existing
        }
        old?.process?.takeIf { it.isAlive }?.stop(STOP_GRACE)
        val begun = follower.follow(run, after) { line(s, it) }
        val orphan = synchronized(lock) {
            when (begun) {
                is Outcome.Failure -> {
                    s.broken = true
                    s.exited = true
                    failed(now)
                    null
                }
                is Outcome.Success -> {
                    s.process = begun.value
                    begun.value.takeIf { closed || streams[run] !== s }
                }
            }
        }
        orphan?.stop(STOP_GRACE)
    }

    /** Строка потока — из потока чтения. */
    private fun line(s: Stream, r: Outcome<AgentReply>) = synchronized(lock) {
        if (s.broken) return@synchronized
        val now = clock.monotonicNanos()
        s.lastLineAt = now
        val reply = (r as? Outcome.Success)?.value
        if (reply == null || !reply.ok) {
            s.broken = true
            if (reply?.diagnostics?.any { it.code == DiagCode.UNSUPPORTED } == true) unsupported(now) else failed(now)
            return@synchronized
        }
        s.lines++
        val stream = StreamRef.of(s.run)
        for (e in reply.events) {
            when {
                e.stream != stream -> s.broken = true
                e.sequence <= s.complete -> Unit
                e.sequence == s.complete + 1 -> {
                    s.events[e.sequence] = e
                    s.complete = e.sequence
                }
                // Пропуск в потоке: канал сверится с агентом опросом.
                else -> s.broken = true
            }
            if (s.broken) return@synchronized failed(now)
        }
        // Такты встали, а ход пишет: буфер не растёт без предела — поток прочь, потом опрос.
        if (s.events.size > MAX_BUFFERED) {
            s.broken = true
            return@synchronized failed(now)
        }
        if (!reply.more) reply.run?.let { s.status = it }
    }

    /** Под замком. */
    private fun failed(now: Long) {
        retryAt = maxOf(retryAt ?: now, now + retryAfter.inWholeNanoseconds)
    }

    /** Под замком. */
    private fun unsupported(now: Long) {
        retryAt = maxOf(retryAt ?: now, now + UNSUPPORTED_RETRY.inWholeNanoseconds)
    }

    companion object {
        /** Потоков на target: у OpenSSH по умолчанию `MaxSessions` 10, а каждый поток — ещё и JVM на target. */
        const val MAX_STREAMS: Int = 4

        /** После обрыва или отказа поток не начинается раньше: опрос работает и без него. */
        val RETRY_AFTER: Duration = 30.seconds

        /** Агент без `follow` — проба снова не раньше: агент на target обновляют нечасто. */
        val UNSUPPORTED_RETRY: Duration = 10.minutes

        /** Поток молчит дольше трёх признаков жизни агента (`AgentRuntime.FOLLOW_HEARTBEAT`) — он мёртв. */
        val STALE: Duration = 45.seconds

        /** Сколько держать буфер вышедшего потока, который никто не спрашивает. */
        val KEEP_EXITED: Duration = 5.minutes

        /** Событий в буфере потока — не больше. */
        const val MAX_BUFFERED: Int = 2000

        /** Код неверного вызова `dpai`: агент прежней версии не знает `follow`. */
        const val USAGE_EXIT: Int = 2

        private val STOP_GRACE: Duration = 1.seconds
    }
}

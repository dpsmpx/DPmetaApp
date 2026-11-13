package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.execution.agent.ServiceStatus
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Служба агента на ПК (D-109): долгоживущий `dpai agent serve` — служба
 * пользователя systemd на Linux, задача входа в Windows. Супервизоры ходов
 * она запускает своими потомками: ход живёт со службой, а не с сеансом SSH,
 * который его начал (systemd с `KillUserProcesses=yes` убивает процессы
 * сеанса при выходе, и `setsid` от этого не уводит). Протокол агента не
 * меняется: `start` по SSH просит службу, а если её нет или она не ответила
 * — запускает супервизор сам, как прежде.
 *
 * Связь — каталог `service/` агента, без портов: просьба
 * `requests/<SessionId>` (атомарной записью), служба берёт её
 * переименованием в `taken/` — взять или отозвать просьбу может только
 * кто-то один, и супервизор не запускается дважды; ответ —
 * `answers/<SessionId>`. Признак жизни — документ `state`
 * (`dpai.agent-service` v1): номер процесса, время его старта, версия
 * агента (`agentVersion`), время последнего признака.
 */
class AgentService(
    home: String,
    private val clock: Clock,
    private val version: String,
    private val beatEvery: Duration = BEAT_EVERY,
    private val pollEvery: Duration = 200.milliseconds,
) {
    private val vfs = NioVfs(File(home).toPath())

    /** Пауза между опросами — ожиданием защёлки, которую никто не отпускает (без `Thread.sleep`). */
    private val pace = CountDownLatch(1)

    private fun pause() {
        pace.await(pollEvery.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    init {
        vfs.mkdirs(REQUESTS)
        vfs.mkdirs(TAKEN)
        vfs.mkdirs(ANSWERS)
    }

    /** Признак жизни службы из `state`; нет или не читается — `null`. */
    fun state(): ServiceState? = try {
        vfs.read(STATE)?.let { ServiceState.parse(it.toString(Charsets.UTF_8)) }
    } catch (_: IOException) {
        null
    }

    /**
     * Состояние для ответа `service status`: служба идёт — свежий признак жизни и
     * тот самый процесс ([alive]); признак есть, но старый или процесса нет — «молчит».
     */
    fun status(alive: (ServiceState) -> Boolean): ServiceStatus {
        val s = state() ?: return ServiceStatus(ServiceStatus.ABSENT, null, null, null, null)
        val fresh = clock.now().epochMillis - s.beatAt <= (beatEvery * STALE_BEATS).inWholeMilliseconds
        val code = if (fresh && alive(s)) ServiceStatus.RUNNING else ServiceStatus.SILENT
        return ServiceStatus(code, s.pid, s.version, s.startedAt, s.beatAt)
    }

    /** Итог просьбы к службе. */
    sealed interface Asked {
        /** Служба взяла ход и ответила: супервизор запущен или отказ с причиной. */
        data class Answered(val result: Outcome<Unit>) : Asked

        /** Службы нет, она не идёт или не взяла просьбу в срок — просьба отозвана: запускать самому. */
        data object Nobody : Asked
    }

    /**
     * Попросить службу запустить супервизор хода [id]. Служба не идёт ([running]
     * — `false`) — сразу [Asked.Nobody]. Не взяла за [take] — просьба отзывается
     * (переименованием: если служба успела взять — ждём её ответа до [answer]).
     */
    fun ask(id: SessionId, running: Boolean, take: Duration = 3.seconds, answer: Duration = 10.seconds): Asked {
        if (!running) return Asked.Nobody
        val name = id.value
        try {
            val tmp = "$REQUESTS/.$name.tmp"
            vfs.write(tmp, CanonicalJson.write(JsonObject.of("run" to JsonString(name), "askedAt" to JsonNumber.of(clock.now().epochMillis))).toByteArray(Charsets.UTF_8))
            vfs.rename(tmp, "$REQUESTS/$name")
        } catch (_: IOException) {
            return Asked.Nobody
        }
        val start = clock.monotonicNanos()
        while (clock.monotonicNanos() - start < take.inWholeNanoseconds) {
            answerOf(name)?.let { return Asked.Answered(it) }
            if (!vfs.exists("$REQUESTS/$name")) break
            pause()
        }
        answerOf(name)?.let { return Asked.Answered(it) }
        // Отозвать: переименование удастся, только если служба ещё не взяла просьбу.
        val withdrawn = try {
            vfs.rename("$REQUESTS/$name", "$REQUESTS/.$name.withdrawn")
            vfs.delete("$REQUESTS/.$name.withdrawn")
            true
        } catch (_: IOException) {
            false
        }
        if (withdrawn) return Asked.Nobody
        val more = clock.monotonicNanos()
        while (clock.monotonicNanos() - more < answer.inWholeNanoseconds) {
            answerOf(name)?.let { return Asked.Answered(it) }
            pause()
        }
        return Asked.Answered(Outcome.failure(DiagCode.PROCESS_START_FAILED, "служба агента взяла ход, но не ответила за ${answer.inWholeSeconds} с", "run $name"))
    }

    /** Ответ службы на просьбу [name] — прочитать и убрать; ещё нет — `null`. */
    private fun answerOf(name: String): Outcome<Unit>? {
        val path = "$ANSWERS/$name"
        val bytes = try {
            vfs.read(path)
        } catch (_: IOException) {
            null
        } ?: return null
        runCatching { vfs.delete(path) }
        val obj = JsonParser().parse(bytes.toString(Charsets.UTF_8)).valueOrNull() as? JsonObject
        val ok = (obj?.get("ok") as? JsonBool)?.value
        return if (ok == true) {
            Outcome.success(Unit)
        } else {
            val why = (obj?.get("why") as? JsonString)?.value ?: "служба не запустила супервизор"
            Outcome.failure(DiagCode.PROCESS_START_FAILED, why, "run $name")
        }
    }

    /**
     * Цикл службы: признак жизни раз в [beatEvery], просьбы — по мере прихода;
     * каждую — взять, запустить супервизор [launch], ответить. [me] — кто служба;
     * [stop] — пора выходить (тесты; служба ОС останавливается сигналом).
     */
    fun serve(me: ProcessMark, launch: (SessionId) -> Outcome<Unit>, stop: () -> Boolean = { false }) {
        val startedAt = me.startedAt
        var nextBeat = 0L
        // Просьбы, взятые прежней службой и брошенные (служба умерла посреди), — ответом-отказом.
        for (name in runCatching { vfs.list(TAKEN) }.getOrDefault(emptyList())) {
            answer(name, Outcome.failure(DiagCode.PROCESS_START_FAILED, "служба агента перезапустилась, не запустив супервизор", "run $name"))
            runCatching { vfs.delete("$TAKEN/$name") }
        }
        while (!stop()) {
            val now = clock.monotonicNanos()
            if (now >= nextBeat) {
                beat(me.pid, startedAt)
                nextBeat = now + beatEvery.inWholeNanoseconds
            }
            for (name in runCatching { vfs.list(REQUESTS) }.getOrDefault(emptyList())) {
                if (name.startsWith(".")) continue
                val id = IdKinds.SESSION.parse(name).valueOrNull()
                val took = try {
                    vfs.rename("$REQUESTS/$name", "$TAKEN/$name")
                    true
                } catch (_: IOException) {
                    false
                }
                if (!took) continue
                val r = if (id == null) Outcome.failure(DiagCode.VALUE_INVALID, "просьба не о ходе", name) else runCatching { launch(id) }.getOrElse {
                    Outcome.failure(DiagCode.PROCESS_START_FAILED, "служба: ${it.javaClass.simpleName}", "run $name")
                }
                answer(name, r)
                runCatching { vfs.delete("$TAKEN/$name") }
            }
            pause()
        }
    }

    private fun answer(name: String, r: Outcome<Unit>) {
        val f = linkedMapOf<String, JsonValue>("ok" to JsonBool(r is Outcome.Success))
        if (r is Outcome.Failure) f["why"] = JsonString(r.errors.firstOrNull()?.message ?: "отказ")
        runCatching {
            val tmp = "$ANSWERS/.$name.tmp"
            vfs.write(tmp, CanonicalJson.write(JsonObject(f)).toByteArray(Charsets.UTF_8))
            vfs.rename(tmp, "$ANSWERS/$name")
        }
    }

    private fun beat(pid: Long, startedAt: Long?) {
        val s = ServiceState(pid, startedAt, version, clock.now().epochMillis)
        runCatching {
            vfs.write("$STATE.tmp", CanonicalJson.write(s.toJson()).toByteArray(Charsets.UTF_8))
            vfs.rename("$STATE.tmp", STATE)
        }
    }

    companion object {
        const val DIR = "service"
        const val STATE = "$DIR/state"
        const val REQUESTS = "$DIR/requests"
        const val TAKEN = "$DIR/taken"
        const val ANSWERS = "$DIR/answers"

        /** Признак жизни — раз в 5 с; служба молчит три признака — считается не идущей. */
        val BEAT_EVERY: Duration = 5.seconds
        const val STALE_BEATS = 3
    }
}

/** Признак жизни службы `dpai.agent-service` v1: кто она и когда отметилась. */
data class ServiceState(val pid: Long, val startedAt: Long?, val version: String, val beatAt: Long) {
    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, 1))
        f["pid"] = JsonNumber.of(pid)
        startedAt?.let { f["startedAt"] = JsonNumber.of(it) }
        // Не `version`: это поле — версия формата заголовка.
        f["agentVersion"] = JsonString(version)
        f["beatAt"] = JsonNumber.of(beatAt)
        return JsonObject(f)
    }

    companion object {
        const val FORMAT = "dpai.agent-service"

        fun parse(text: String): ServiceState? {
            val obj = JsonParser().parse(text).valueOrNull() as? JsonObject ?: return null
            val d = DiagnosticCollector()
            val f = JsonFields(obj, "$", d)
            FormatHeader.read(f, FORMAT, 1..1, d) ?: return null
            val pid = f.long("pid")
            val startedAt = f.optLong("startedAt")
            val version = f.string("agentVersion")
            val beatAt = f.long("beatAt")
            f.finish()
            if (d.hasErrors || pid == null || version == null || beatAt == null) return null
            return ServiceState(pid, startedAt, version, beatAt)
        }
    }
}

/** Отказ службы строкой диагностики — для ответа `service install|remove`. */
internal fun serviceError(code: DiagCode, message: String): Diagnostic = Diagnostic.error(code, message, "service")

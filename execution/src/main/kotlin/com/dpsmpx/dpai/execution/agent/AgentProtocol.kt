package com.dpsmpx.dpai.execution.agent

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.DiagnosticJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonPath
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Состояние запуска на target (`docs/EXECUTION_MODEL.md`, §5): `LOST` —
 * супервизор агента умер, не записав выхода; что с процессом — решает
 * сверка (D-13: не «завершился»).
 */
enum class RunState { STARTING, RUNNING, EXITED, LOST }

/** Почему запуск закончился. */
enum class ExitReason { EXITED, TIMEOUT, STOPPED, START_FAILED, LOST }

/**
 * Запуск на target: [id] — сессия, которой он принадлежит (каждый процесс
 * привязан к сессии, `docs/EXECUTION_MODEL.md`, §5); [turn] — номер хода
 * (каждый `start` завершённого запуска — новый ход в том же журнале);
 * [lastSequence] — номер последнего записанного события потока запуска.
 */
data class RunStatus(
    val id: SessionId,
    val state: RunState,
    val pid: Long? = null,
    val exitCode: Int? = null,
    val reason: ExitReason? = null,
    val lastSequence: Long = 0,
    val turn: Int = 1,
) {
    val finished: Boolean get() = state == RunState.EXITED || state == RunState.LOST

    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>("id" to JsonString(id.value), "state" to JsonString(state.name))
        pid?.let { f["pid"] = JsonNumber.of(it) }
        exitCode?.let { f["exitCode"] = JsonNumber.of(it) }
        reason?.let { f["reason"] = JsonString(it.name) }
        f["lastSequence"] = JsonNumber.of(lastSequence)
        f["turn"] = JsonNumber.of(turn)
        return JsonObject(f)
    }

    companion object {
        fun read(f: JsonFields): RunStatus? {
            val d = f.diagnostics
            val id = f.string("id")?.let { IdKinds.SESSION.parse(it).valueOrNull() ?: null.also { _ -> d.error(DiagCode.ID_INVALID, "не SessionId", JsonPath.field(f.path, "id")) } }
            val state = f.string("state")?.let { enumOf<RunState>(it, JsonPath.field(f.path, "state"), d) }
            val pid = f.optLong("pid")
            val exit = f.optLong("exitCode")?.toInt()
            val reason = f.optString("reason")?.let { enumOf<ExitReason>(it, JsonPath.field(f.path, "reason"), d) }
            val last = f.long("lastSequence")
            // Ход появился на этапе 4; ответ агента этапа 3 без него — первый ход.
            val turn = if (f.has("turn")) f.int("turn") else 1
            f.finish()
            if (id == null || state == null || last == null || turn == null) return null
            return RunStatus(id, state, pid, exit, reason, last, turn)
        }
    }
}

/** Итог остановки на target: сироты перечислены, а не скрыты (ТЗ, §119). */
data class StopResult(val forced: Boolean, val survivors: List<Long>) {
    fun toJson(): JsonObject =
        JsonObject.of("forced" to JsonBool(forced), "survivors" to JsonArray(survivors.map { JsonNumber.of(it) }))

    companion object {
        fun read(f: JsonFields): StopResult? {
            val forced = f.boolean("forced")
            val survivors = f.array("survivors")?.items?.map { (it as? JsonNumber)?.toLongExact() }
            f.finish()
            if (forced == null || survivors == null || survivors.any { it == null }) return null
            return StopResult(forced, survivors.filterNotNull())
        }
    }
}

/** Проба программы на target: есть ли она и какая версия (`claude --version`, `git --version`). */
data class ToolProbe(val found: Boolean, val version: String?, val detail: String?) {
    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>("found" to JsonBool(found))
        version?.let { f["version"] = JsonString(it) }
        detail?.let { f["detail"] = JsonString(it) }
        return JsonObject(f)
    }

    companion object {
        fun read(f: JsonFields): ToolProbe? {
            val found = f.boolean("found")
            val version = f.optString("version")
            val detail = f.optString("detail")
            f.finish()
            return found?.let { ToolProbe(it, version, detail) }
        }
    }
}

/**
 * Что агент знает о своей машине (ТЗ, §34): версия, ОС, Java, часы, диск,
 * память, Claude Code и Git. Неизвестное — отсутствующее поле, а не ноль
 * (D-13).
 */
data class DoctorReport(
    val agentVersion: String,
    val os: String,
    val arch: String,
    val javaVersion: String,
    val timeMillis: Long,
    val termux: Boolean,
    val diskFreeBytes: Long?,
    val memoryTotalBytes: Long?,
    val memoryFreeBytes: Long?,
    val claude: ToolProbe,
    val git: ToolProbe,
) {
    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>(
            "agentVersion" to JsonString(agentVersion),
            "os" to JsonString(os),
            "arch" to JsonString(arch),
            "javaVersion" to JsonString(javaVersion),
            "timeMillis" to JsonNumber.of(timeMillis),
            "termux" to JsonBool(termux),
        )
        diskFreeBytes?.let { f["diskFreeBytes"] = JsonNumber.of(it) }
        memoryTotalBytes?.let { f["memoryTotalBytes"] = JsonNumber.of(it) }
        memoryFreeBytes?.let { f["memoryFreeBytes"] = JsonNumber.of(it) }
        f["claude"] = claude.toJson()
        f["git"] = git.toJson()
        return JsonObject(f)
    }

    companion object {
        fun read(f: JsonFields): DoctorReport? {
            val r = DoctorReport(
                agentVersion = f.string("agentVersion") ?: "",
                os = f.string("os") ?: "",
                arch = f.string("arch") ?: "",
                javaVersion = f.string("javaVersion") ?: "",
                timeMillis = f.long("timeMillis") ?: 0,
                termux = f.boolean("termux") ?: false,
                diskFreeBytes = f.optLong("diskFreeBytes"),
                memoryTotalBytes = f.optLong("memoryTotalBytes"),
                memoryFreeBytes = f.optLong("memoryFreeBytes"),
                claude = f.obj("claude")?.let(ToolProbe::read) ?: ToolProbe(false, null, null),
                git = f.obj("git")?.let(ToolProbe::read) ?: ToolProbe(false, null, null),
            )
            f.finish()
            return r
        }
    }
}

/**
 * Ответ агента — одна строка JSON `dpai.agent-reply` на стандартном
 * выводе (`docs/EXECUTION_MODEL.md`, §1). Неудача команды — `ok = false` и
 * диагностика; разбор строгий: чужой формат, неизвестное поле —
 * `AGENT_REPLY_INVALID` с находками причинами (D-09).
 *
 * v2 добавляет [more]: страница событий обрезана по объёму — за ней есть
 * ещё (ответ — одна строка ограниченной длины). Ответ без [more] пишется
 * как v1: так его читает и контроллер прежней версии; читаются v1 и v2.
 */
data class AgentReply(
    val command: String,
    val ok: Boolean,
    val diagnostics: List<Diagnostic> = emptyList(),
    val run: RunStatus? = null,
    val events: List<EventEnvelope> = emptyList(),
    val stop: StopResult? = null,
    val doctor: DoctorReport? = null,
    /** Страница событий обрезана по объёму: за ней есть ещё (v2). */
    val more: Boolean = false,
) {
    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, if (more) VERSION else 1))
        f["command"] = JsonString(command)
        f["ok"] = JsonBool(ok)
        f["diagnostics"] = DiagnosticJson.writeAll(diagnostics)
        run?.let { f["run"] = it.toJson() }
        if (events.isNotEmpty()) f["events"] = JsonArray(events.map { it.toJson() })
        stop?.let { f["stop"] = it.toJson() }
        doctor?.let { f["doctor"] = it.toJson() }
        if (more) f["more"] = JsonBool(true)
        return JsonObject(f)
    }

    /** Ответ как `Outcome`: неудача агента — его диагностика. */
    fun toOutcome(): Outcome<AgentReply> =
        if (ok || diagnostics.none { it.isError }) {
            Outcome.success(this, diagnostics.filterNot { it.isError })
        } else {
            Outcome.Failure(diagnostics)
        }

    companion object {
        const val FORMAT = "dpai.agent-reply"
        const val VERSION = 2

        fun failure(command: String, diagnostics: List<Diagnostic>): AgentReply = AgentReply(command, ok = false, diagnostics = diagnostics)

        /** Последняя непустая строка вывода — ответ; строки до неё — не протокол и не читаются. */
        fun parseOutput(stdout: List<String>, subject: String): Outcome<AgentReply> {
            val line = stdout.lastOrNull { it.isNotBlank() }
                ?: return Outcome.failure(DiagCode.AGENT_REPLY_INVALID, "агент ничего не ответил", subject)
            return parse(line).let { r ->
                if (r is Outcome.Failure) Outcome.Failure(listOf(Diagnostic.of(DiagCode.AGENT_REPLY_INVALID, "ответ агента не прочитан", subject, causes = r.errors))) else r
            }
        }

        fun parse(text: String): Outcome<AgentReply> {
            val value = when (val p = JsonParser().parse(text)) {
                is Outcome.Failure -> return p
                is Outcome.Success -> p.value
            }
            val d = DiagnosticCollector()
            val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
            FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
            val command = f.string("command")
            val ok = f.boolean("ok")
            val diagnostics = f.array("diagnostics")?.let { DiagnosticJson.readAll(it, "$.diagnostics", d) }.orEmpty()
            val run = f.optObj("run")?.let(RunStatus::read)
            val events = (f.raw("events", required = false) as? JsonArray)?.items.orEmpty().mapIndexedNotNull { i, e ->
                when (val r = EventEnvelope.fromJson(e)) {
                    is Outcome.Success -> r.value
                    is Outcome.Failure -> null.also { r.errors.forEach { x -> d.error(x.code, x.message, "$.events[$i]" + (x.subject?.removePrefix("$") ?: "")) } }
                }
            }
            val stop = f.optObj("stop")?.let(StopResult::read)
            val doctor = f.optObj("doctor")?.let(DoctorReport::read)
            val more = (f.raw("more", required = false) as? JsonBool)?.value ?: false
            f.finish()
            if (d.hasErrors || command == null || ok == null) return Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.AGENT_REPLY_INVALID, "ответ неполон")) })
            return Outcome.success(AgentReply(command, ok, diagnostics, run, events, stop, doctor, more))
        }
    }
}

private inline fun <reified E : Enum<E>> enumOf(name: String, path: String, d: DiagnosticCollector): E? =
    enumValues<E>().firstOrNull { it.name == name } ?: null.also { d.error(DiagCode.VALUE_INVALID, "неизвестное значение", path) }

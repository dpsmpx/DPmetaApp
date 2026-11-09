package com.dpsmpx.dpmeta.actions

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.OperationId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.Vfs

/** Чем кончилось действие в журнале. */
enum class ActionStatus(val word: String) {
    DONE("выполнено"),
    FAILED("не выполнено"),
    REFUSED("отказ до исполнения"),

    /** Запрошено, итога нет: процесс умер во время исполнения — проверить результат вручную. */
    UNKNOWN("итог неизвестен"),
}

/** Строка журнала действий для экрана (F-44): что, где, когда, как подтверждено и чем кончилось. */
data class ActionEntry(
    val id: String,
    val at: Timestamp,
    /** Код вида ([ActionKind.code]); вид, которого эта версия не знает, остаётся строкой. */
    val kind: String,
    val level: Int,
    val target: String,
    val params: Map<String, String>,
    val profile: String,
    /** Как подтверждено: `shown`, `device`, `typed-name` или пусто. */
    val way: String,
    val status: ActionStatus,
    val detail: String,
    val link: String?,
    val settledAt: Timestamp?,
) {
    /** Поиск по журналу (F-44): без учёта регистра по виду, цели, параметрам, итогу. */
    fun matches(text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return true
        return listOf(kind, target, profile, detail, link.orEmpty(), status.word).any { q in it.lowercase() } ||
            params.any { (k, v) -> q in k.lowercase() || q in v.lowercase() }
    }
}

/**
 * Журнал действий DPmetaApp (ТЗ, §6.3, F-44; D-07): события `ACTION_REQUESTED`,
 * `ACTION_SETTLED`, `ACTION_REFUSED` в журнале `dpai/storage` — только добавление,
 * fsync на каждую запись, цепочка хешей (порча или выпавшая запись — отказ чтения,
 * а не тихо другой журнал). Параметры, итог и причина отказа проходят `Redactor` ядра.
 */
class ActionLog(private val vfs: Vfs, private val clock: Clock, random: RandomSource, private val path: String = PATH) {
    private val ids = IdGenerator(clock, random)
    private var journal: EventJournal? = null

    @Synchronized
    fun requested(id: OperationId, command: ActionCommand, way: String): Outcome<Long> =
        append(EventType.ACTION_REQUESTED, base(id, command) + ("way" to JsonString(way)))

    @Synchronized
    fun settled(id: OperationId, command: ActionCommand, result: Outcome<ActionResult>): Outcome<Long> {
        val fields = base(id, command) + when (result) {
            is Outcome.Success -> listOfNotNull(
                "ok" to JsonBool(true),
                "detail" to JsonString(text(result.value.detail)),
                result.value.link?.let { "link" to JsonString(text(it)) },
            )
            is Outcome.Failure -> listOf(
                "ok" to JsonBool(false),
                "code" to JsonString(result.errors.first().code.name),
                "detail" to JsonString(text(result.errors.first().message)),
            )
        }
        return append(EventType.ACTION_SETTLED, fields)
    }

    @Synchronized
    fun refused(id: OperationId, command: ActionCommand, way: String?, why: Diagnostic): Outcome<Long> =
        append(
            EventType.ACTION_REFUSED,
            base(id, command) + listOfNotNull(
                way?.let { "way" to JsonString(it) },
                "code" to JsonString(why.code.name),
                "detail" to JsonString(text(why.message)),
            ),
        )

    /** Все действия по порядку запроса; порча журнала — отказ `JOURNAL_CORRUPTED`. */
    fun entries(): Outcome<List<ActionEntry>> {
        if (!vfs.exists(path)) return Outcome.success(emptyList())
        val records = when (val r = EventJournal.readCommitted(vfs, path)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val order = ArrayList<String>()
        val byId = HashMap<String, ActionEntry>()
        for (rec in records) {
            val e = rec.event
            val p = e.payload
            if ((p["format"] as? JsonString)?.value != FORMAT) continue
            val id = (p["action"] as? JsonString)?.value ?: continue
            when (e.type) {
                EventType.ACTION_REQUESTED, EventType.ACTION_REFUSED -> {
                    val refused = e.type == EventType.ACTION_REFUSED
                    if (id !in byId) order += id
                    byId[id] = entry(id, e.occurredAt, p, if (refused) ActionStatus.REFUSED else ActionStatus.UNKNOWN, if (refused) string(p, "detail") else "")
                }
                EventType.ACTION_SETTLED -> {
                    val prev = byId[id] ?: entry(id, e.occurredAt, p, ActionStatus.UNKNOWN, "").also { order += id }
                    val ok = (p["ok"] as? JsonBool)?.value == true
                    byId[id] = prev.copy(
                        status = if (ok) ActionStatus.DONE else ActionStatus.FAILED,
                        detail = string(p, "detail"),
                        link = (p["link"] as? JsonString)?.value,
                        settledAt = e.occurredAt,
                    )
                }
                else -> Unit
            }
        }
        return Outcome.success(order.map { byId.getValue(it) })
    }

    private fun entry(id: String, at: Timestamp, p: JsonObject, status: ActionStatus, detail: String): ActionEntry {
        val repo = string(p, "repo")
        val branch = (p["branch"] as? JsonString)?.value
        return ActionEntry(
            id = id,
            at = at,
            kind = string(p, "kind"),
            level = (p["level"] as? JsonNumber)?.toLongExact()?.toInt() ?: -1,
            target = repo + (branch?.let { "@$it" } ?: ""),
            params = (p["params"] as? JsonObject)?.fields?.entries?.mapNotNull { (k, v) -> (v as? JsonString)?.let { k to it.value } }?.toMap().orEmpty(),
            profile = (p["profile"] as? JsonString)?.value.orEmpty(),
            way = (p["way"] as? JsonString)?.value.orEmpty(),
            status = status,
            detail = detail,
            link = null,
            settledAt = null,
        )
    }

    private fun base(id: OperationId, c: ActionCommand): List<Pair<String, JsonValue>> = listOfNotNull(
        "format" to JsonString(FORMAT),
        "version" to JsonNumber.of(VERSION),
        "action" to JsonString(id.value),
        "kind" to JsonString(c.kind.code),
        "level" to JsonNumber.of(c.level.code),
        "repo" to JsonString("${c.target.repo}"),
        c.target.branch?.let { "branch" to JsonString(it) },
        // Параметры — через Redactor: секрет из текста команды в журнал не попадает (D-14).
        "params" to JsonObject(c.params.toSortedMap().mapValues { JsonString(text(it.value, MAX_PARAM_TEXT)) }),
        c.profile.takeIf { it.isNotEmpty() }?.let { "profile" to JsonString(text(it)) },
    )

    private fun append(type: EventType, fields: List<Pair<String, JsonValue>>): Outcome<Long> {
        val j = journal ?: when (val o = EventJournal.open(vfs, path)) {
            is Outcome.Failure -> return o
            is Outcome.Success -> o.value.also { journal = it }
        }
        val now = clock.now()
        val next = (j.records().lastOrNull()?.event?.sequence ?: 0L) + 1
        val event = EventEnvelope(
            id = ids.next(IdKinds.EVENT),
            type = type,
            stream = StreamRef.SYSTEM,
            sequence = next,
            occurredAt = now,
            recordedAt = now,
            payload = JsonObject(fields.toMap()),
        )
        val r = j.append(event)
        // Сбой записи — журнал открыть заново в следующий раз: состояние в памяти могло разойтись с диском.
        if (r is Outcome.Failure) journal = null
        return r
    }

    private fun string(p: JsonObject, key: String): String = (p[key] as? JsonString)?.value.orEmpty()

    private fun text(s: String, max: Int = MAX_TEXT): String = Redactor.STANDARD.redact(s).take(max)

    companion object {
        const val FORMAT: String = "dpmeta.action"
        const val VERSION: Int = 1
        const val PATH: String = "dpmeta/actions.jsonl"
        const val MAX_TEXT: Int = 1000

        /** Длинный параметр (текст комментария) в журнале — началом: журнал не копия текста. */
        const val MAX_PARAM_TEXT: Int = 500
    }
}

package com.dpsmpx.dpai.core.events

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.CorrelationId
import com.dpsmpx.dpai.core.ids.EventId
import com.dpsmpx.dpai.core.ids.IdKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TypedId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Вид потока событий — сущность, к которой событие относится. */
enum class StreamKind(internal val idKind: IdKind<*>?) {
    TASK(IdKinds.TASK),
    SESSION(IdKinds.SESSION),
    ACCOUNT(IdKinds.ACCOUNT),
    TARGET(IdKinds.TARGET),
    SUBSCRIPTION(IdKinds.SUBSCRIPTION),
    SYSTEM(null),

    // Виды потоков только дописываются (как типы событий, D-12).
    REPOSITORY(IdKinds.REPOSITORY),
    WORKTREE(IdKinds.WORKTREE),
    REQUEST(IdKinds.REQUEST),
}

/**
 * Поток событий: вид и идентификатор сущности. У системного потока
 * идентификатор — `system`. Создаётся только с идентификатором своего вида.
 */
class StreamRef private constructor(val kind: StreamKind, val id: String) {
    override fun equals(other: Any?): Boolean = other is StreamRef && kind == other.kind && id == other.id

    override fun hashCode(): Int = 31 * kind.hashCode() + id.hashCode()

    override fun toString(): String = "${kind.name.lowercase()}:$id"

    companion object {
        val SYSTEM: StreamRef = StreamRef(StreamKind.SYSTEM, "system")

        fun of(id: TypedId): StreamRef {
            val kind = StreamKind.entries.firstOrNull { k -> k.idKind?.let { id.value.startsWith(it.prefix + "_") } == true }
            requireNotNull(kind) { "у идентификатора ${id.value} нет своего потока событий" }
            return StreamRef(kind, id.value)
        }

        fun parse(kind: String, id: String): Outcome<StreamRef> {
            val k = StreamKind.entries.firstOrNull { it.name.lowercase() == kind }
                ?: return Outcome.failure(DiagCode.VALUE_INVALID, "неизвестный вид потока", "$.stream.kind")
            if (k == StreamKind.SYSTEM) {
                return if (id == "system") {
                    Outcome.success(SYSTEM)
                } else {
                    Outcome.failure(DiagCode.VALUE_INVALID, "у системного потока id — system", "$.stream.id")
                }
            }
            return when (val parsed = k.idKind!!.parse(id)) {
                is Outcome.Success -> Outcome.success(StreamRef(k, id))
                is Outcome.Failure -> Outcome.Failure(parsed.diagnostics)
            }
        }
    }
}

/**
 * Событие (D-12; `docs/DATA_MODEL.md`, §4, формат `dpai.event` v1): неизменяемое,
 * с устойчивым идентификатором, номером в потоке без пропусков, временем,
 * корреляцией и причиной. Повтор события с тем же [id] идемпотентен.
 */
data class EventEnvelope(
    val id: EventId,
    val type: EventType,
    val stream: StreamRef,
    val sequence: Long,
    val occurredAt: Timestamp,
    val recordedAt: Timestamp,
    val correlationId: CorrelationId? = null,
    val causationId: EventId? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(sequence >= 1) { "номер в потоке начинается с 1" }
    }

    fun toJson(): JsonObject {
        val fields = linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, VERSION))
        fields["id"] = JsonString(id.value)
        fields["type"] = JsonString(type.name)
        fields["stream"] = JsonObject.of("kind" to JsonString(stream.kind.name.lowercase()), "id" to JsonString(stream.id))
        fields["sequence"] = JsonNumber.of(sequence)
        fields["occurredAt"] = JsonNumber.of(occurredAt.epochMillis)
        fields["recordedAt"] = JsonNumber.of(recordedAt.epochMillis)
        correlationId?.let { fields["correlationId"] = JsonString(it.value) }
        causationId?.let { fields["causationId"] = JsonString(it.value) }
        fields["payload"] = payload
        return JsonObject(fields)
    }

    companion object {
        const val FORMAT = "dpai.event"
        const val VERSION = 1

        /** Строгое чтение `dpai.event` v1: все находки за один проход, с путями. */
        fun fromJson(value: JsonValue): Outcome<EventEnvelope> {
            val diags = DiagnosticCollector()
            val f = JsonFields.root(value, diags) ?: return Outcome.Failure(diags.all)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, diags) ?: return Outcome.Failure(diags.all)
            val id = f.string("id")?.let { idOf(IdKinds.EVENT, it, "$.id", diags) }
            val type = f.string("type")?.let { name ->
                EventType.entries.firstOrNull { it.name == name }
                    ?: null.also { diags.error(DiagCode.EVENT_TYPE_UNKNOWN, "неизвестный вид события", "$.type") }
            }
            val streamFields = f.obj("stream")
            val stream = streamFields?.let { s ->
                val kind = s.string("kind")
                val sid = s.string("id")
                s.finish()
                if (kind != null && sid != null) {
                    when (val r = StreamRef.parse(kind, sid)) {
                        is Outcome.Success -> r.value
                        is Outcome.Failure -> null.also { diags.addAll(r.diagnostics) }
                    }
                } else {
                    null
                }
            }
            val sequence = f.long("sequence")?.let {
                if (it >= 1) it else null.also { diags.error(DiagCode.VALUE_INVALID, "номер в потоке начинается с 1", "$.sequence") }
            }
            val occurredAt = f.long("occurredAt")?.let(::Timestamp)
            val recordedAt = f.long("recordedAt")?.let(::Timestamp)
            val correlation = f.optString("correlationId")?.let { idOf(IdKinds.CORRELATION, it, "$.correlationId", diags) }
            val causation = f.optString("causationId")?.let { idOf(IdKinds.EVENT, it, "$.causationId", diags) }
            val payload = f.raw("payload")?.let {
                it as? JsonObject ?: null.also { diags.error(DiagCode.FIELD_TYPE, "ожидалось: объект", "$.payload") }
            }
            f.finish()
            if (diags.hasErrors) return Outcome.Failure(diags.all)
            return Outcome.success(
                EventEnvelope(
                    id!!, type!!, stream!!, sequence!!, occurredAt!!, recordedAt!!, correlation, causation, payload!!,
                ),
                diags.all,
            )
        }

        private fun <T : TypedId> idOf(kind: IdKind<T>, text: String, path: String, diags: DiagnosticCollector): T? =
            when (val r = kind.parse(text)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> null.also {
                    r.diagnostics.forEach { d -> diags.error(d.code, d.message, path) }
                }
            }
    }
}

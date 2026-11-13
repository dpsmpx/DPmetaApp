package com.dpsmpx.dpai.orchestrator.commands

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Настройки контура, которые задаёт человек (D-42, п. 5, 6): выключенные
 * targets — на них не начинаются новые сессии; [maxSessions] — предел
 * одновременных сессий всего контура поверх предела каждого target
 * (`TargetSlot.maxSessions`); `null` — без общего предела.
 * [autoExecutorSwitch] — у предела всех слотов исполнителя задача сама уходит
 * к следующему по кругу (D-100); по умолчанию нет — ждёт решения человека (D-165).
 */
data class ControlState(val disabledTargets: Set<ExecutionTargetId> = emptySet(), val maxSessions: Int? = null, val autoExecutorSwitch: Boolean = false) {
    companion object {
        val EMPTY = ControlState()
    }
}

/** Допустимый общий предел сессий: больше 64 одновременных ходов контур не ведёт (как предел подписки). */
val SESSION_LIMIT_RANGE = 1..64

/**
 * Настройки контура в журнале: `CONTROL_TARGET_ENABLED_SET` в потоке
 * target, `CONTROL_SESSION_LIMIT_SET` и `CONTROL_EXECUTOR_SWITCH_SET` в
 * системном; снимок `dpai.control-state` v2 (v1 — без перехода у предела,
 * читается как «выключено») — после перезапуска выключенное остаётся
 * выключенным.
 */
object ControlProjection : Projection<ControlState> {
    const val FORMAT = "dpai.control-state"
    const val VERSION = 2

    override val name: String = "control"

    override fun initial(): ControlState = ControlState.EMPTY

    override fun apply(state: ControlState, event: EventEnvelope): ControlState {
        if (event.type != EventType.CONTROL_TARGET_ENABLED_SET && event.type != EventType.CONTROL_SESSION_LIMIT_SET && event.type != EventType.CONTROL_EXECUTOR_SWITCH_SET) return state
        val d = DiagnosticCollector()
        val f = JsonFields(event.payload, "$.payload", d)
        val next = when (event.type) {
            EventType.CONTROL_TARGET_ENABLED_SET -> {
                val target = f.string("target")?.let { IdKinds.TARGET.parse(it).valueOrNull() }
                val enabled = f.boolean("enabled")
                if (target == null || enabled == null) null else setTarget(state, target, enabled)
            }
            EventType.CONTROL_EXECUTOR_SWITCH_SET -> f.boolean("auto")?.let { state.copy(autoExecutorSwitch = it) }
            else -> {
                val limit = f.optLong("maxSessions")?.toInt()
                if (limit != null && limit !in SESSION_LIMIT_RANGE) null else state.copy(maxSessions = limit)
            }
        }
        f.finish()
        return checkNotNull(next.takeIf { !d.hasErrors }) { "событие настроек контура №${event.sequence} не читается: ${d.all}" }
    }

    fun setTarget(state: ControlState, target: ExecutionTargetId, enabled: Boolean): ControlState =
        state.copy(disabledTargets = if (enabled) state.disabledTargets - target else state.disabledTargets + target)

    override fun encode(state: ControlState): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "disabledTargets" to JsonArray(state.disabledTargets.map { it.value }.sorted().map(::JsonString)),
            "maxSessions" to (state.maxSessions?.let { JsonNumber.of(it) } ?: JsonNull),
            "autoExecutorSwitch" to JsonBool(state.autoExecutorSwitch),
        ),
    )

    override fun decode(json: JsonObject): Outcome<ControlState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        val version = FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
        val targets = f.array("disabledTargets")?.items.orEmpty().mapIndexed { i, v ->
            (v as? JsonString)?.let { IdKinds.TARGET.parse(it.value).valueOrNull() }
                ?: null.also { d.error(DiagCode.FIELD_TYPE, "ожидался id target", "$.disabledTargets[$i]") }
        }
        val limit = f.optLong("maxSessions")?.toInt()
        if (limit != null && limit !in SESSION_LIMIT_RANGE) d.error(DiagCode.VALUE_INVALID, "предел сессий — от 1 до 64", "$.maxSessions")
        val auto = if (version >= 2) f.boolean("autoExecutorSwitch") ?: false else false
        f.finish()
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(ControlState(targets.filterNotNull().toSet(), limit, auto))
    }
}

fun EventRecorder.targetEnabledEnvelope(target: ExecutionTargetId, enabled: Boolean, at: Timestamp): EventEnvelope =
    envelope(EventType.CONTROL_TARGET_ENABLED_SET, StreamRef.of(target), at, JsonObject(linkedMapOf("target" to JsonString(target.value), "enabled" to JsonBool(enabled))))

fun EventRecorder.executorSwitchEnvelope(auto: Boolean, at: Timestamp): EventEnvelope =
    envelope(EventType.CONTROL_EXECUTOR_SWITCH_SET, StreamRef.SYSTEM, at, JsonObject(linkedMapOf("auto" to JsonBool(auto))))

fun EventRecorder.sessionLimitEnvelope(limit: Int?, at: Timestamp): EventEnvelope =
    envelope(EventType.CONTROL_SESSION_LIMIT_SET, StreamRef.SYSTEM, at, JsonObject(linkedMapOf("maxSessions" to (limit?.let { JsonNumber.of(it) } ?: JsonNull))))

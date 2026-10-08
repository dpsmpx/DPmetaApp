package com.dpsmpx.dpai.sessions

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.TypedId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.state.SessionState

/**
 * События сессий в конвертах `dpai.event` (поток — сессия) и состояние
 * сессий в снимке `dpai.sessions-state` v3 (`docs/DATA_MODEL.md`, §4).
 * Чтение строгое: неизвестное поле, чужой идентификатор — ошибка (D-09).
 */
object SessionCodec {
    const val STATE_FORMAT = "dpai.sessions-state"
    /**
     * v3: инструменты ходов `usage.tools` (D-41); v2 — расход сессии `usage`
     * (D-40); v1 и v2 читаются — чего в них нет, того нет и в состоянии.
     */
    const val STATE_VERSION = 3

    fun type(e: SessionEvent): EventType = when (e) {
        is SessionEvent.Created -> EventType.SESSION_CREATED
        is SessionEvent.StateChanged -> EventType.SESSION_STATE_CHANGED
        is SessionEvent.Heartbeat -> EventType.SESSION_HEARTBEAT
        is SessionEvent.TurnStarted -> EventType.SESSION_TURN_STARTED
        is SessionEvent.TurnFinished -> EventType.SESSION_TURN_FINISHED
        is SessionEvent.Output -> EventType.SESSION_OUTPUT
        is SessionEvent.ToolStarted -> EventType.SESSION_TOOL_STARTED
        is SessionEvent.PromptPrepared -> EventType.SESSION_PROMPT_PREPARED
        is SessionEvent.TurnUsage -> EventType.SESSION_TURN_USAGE
        is SessionEvent.TurnTools -> EventType.SESSION_TURN_TOOLS
    }

    fun stream(e: SessionEvent): StreamRef = StreamRef.of(e.session)

    fun payload(e: SessionEvent): JsonObject = when (e) {
        is SessionEvent.Created -> obj(
            "session" to id(e.session), "key" to JsonString(e.key), "task" to id(e.task), "slot" to id(e.slot),
            "subscription" to id(e.subscription), "target" to id(e.target), "configDir" to JsonString(e.configDir),
            "provider" to JsonString(e.provider.value), "continues" to (e.continues?.let(::id) ?: JsonNull),
        )
        is SessionEvent.StateChanged -> obj(
            "session" to id(e.session), "from" to JsonString(e.from.name), "to" to JsonString(e.to.name),
            "reason" to (e.reason?.let { JsonString(it.name) } ?: JsonNull),
        )
        is SessionEvent.Heartbeat -> obj("session" to id(e.session), "agentSequence" to (e.agentSequence?.let { JsonNumber.of(it) } ?: JsonNull))
        is SessionEvent.TurnStarted -> obj("session" to id(e.session), "turn" to JsonNumber.of(e.turn))
        is SessionEvent.TurnFinished -> obj(
            "session" to id(e.session), "turn" to JsonNumber.of(e.turn), "status" to JsonString(e.status),
            "codes" to JsonArray(e.codes.map { JsonString(it.name) }),
        )
        is SessionEvent.Output -> obj("session" to id(e.session), "text" to JsonString(e.text))
        is SessionEvent.ToolStarted -> obj("session" to id(e.session), "tool" to JsonString(e.tool))
        is SessionEvent.PromptPrepared -> JsonObject(linkedMapOf<String, JsonValue>("session" to id(e.session)).apply { putAll(UsageJson.prompt(e.facts).fields) })
        is SessionEvent.TurnUsage -> obj("session" to id(e.session), "turn" to JsonNumber.of(e.turn), "usage" to UsageJson.turn(e.usage))
        is SessionEvent.TurnTools -> obj("session" to id(e.session), "turn" to JsonNumber.of(e.turn), "tools" to UsageJson.tools(e.tools))
    }

    /** Событие сессии из типа, времени и полезной нагрузки; чужой тип — `null`. */
    fun decode(type: EventType, at: Timestamp, payload: JsonObject): Outcome<SessionEvent>? {
        val d = DiagnosticCollector()
        val f = JsonFields(payload, "$.payload", d)
        val session = { f.typed("session", IdKinds.SESSION) }
        val event: SessionEvent? = when (type) {
            EventType.SESSION_CREATED -> {
                val s = session()
                val key = f.string("key")
                val task = f.typed("task", IdKinds.TASK)
                val slot = f.typed("slot", IdKinds.ACCOUNT)
                val sub = f.typed("subscription", IdKinds.SUBSCRIPTION)
                val target = f.typed("target", IdKinds.TARGET)
                val dir = f.string("configDir")
                val provider = f.string("provider")?.let { p ->
                    ProviderSessionId.parse(p).valueOrNull() ?: null.also { d.error(DiagCode.ID_INVALID, "не id сессии провайдера", "$.payload.provider") }
                }
                val continues = f.optString("continues")?.let { c -> IdKinds.SESSION.parse(c).valueOrNull() ?: null.also { d.error(DiagCode.ID_INVALID, "не SessionId", "$.payload.continues") } }
                if (s != null && key != null && task != null && slot != null && sub != null && target != null && dir != null && provider != null) {
                    SessionEvent.Created(s, key, task, slot, sub, target, dir, provider, continues, at)
                } else {
                    null
                }
            }
            EventType.SESSION_STATE_CHANGED -> {
                val s = session()
                val from = f.enumOf<SessionState>("from")
                val to = f.enumOf<SessionState>("to")
                val reason = f.optString("reason")?.let { r -> DiagCode.entries.firstOrNull { it.name == r } ?: null.also { d.error(DiagCode.VALUE_INVALID, "неизвестный код", "$.payload.reason") } }
                if (s != null && from != null && to != null) SessionEvent.StateChanged(s, from, to, reason, at) else null
            }
            EventType.SESSION_HEARTBEAT -> session()?.let { SessionEvent.Heartbeat(it, at, f.optLong("agentSequence")) }
            EventType.SESSION_TURN_STARTED -> {
                val s = session()
                val turn = f.int("turn")
                if (s != null && turn != null) SessionEvent.TurnStarted(s, turn, at) else null
            }
            EventType.SESSION_TURN_FINISHED -> {
                val s = session()
                val turn = f.int("turn")
                val status = f.string("status")
                val codes = f.array("codes")?.items?.map { c -> DiagCode.entries.firstOrNull { it.name == (c as? JsonString)?.value } }
                if (codes != null && codes.any { it == null }) d.error(DiagCode.VALUE_INVALID, "неизвестный код", "$.payload.codes")
                if (s != null && turn != null && status != null && codes != null && codes.none { it == null }) {
                    SessionEvent.TurnFinished(s, turn, status, codes.filterNotNull(), at)
                } else {
                    null
                }
            }
            EventType.SESSION_OUTPUT -> {
                val s = session()
                val text = f.string("text")
                if (s != null && text != null) SessionEvent.Output(s, text, at) else null
            }
            EventType.SESSION_TOOL_STARTED -> {
                val s = session()
                val tool = f.string("tool")
                if (s != null && tool != null) SessionEvent.ToolStarted(s, tool, at) else null
            }
            EventType.SESSION_PROMPT_PREPARED -> {
                val s = session()
                val facts = UsageJson.readPrompt(f)
                if (s != null && facts != null) SessionEvent.PromptPrepared(s, facts, at) else null
            }
            EventType.SESSION_TURN_USAGE -> {
                val s = session()
                val turn = f.int("turn")
                val usage = f.obj("usage")?.let(UsageJson::readTurn)
                if (s != null && turn != null && usage != null) SessionEvent.TurnUsage(s, turn, usage, at) else null
            }
            EventType.SESSION_TURN_TOOLS -> {
                val s = session()
                val turn = f.int("turn")
                val tools = f.obj("tools")?.let(UsageJson::readTools)
                if (s != null && turn != null && tools != null) SessionEvent.TurnTools(s, turn, tools, at) else null
            }
            else -> return null
        }
        f.finish()
        return if (d.hasErrors || event == null) {
            Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "событие сессии неполно", "$.payload")) })
        } else {
            Outcome.success(event)
        }
    }

    // ---- снимок ----

    fun encodeState(s: SessionsState): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(STATE_FORMAT, STATE_VERSION),
            "sessions" to JsonArray(s.sessions.values.sortedBy { it.id.value }.map(::encodeRecord)),
        ),
    )

    private fun encodeRecord(r: SessionRecord): JsonObject = obj(
        "id" to id(r.id), "key" to JsonString(r.key), "task" to id(r.task), "slot" to id(r.slot), "subscription" to id(r.subscription),
        "target" to id(r.target), "configDir" to JsonString(r.configDir), "provider" to JsonString(r.provider.value),
        "continues" to (r.continues?.let(::id) ?: JsonNull), "state" to JsonString(r.state.name),
        "createdAt" to JsonNumber.of(r.createdAt.epochMillis), "updatedAt" to JsonNumber.of(r.updatedAt.epochMillis),
        "lastHeartbeat" to (r.lastHeartbeat?.let { JsonNumber.of(it.epochMillis) } ?: JsonNull), "turn" to JsonNumber.of(r.turn),
        "lastReason" to (r.lastReason?.let { JsonString(it.name) } ?: JsonNull),
        "agentSequence" to JsonNumber.of(r.agentSequence),
        "usage" to (r.usage.takeIf { it != UsageTotals.EMPTY }?.let(UsageJson::totals) ?: JsonNull),
    )

    fun decodeState(json: JsonObject): Outcome<SessionsState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, STATE_FORMAT, 1..STATE_VERSION, d) ?: return Outcome.Failure(d.all)
        val records = f.array("sessions")?.items.orEmpty().mapIndexedNotNull { i, item ->
            val o = item as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.sessions[$i]") }
            val r = JsonFields(o, "$.sessions[$i]", d)
            val rec = runCatching {
                SessionRecord(
                    id = r.typed("id", IdKinds.SESSION)!!,
                    key = r.string("key")!!,
                    task = r.typed("task", IdKinds.TASK)!!,
                    slot = r.typed("slot", IdKinds.ACCOUNT)!!,
                    subscription = r.typed("subscription", IdKinds.SUBSCRIPTION)!!,
                    target = r.typed("target", IdKinds.TARGET)!!,
                    configDir = r.string("configDir")!!,
                    provider = ProviderSessionId.parse(r.string("provider")!!).valueOrNull()!!,
                    continues = r.optString("continues")?.let { IdKinds.SESSION.parse(it).valueOrNull()!! },
                    state = r.enumOf<SessionState>("state")!!,
                    createdAt = Timestamp(r.long("createdAt")!!),
                    updatedAt = Timestamp(r.long("updatedAt")!!),
                    lastHeartbeat = r.optLong("lastHeartbeat")?.let(::Timestamp),
                    turn = r.int("turn")!!,
                    lastReason = r.optString("lastReason")?.let { n -> DiagCode.entries.first { it.name == n } },
                    agentSequence = r.long("agentSequence")!!,
                    usage = r.optObj("usage")?.let { u -> UsageJson.readTotals(u) ?: error("расход не читается") } ?: UsageTotals.EMPTY,
                )
            }.getOrNull()
            r.finish()
            rec ?: null.also { d.error(DiagCode.VALUE_INVALID, "запись сессии не читается", "$.sessions[$i]") }
        }
        f.finish()
        if (d.hasErrors) return Outcome.Failure(d.all)
        return Outcome.success(SessionsState(records.associateBy { it.id }))
    }

    private fun obj(vararg pairs: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*pairs).filterValues { it != JsonNull })

    private fun id(v: TypedId): JsonString = JsonString(v.value)

    private fun <T : TypedId> JsonFields.typed(name: String, kind: IdKind<T>): T? = string(name)?.let { v ->
        kind.parse(v).valueOrNull() ?: null.also { diagnostics.error(DiagCode.ID_INVALID, "ожидался ${kind.typeName}", "$path.$name") }
    }

    private inline fun <reified E : Enum<E>> JsonFields.enumOf(name: String): E? = string(name)?.let { v ->
        enumValues<E>().firstOrNull { it.name == v } ?: null.also { diagnostics.error(DiagCode.VALUE_INVALID, "неизвестное значение", "$path.$name") }
    }
}

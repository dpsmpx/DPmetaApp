package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.accounts.AccountEvent.ErrorRecorded
import com.dpsmpx.dpai.accounts.AccountEvent.ErrorsCleared
import com.dpsmpx.dpai.accounts.AccountEvent.IdentityConfirmed
import com.dpsmpx.dpai.accounts.AccountEvent.LimitObserved
import com.dpsmpx.dpai.accounts.AccountEvent.LimitReset
import com.dpsmpx.dpai.accounts.AccountEvent.LoginExpiring
import com.dpsmpx.dpai.accounts.AccountEvent.LoginObserved
import com.dpsmpx.dpai.accounts.AccountEvent.OverlapDetected
import com.dpsmpx.dpai.accounts.AccountEvent.SessionLeased
import com.dpsmpx.dpai.accounts.AccountEvent.SessionReleased
import com.dpsmpx.dpai.accounts.AccountEvent.SlotDisabled
import com.dpsmpx.dpai.accounts.AccountEvent.SlotEnabled
import com.dpsmpx.dpai.accounts.AccountEvent.SlotRegistered
import com.dpsmpx.dpai.accounts.AccountEvent.SlotStateChanged
import com.dpsmpx.dpai.accounts.AccountEvent.SubscriptionRegistered
import com.dpsmpx.dpai.accounts.AccountEvent.SubscriptionSwitched
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.IdKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TypedId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.state.AccountState

/**
 * События и состояние аккаунтов в JSON (D-09, D-12): событие — тип,
 * поток и полезная нагрузка для `dpai.event`; состояние — снимок
 * `dpai.accounts-state` v1. Чтение строгое: неизвестное поле, значение не
 * из перечисления, чужой вид идентификатора — находка с путём.
 */
object AccountCodec {
    const val STATE_FORMAT = "dpai.accounts-state"
    const val STATE_VERSION = 1

    fun type(e: AccountEvent): EventType = when (e) {
        is SubscriptionRegistered -> EventType.ACCOUNT_SUBSCRIPTION_REGISTERED
        is SlotRegistered -> EventType.ACCOUNT_SLOT_REGISTERED
        is LoginObserved -> EventType.ACCOUNT_LOGIN_CHANGED
        is IdentityConfirmed -> EventType.ACCOUNT_IDENTITY_CONFIRMED
        is OverlapDetected -> EventType.ACCOUNT_OVERLAP_DETECTED
        is LimitObserved -> if (e.level == LimitLevel.REACHED) EventType.ACCOUNT_RATE_LIMITED else EventType.ACCOUNT_NEAR_LIMIT
        is LimitReset -> EventType.ACCOUNT_LIMIT_RESET
        is LoginExpiring -> EventType.ACCOUNT_LOGIN_EXPIRING
        is ErrorRecorded -> EventType.ACCOUNT_ERROR_RECORDED
        is ErrorsCleared -> EventType.ACCOUNT_ERRORS_CLEARED
        is SlotDisabled -> EventType.ACCOUNT_SLOT_DISABLED
        is SlotEnabled -> EventType.ACCOUNT_SLOT_ENABLED
        is SessionLeased -> EventType.ACCOUNT_SESSION_LEASED
        is SessionReleased -> EventType.ACCOUNT_SESSION_RELEASED
        is SubscriptionSwitched -> EventType.ACCOUNT_SWITCHED
        is SlotStateChanged -> if (e.to == AccountState.AUTH_REQUIRED) EventType.ACCOUNT_AUTH_REQUIRED else EventType.ACCOUNT_STATE_CHANGED
    }

    /** Поток события: подписка, слот или задача (D-12). */
    fun stream(e: AccountEvent): StreamRef = StreamRef.of(
        when (e) {
            is SubscriptionRegistered -> e.subscription
            is IdentityConfirmed -> e.subscription
            is LimitObserved -> e.subscription
            is LimitReset -> e.subscription
            is SubscriptionSwitched -> e.task
            is SlotRegistered -> e.slot
            is LoginObserved -> e.slot
            is OverlapDetected -> e.slot
            is LoginExpiring -> e.slot
            is ErrorRecorded -> e.slot
            is ErrorsCleared -> e.slot
            is SlotDisabled -> e.slot
            is SlotEnabled -> e.slot
            is SessionLeased -> e.slot
            is SessionReleased -> e.slot
            is SlotStateChanged -> e.slot
        },
    )

    fun payload(e: AccountEvent): JsonObject = when (e) {
        is SubscriptionRegistered -> obj(
            "subscription" to id(e.subscription), "label" to JsonString(e.label),
            "maxParallelSessions" to JsonNumber.of(e.maxParallelSessions), "rotationAllowed" to JsonBool(e.rotationAllowed),
        )
        is SlotRegistered -> obj(
            "slot" to id(e.slot), "subscription" to id(e.subscription), "target" to id(e.target), "configDir" to JsonString(e.configDir),
        )
        is LoginObserved -> obj("slot" to id(e.slot), "login" to name(e.login), "authMethod" to opt(e.authMethod))
        is IdentityConfirmed -> obj("subscription" to id(e.subscription), "identity" to JsonString(e.identity.toString()), "plan" to opt(e.plan))
        is OverlapDetected -> obj("slot" to id(e.slot), "kind" to name(e.kind), "other" to opt(e.other?.value))
        is LimitObserved -> obj(
            "subscription" to id(e.subscription), "window" to name(e.window), "level" to name(e.level), "resetAt" to time(e.resetAt),
        )
        is LimitReset -> obj("subscription" to id(e.subscription), "window" to name(e.window), "evidence" to name(e.evidence))
        is LoginExpiring -> obj("slot" to id(e.slot), "expiresAt" to time(e.expiresAt))
        is ErrorRecorded -> obj("slot" to id(e.slot), "code" to JsonString(e.code.name), "cooldownUntil" to time(e.cooldownUntil))
        is ErrorsCleared -> obj("slot" to id(e.slot))
        is SlotDisabled -> obj("slot" to id(e.slot))
        is SlotEnabled -> obj("slot" to id(e.slot))
        is SessionLeased -> obj("slot" to id(e.slot), "session" to id(e.session), "task" to id(e.task))
        is SessionReleased -> obj("slot" to id(e.slot), "session" to id(e.session))
        is SubscriptionSwitched -> obj("task" to id(e.task), "from" to id(e.from), "to" to id(e.to))
        is SlotStateChanged -> obj("slot" to id(e.slot), "from" to name(e.from), "to" to name(e.to))
    }

    /** Событие аккаунтов из типа, времени и полезной нагрузки; чужой тип — `null`. */
    fun decode(type: EventType, at: Timestamp, payload: JsonObject): Outcome<AccountEvent>? {
        val d = DiagnosticCollector()
        val f = JsonFields(payload, "$.payload", d)
        val event: AccountEvent? = when (type) {
            EventType.ACCOUNT_SUBSCRIPTION_REGISTERED -> build(
                f.id("subscription", IdKinds.SUBSCRIPTION), f.string("label"), f.int("maxParallelSessions"), f.boolean("rotationAllowed"),
            ) { a, b, c, e -> SubscriptionRegistered(a, b, c, e, at) }
            EventType.ACCOUNT_SLOT_REGISTERED -> build(
                f.id("slot", IdKinds.ACCOUNT), f.id("subscription", IdKinds.SUBSCRIPTION), f.id("target", IdKinds.TARGET), f.string("configDir"),
            ) { a, b, c, e -> SlotRegistered(a, b, c, e, at) }
            EventType.ACCOUNT_LOGIN_CHANGED -> build(f.id("slot", IdKinds.ACCOUNT), f.enum<LoginStatus>("login")) { a, b ->
                LoginObserved(a, b, f.optString("authMethod"), at)
            }
            EventType.ACCOUNT_IDENTITY_CONFIRMED -> build(f.id("subscription", IdKinds.SUBSCRIPTION), f.hash("identity")) { a, b ->
                IdentityConfirmed(a, b, f.optString("plan"), at)
            }
            EventType.ACCOUNT_OVERLAP_DETECTED -> build(f.id("slot", IdKinds.ACCOUNT), f.enum<OverlapKind>("kind")) { a, b ->
                OverlapDetected(a, b, f.optId("other", IdKinds.SUBSCRIPTION), at)
            }
            EventType.ACCOUNT_RATE_LIMITED, EventType.ACCOUNT_NEAR_LIMIT -> build(
                f.id("subscription", IdKinds.SUBSCRIPTION), f.enum<LimitWindow>("window"), f.enum<LimitLevel>("level"),
            ) { a, b, c -> LimitObserved(a, b, c, f.optTime("resetAt"), at) }
            EventType.ACCOUNT_LIMIT_RESET -> build(
                f.id("subscription", IdKinds.SUBSCRIPTION), f.enum<LimitWindow>("window"), f.enum<ResetEvidence>("evidence"),
            ) { a, b, c -> LimitReset(a, b, c, at) }
            EventType.ACCOUNT_LOGIN_EXPIRING -> build(f.id("slot", IdKinds.ACCOUNT)) { a -> LoginExpiring(a, f.optTime("expiresAt"), at) }
            EventType.ACCOUNT_ERROR_RECORDED -> build(f.id("slot", IdKinds.ACCOUNT), f.enum<DiagCode>("code")) { a, b ->
                ErrorRecorded(a, b, f.optTime("cooldownUntil"), at)
            }
            EventType.ACCOUNT_ERRORS_CLEARED -> build(f.id("slot", IdKinds.ACCOUNT)) { ErrorsCleared(it, at) }
            EventType.ACCOUNT_SLOT_DISABLED -> build(f.id("slot", IdKinds.ACCOUNT)) { SlotDisabled(it, at) }
            EventType.ACCOUNT_SLOT_ENABLED -> build(f.id("slot", IdKinds.ACCOUNT)) { SlotEnabled(it, at) }
            EventType.ACCOUNT_SESSION_LEASED -> build(f.id("slot", IdKinds.ACCOUNT), f.id("session", IdKinds.SESSION), f.id("task", IdKinds.TASK)) { a, b, c ->
                SessionLeased(a, b, c, at)
            }
            EventType.ACCOUNT_SESSION_RELEASED -> build(f.id("slot", IdKinds.ACCOUNT), f.id("session", IdKinds.SESSION)) { a, b ->
                SessionReleased(a, b, at)
            }
            EventType.ACCOUNT_SWITCHED -> build(f.id("task", IdKinds.TASK), f.id("from", IdKinds.SUBSCRIPTION), f.id("to", IdKinds.SUBSCRIPTION)) { a, b, c ->
                SubscriptionSwitched(a, b, c, at)
            }
            EventType.ACCOUNT_STATE_CHANGED, EventType.ACCOUNT_AUTH_REQUIRED -> build(
                f.id("slot", IdKinds.ACCOUNT), f.enum<AccountState>("from"), f.enum<AccountState>("to"),
            ) { a, b, c -> SlotStateChanged(a, b, c, at) }
            else -> return null
        }
        f.finish()
        return if (d.hasErrors || event == null) Outcome.Failure(d.all.ifEmpty { listOf(missing()) }) else Outcome.success(event)
    }

    // ---- снимок состояния ----

    fun encodeState(s: AccountsState): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(STATE_FORMAT, STATE_VERSION),
            "subscriptions" to JsonArray(
                s.subscriptions.values.sortedBy { it.id.value }.map { sub ->
                    obj(
                        "id" to id(sub.id), "label" to JsonString(sub.label), "maxParallelSessions" to JsonNumber.of(sub.maxParallelSessions),
                        "rotationAllowed" to JsonBool(sub.rotationAllowed), "identity" to opt(sub.identity?.toString()), "plan" to opt(sub.plan),
                        "windows" to JsonArray(
                            LimitWindow.entries.map { w ->
                                val ws = sub.window(w)
                                obj("window" to name(w), "level" to name(ws.level), "resetAt" to time(ws.resetAt), "observedAt" to time(ws.observedAt))
                            },
                        ),
                    )
                },
            ),
            "slots" to JsonArray(
                s.slots.values.sortedBy { it.id.value }.map { slot ->
                    obj(
                        "id" to id(slot.id), "subscription" to id(slot.subscriptionId), "target" to id(slot.targetId),
                        "configDir" to JsonString(slot.configDir), "state" to name(slot.state), "login" to name(slot.login),
                        "authMethod" to opt(slot.authMethod), "disabled" to JsonBool(slot.disabled),
                        "sessions" to JsonArray(
                            slot.sessions.entries.sortedBy { it.key.value }.map { (k, v) -> obj("session" to id(k), "task" to id(v)) },
                        ),
                        "errorStreak" to JsonNumber.of(slot.errorStreak), "cooldownUntil" to time(slot.cooldownUntil),
                        "loginExpiring" to JsonBool(slot.loginExpiring), "loginExpiresAt" to time(slot.loginExpiresAt),
                    )
                },
            ),
            "taskSubscription" to JsonArray(
                s.taskSubscription.entries.sortedBy { it.key.value }.map { (k, v) -> obj("task" to id(k), "subscription" to id(v)) },
            ),
            "switches" to JsonArray(
                s.switches.entries.sortedBy { it.key.value }.map { (k, v) ->
                    obj("task" to id(k), "at" to JsonArray(v.map { JsonNumber.of(it.epochMillis) }))
                },
            ),
        ),
    )

    fun decodeState(json: JsonObject): Outcome<AccountsState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, STATE_FORMAT, STATE_VERSION..STATE_VERSION, d) ?: return Outcome.Failure(d.all)
        val subs = f.objects("subscriptions").mapNotNull { s ->
            val windows = s.objects("windows").mapNotNull { w ->
                build(w.enum<LimitWindow>("window"), w.enum<LimitLevel>("level")) { a, b -> a to WindowState(b, w.optTime("resetAt"), w.optTime("observedAt")) }
                    .also { w.finish() }
            }.toMap()
            build(s.id("id", IdKinds.SUBSCRIPTION), s.string("label"), s.int("maxParallelSessions"), s.boolean("rotationAllowed")) { a, b, c, e ->
                SubscriptionState(a, b, c, e, s.optHash("identity"), s.optString("plan"), LimitWindow.entries.associateWith { windows[it] ?: WindowState() })
            }.also { s.finish() }
        }
        val slots = f.objects("slots").mapNotNull { s ->
            val sessions = s.objects("sessions").mapNotNull { p ->
                build(p.id("session", IdKinds.SESSION), p.id("task", IdKinds.TASK)) { a, b -> a to b }.also { p.finish() }
            }.toMap()
            build(
                s.id("id", IdKinds.ACCOUNT), s.id("subscription", IdKinds.SUBSCRIPTION), s.id("target", IdKinds.TARGET), s.string("configDir"),
            ) { a, b, c, e ->
                val state = s.enum<AccountState>("state")
                val login = s.enum<LoginStatus>("login")
                val disabled = s.boolean("disabled")
                val streak = s.int("errorStreak")
                val expiring = s.boolean("loginExpiring")
                if (state == null || login == null || disabled == null || streak == null || expiring == null) {
                    null
                } else {
                    SlotState(
                        a, b, c, e, state, login, s.optString("authMethod"), disabled, sessions, streak,
                        s.optTime("cooldownUntil"), expiring, s.optTime("loginExpiresAt"),
                    )
                }
            }.also { s.finish() }
        }
        val taskSub = f.objects("taskSubscription").mapNotNull { p ->
            build(p.id("task", IdKinds.TASK), p.id("subscription", IdKinds.SUBSCRIPTION)) { a, b -> a to b }.also { p.finish() }
        }.toMap()
        val switches = f.objects("switches").mapNotNull { p ->
            val times = p.array("at")?.items?.mapNotNull { (it as? JsonNumber)?.toLongExact()?.let(::Timestamp) }
            build(p.id("task", IdKinds.TASK)) { a -> times?.let { a to it } }.also { p.finish() }
        }.toMap()
        f.finish()
        if (d.hasErrors) return Outcome.Failure(d.all)
        return Outcome.success(
            AccountsState(subs.associateBy { it.id }, slots.associateBy { it.id }, taskSub, switches),
        )
    }

    // ---- помощники ----

    private fun obj(vararg pairs: Pair<String, JsonValue?>): JsonObject =
        JsonObject(linkedMapOf(*pairs.filter { it.second != null }.map { it.first to it.second!! }.toTypedArray()))

    private fun id(v: TypedId): JsonString = JsonString(v.value)

    private fun name(v: Enum<*>): JsonString = JsonString(v.name)

    private fun opt(v: String?): JsonString? = v?.let(::JsonString)

    private fun time(t: Timestamp?): JsonNumber? = t?.let { JsonNumber.of(it.epochMillis) }

    private fun missing() = com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.FIELD_MISSING, "событие аккаунтов неполно")

    private fun <T : TypedId> JsonFields.id(name: String, kind: IdKind<T>): T? = string(name)?.let { s ->
        kind.parse(s).valueOrNull().also { if (it == null) idError(name, kind) }
    }

    private fun <T : TypedId> JsonFields.optId(name: String, kind: IdKind<T>): T? = optString(name)?.let { s ->
        kind.parse(s).valueOrNull().also { if (it == null) idError(name, kind) }
    }

    private fun JsonFields.idError(name: String, kind: IdKind<*>) {
        raw(name, required = false)
        diagnostics.error(DiagCode.ID_INVALID, "ожидался ${kind.typeName}", "$path.$name")
    }

    private inline fun <reified E : Enum<E>> JsonFields.enum(name: String): E? = string(name)?.let { s ->
        enumValues<E>().firstOrNull { it.name == s } ?: null.also { diagnostics.error(DiagCode.VALUE_INVALID, "неизвестное значение", "$path.$name") }
    }

    private fun JsonFields.hash(name: String): ContentHash? = string(name)?.let { s ->
        ContentHash.parse(s).valueOrNull() ?: null.also { diagnostics.error(DiagCode.HASH_INVALID, "не хеш", "$path.$name") }
    }

    private fun JsonFields.optHash(name: String): ContentHash? = optString(name)?.let { s ->
        ContentHash.parse(s).valueOrNull() ?: null.also { diagnostics.error(DiagCode.HASH_INVALID, "не хеш", "$path.$name") }
    }

    private fun JsonFields.optTime(name: String): Timestamp? = optLong(name)?.let(::Timestamp)

    private fun JsonFields.objects(name: String): List<JsonFields> = array(name)?.items?.mapIndexedNotNull { i, item ->
        (item as? JsonObject)?.let { JsonFields(it, "$path.$name[$i]", diagnostics) }
            ?: null.also { diagnostics.error(DiagCode.FIELD_TYPE, "ожидался объект", "$path.$name[$i]") }
    }.orEmpty()

    private inline fun <A, R> build(a: A?, f: (A) -> R?): R? = if (a == null) null else f(a)

    private inline fun <A, B, R> build(a: A?, b: B?, f: (A, B) -> R?): R? = if (a == null || b == null) null else f(a, b)

    private inline fun <A, B, C, R> build(a: A?, b: B?, c: C?, f: (A, B, C) -> R?): R? =
        if (a == null || b == null || c == null) null else f(a, b, c)

    private inline fun <A, B, C, D, R> build(a: A?, b: B?, c: C?, e: D?, f: (A, B, C, D) -> R?): R? =
        if (a == null || b == null || c == null || e == null) null else f(a, b, c, e)
}

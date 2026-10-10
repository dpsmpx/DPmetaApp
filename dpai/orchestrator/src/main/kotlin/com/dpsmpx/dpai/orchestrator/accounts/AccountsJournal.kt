package com.dpsmpx.dpai.orchestrator.accounts

import com.dpsmpx.dpai.accounts.AccountCodec
import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Состояние аккаунтов как проекция журнала (D-12, D-24): события аккаунтов
 * — в журнал конвертами `dpai.event`, состояние — из журнала и снимка
 * `dpai.accounts-state`.
 */
object AccountsProjection : Projection<AccountsState> {
    override val name: String = "accounts"

    override fun initial(): AccountsState = AccountsState.EMPTY

    override fun apply(state: AccountsState, event: EventEnvelope): AccountsState {
        val decoded = AccountCodec.decode(event.type, event.occurredAt, event.payload) ?: return state
        // Журнал сверен хешами (D-24): негодное событие аккаунтов здесь — несовместимость версий, а не порча.
        val e = checkNotNull(decoded.valueOrNull()) { "событие аккаунтов №${event.sequence} не читается: ${decoded.diagnostics}" }
        return Accounts.apply(state, e)
    }

    override fun encode(state: AccountsState): JsonObject = AccountCodec.encodeState(state)

    override fun decode(json: JsonObject): Outcome<AccountsState> = AccountCodec.decodeState(json)
}

/** Конверты для событий аккаунтов: тип, поток и полезная нагрузка — из [AccountCodec]. */
fun EventRecorder.accountEnvelopes(events: List<AccountEvent>): List<EventEnvelope> =
    events.map { envelope(AccountCodec.type(it), AccountCodec.stream(it), it.at, AccountCodec.payload(it)) }

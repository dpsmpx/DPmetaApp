package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamKind
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.core.state.AccountState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AccountCodecTest {
    @Test
    fun typesAndStreams() {
        val f = Fixture()
        val sub = f.subscription()
        val slot = f.slot(sub)
        val reg = f.events.first()
        assertEquals(EventType.ACCOUNT_SUBSCRIPTION_REGISTERED, AccountCodec.type(reg))
        assertEquals(StreamKind.SUBSCRIPTION, AccountCodec.stream(reg).kind)
        f.login(slot)
        val toAvailable = f.events.filterIsInstance<AccountEvent.SlotStateChanged>().single()
        assertEquals(EventType.ACCOUNT_STATE_CHANGED, AccountCodec.type(toAvailable))
        assertEquals(StreamKind.ACCOUNT, AccountCodec.stream(toAvailable).kind)
        assertEquals(EventType.ACCOUNT_AUTH_REQUIRED, AccountCodec.type(toAvailable.copy(from = AccountState.AVAILABLE, to = AccountState.AUTH_REQUIRED)))
    }

    @Test
    fun strictDecoding() {
        val f = Fixture()
        val sub = f.subscription()
        val e = f.events.first()
        val good = AccountCodec.payload(e)
        val extra = JsonObject(good.fields + ("extra" to json(1)))
        assertEquals(DiagCode.FIELD_UNKNOWN, assertIs<Outcome.Failure>(AccountCodec.decode(AccountCodec.type(e), e.at, extra)).errors.single().code)
        val wrongKind = JsonObject(good.fields + ("subscription" to json(f.ids.next(com.dpsmpx.dpai.core.ids.IdKinds.TASK).value)))
        assertEquals(DiagCode.ID_INVALID, assertIs<Outcome.Failure>(AccountCodec.decode(AccountCodec.type(e), e.at, wrongKind)).errors.single().code)
        assertNull(AccountCodec.decode(EventType.TASK_CREATED, e.at, good), "чужой тип — не событие аккаунтов")
        assertEquals(sub, (AccountCodec.decode(AccountCodec.type(e), e.at, good)!!.valueOrNull() as AccountEvent.SubscriptionRegistered).subscription)
    }

    @Test
    fun rotationEventAndSnapshotRoundTrip() {
        val f = Fixture()
        val sub = f.subscription()
        f.ok(f.accounts.setRotation(f.state, sub, true, f.now))
        val e = f.events.last()
        assertEquals(EventType.ACCOUNT_ROTATION_SET, AccountCodec.type(e))
        assertEquals(e, AccountCodec.decode(AccountCodec.type(e), e.at, AccountCodec.payload(e))!!.valueOrNull())
        assertEquals(f.state, AccountCodec.decodeState(AccountCodec.encodeState(f.state)).valueOrNull(), "rotationExplicit в снимке")
        val old = AccountCodec.encodeState(Fixture().also { it.subscription() }.state)
        assertEquals(false, AccountCodec.decodeState(old).valueOrNull()!!.subscriptions.values.single().rotationExplicit, "снимок до D-56 читается")
    }
}

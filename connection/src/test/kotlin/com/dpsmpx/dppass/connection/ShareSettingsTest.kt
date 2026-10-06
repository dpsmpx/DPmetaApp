package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.core.IpAddress
import com.dpsmpx.dppass.core.ManualClock
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.engine.ShareSpec
import com.dpsmpx.dppass.storage.InMemoryBlobStore
import com.dpsmpx.dppass.storage.Quarantine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Раздача туннеля другим устройствам (D-51): настройки, хранение, вход для ядра. */
class ShareSettingsTest {
    private val clock = ManualClock(1_000)

    @Test
    fun generatedSettingsAreOffRandomAndInRange() {
        val a = ShareSettings.generate()
        val b = ShareSettings.generate()
        assertFalse(a.enabled, "раздача выключена по умолчанию")
        assertTrue(a.port in ShareSettings.PORT_FROM until ShareSettings.PORT_UNTIL)
        assertEquals(ShareSettings.USER_LENGTH, a.user.reveal().length)
        assertEquals(ShareSettings.PASSWORD_LENGTH, a.password.reveal().length)
        assertNotEquals(a.password.reveal(), b.password.reveal(), "пароль случайный")
    }

    @Test
    fun specOnlyWhenEnabledAndOnPrivateAddresses() {
        val s = ShareSettings(true, 41234, Secret("u"), Secret("p"))
        val hotspot = ShareSpec.Endpoint(IpAddress.parse("192.168.43.1")!!, 24)
        val spec = s.spec(listOf(hotspot, hotspot))!!
        assertEquals(listOf(hotspot), spec.endpoints, "повтор адреса отброшен")
        assertEquals(41234, spec.port)
        assertNull(s.copy(enabled = false).spec(listOf(hotspot)), "выключено — без входа")
        assertNull(s.spec(emptyList()), "нет интерфейсов раздачи — без входа")
    }

    @Test
    fun storeRoundTripKeepsCredentialsAndNoPlainSecretsLeak() {
        val blobs = InMemoryBlobStore()
        val service = ShareService(ShareStore(blobs, Quarantine(blobs, clock)))
        val first = service.current().valueOrNull()!!
        assertFalse(first.enabled)
        val on = service.setEnabled(true).valueOrNull()!!
        assertTrue(on.enabled)
        assertEquals(first.password.reveal(), on.password.reveal(), "включение не меняет пароль")
        val again = ShareService(ShareStore(blobs, Quarantine(blobs, clock))).current().valueOrNull()!!
        assertEquals(on.port, again.port)
        assertEquals(on.user.reveal(), again.user.reveal())
        assertEquals(on.password.reveal(), again.password.reveal())
        assertTrue(again.enabled, "переживает перезапуск")
        val renewed = service.regenerate().valueOrNull()!!
        assertTrue(renewed.enabled, "смена пароля не выключает раздачу")
        assertNotEquals(on.password.reveal(), renewed.password.reveal())
        // Строковое представление — без секретов.
        assertFalse(renewed.password.reveal() in renewed.toString())
    }

    @Test
    fun corruptedSettingsGoToQuarantineAndShareIsOff() {
        val blobs = InMemoryBlobStore()
        blobs.write(ShareStore.KEY, """{"format":"dppass.share","format_version":1,"enabled":true,"port":80,"user":"u","password":"p"}""".toByteArray())
        val r = ShareStore(blobs, Quarantine(blobs, clock)).load()
        assertFalse(r.valueOrNull()!!.enabled, "нечитаемое — раздача выключена, а не включена с чужим паролем")
        assertTrue(r.diagnostics.any { it.code == DiagCode.STORAGE_CORRUPTED })
        assertEquals(1, Quarantine(blobs, clock).list().valueOrNull()!!.size)
    }
}

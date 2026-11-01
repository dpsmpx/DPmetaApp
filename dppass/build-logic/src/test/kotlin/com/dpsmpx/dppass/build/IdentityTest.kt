package com.dpsmpx.dppass.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {
    private val validId = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

    @Test
    fun randomApplicationIdIsAValidPackageWithoutGivingAwayPurpose() {
        repeat(200) { n ->
            val id = AppIdentity.random(n.toLong())
            assertTrue(validId.matches(id.applicationId), "неверный пакет: ${id.applicationId}")
            assertTrue(id.applicationId.startsWith("com."))
            val lower = (id.applicationId + " " + id.label).lowercase()
            for (bad in listOf("vpn", "proxy", "tunnel", "dppass", "xray")) {
                assertTrue(bad !in lower, "личность выдаёт назначение: $id")
            }
        }
    }

    @Test
    fun sameSeedGivesSameIdentityDifferentSeedDiffers() {
        assertEquals(AppIdentity.random(42), AppIdentity.random(42))
        assertNotEquals(AppIdentity.random(1), AppIdentity.random(2))
    }

    @Test
    fun seedOfIsStableForTheSameStringAndEmptyIsRandom() {
        assertEquals(AppIdentity.seedOf("нора"), AppIdentity.seedOf("нора"))
        assertNotEquals(AppIdentity.seedOf("a"), AppIdentity.seedOf("b"))
        assertEquals(null, AppIdentity.seedOf(""))
        assertEquals(null, AppIdentity.seedOf("   "))
        assertEquals(null, AppIdentity.seedOf(null))
    }

    /**
     * Контрольное значение (D-49): `tools/release/sign_apks.py` повторяет seedOf и
     * random на Python и сверяет пакет собранного APK с ожидаемым. Значение ниже
     * посчитано той копией; расхождение — одна из сторон изменилась без другой.
     */
    @Test
    fun knownVectorMatchesSignApksCopy() {
        val seed = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881" // SHA-256 строки «x»
        assertEquals(AppIdentity.Identity("com.sehemop.sibafe", "Grove"), AppIdentity.random(AppIdentity.seedOf(seed)))
    }

    @Test
    fun stableIdentityIsTheRepositoryNamespace() {
        assertEquals("com.dpsmpx.dppass", AppIdentity.STABLE.applicationId)
        assertEquals("DPpass", AppIdentity.STABLE.label)
    }
}

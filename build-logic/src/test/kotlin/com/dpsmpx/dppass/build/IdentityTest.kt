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

    @Test
    fun stableIdentityIsTheRepositoryNamespace() {
        assertEquals("com.dpsmpx.dppass", AppIdentity.STABLE.applicationId)
        assertEquals("DPpass", AppIdentity.STABLE.label)
    }
}

package com.dpsmpx.dpai.security

import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonBool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Свод `claude auth status` на target (D-55, ревью A-72): отпечаток вместо email и организации. */
class AccountIdentityTest {
    @Test
    fun summaryKeepsLoginAndFingerprintButNoPersonalData() {
        val raw = """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"A@Example.org ","orgId":"org-1","orgName":"Моя","subscriptionType":"pro"}"""
        val s = AccountIdentity.summarize(raw)
        val text = CanonicalJson.write(s)
        assertFalse("example.org" in text.lowercase() || "org-1" in text || "Моя" in text, text)
        assertEquals(AccountIdentity.fingerprint("org-1", "a@example.org").toString(), (s["identity"] as com.dpsmpx.dpai.core.serialization.JsonString).value)
        assertEquals(JsonBool(true), s["loggedIn"])
    }

    @Test
    fun garbageIsInvalidWithoutTheText() {
        for (raw in listOf("command not found: a@example.org", """{"email":"a@example.org"}""", "")) {
            val s = AccountIdentity.summarize(raw)
            assertEquals(JsonBool(true), s["invalid"])
            assertFalse("example.org" in CanonicalJson.write(s))
        }
    }

    @Test
    fun fingerprintIsCaseAndSpaceInsensitiveAndUnknownWithoutData() {
        assertEquals(AccountIdentity.fingerprint("o", " X@Y.org"), AccountIdentity.fingerprint("o", "x@y.org"))
        assertNotNull(AccountIdentity.fingerprint(null, "x@y.org"))
        assertNull(AccountIdentity.fingerprint(null, null))
    }
}

package com.dpsmpx.dpmeta.net

import com.dpsmpx.dpai.core.diagnostics.Secret
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccountLeaseTest {
    private val secret = Secret(listOf("synthetic", "account").joinToString("-"))

    @Test
    fun aNewServiceNeedsExplicitAuthorization() {
        val lease = AccountLease()
        assertNull(lease.read())
        assertTrue(lease.grant(lease.request(), secret))
        // Чтение повторяется без TTL/диска, пока право явно не отозвано.
        repeat(3) { assertSame(secret, lease.read()) }
        assertNull(AccountLease().read())
    }

    @Test
    fun stoppingServiceRevokesAccessAndLateAuthenticationCannotRestoreIt() {
        val lease = AccountLease()
        val pendingAuthentication = lease.request()
        assertTrue(lease.grant(pendingAuthentication, secret))
        lease.close()
        assertNull(lease.read())
        assertFalse(lease.grant(pendingAuthentication, secret))
        assertNull(lease.read())
        assertTrue(lease.grant(lease.request(), secret))
        assertSame(secret, lease.read())
    }

    @Test
    fun repeatedRevocationAlsoInvalidatesPendingAccountReplacement() {
        val lease = AccountLease()
        lease.close()
        val pending = lease.request()
        lease.close()
        lease.close()
        assertFalse(lease.grant(pending, secret))
        assertNull(lease.read())
        assertFalse(lease.toString().contains("synthetic"))
    }
}

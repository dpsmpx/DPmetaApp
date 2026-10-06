package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Разбор `claude auth status --json` (`docs/research/CLAUDE_CODE_CLI.md`, §4). */
class AuthStatusTest {
    private fun parse(text: String) = AuthStatus.fromClaudeJson(JsonParser().parse(text).valueOrNull()!!)

    @Test
    fun claudeFormLoginWithIdentity() {
        val email = "author" + "@example.org"
        val s = parse(
            """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"$email","orgId":"0f8fad5b-d9cb-469f-a165-70867728950e","orgName":"x","subscriptionType":"max","configDirectory":"/home/u/.claude-a","newField":1}""",
        ).valueOrNull()!!
        assertTrue(s.isClaudeLogin)
        assertEquals("max", s.plan)
        assertFalse(s.identity.toString().contains("author"), "email не хранится")
        assertEquals(s.identity, AuthStatus.fingerprint("0f8fad5b-d9cb-469f-a165-70867728950e", "AUTHOR@example.org "))
        assertNotEquals(s.identity, AuthStatus.fingerprint("0f8fad5b-d9cb-469f-a165-70867728950e", "other@example.org"))
    }

    @Test
    fun otherMethodsAndLoggedOut() {
        val token = parse("""{"loggedIn":true,"authMethod":"oauth_token","apiProvider":"firstParty"}""").valueOrNull()!!
        assertFalse(token.isClaudeLogin)
        assertNull(token.identity)
        val out = parse("""{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}""").valueOrNull()!!
        assertFalse(out.loggedIn)
        assertEquals(DiagCode.FIELD_MISSING, assertIs<Outcome.Failure>(parse("""{"authMethod":"claude.ai"}""")).errors.single().code)
    }
}

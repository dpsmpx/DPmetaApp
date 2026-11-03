package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.state.AccountState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Слоты Codex и Gemini (D-100): вход аккаунтом, окружение слота, отбор задачи, формат v3. */
class ExecutorAccountsTest {
    @Test
    fun codexAndGeminiLoginStatus() {
        val chatgpt = AuthStatus.fromCodexStatus("WARNING: …\nLogged in using ChatGPT\n").valueOrNull()!!
        assertTrue(chatgpt.isAllowed(Executor.CODEX))
        assertFalse(chatgpt.isAllowed(Executor.CLAUDE))
        val key = AuthStatus.fromCodexStatus("Logged in using an API key - sk-***").valueOrNull()!!
        assertTrue(key.loggedIn && !key.isAllowed(Executor.CODEX))
        assertFalse(AuthStatus.fromCodexStatus("Not logged in").valueOrNull()!!.loggedIn)
        assertEquals(DiagCode.CLAUDE_AUTH_STATUS_INVALID, assertIs<Outcome.Failure>(AuthStatus.fromCodexStatus("что-то другое")).errors.single().code)

        assertTrue(AuthStatus.fromGeminiProbe("selectedType=\"oauth-personal\"\ncreds=yes").valueOrNull()!!.isAllowed(Executor.GEMINI))
        assertFalse(AuthStatus.fromGeminiProbe("selectedType=oauth-personal\ncreds=no").valueOrNull()!!.loggedIn, "без файла входа — вход нужен")
        val apiKey = AuthStatus.fromGeminiProbe("selectedType=gemini-api-key\ncreds=no").valueOrNull()!!
        assertTrue(apiKey.loggedIn && !apiKey.isAllowed(Executor.GEMINI))
        assertFalse(AuthStatus.fromGeminiProbe("creds=no").valueOrNull()!!.loggedIn)
        assertIs<Outcome.Failure>(AuthStatus.fromGeminiProbe(""))
    }

    @Test
    fun slotLoginByExecutor() {
        val f = Fixture()
        val codex = f.slot(f.subscription("ChatGPT", executor = Executor.CODEX), dir = "~/.codex-slot-1")
        assertEquals(Executor.CODEX, f.state.slots.getValue(codex).executor)
        // Ключ API у Codex — запрещённый способ, как у Claude (D-29, D-100).
        val r = f.record(f.accounts.observeAuthStatus(f.state, codex, AuthStatus(true, "an-api-key", null, null, null), f.now))
        assertTrue(r.diagnostics.any { it.code == DiagCode.ACCOUNT_AUTH_METHOD_FORBIDDEN && "ChatGPT" in it.message })
        assertEquals(LoginStatus.FORBIDDEN_METHOD, f.state.slots.getValue(codex).login)
        // Вход ChatGPT — без отпечатка аккаунта, и это не находка.
        val ok = f.record(f.accounts.observeAuthStatus(f.state, codex, AuthStatus(true, AuthStatus.CHATGPT, null, null, null), f.now))
        assertTrue(ok.diagnostics.none { it.code == DiagCode.ACCOUNT_IDENTITY_UNKNOWN })
        assertEquals(LoginStatus.OK, f.state.slots.getValue(codex).login)
        assertEquals(AccountState.AVAILABLE, f.state.slots.getValue(codex).state)
    }

    @Test
    fun leaseByExecutor() {
        val f = Fixture()
        val claude = f.slot(f.subscription("Claude"))
        f.login(claude)
        val gemini = f.slot(f.subscription("Google", executor = Executor.GEMINI), dir = "~/.gemini-slot-1")
        f.record(f.accounts.observeAuthStatus(f.state, gemini, AuthStatus(true, AuthStatus.GOOGLE, null, null, null), f.now))
        val task = f.task()
        val session = f.ids.next(com.dpsmpx.dpai.core.ids.IdKinds.SESSION)
        val l = f.accounts.lease(f.state, session, task, f.now, executor = Executor.GEMINI).valueOrNull()!!
        assertEquals(gemini, l.slot)
        val c = f.accounts.lease(f.state, f.ids.next(com.dpsmpx.dpai.core.ids.IdKinds.SESSION), f.task(), f.now, executor = Executor.CLAUDE).valueOrNull()!!
        assertEquals(claude, c.slot)
        assertEquals(DiagCode.ACCOUNT_NONE_AVAILABLE, assertIs<Outcome.Failure>(f.accounts.lease(f.state, f.ids.next(com.dpsmpx.dpai.core.ids.IdKinds.SESSION), f.task(), f.now, executor = Executor.CODEX)).errors.single().code)
    }

    @Test
    fun slotEnvironmentByExecutor() {
        val f = Fixture()
        val codex = f.state.let { f.slot(f.subscription(executor = Executor.CODEX), dir = "~/.codex-slot-1") }.let { f.state.slots.getValue(it) }
        val key = "s" + "k-proj-" + "z".repeat(30)
        val env = SlotEnvironment.forSlot(mapOf("PATH" to "/bin", "OPENAI_API_KEY" to key, "CODEX_HOME" to "/x"), codex).valueOrNull()!!
        assertEquals(mapOf("CODEX_HOME" to "~/.codex-slot-1", "PATH" to "/bin"), env)
        val gemini = f.slot(f.subscription(executor = Executor.GEMINI), dir = "~/.gemini-slot-1").let { f.state.slots.getValue(it) }
        val genv = SlotEnvironment.forSlot(mapOf("GEMINI_API_KEY" to key, "GOOGLE_CLOUD_PROJECT" to "p"), gemini).valueOrNull()!!
        assertEquals(mapOf("GEMINI_CLI_HOME" to "~/.gemini-slot-1"), genv)
    }

    @Test
    fun stateFormatV3() {
        val f = Fixture()
        val codex = f.slot(f.subscription("ChatGPT", executor = Executor.CODEX), dir = "~/.codex-slot-1")
        f.login(f.slot(f.subscription("Claude")))
        val json = AccountCodec.encodeState(f.state)
        val back = AccountCodec.decodeState(json).valueOrNull()!!
        assertEquals(f.state.slots, back.slots)
        assertEquals(f.state.subscriptions, back.subscriptions)
        assertEquals(Executor.CODEX, back.slots.getValue(codex).executor)
        // Claude не пишется: прежние формы читаются как Claude.
        assertEquals(2, Regex("\"executor\":\"codex\"").findAll(json.toString()).count())
        val bad = json.toString().replace("\"executor\":\"codex\"", "\"executor\":\"copilot\"")
        assertIs<Outcome.Failure>(AccountCodec.decodeState(JsonParser().parse(bad).valueOrNull()!! as com.dpsmpx.dpai.core.serialization.JsonObject))
        // Событие регистрации — с исполнителем.
        val reg = f.events.filterIsInstance<AccountEvent.SubscriptionRegistered>().first()
        val decoded = AccountCodec.decode(AccountCodec.type(reg), reg.at, AccountCodec.payload(reg))!!.valueOrNull()
        assertEquals(reg, decoded)
    }
}

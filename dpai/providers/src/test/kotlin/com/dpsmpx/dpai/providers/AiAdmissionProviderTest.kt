package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.AiAdmission
import com.dpsmpx.dpai.accounts.AiMode
import com.dpsmpx.dpai.accounts.AiModePolicy
import com.dpsmpx.dpai.accounts.AiModelRef
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.accounts.Routines
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiAdmissionProviderTest {
    private val request = ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "Проверить")))
    private val reply = ChatReply("готово", StopKind.END, "end", ChatUsage.of(null, null))

    @Test fun factoryGuardsActualRequestModelForEveryApiIncludingLocal() {
        for (kind in ProviderKind.entries) {
            val p = profile(kind, if (kind == ProviderKind.OPENAI_COMPATIBLE) "http://127.0.0.1:8080/v1" else "", custom = kind == ProviderKind.OPENAI_COMPATIBLE)
            val g = AiAdmission()
            g.configure("unconfigured", AiModePolicy(AiMode.SINGLE, listOf(AiModelRef.api(p))), true)
            val t = FakeTransport { FakeReply(200, "{}") }
            val provider = Providers.of(p, null, t, g)
            assertIs<ToolChat>(provider)
            assertIs<Outcome.Failure>(provider.chat(request.copy(model = "other"), Cancel()) {})
            assertIs<Outcome.Failure>(provider.chatWithTools(request.copy(model = "other"), emptyList(), Cancel(), {}, { ToolResult("ok") }))
            assertTrue(t.calls.isEmpty())
            provider.models()
            assertEquals(0, g.active())
        }
    }

    @Test fun wholeToolConversationHoldsLeaseAndExceptionReleasesIt() {
        val p = profile(ProviderKind.ANTHROPIC)
        val g = AiAdmission()
        g.configure("unconfigured", AiModePolicy(AiMode.SINGLE, listOf(AiModelRef.api(p))), true)
        val delegate = object : AIProvider, ToolChat {
            override fun models(cancel: Cancel): Outcome<List<ModelInfo>> = Outcome.success(emptyList())
            override fun chat(request: ChatRequest, cancel: Cancel, onText: (String) -> Unit): Outcome<ChatReply> = error("обрыв тестового транспорта")
            override fun chatWithTools(request: ChatRequest, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit, run: (ToolCall) -> ToolResult): Outcome<ChatReply> {
                assertEquals(1, g.active())
                assertIs<Outcome.Failure>(g.acquire("completion", AiModelRef.api(p)))
                assertIs<Outcome.Failure>(g.configure(g.snapshot().revision, null, true))
                return Outcome.success(reply)
            }
        }
        val admitted = AdmittedProvider(p, delegate, g)
        assertIs<Outcome.Success<*>>(admitted.chatWithTools(request, emptyList(), Cancel(), {}, { ToolResult("ok") }))
        assertEquals(0, g.active())
        assertFailsWith<IllegalStateException> { admitted.chat(request, Cancel()) {} }
        assertEquals(0, g.active())
    }

    @Test fun cloudRoutineCannotBypassSingleAndUnknownOutcomeSurvivesRestart() {
        val p = profile(ProviderKind.ANTHROPIC)
        val routine = Routines.form(IdKinds.ACCOUNT.trusted("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X"), "Маршрут", "trig_example", null, null, null).valueOrNull()!!
        val t = FakeTransport { FakeReply(200, "{}") }
        val g = AiAdmission()
        g.configure("unconfigured", AiModePolicy(AiMode.SINGLE, listOf(AiModelRef.api(p))), true)
        val token = Secret(sampleKey("sk-ant-oat01"))
        assertIs<Outcome.Failure>(RoutineClient(t, g).fire(routine, token, "Проверить"))
        assertTrue(t.calls.isEmpty())
        g.configure(g.snapshot().revision, null, true)
        assertIs<Outcome.Failure>(RoutineClient(t, g).fire(routine, token, "Проверить"))
        assertEquals(1, g.snapshot().pending.size)
        val restored = AiAdmission()
        restored.install(Outcome.success(g.snapshot())) { Outcome.success(Unit) }
        assertIs<Outcome.Failure>(restored.configure("unconfigured", AiModePolicy(AiMode.SINGLE, listOf(AiModelRef.api(p))), true))
        val linked = FakeTransport { FakeReply(200, """{"claude_code_session_id":"example","claude_code_session_url":"https://claude.ai/code/example?view=compact"}""") }
        assertIs<Outcome.Success<*>>(RoutineClient(linked, g).fire(routine, token, "Проверить"))
        assertEquals("https://claude.ai/code/example", g.snapshot().pending.last().reference)
        assertIs<Outcome.Failure>(g.cloudReference(g.snapshot().pending.last().owner, "x".repeat(501)))
    }
}

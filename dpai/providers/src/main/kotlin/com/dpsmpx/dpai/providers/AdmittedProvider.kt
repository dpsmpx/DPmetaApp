package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.AiAdmission
import com.dpsmpx.dpai.accounts.AiModelRef
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.util.concurrent.atomic.AtomicLong

/** Один резерв на весь ответ, включая все раунды инструментов. Каталог моделей не запускает ИИ. */
internal class AdmittedProvider(
    private val profile: ApiProfile,
    private val delegate: AIProvider,
    private val admission: AiAdmission,
) : AIProvider, ToolChat {
    override fun models(cancel: Cancel): Outcome<List<ModelInfo>> = delegate.models(cancel)

    override fun chat(request: ChatRequest, cancel: Cancel, onText: (String) -> Unit): Outcome<ChatReply> = admitted(request) {
        delegate.chat(request, cancel, onText)
    }

    override fun chatWithTools(request: ChatRequest, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit, run: (ToolCall) -> ToolResult): Outcome<ChatReply> = admitted(request) {
        val toolChat = delegate as? ToolChat ?: return@admitted Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "провайдер не поддерживает инструменты")
        toolChat.chatWithTools(request, tools, cancel, onText, run)
    }

    private fun admitted(request: ChatRequest, run: () -> Outcome<ChatReply>): Outcome<ChatReply> {
        if (request.model.isBlank()) return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "модель ответа не выбрана")
        val ref = try { AiModelRef.api(profile, request.model) } catch (_: IllegalArgumentException) {
            return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "неверное имя модели ответа")
        }
        val permit = when (val r = admission.acquire("api:${sequence.incrementAndGet()}", ref)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return try { run() } finally { permit.release() }
    }

    companion object { private val sequence = AtomicLong() }
}

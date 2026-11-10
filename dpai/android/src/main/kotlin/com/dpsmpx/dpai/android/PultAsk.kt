package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.ChatMessage
import com.dpsmpx.dpai.providers.ChatRequest
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.providers.StopKind
import com.dpsmpx.dpai.providers.ToolChat
import com.dpsmpx.dpai.providers.ToolResult
import com.dpsmpx.dpai.providers.ToolSpec

/**
 * «Спросить Claude» пульта DPmetaApp (D-118; ТЗ DPmetaApp, F-21): вопрос уходит модели
 * API-профиля Anthropic с инструментами, которые даёт пульт. Оболочка `:shell` видит
 * мост простыми типами; ключ профиля остаётся в Keystore подсистемы. Что делают
 * инструменты, решает пульт: подсистема исполняет только переданную функцию.
 * Вызовы — не на главном потоке.
 */
object PultAsk {
    /** API-профиль Anthropic глазами пульта. */
    data class Profile(val id: String, val name: String, val model: String, val hasKey: Boolean)

    data class Tool(val name: String, val description: String, val schema: JsonObject)

    /** Реплика переписки: [user] — человек, иначе модель. */
    data class Turn(val user: Boolean, val text: String)

    /** Ответ: текст, вид остановки (`end`, `length`, `refusal`, `other`), её сырая причина и расход. */
    data class Answer(val text: String, val stop: String, val stopReason: String?, val inputTokens: Long?, val outputTokens: Long?)

    fun profiles(context: Context): List<Profile> {
        ModelsStore.init(context)
        return ModelsStore.profiles.value.filter { it.kind == ProviderKind.ANTHROPIC }
            .map { Profile(it.id.value, it.name, it.model, ModelsStore.hasKey(it.id)) }
    }

    /** Модели профиля — из Models API: id и имя для экрана. */
    fun models(context: Context, profileId: String): Outcome<List<Pair<String, String?>>> {
        ModelsStore.init(context)
        val p = ModelsStore.profiles.value.firstOrNull { it.id.value == profileId && it.kind == ProviderKind.ANTHROPIC }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "профиль не найден — удалён?", "ask")
        return when (val r = runCatching { ModelsStore.provider(p).models() }.getOrElse { fail(it) }) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.map { it.id to it.name })
        }
    }

    /**
     * Вопрос модели. [onCancel] получает функцию отмены — пульт зовёт её по «Стоп»;
     * [onText] — куски ответа; [runTool] — инструмент пульта: текст и признак ошибки.
     */
    fun ask(
        context: Context,
        profileId: String,
        model: String,
        effort: String?,
        system: String,
        turns: List<Turn>,
        tools: List<Tool>,
        onCancel: (() -> Unit) -> Unit,
        onText: (String) -> Unit,
        runTool: (String, JsonObject) -> Pair<String, Boolean>,
    ): Outcome<Answer> {
        ModelsStore.init(context)
        val p = ModelsStore.profiles.value.firstOrNull { it.id.value == profileId && it.kind == ProviderKind.ANTHROPIC }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "профиль не найден — удалён?", "ask")
        val provider = ModelsStore.provider(p) as? ToolChat
            ?: return Outcome.failure(DiagCode.UNSUPPORTED, "провайдер профиля не умеет инструменты", "ask")
        val cancel = Cancel()
        onCancel { cancel.cancel() }
        val request = ChatRequest(model, turns.map { ChatMessage(if (it.user) ChatRole.USER else ChatRole.ASSISTANT, it.text) }, system, p.maxOutputTokens, effort)
        val specs = tools.map { ToolSpec(it.name, it.description, it.schema) }
        val r = runCatching {
            provider.chatWithTools(request, specs, cancel, onText) { call ->
                val (text, isError) = runTool(call.name, call.input)
                ToolResult(text, isError)
            }
        }.getOrElse { fail(it) }
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(
                Answer(
                    r.value.text,
                    when (r.value.stop) {
                        StopKind.END -> "end"
                        StopKind.LENGTH -> "length"
                        StopKind.REFUSAL -> "refusal"
                        StopKind.OTHER -> "other"
                    },
                    r.value.stopReason,
                    (r.value.usage.input as? Measure.Known)?.value,
                    (r.value.usage.output as? Measure.Known)?.value,
                ),
            )
        }
    }

    private fun fail(e: Throwable): Outcome.Failure = Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${e.javaClass.simpleName}", "ask")
}

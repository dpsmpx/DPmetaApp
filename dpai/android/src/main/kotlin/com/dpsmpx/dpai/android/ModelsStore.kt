package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ApiProfileBook
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.CorrelationId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.providers.AIProvider
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.ChatBook
import com.dpsmpx.dpai.providers.ChatEntry
import com.dpsmpx.dpai.providers.ChatReply
import com.dpsmpx.dpai.providers.ChatRequest
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.providers.Conversation
import com.dpsmpx.dpai.providers.EntryStatus
import com.dpsmpx.dpai.providers.ModelInfo
import com.dpsmpx.dpai.providers.Providers
import com.dpsmpx.dpai.providers.StopKind
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Состояние вкладки «Модели» (D-98) на процесс: API-профили, переписка и
 * ответы, которые идут сейчас. Документы — в `app_models/` данных
 * приложения: вне диска приложения (D-96 отдаёт только `files/`) и вне
 * копий (`allowBackup="false"`). Ответ идёт в своей области корутин, а не
 * на экране: уход с экрана и поворот его не обрывают.
 */
object ModelsStore {
    private const val PROFILES = "api-profiles"
    private const val CHATS = "api-chats"

    /** Ответ, который идёт сейчас: текст до сих пор и отмена. */
    data class Running(val text: String, val cancel: Cancel)

    private lateinit var app: Context
    private var docs: DocumentStore? = null
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val profileFlow = MutableStateFlow<List<ApiProfile>>(emptyList())
    private val chatFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private val runningFlow = MutableStateFlow<Map<CorrelationId, Running>>(emptyMap())
    private val problemFlow = MutableStateFlow<String?>(null)
    private val readyFlow = MutableStateFlow(false)

    val profiles: StateFlow<List<ApiProfile>> = profileFlow
    val chats: StateFlow<List<Conversation>> = chatFlow
    val running: StateFlow<Map<CorrelationId, Running>> = runningFlow
    /** Документ не прочитан (D-87: не молча): что и почему; новая запись заменит его. */
    val problem: StateFlow<String?> = problemFlow
    val ready: StateFlow<Boolean> = readyFlow

    /** Чтение документов — с фонового потока, один раз на процесс. */
    @Synchronized
    fun init(context: Context) {
        if (docs != null) return
        app = context.applicationContext
        val d = DocumentStore(NioVfs(app.getDir("models", Context.MODE_PRIVATE).toPath()), "docs")
        docs = d
        profileFlow.value = read(d, PROFILES, "профили") { ApiProfileBook.fromJson(it) }.orEmpty()
        chatFlow.value = read(d, CHATS, "переписка") { ChatBook.fromJson(it) }.orEmpty()
        readyFlow.value = true
    }

    private fun <T> read(d: DocumentStore, key: String, what: String, parse: (JsonObject) -> Outcome<T>): T? {
        val stored = when (val r = d.read(key)) {
            is Outcome.Failure -> return null.also { problemFlow.value = "$what не прочитаны: ${r.errors.joinToString("; ") { it.message }}" }
            is Outcome.Success -> r.value ?: return null
        }
        return when (val p = parse(stored.content)) {
            is Outcome.Success -> p.value
            is Outcome.Failure -> null.also { problemFlow.value = "$what не прочитаны: ${p.errors.joinToString("; ") { "${it.message} (${it.subject})" }}" }
        }
    }

    fun newProfileId(): AccountId = ids.next(IdKinds.ACCOUNT)

    fun hasKey(id: AccountId): Boolean = ApiKeys.has(app, id)

    /** Сохранить профиль; [key] — новый ключ (`null` — оставить прежний). Итог — текст отказа или `null`. */
    @Synchronized
    fun saveProfile(p: ApiProfile, key: Secret?): String? {
        val d = docs ?: return "хранилище не готово"
        if (key != null && !key.isEmpty && !ApiKeys.put(app, p.id, key)) return "ключ не сохранён: Keystore отказал"
        val next = profileFlow.value.filter { it.id != p.id } + p
        return when (val w = d.write(PROFILES, ApiProfileBook.toJson(next))) {
            is Outcome.Failure -> "профиль не сохранён: ${w.errors.first().message}"
            is Outcome.Success -> null.also { profileFlow.value = next }
        }
    }

    /** Профиль, его ключ и его чаты. */
    @Synchronized
    fun deleteProfile(id: AccountId) {
        val d = docs ?: return
        val next = profileFlow.value.filter { it.id != id }
        if (d.write(PROFILES, ApiProfileBook.toJson(next)) is Outcome.Success) profileFlow.value = next
        ApiKeys.remove(app, id)
        chatFlow.value.filter { it.profile == id }.forEach { c -> runningFlow.value[c.id]?.cancel?.cancel() }
        writeChats(chatFlow.value.filter { it.profile != id })
    }

    /** Провайдер профиля с его ключом. */
    fun provider(p: ApiProfile, key: Secret? = null): AIProvider = Providers.of(p, key ?: ApiKeys.get(app, p.id))

    /** Новый разговор — в списке сразу, в файле — с первой репликой. */
    @Synchronized
    fun newChat(profile: ApiProfile): Conversation {
        val c = Conversation(ids.next(IdKinds.CORRELATION), profile.id, "", emptyList())
        chatFlow.value = chatFlow.value + c
        return c
    }

    @Synchronized
    fun deleteChat(id: CorrelationId) {
        runningFlow.value[id]?.cancel?.cancel()
        writeChats(chatFlow.value.filter { it.id != id })
    }

    @Synchronized
    private fun upsert(c: Conversation) {
        writeChats(chatFlow.value.filter { it.id != c.id } + c)
    }

    @Synchronized
    private fun writeChats(next: List<Conversation>) {
        chatFlow.value = next
        // Пустые разговоры не пишутся: «новый чат» без реплики не переживает перезапуск.
        docs?.write(CHATS, ChatBook.toJson(next.filter { it.entries.isNotEmpty() }))?.let { w ->
            if (w is Outcome.Failure) problemFlow.value = "переписка не сохранена: ${w.errors.first().message}"
        }
    }

    /**
     * Реплика человека и ответ модели потоком. Ответ, прерванный или
     * неудачный, остаётся в переписке с пометкой, но в следующий запрос не
     * уходит (`Conversation.context`). Итог — текст отказа до отправки или `null`.
     */
    fun send(conversationId: CorrelationId, text: String): String? {
        val conv = chatFlow.value.firstOrNull { it.id == conversationId } ?: return "разговор не найден"
        val profile = profileFlow.value.firstOrNull { it.id == conv.profile } ?: return "профиль разговора удалён"
        if (profile.model.isBlank()) return "в профиле не выбрана модель: «Изменить» → «Проверить ключ»"
        if (conversationId in runningFlow.value) return "ответ ещё идёт"
        val withUser = conv.plus(ChatEntry(ChatRole.USER, text)).let { if (it.title.isBlank()) it.copy(title = Conversation.title(text)) else it }
        upsert(withUser)
        val cancel = Cancel()
        runningFlow.update { it + (conversationId to Running("", cancel)) }
        scope.launch {
            val sb = StringBuilder()
            val request = ChatRequest(profile.model, withUser.context(), null, profile.maxOutputTokens)
            val r = runCatching {
                provider(profile).chat(request, cancel) { piece ->
                    sb.append(piece)
                    val now = sb.toString()
                    runningFlow.update { m -> if (conversationId in m) m + (conversationId to Running(now, cancel)) else m }
                }
            }.getOrElse { e -> Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${e.javaClass.simpleName}", profile.kind.key) }
            val entry = when (r) {
                is Outcome.Success -> reply(profile, r.value)
                is Outcome.Failure -> {
                    val e = r.errors.firstOrNull() ?: r.diagnostics.first()
                    if (e.code == DiagCode.CANCELLED_BY_USER) {
                        ChatEntry(ChatRole.ASSISTANT, sb.toString(), profile.model, EntryStatus.CANCELLED, "прервано")
                    } else {
                        ChatEntry(ChatRole.ASSISTANT, sb.toString(), profile.model, EntryStatus.FAILED, "${Words.reason(e.code.name)}: ${e.message}")
                    }
                }
            }
            synchronized(this@ModelsStore) {
                val latest = chatFlow.value.firstOrNull { it.id == conversationId }
                if (latest != null) upsert(latest.plus(entry))
            }
            runningFlow.update { it - conversationId }
        }
        return null
    }

    fun stop(conversationId: CorrelationId) {
        runningFlow.value[conversationId]?.cancel?.cancel()
    }

    private fun reply(profile: ApiProfile, r: ChatReply): ChatEntry {
        val (status, note) = when (r.stop) {
            StopKind.END -> EntryStatus.OK to null
            StopKind.LENGTH -> EntryStatus.LENGTH to "оборван по пределу длины ответа"
            StopKind.REFUSAL -> EntryStatus.REFUSED to "модель отказалась отвечать (${r.stopReason})"
            StopKind.OTHER -> EntryStatus.OK to "остановка: ${r.stopReason}"
        }
        return ChatEntry(
            ChatRole.ASSISTANT, r.text, profile.model, status, note,
            (r.usage.input as? Measure.Known)?.value, (r.usage.output as? Measure.Known)?.value,
        )
    }

    /** Модель по умолчанию из списка: у Anthropic — нынешняя Opus, иначе — первая. */
    fun suggest(models: List<ModelInfo>): String? = models.firstOrNull { it.id == "claude-opus-5-5" }?.id ?: models.firstOrNull()?.id
}

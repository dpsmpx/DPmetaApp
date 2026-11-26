package com.dpsmpx.dpai.android

import android.annotation.SuppressLint
import android.content.Context
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ApiProfileBook
import com.dpsmpx.dpai.accounts.Routine
import com.dpsmpx.dpai.accounts.RoutineBook
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.CorrelationId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.TargetBook
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.providers.AIProvider
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.ChatBook
import com.dpsmpx.dpai.providers.ChatEntry
import com.dpsmpx.dpai.providers.ChatHandoff
import com.dpsmpx.dpai.providers.ChatMessage
import com.dpsmpx.dpai.providers.ChatReply
import com.dpsmpx.dpai.providers.ChatRequest
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.providers.Conversation
import com.dpsmpx.dpai.providers.EntryStatus
import com.dpsmpx.dpai.providers.ModelInfo
import com.dpsmpx.dpai.providers.Providers
import com.dpsmpx.dpai.providers.RoutineClient
import com.dpsmpx.dpai.providers.RoutineFired
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
 * ответы, которые идут сейчас; и маршруты Claude Code (D-116) — тоже вид
 * аккаунта с ключом в Keystore. Документы — в `app_models/` данных
 * приложения: вне диска приложения (D-96 отдаёт только `files/`) и вне
 * копий (`allowBackup="false"`). Ответ идёт в своей области корутин, а не
 * на экране: уход с экрана и поворот его не обрывают.
 */
// init сохраняет только context.applicationContext; ссылки на Activity здесь нет.
@SuppressLint("StaticFieldLeak")
object ModelsStore {
    private const val PROFILES = "api-profiles"
    private const val CHATS = "api-chats"
    private const val CHAT_FULL_ACCESS = "dpai.chat-full-access"
    private const val ROUTINES = "routines"

    /** Ответ, который идёт сейчас: текст до сих пор и отмена. */
    data class Running(val text: String, val cancel: Cancel, val actions: List<String> = emptyList())

    private lateinit var app: Context
    private var docs: DocumentStore? = null
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val profileFlow = MutableStateFlow<List<ApiProfile>>(emptyList())
    private val chatFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private val routineFlow = MutableStateFlow<List<Routine>>(emptyList())
    private val runningFlow = MutableStateFlow<Map<CorrelationId, Running>>(emptyMap())
    private val problemFlow = MutableStateFlow<String?>(null)
    private val readyFlow = MutableStateFlow(false)

    val profiles: StateFlow<List<ApiProfile>> = profileFlow
    val chats: StateFlow<List<Conversation>> = chatFlow
    val routines: StateFlow<List<Routine>> = routineFlow
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
        routineFlow.value = read(d, ROUTINES, "маршруты") { RoutineBook.fromJson(it) }.orEmpty()
        val interrupted = chatFlow.value.map { c ->
            if (c.project != null && c.entries.lastOrNull()?.role == ChatRole.USER) c.plus(ChatEntry(ChatRole.ASSISTANT, app.getString(R.string.api_work_interrupted), status = EntryStatus.CANCELLED)) else c
        }
        if (interrupted != chatFlow.value) writeChats(interrupted)
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

    /** Admin-ключ API-профиля Anthropic (`DPmetaApp:D-44`) — только отчёты; хранится рядом с ключом API, под своим именем. */
    private fun adminName(id: AccountId) = "${id.value}:admin"

    fun hasAdminKey(id: AccountId): Boolean = ApiKeys.hasNamed(app, adminName(id))

    fun adminKey(id: AccountId): Secret? = ApiKeys.getNamed(app, adminName(id))

    /** Отчёты Admin API для экрана (F-19): расход, стоимость и лимиты скорости. */
    data class AdminView(
        val usage: List<com.dpsmpx.dpai.providers.UsageRow>,
        val cost: List<com.dpsmpx.dpai.providers.CostRow>,
        val limits: List<com.dpsmpx.dpai.providers.RateLimitRow>,
        val refreshedAt: String?,
    )

    /** F-18: Admin-ключ проверяется отчётом о расходе за последние сутки; итог — текст отказа или `null`. */
    fun checkAdminKey(key: Secret): String? = when (val r = runCatching { com.dpsmpx.dpai.providers.AdminClient().check(key, Clock.SYSTEM.now()) }.getOrElse { fail(it) }) {
        is Outcome.Success -> null
        is Outcome.Failure -> r.errors.first().let { "${Words.reason(it.code.name)}: ${it.message}" }
    }

    /**
     * Расход и стоимость за [days] полных суток по UTC (до вчера: корзины — сутки UTC,
     * сегодняшние данные ещё неполны) и лимиты скорости. Блокирующий — с фонового потока.
     */
    fun adminReport(id: AccountId, days: Int): Outcome<AdminView> {
        val key = adminKey(id)
        val end = java.time.Instant.ofEpochMilli(Clock.SYSTEM.now().epochMillis).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
        val from = com.dpsmpx.dpai.core.clock.Timestamp(end.minus(days.toLong(), java.time.temporal.ChronoUnit.DAYS).toEpochMilli())
        val to = com.dpsmpx.dpai.core.clock.Timestamp(end.toEpochMilli())
        return runCatching {
            val client = com.dpsmpx.dpai.providers.AdminClient()
            val usage = when (val r = client.usage(key, from, to)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val cost = when (val r = client.cost(key, from, to)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            when (val r = client.rateLimits(key)) {
                is Outcome.Failure -> r
                is Outcome.Success -> Outcome.success(AdminView(usage.rows, cost.rows, r.value, usage.refreshedAt ?: cost.refreshedAt))
            }
        }.getOrElse { fail(it) }
    }

    private fun fail(e: Throwable): Outcome.Failure = Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${e.javaClass.simpleName}", "admin")

    /** Сохранить Admin-ключ ([key] — новый) или стереть его ([remove]). Итог — текст отказа или `null`. */
    fun saveAdminKey(id: AccountId, key: Secret?, remove: Boolean): String? {
        if (remove) {
            ApiKeys.removeNamed(app, adminName(id))
            return null
        }
        if (key == null || key.isEmpty) return null
        return if (ApiKeys.putNamed(app, adminName(id), key)) null else "Admin-ключ не сохранён: Keystore отказал"
    }

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
        ApiKeys.removeNamed(app, adminName(id))
        chatFlow.value.filter { it.profile == id }.forEach { c -> revokeChatFullAccess(c.id) }
        writeChats(chatFlow.value.filter { it.profile != id })
    }

    /** Сохранить маршрут; [token] — новый токен (`null` — оставить прежний). Итог — текст отказа или `null`. */
    @Synchronized
    fun saveRoutine(r: Routine, token: Secret?): String? {
        val d = docs ?: return "хранилище не готово"
        if (token != null && !token.isEmpty && !ApiKeys.put(app, r.id, token)) return "токен не сохранён: Keystore отказал"
        val next = routineFlow.value.filter { it.id != r.id } + r
        return when (val w = d.write(ROUTINES, RoutineBook.toJson(next))) {
            is Outcome.Failure -> "маршрут не сохранён: ${w.errors.first().message}"
            is Outcome.Success -> null.also { routineFlow.value = next }
        }
    }

    /** Маршрут и его токен. */
    @Synchronized
    fun deleteRoutine(id: AccountId) {
        val d = docs ?: return
        val next = routineFlow.value.filter { it.id != id }
        if (d.write(ROUTINES, RoutineBook.toJson(next)) is Outcome.Success) routineFlow.value = next
        ApiKeys.remove(app, id)
    }

    /**
     * Запуск маршрута (D-116): блокирующий вызов — с фонового потока. Цену
     * и журнал действия держит вызывающий (пульт — `:core:actions`, D-07).
     */
    fun fireRoutine(id: AccountId, text: String): Outcome<RoutineFired> {
        val r = routineFlow.value.firstOrNull { it.id == id }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "маршрут не найден — удалён?", RoutineClient.SUBJECT)
        return runCatching { RoutineClient().fire(r, ApiKeys.get(app, r.id), text) }
            .getOrElse { e -> Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${e.javaClass.simpleName}", RoutineClient.SUBJECT) }
    }

    /** Провайдер профиля с его ключом. */
    fun provider(p: ApiProfile, key: Secret? = null): AIProvider = Providers.of(p, key ?: ApiKeys.get(app, p.id))

    /** Новый разговор — в списке сразу, в файле — с первой репликой; пустой разговор профиля — переиспользуется. */
    @Synchronized
    fun newChat(profile: ApiProfile): Conversation {
        chatFlow.value.firstOrNull { it.profile == profile.id && it.project == null && it.entries.isEmpty() }?.let { return it }
        val c = Conversation(ids.next(IdKinds.CORRELATION), profile.id, "", emptyList())
        chatFlow.value = chatFlow.value + c
        return c
    }

    /** Разговор выбранной пары профиль/проект; обычная переписка в него не переносится. */
    @Synchronized
    fun newProjectChat(profile: ApiProfile, project: com.dpsmpx.dpai.core.ids.ProjectId): Conversation {
        chatFlow.value.lastOrNull { it.profile == profile.id && it.project == project }?.let { return it }
        val c = Conversation(ids.next(IdKinds.CORRELATION), profile.id, "", emptyList(), project)
        chatFlow.value = chatFlow.value + c
        return c
    }

    /** Чат можно подключить к любому уже созданному проекту его target; выбора модели недостаточно для смены прав. */
    @Synchronized
    fun connectProject(chat: CorrelationId, project: com.dpsmpx.dpai.core.ids.ProjectId): String? {
        val previous = chatFlow.value.firstOrNull { it.id == chat } ?: return "разговор не найден"
        if (chat in runningFlow.value) return "дождитесь ответа или остановите ход"
        val controller = Controller.get(app.filesDir)
        val linked = controller.projects().projects.firstOrNull { it.project == project } ?: return "проект удалён"
        if (controller.targets().none { it.id == linked.target }) return "target проекта удалён"
        if (previous.project == project) return null
        revokeChatFullAccess(chat)
        if (chatFullAccess(chat)) return "сначала отключите полный доступ"
        return if (writeChats(chatFlow.value.filter { it.id != chat } + previous.copy(project = project))) null else "привязка проекта не сохранена"
    }

    /** Смена API-профиля продолжает тот же разговор и его контекст; пользовательский грант не переносится. */
    @Synchronized
    fun switchProfile(chat: CorrelationId, profile: AccountId): String? {
        val previous = chatFlow.value.firstOrNull { it.id == chat } ?: return "разговор не найден"
        if (chat in runningFlow.value) return "ответ ещё идёт"
        if (profileFlow.value.none { it.id == profile }) return "профиль удалён"
        if (previous.profile == profile) return null
        revokeChatFullAccess(chat)
        if (chatFullAccess(chat)) return "сначала отключите полный доступ"
        return if (writeChats(chatFlow.value.filter { it.id != chat } + previous.copy(profile = profile))) null else "смена исполнителя не сохранена"
    }

    /** Человек переносит только видимую переписку CLI в новый API-чат, без полномочий старой задачи. */
    @Synchronized
    fun importCliChat(profile: AccountId, project: com.dpsmpx.dpai.core.ids.ProjectId?, title: String,
                      messages: List<ChatMessage>, source: String): Conversation? {
        if (profileFlow.value.none { it.id == profile }) return null
        if (project != null && Controller.get(app.filesDir).projects().projects.none { it.project == project }) return null
        val imported = ChatHandoff.importMessages(messages).map { if (it.role == ChatRole.ASSISTANT) it.copy(model = source) else it }
        val c = Conversation(ids.next(IdKinds.CORRELATION), profile, title.take(60), imported, project)
        return c.takeIf { writeChats(chatFlow.value + it) }
    }

    /**
     * Подтверждение делегирования не содержится в запросе модели и не берётся из её текста.
     * В приватных preferences хранится только отпечаток связки проект/target (D-218).
     */
    private fun fullAccessPreferences() = app.getSharedPreferences(CHAT_FULL_ACCESS, Context.MODE_PRIVATE)

    private fun accessScope(project: ProjectEntry, target: TargetEntry): String =
        "v1:sha256:" + Hashing.sha256(listOf(project.project.value, project.root, project.worktreeRoot, project.integrationBranch,
            CanonicalJson.write(TargetBook.toJson(listOf(target)))).joinToString("\u0000")).hex

    private fun accessContext(chat: CorrelationId): Pair<ProjectEntry, TargetEntry>? {
        val linked = chatFlow.value.firstOrNull { it.id == chat }?.project ?: return null
        val controller = Controller.get(app.filesDir)
        val project = controller.projects().projects.firstOrNull { it.project == linked } ?: return null
        val target = controller.targets().firstOrNull { it.id == project.target } ?: return null
        return project to target
    }

    @Synchronized
    fun chatFullAccessScope(chat: CorrelationId): String? =
        accessContext(chat)?.let { (project, target) -> accessScope(project, target) }

    @Synchronized
    fun chatFullAccess(chat: CorrelationId): Boolean {
        val (project, target) = accessContext(chat) ?: return false
        return fullAccessPreferences().getString(chat.value, null) == accessScope(project, target)
    }

    /** Только оболочка UI вызывает после PIN/биометрии и точного имени; API-инструмента нет. */
    // В отличие от KTX edit здесь нужен результат commit(): отказ записи запрещает выдавать грант.
    @SuppressLint("ApplySharedPref", "UseKtx")
    @Synchronized
    fun enableChatFullAccess(chat: CorrelationId, typedName: String, expectedScope: String): Boolean {
        val (project, target) = accessContext(chat) ?: return false
        if (typedName != project.root.trimEnd('/').substringAfterLast('/') || expectedScope != accessScope(project, target)) return false
        val stored = fullAccessPreferences().edit().putString(chat.value, expectedScope).commit()
        if (!stored) return false
        if (!writeChats(chatFlow.value)) {
            fullAccessPreferences().edit().remove(chat.value).commit()
            return false
        }
        AppLogs.get(app).info("ai", "Полный доступ к проектному API-чату включён человеком")
        return true
    }

    /** Проверяется брокером и повторно при consume; сам агент изменить грант не может. */
    @Synchronized
    fun fullAccessFor(owner: String, project: ProjectEntry, target: TargetEntry): Boolean {
        if (!owner.startsWith("chat:")) return false
        val chat = IdKinds.CORRELATION.parse(owner.removePrefix("chat:")).valueOrNull() ?: return false
        if (chatFlow.value.none { it.id == chat && it.project == project.project }) return false
        // Сравниваем также с текущей конфигурацией контроллера: runtime мог
        // удерживать старый снимок проекта/target после их изменения в UI.
        val (currentProject, currentTarget) = accessContext(chat) ?: return false
        val requestedScope = accessScope(project, target)
        if (requestedScope != accessScope(currentProject, currentTarget)) return false
        return fullAccessPreferences().getString(chat.value, null) == requestedScope
    }

    // Результат commit() нужен для запрета повторной выдачи гранта после отказа сохранения.
    @SuppressLint("ApplySharedPref", "UseKtx")
    @Synchronized
    fun revokeChatFullAccess(chat: CorrelationId) {
        if (!fullAccessPreferences().edit().remove(chat.value).commit()) {
            problemFlow.value = "не удалось отозвать полный доступ: запись настроек отказала"
            return
        }
        runningFlow.value[chat]?.cancel?.cancel()
        writeChats(chatFlow.value)
        AppLogs.get(app).info("ai", "Полный доступ к проектному API-чату отозван человеком")
    }

    @Synchronized
    fun deleteChat(id: CorrelationId) {
        revokeChatFullAccess(id)
        writeChats(chatFlow.value.filter { it.id != id })
    }

    @Synchronized
    private fun upsert(c: Conversation) {
        writeChats(chatFlow.value.filter { it.id != c.id } + c)
    }

    @Synchronized
    private fun writeChats(next: List<Conversation>): Boolean {
        // Привязанный к проекту чат сохраняем до первой реплики; обычный пустой остаётся временным.
        val persistent = next.filter { it.entries.isNotEmpty() || it.project != null }
        val written = docs?.write(CHATS, ChatBook.toJson(persistent)) ?: return false
        if (written is Outcome.Failure) {
            problemFlow.value = "переписка не сохранена: ${written.errors.first().message}"
            return false
        }
        chatFlow.value = next
        return true
    }

    /**
     * Реплика человека и ответ модели потоком. Ответ, прерванный или
     * неудачный, остаётся в переписке с пометкой, но в следующий запрос не
     * уходит (`Conversation.context`). Итог — текст отказа до отправки или `null`.
     */
    @Synchronized
    fun send(conversationId: CorrelationId, text: String): String? {
        val conv = chatFlow.value.firstOrNull { it.id == conversationId } ?: return "разговор не найден"
        val profile = profileFlow.value.firstOrNull { it.id == conv.profile } ?: return "профиль разговора удалён"
        if (profile.model.isBlank()) return "в профиле не выбрана модель: «Изменить» → «Проверить ключ»"
        // Сервер телефона не запущен — на его порту может слушать кто угодно (T18): не спрашивать.
        if (LocalModel.isOwn(app, profile) && !LocalModel.usable(profile)) return "сервер llama.cpp на телефоне не запущен: вкладка Linux → «Запустить»"
        if (conversationId in runningFlow.value) return "ответ ещё идёт"
        val selectedProvider = provider(profile)
        val withUser = conv.plus(ChatEntry(ChatRole.USER, text)).let { if (it.title.isBlank()) it.copy(title = Conversation.title(text)) else it }
        upsert(withUser)
        val cancel = Cancel()
        runningFlow.update { it + (conversationId to Running("", cancel)) }
        scope.launch {
            val sb = StringBuilder()
            val onText: (String) -> Unit = { piece ->
                sb.append(piece)
                runningFlow.update { m -> m[conversationId]?.let { m + (conversationId to it.copy(text = sb.toString())) } ?: m }
            }
            var contextScope: String? = null
            var contextSources = emptyMap<String, String?>()
            var contextCommit: String? = null
            val r = runCatching {
                val project = withUser.project
                if (project == null) selectedProvider.chat(ChatRequest(profile.model, withUser.context(), null, profile.maxOutputTokens), cancel, onText)
                else when (val opened = ApiProjectRuntime.open(app, project, EnvironmentApprovalBroker.chat(conversationId.value))) {
                    is Outcome.Failure -> opened
                    is Outcome.Success -> {
                        val tools = selectedProvider as? com.dpsmpx.dpai.providers.ToolChat
                        if (tools == null) Outcome.failure(DiagCode.UNSUPPORTED, "провайдер не поддерживает проектные инструменты")
                        else opened.value.use { session ->
                            val prepared = when (val r = session.prepare(withUser, text, cancel)) { is Outcome.Failure -> return@use r; is Outcome.Success -> r.value }
                            synchronized(this@ModelsStore) {
                                val latest = chatFlow.value.firstOrNull { it.id == conversationId }
                                if (latest?.entries?.lastOrNull()?.role == ChatRole.USER) upsert(latest.copy(entries = latest.entries.dropLast(1) + latest.entries.last().copy(contextScope = session.contextScope, contextCommit = session.evidenceCommit)))
                            }
                            val answer = tools.chatWithTools(ChatRequest(profile.model, prepared.first, prepared.second, profile.maxOutputTokens, inputBudget = com.dpsmpx.dpai.providers.ContextInputBudget(session.contextProfile.maxChars, session.contextProfile.maxTokens), beforeSend = session::authorizeContext), session.tools, cancel, onText) { call ->
                            val result = session.run(call, cancel)
                            val path = ((call.input["path"] as? com.dpsmpx.dpai.core.serialization.JsonString)?.value ?: "").take(200)
                            val action = app.getString(if (result.isError) R.string.api_work_action_failed else R.string.api_work_action_ok, call.name, path)
                            runningFlow.update { m -> m[conversationId]?.let { m + (conversationId to it.copy(actions = (it.actions + action).takeLast(64))) } ?: m }
                            result
                            }
                            contextScope = session.evidenceScope
                            contextSources = session.evidenceSources
                            contextCommit = session.evidenceCommit
                            when (val valid = session.validateEvidence()) { is Outcome.Failure -> valid; is Outcome.Success -> answer }
                        }
                    }
                }
            }.getOrElse { e -> Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${e.javaClass.simpleName}", profile.kind.key) }
            val entry = when (r) {
                is Outcome.Success -> reply(profile, r.value).copy(contextScope = contextScope, contextSources = contextSources, contextCommit = contextCommit)
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
                val actions = runningFlow.value[conversationId]?.actions.orEmpty()
                val recorded = if (actions.isEmpty()) entry else entry.copy(text = entry.text + "\n\n" + app.getString(R.string.api_work_actions, actions.joinToString("\n")))
                if (latest != null) upsert(latest.plus(recorded))
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

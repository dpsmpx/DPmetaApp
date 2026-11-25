package com.dpsmpx.dpai.orchestrator.control

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.notifications.RequestAction
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Запрос к каналу: имена заголовков — в нижнем регистре; тело — как пришло, строкой UTF-8. */
data class ControlRequest(val method: String, val path: String, val headers: Map<String, String> = emptyMap(), val body: String? = null)

/**
 * Ответ канала: код HTTP, тело JSON и подпись (D-92) — над телом в
 * `CanonicalJson`, ровно так его и пишет оболочка; `null` — ответ без
 * подписи (сопряжение, отказ в подписи запроса).
 */
data class ControlResponse(val status: Int, val body: JsonObject, val signature: String? = null)

/**
 * Сопряжение `dpai` с приложением (D-47): код из шести цифр на
 * [codeTtl] — его показывает экран, после [MAX_ATTEMPTS] неверных попыток
 * действует общая пауза 30 секунд без уничтожения кода; верный код — постоянный токен (32 случайных байта, hex), он лежит в
 * файле приложения [TOKEN] через `Vfs` (D-24). Токен — секрет: в журнал и
 * ответы, кроме ответа на сопряжение, не попадает; запросы `dpai` и ответы
 * приложения подписываются им, сам он в сеть больше не уходит (D-92).
 */
class Pairing(
    private val vfs: Vfs,
    private val random: RandomSource,
    private val clock: Clock,
    private val codeTtl: Duration = 2.minutes,
    private val onRefusal: () -> Unit = {},
) {
    private data class Code(val value: String, val until: Timestamp)

    private var code: Code? = null
    private var attempts = 0
    private var blockedUntil: Timestamp? = null
    private var refusals = 0

    data class Status(val refusals: Int, val retryAfterMillis: Long)

    @Synchronized
    fun status(): Status = Status(refusals, ((blockedUntil?.epochMillis ?: 0) - clock.now().epochMillis).coerceAtLeast(0))

    @Synchronized
    fun newCode(): Pair<String, Timestamp> {
        val n = random.nextInt(1_000_000)
        val c = Code("%06d".format(n), clock.now() + codeTtl)
        code = c
        return c.value to c.until
    }

    @Synchronized
    fun pair(entered: String): Outcome<String> {
        val blocked = blockedUntil
        if (blocked != null && clock.now() < blocked) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "слишком много попыток сопряжения: подождите 30 секунд; код не уничтожен")
        }
        if (blocked != null) { blockedUntil = null; attempts = 0 }
        val c = code ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "кода нет: покажите код на экране приложения")
        if (clock.now() >= c.until) {
            code = null
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "срок кода истёк: покажите новый")
        }
        if (!same(entered.trim(), c.value)) {
            refusals++
            onRefusal()
            if (++attempts >= MAX_ATTEMPTS) blockedUntil = Timestamp(clock.now().epochMillis + 30_000)
            return Outcome.failure(DiagCode.VALUE_INVALID, "код не подходит")
        }
        code = null
        attempts = 0
        return Outcome.success(token())
    }

    /** Токен приложения: есть — прежний (несколько машин с одним токеном), нет — новый. */
    @Synchronized
    fun token(): String = stored() ?: run {
        val t = random.nextBytes(32).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        vfs.write(TOKEN, "$t\n".toByteArray())
        vfs.fsync(TOKEN)
        t
    }

    /** Сопряжён ли `dpai` (токен выдаётся только сопряжением) — для отметки в инструкции «Настройка». */
    fun paired(): Boolean = vfs.read(TOKEN) != null

    /** Отозвать: новый токен при следующем сопряжении, старые `dpai` — отказ. */
    @Synchronized
    fun revoke() {
        vfs.delete(TOKEN)
        seen.clear()
    }

    /** Одноразовые значения принятых запросов и когда приняты: повтор подписанного запроса — отказ (D-92). */
    private val seen = HashMap<String, Timestamp>()

    private fun stored(): String? = vfs.read(TOKEN)?.toString(Charsets.UTF_8)?.trim()?.takeIf(ControlAuth::validToken)

    /**
     * Запрос подписан токеном приложения (D-92): подпись верна, время —
     * в пределах [ControlAuth.WINDOW] от часов телефона, значение — впервые.
     * Время и значение смотрятся после подписи: без токена не узнать ничего
     * и не забить память значений. Итог — значение запроса: им подписывается ответ.
     */
    @Synchronized
    fun verify(method: String, path: String, headers: Map<String, String>, body: String?): Outcome<String> {
        val token = stored() ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "dpai не сопряжён")
        val time = headers[ControlAuth.TIME]?.toLongOrNull()
        val nonce = headers[ControlAuth.NONCE]?.takeIf(ControlAuth::validNonce)
        val signature = headers[ControlAuth.SIGNATURE]?.takeIf(ControlAuth::validSignature)
        if (time == null || nonce == null || signature == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "запрос без подписи: обновите dpai")
        if (!ControlAuth.same(signature, ControlAuth.sign(token, ControlAuth.requestText(method, path, time, nonce, body)))) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "подпись неверна")
        }
        val now = clock.now()
        if (kotlin.math.abs(now.epochMillis - time) > ControlAuth.WINDOW.inWholeMilliseconds) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "часы машины с dpai и телефона расходятся больше чем на ${ControlAuth.WINDOW.inWholeMinutes} мин")
        }
        // Значение живёт, пока запрос с ним ещё мог бы пройти по времени: два окна от приёма.
        seen.entries.removeIf { now - it.value > ControlAuth.WINDOW * 2 }
        if (seen.putIfAbsent(nonce, now) != null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "повтор запроса")
        return Outcome.success(nonce)
    }

    /** Подпись ответа на запрос со значением [nonce]; токена нет (отозван) — `null`, и `dpai` ответу не поверит. */
    fun signResponse(nonce: String, status: Int, body: String): String? =
        stored()?.let { ControlAuth.sign(it, ControlAuth.responseText(nonce, status, body)) }

    /** Сравнение за постоянное время: подбор по времени ответа не проходит. */
    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val TOKEN = "control-token"
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Управляющий канал приложения для `dpai` (D-47): разбор пути и тела,
 * ответ JSON. Сокет и HTTP — у оболочки (Android), здесь — смысл, чтобы
 * проверять тестами на JVM:
 *
 * - `POST /v1/pair` `{"code"}` — токен (без токена);
 * - `GET /v1/state` — задачи, сессии, ждущие запросы, подписки, настройки;
 * - `GET /v1/tasks/<id>/messages` — итоговые сообщения ходов задачи;
 * - `GET /v1/tasks/<id>/diff` — изменения задачи (`TaskDiff`);
 * - `GET /v1/tasks/<id>/reviews` — роли ревью задачи и итоги с находками (D-73);
 * - `POST /v1/search` `{"query"}` — глобальный поиск (ТЗ, §75): его даёт
 *   оболочка ([search]) — поиск живёт в модуле `ui`, а не здесь;
 * - `POST /v1/commands` — команда ([CommandCodec]) через очередь контура:
 *   [submit] — та же, что у экрана (D-42, п. 1); опасное — только с
 *   `confirmed` (ТЗ, §68); решающее — только подтверждением на телефоне
 *   (D-91): `202`, а запрос снова показывает [confirmOnPhone].
 *
 * Всё, кроме сопряжения, — подписанные запросы, и ответы подписаны (D-92).
 */
class ControlApi(
    private val pairing: Pairing,
    private val orchestrator: () -> Orchestrator?,
    /** Поиск по запросу — итог в JSON; `null` у функции или у итога — поиска нет (контура нет). */
    private val search: ((String) -> JsonObject?)? = null,
    /** Решающее из канала (D-91): показать на телефоне то, что человек подтвердит там; оболочка без экрана — ничего. */
    private val confirmOnPhone: (Command) -> Unit = {},
    /** Только подписанный общий канал; оболочка сохраняет подтверждения исполнителей (D-222). */
    private val automation: ((JsonObject) -> ControlResponse)? = null,
    private val submit: (Command) -> OperationOutcome,
) {
    /** Итог команды для канала: `OperationResult` в JSON или отказ до исполнения. */
    sealed interface OperationOutcome {
        data class Done(val result: com.dpsmpx.dpai.core.operation.OperationResult<*>) : OperationOutcome

        data class Refused(val diagnostics: List<Diagnostic>) : OperationOutcome
    }

    fun handle(request: ControlRequest): ControlResponse {
        val (method, path, headers, body) = request
        if (method == "POST" && path == "/v1/pair") return pair(body)
        val nonce = when (val v = pairing.verify(method, path, headers, body)) {
            is Outcome.Failure -> return error(401, DiagCode.OPERATION_NOT_ALLOWED, "${v.errors.first().message}: dpai control pair <код>")
            is Outcome.Success -> v.value
        }
        val r = route(method, path, body)
        return r.copy(signature = pairing.signResponse(nonce, r.status, CanonicalJson.write(r.body)))
    }

    private fun route(method: String, path: String, body: String?): ControlResponse {
        if (method == "POST" && path == "/v1/automation") {
            val input = parse(body) as? JsonObject ?: return error(400, DiagCode.VALUE_INVALID, "нужен JSON запроса автоматизации")
            return automation?.invoke(input) ?: error(503, DiagCode.UNSUPPORTED, "адаптер автоматизации не подключён")
        }
        val o = orchestrator() ?: return error(503, DiagCode.OPERATION_NOT_ALLOWED, "контроллер не запущен: в приложении нет target с каталогом Claude")
        return when {
            method == "GET" && path == "/v1/state" -> ControlResponse(200, ControlSnapshot.of(o))
            method == "GET" && path.startsWith("/v1/tasks/") && path.endsWith("/messages") -> {
                val id = IdKinds.TASK.parse(path.removePrefix("/v1/tasks/").removeSuffix("/messages")).valueOrNull()
                    ?: return error(400, DiagCode.ID_INVALID, "ожидался TaskId")
                ControlResponse(200, ControlSnapshot.messages(o, id))
            }
            method == "GET" && path.startsWith("/v1/tasks/") && path.endsWith("/diff") -> {
                val id = IdKinds.TASK.parse(path.removePrefix("/v1/tasks/").removeSuffix("/diff")).valueOrNull()
                    ?: return error(400, DiagCode.ID_INVALID, "ожидался TaskId")
                when (val d = o.taskDiff(id)) {
                    is Outcome.Success -> ControlResponse(200, d.value.toJson())
                    is Outcome.Failure -> ControlResponse(if (d.errors.any { it.code == DiagCode.ENTITY_NOT_FOUND }) 404 else 409, failure(d.diagnostics))
                }
            }
            method == "GET" && path.startsWith("/v1/tasks/") && path.endsWith("/reviews") -> {
                val id = IdKinds.TASK.parse(path.removePrefix("/v1/tasks/").removeSuffix("/reviews")).valueOrNull()
                    ?: return error(400, DiagCode.ID_INVALID, "ожидался TaskId")
                ControlSnapshot.reviews(o, id)?.let { ControlResponse(200, it) } ?: error(404, DiagCode.ENTITY_NOT_FOUND, "нет такой задачи")
            }
            method == "POST" && path == "/v1/search" -> searchResponse(body)
            method == "POST" && path == "/v1/commands" -> command(o, body)
            else -> error(404, DiagCode.UNSUPPORTED, "нет такого пути: $method $path")
        }
    }

    private fun pair(body: String?): ControlResponse {
        val code = (parse(body) as? JsonObject)?.get("code")?.let { (it as? JsonString)?.value } ?: return error(400, DiagCode.FIELD_MISSING, "нужно поле code")
        return when (val r = pairing.pair(code)) {
            is Outcome.Failure -> ControlResponse(403, failure(r.diagnostics))
            is Outcome.Success -> ControlResponse(200, JsonObject.of("token" to JsonString(r.value)))
        }
    }

    private fun command(o: Orchestrator, body: String?): ControlResponse {
        val json = parse(body) as? JsonObject ?: return error(400, DiagCode.VALUE_INVALID, "тело — объект JSON команды")
        val parsed = when (val p = CommandCodec.decode(json, BY)) {
            is Outcome.Failure -> return ControlResponse(400, failure(p.diagnostics))
            is Outcome.Success -> p.value
        }
        val c = parsed.command
        if ((CommandCodec.dangerous(c) || destructiveAnswer(o, c)) && !parsed.confirmed) {
            return error(409, DiagCode.APPROVAL_REQUIRED, "опасное действие: подтвердите явно (dpai … --yes)")
        }
        if (decisive(o, c)) {
            confirmOnPhone(c)
            return ControlResponse(
                202,
                JsonObject.of(
                    "status" to JsonString("CONFIRM_ON_PHONE"),
                    "diagnostics" to CommandCodec.diagnostics(listOf(Diagnostic.warning(DiagCode.APPROVAL_REQUIRED, "решающее — только на телефоне: запрос показан там снова, подтвердите его (D-91)"))),
                ),
            )
        }
        return when (val r = submit(c)) {
            is OperationOutcome.Refused -> ControlResponse(503, failure(r.diagnostics))
            is OperationOutcome.Done -> ControlResponse(200, CommandCodec.result(r.result))
        }
    }

    /** Поиск (ТЗ, §75): запрос — строка от 2 до [MAX_QUERY] знаков; поиска нет — 503 с причиной. */
    private fun searchResponse(body: String?): ControlResponse {
        val q = ((parse(body) as? JsonObject)?.get("query") as? JsonString)?.value?.trim()
            ?: return error(400, DiagCode.VALUE_INVALID, "тело — {\"query\": \"…\"}")
        if (q.length !in 2..MAX_QUERY) return error(400, DiagCode.VALUE_INVALID, "запрос — от 2 до $MAX_QUERY знаков")
        val f = search ?: return error(503, DiagCode.UNSUPPORTED, "поиск не подключён в этой оболочке")
        return f(q)?.let { ControlResponse(200, it) } ?: error(503, DiagCode.OPERATION_NOT_ALLOWED, "контроллер не запущен: искать негде")
    }

    /**
     * Решающее (D-91): «да» на слияние, на задачу без проверки, на ревью без
     * итога и на сверку после перезапуска, а также исключение задачи. Токен
     * канала лежит у того же пользователя, что и сессии Claude: канал не
     * отличит автора от сессии под prompt injection, телефон — отличит.
     */
    private fun decisive(o: Orchestrator, c: Command): Boolean = when (c) {
        is Command.Waive -> true
        // Аудит A-02: токен канала не доказывает, что команду дал человек.
        // Автопрогон по доверенному манифесту использует внутренний канал и сюда не попадает.
        // Передача задачи другому ИИ и автоматический переход у предела (D-165) — тоже только человек.
        is Command.AddTask, is Command.MoveTask, is Command.SetSessionLimit, is Command.SetExecutorSwitch, is Command.SetExecutor -> true
        is Command.SetPolicy -> o.tasks.tasks[c.task]?.policy?.let { previous ->
            (c.reviews != null && !c.reviews.containsAll(previous.reviews)) ||
                c.taskClass.ordinal < previous.taskClass.ordinal ||
                c.model != previous.model || c.effort != previous.effort ||
                c.maxBudgetUsdCents != previous.maxBudgetUsdCents
        } == true
        // Список решающих — у запроса (D-128): тот же, что у кнопок уведомления и окна.
        is Command.AnswerRequest -> RequestAction.parse(c.uri).valueOrNull()?.let { a ->
            o.requests.requests[a.request]?.decisive(a.option)
        } == true
        else -> false
    }

    /** Ответ опасным вариантом запроса (D-44) — тоже только с подтверждением. */
    private fun destructiveAnswer(o: Orchestrator, c: Command): Boolean {
        if (c !is Command.AnswerRequest) return false
        val a = RequestAction.parse(c.uri).valueOrNull() ?: return false
        return o.requests.requests[a.request]?.destructive(a.option) == true
    }

    private fun parse(body: String?): JsonValue? = body?.takeIf { it.isNotBlank() }?.let { JsonParser().parse(it).valueOrNull() }

    private fun failure(ds: List<Diagnostic>) = JsonObject.of("status" to JsonString("FAILED"), "diagnostics" to CommandCodec.diagnostics(ds))

    private fun error(status: Int, code: DiagCode, message: String) = ControlResponse(status, failure(listOf(Diagnostic.error(code, message))))

    companion object {
        /** Кто действует через `dpai` — владелец телефона, как с экрана. */
        const val BY = "автор (dpai)"

        /** Предел тела запроса: команда — строки, не файлы. */
        const val MAX_BODY = 64 * 1024

        /** Длиннее запрос поиска не бывает: это строка из поля ввода, а не текст. */
        const val MAX_QUERY = 200

        /**
         * Запрос пришёл от `dpai`, а не от страницы в браузере телефона
         * (аудит 1 ноября 2026, A-21): браузер шлёт `Origin` в каждом
         * запросе `POST` и при DNS rebinding называет в `Host` чужой домен,
         * указывающий на 127.0.0.1. `dpai` (`HttpClient` JDK) `Origin` не
         * шлёт, а `Host` у него — 127.0.0.1 или localhost (туннель SSH с ПК —
         * тоже на loopback). Нет заголовка `Host` — отказ: HTTP/1.1 его требует.
         */
        fun fromCli(host: String?, origin: String?): Boolean {
            if (origin != null) return false
            val h = host?.trim()?.lowercase() ?: return false
            val name = if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':')
            return name in LOOPBACK
        }

        private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")
    }
}

/** Снимок для `dpai` (D-47): то же, что видит экран, — данными. */
object ControlSnapshot {
    /** Расход (D-42, п. 4): ходов не было — поля нет; стоимость — строкой, без потери точности. */
    private fun usage(u: com.dpsmpx.dpai.sessions.UsageSummary): JsonObject? = if (u.empty) {
        null
    } else {
        JsonObject.of(
            "turns" to JsonNumber.of(u.turns), "unknownTurns" to JsonNumber.of(u.unknownTurns),
            "inputTokens" to JsonNumber.of(u.inputTokens), "outputTokens" to JsonNumber.of(u.outputTokens),
            "cacheReadTokens" to JsonNumber.of(u.cacheReadTokens), "costUsd" to JsonString(u.costUsd.toPlainString()),
            "costEstimated" to JsonBool(u.costEstimated),
        )
    }

    fun of(o: Orchestrator): JsonObject {
        val now = o.now()
        return JsonObject(
            linkedMapOf<String, JsonValue>(
                "tasks" to JsonArray(
                    o.tasks.tasks.values.sortedBy { it.createdAt.epochMillis }.map { t ->
                        JsonObject(
                            linkedMapOf<String, JsonValue>(
                                "id" to JsonString(t.id.value), "title" to JsonString(t.title), "state" to JsonString(t.state.name),
                                "priority" to JsonString(t.priority.name), "held" to JsonBool(t.held), "taskClass" to JsonString(t.policy.taskClass.name),
                                "archived" to JsonBool(t.archived),
                            ).apply {
                                t.policy.model?.let { put("model", JsonString(it)) }
                                // Роли ревью (D-73) и итог каждой: нет итога — роли нет в `verdicts`.
                                if (t.policy.reviews.isNotEmpty()) {
                                    put("reviews", JsonArray(t.policy.reviews.sorted().map { JsonString(it.name) }))
                                    put("verdicts", JsonObject(t.reviews.entries.sortedBy { it.key }.associateTo(linkedMapOf<String, JsonValue>()) { (r, v) -> r.name to JsonString(v.verdict.name) }))
                                }
                                // Причина — пока что-то не так; у выполненной это уже история (отмена до повтора и т. п.).
                                t.lastReason?.takeIf { t.state != com.dpsmpx.dpai.core.state.TaskState.DONE }?.let { put("reason", JsonString(it.name)) }
                                o.waitingReason(t.id)?.let { put("waiting", JsonString(it)) }
                                usage(com.dpsmpx.dpai.sessions.UsageSummary.of(o.sessions.sessions.values.filter { it.task == t.id }.map { it.usage }))?.let { put("usage", it) }
                            },
                        )
                    },
                ),
                "sessions" to JsonArray(
                    o.sessions.sessions.values.sortedBy { it.createdAt.epochMillis }.map { s ->
                        JsonObject(
                            linkedMapOf<String, JsonValue>(
                                "id" to JsonString(s.id.value), "task" to JsonString(s.task.value), "state" to JsonString(s.state.name),
                                "target" to JsonString(s.target.value), "slot" to JsonString(s.slot.value), "turn" to JsonNumber.of(s.turn),
                            ).apply {
                                usage(com.dpsmpx.dpai.sessions.UsageSummary.of(listOf(s.usage)))?.let { put("usage", it) }
                                s.review?.let { put("review", JsonString(it)) }
                            },
                        )
                    },
                ),
                "requests" to JsonArray(
                    o.pendingRequests().map { r ->
                        JsonObject(
                            linkedMapOf<String, JsonValue>(
                                "id" to JsonString(r.id.value), "kind" to JsonString(r.kind.name), "title" to JsonString(r.title), "body" to JsonString(r.body),
                                "options" to JsonArray(
                                    r.options.map { op ->
                                        JsonObject.of(
                                            "id" to JsonString(op.id), "label" to JsonString(op.label), "role" to JsonString(op.role.name),
                                            "needsText" to JsonBool(op.needsText), "uri" to JsonString(RequestAction(r.id, op.id, r.hash).toUri()),
                                        )
                                    },
                                ),
                            ).apply { r.task?.let { put("task", JsonString(it.value)) } },
                        )
                    },
                ),
                "subscriptions" to JsonArray(
                    o.accounts.subscriptions.values.sortedBy { it.id.value }.map { sub ->
                        JsonObject.of(
                            "id" to JsonString(sub.id.value), "label" to JsonString(sub.label), "nearPercent" to JsonNumber.of(sub.nearPercent),
                            "near" to JsonBool(sub.nearAt(now)), "rotation" to JsonBool(sub.rotationAllowed),
                            "windows" to JsonArray(
                                sub.windows.entries.sortedBy { it.key.name }.map { (w, ws) ->
                                    JsonObject(
                                        linkedMapOf<String, JsonValue>("window" to JsonString(w.name), "level" to JsonString(ws.level.name)).apply {
                                            ws.utilization?.let { put("percent", JsonNumber.of(Math.round(it * 100))) }
                                            ws.resetAt?.let { put("resetAt", JsonNumber.of(it.epochMillis)) }
                                        },
                                    )
                                },
                            ),
                        )
                    },
                ),
                "slots" to JsonArray(
                    o.accounts.slots.values.sortedBy { it.id.value }.map { sl ->
                        JsonObject.of(
                            "id" to JsonString(sl.id.value), "target" to JsonString(sl.targetId.value), "state" to JsonString(sl.state.name),
                            "disabled" to JsonBool(sl.disabled),
                        )
                    },
                ),
                "control" to JsonObject(
                    linkedMapOf<String, JsonValue>(
                        "disabledTargets" to JsonArray(o.control.disabledTargets.map { JsonString(it.value) }),
                    ).apply { o.control.maxSessions?.let { put("maxSessions", JsonNumber.of(it)) } },
                ),
            ),
        )
    }

    fun messages(o: Orchestrator, task: com.dpsmpx.dpai.core.ids.TaskId): JsonObject = JsonObject.of(
        "messages" to JsonArray(
            o.turnMessages(task).map { m ->
                JsonObject(
                    linkedMapOf<String, JsonValue>(
                        "session" to JsonString(m.session.value), "turn" to JsonNumber.of(m.turn), "at" to JsonNumber.of(m.at.epochMillis),
                        "status" to JsonString(m.status), "text" to JsonString(m.text),
                    ).apply { m.model?.let { put("model", JsonString(it)) } },
                )
            },
        ),
    )

    /** Роли ревью задачи и итоги с находками (D-73); `null` — нет такой задачи. */
    fun reviews(o: Orchestrator, task: com.dpsmpx.dpai.core.ids.TaskId): JsonObject? {
        val t = o.tasks.tasks[task] ?: return null
        return JsonObject.of(
            "roles" to JsonArray(t.policy.reviews.sorted().map { JsonString(it.name) }),
            "reviews" to JsonArray(
                t.reviews.values.sortedBy { it.role }.map { r ->
                    JsonObject(
                        linkedMapOf<String, JsonValue>(
                            "role" to JsonString(r.role.name), "verdict" to JsonString(r.verdict.name), "attempt" to JsonNumber.of(r.attempt),
                            "findings" to JsonArray(
                                r.findings.map { f ->
                                    JsonObject(
                                        linkedMapOf<String, JsonValue>("severity" to JsonString(f.severity.name), "title" to JsonString(f.title)).apply {
                                            f.file?.let { put("file", JsonString(it)) }
                                            f.line?.let { put("line", JsonNumber.of(it)) }
                                            f.detail?.let { put("detail", JsonString(it)) }
                                        },
                                    )
                                },
                            ),
                            "omitted" to JsonNumber.of(r.omitted),
                        ).apply {
                            r.session?.let { put("session", JsonString(it.value)) }
                            r.by?.let { put("by", JsonString(it)) }
                            r.reason?.let { put("reason", JsonString(it)) }
                        },
                    )
                },
            ),
        )
    }
}

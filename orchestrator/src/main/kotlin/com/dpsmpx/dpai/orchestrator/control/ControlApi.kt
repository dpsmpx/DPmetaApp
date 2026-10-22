package com.dpsmpx.dpai.orchestrator.control

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
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

/** Ответ канала: код HTTP и тело JSON. */
data class ControlResponse(val status: Int, val body: JsonObject)

/**
 * Сопряжение `dpai` с приложением (D-47): код из шести цифр на
 * [codeTtl] — его показывает экран, не больше [MAX_ATTEMPTS] попыток на
 * код; верный код — постоянный токен (32 случайных байта, hex), он лежит в
 * файле приложения [TOKEN] через `Vfs` (D-24). Токен — секрет: в журнал и
 * ответы, кроме ответа на сопряжение, не попадает.
 */
class Pairing(private val vfs: Vfs, private val random: RandomSource, private val clock: Clock, private val codeTtl: Duration = 2.minutes) {
    private data class Code(val value: String, val until: Timestamp, var attempts: Int)

    private var code: Code? = null

    @Synchronized
    fun newCode(): Pair<String, Timestamp> {
        val n = random.nextInt(1_000_000)
        val c = Code("%06d".format(n), clock.now() + codeTtl, 0)
        code = c
        return c.value to c.until
    }

    @Synchronized
    fun pair(entered: String): Outcome<String> {
        val c = code ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "кода нет: покажите код на экране приложения")
        if (clock.now() >= c.until) {
            code = null
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "срок кода истёк: покажите новый")
        }
        if (++c.attempts > MAX_ATTEMPTS) {
            code = null
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "слишком много попыток: покажите новый код")
        }
        if (!same(entered.trim(), c.value)) return Outcome.failure(DiagCode.VALUE_INVALID, "код не подходит")
        code = null
        return Outcome.success(token())
    }

    /** Токен приложения: есть — прежний (несколько машин с одним токеном), нет — новый. */
    @Synchronized
    fun token(): String = vfs.read(TOKEN)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.length == 64 } ?: run {
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
    }

    fun authorized(header: String?): Boolean {
        val given = header?.removePrefix("Bearer ")?.trim() ?: return false
        val have = vfs.read(TOKEN)?.toString(Charsets.UTF_8)?.trim() ?: return false
        return have.length == 64 && same(given, have)
    }

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
 * - `GET /v1/tasks/<id>/reviews` — роли ревью задачи и итоги с находками (D-59);
 * - `POST /v1/commands` — команда ([CommandCodec]) через очередь контура:
 *   [submit] — та же, что у экрана (D-42, п. 1); опасное — только с
 *   `confirmed` (ТЗ, §68).
 */
class ControlApi(
    private val pairing: Pairing,
    private val orchestrator: () -> Orchestrator?,
    private val submit: (Command) -> OperationOutcome,
) {
    /** Итог команды для канала: `OperationResult` в JSON или отказ до исполнения. */
    sealed interface OperationOutcome {
        data class Done(val result: com.dpsmpx.dpai.core.operation.OperationResult<*>) : OperationOutcome

        data class Refused(val diagnostics: List<Diagnostic>) : OperationOutcome
    }

    fun handle(method: String, path: String, authorization: String?, body: String?): ControlResponse {
        if (method == "POST" && path == "/v1/pair") return pair(body)
        if (!pairing.authorized(authorization)) return error(401, DiagCode.OPERATION_NOT_ALLOWED, "нет токена или он неверен: dpai control pair <код>")
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
        return when (val r = submit(c)) {
            is OperationOutcome.Refused -> ControlResponse(503, failure(r.diagnostics))
            is OperationOutcome.Done -> ControlResponse(200, CommandCodec.result(r.result))
        }
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
                                // Роли ревью (D-59) и итог каждой: нет итога — роли нет в `verdicts`.
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
                                "target" to JsonString(s.target.value), "turn" to JsonNumber.of(s.turn),
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
                            "near" to JsonBool(sub.nearAt(now)),
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

    /** Роли ревью задачи и итоги с находками (D-59); `null` — нет такой задачи. */
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

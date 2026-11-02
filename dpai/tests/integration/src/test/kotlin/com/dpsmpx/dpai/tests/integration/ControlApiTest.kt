package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.notifications.RequestAction
import com.dpsmpx.dpai.notifications.RequestState
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.control.CommandCodec
import com.dpsmpx.dpai.orchestrator.control.ControlApi
import com.dpsmpx.dpai.orchestrator.control.ControlAuth
import com.dpsmpx.dpai.orchestrator.control.ControlRequest
import com.dpsmpx.dpai.orchestrator.control.ControlResponse
import com.dpsmpx.dpai.orchestrator.control.Pairing
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Управляющий канал `dpai` (D-47): сопряжение по коду, токен, снимок
 * состояния, команды через очередь контура, опасное — только с
 * подтверждением; кодек команд туда и обратно.
 */
class ControlApiTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(442))
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(443)))
    private val targets = List(1) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
    private val accounts: AccountsState = run {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, targets[0].id, "/c/0", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
    }
    private val project = ids.next(IdKinds.PROJECT)

    private lateinit var runner: MockRunner

    /** [profiled] — с профилем проверки; без него «готово» хода — COMPLETION_UNVERIFIED и запрос человеку (D-23). */
    private fun boot(profiled: Boolean = true, scenario: MockScenario = MockScenario.SLOW_OUTPUT): Orchestrator {
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "journal.log") as Outcome.Success).value
        runner = MockRunner.create(clock, RandomSource.seeded(1), host) { _, _ -> scenario }
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok"))))).takeIf { profiled }
        return Orchestrator(
            clock, ids, RandomSource.seeded(444), journal, runner, ProjectConfig(project, null, dir.toString(), profile), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts, documents = DocumentStore(vfs, "docs"),
        )
    }

    private fun api(o: Orchestrator, pairing: Pairing, confirmOnPhone: (Command) -> Unit = {}) = ControlApi(pairing, { o }, confirmOnPhone = confirmOnPhone) { c ->
        ControlApi.OperationOutcome.Done(o.submit(c).also { o.runCommands() }.get())
    }

    private val nonces = RandomSource.seeded(4471)

    /** Запрос, как его шлёт `dpai` (D-92): с токеном — подписанный, без — только сопряжение. */
    private fun call(api: ControlApi, method: String, path: String, token: String?, body: String?): ControlResponse {
        val headers = token?.let { ControlAuth.requestHeaders(it, method, path, body, clock.now().epochMillis, ControlAuth.newNonce(nonces)) }.orEmpty()
        return api.handle(ControlRequest(method, path, headers, body))
    }

    private fun str(j: JsonObject, k: String) = (j[k] as JsonString).value

    @Test
    fun pairingStateCommandsAndConfirmation() {
        val o = boot()
        val pairing = Pairing(NioVfs(dir.resolve("app").also { it.toFile().mkdirs() }), RandomSource.seeded(9), clock)
        val api = api(o, pairing)
        assertEquals(401, call(api, "GET", "/v1/state", null, null).status, "без токена — отказ")
        assertEquals(403, call(api, "POST", "/v1/pair", null, """{"code":"123456"}""").status, "кода нет")
        val (code, _) = pairing.newCode()
        assertEquals(403, call(api, "POST", "/v1/pair", null, """{"code":"000000x"}""").status)
        val ok = call(api, "POST", "/v1/pair", null, """{"code":"$code"}""")
        assertEquals(200, ok.status, ok.body.toString())
        val token = str(ok.body, "token")
        assertEquals(403, call(api, "POST", "/v1/pair", null, """{"code":"$code"}""").status, "код одноразовый")
        assertEquals(401, call(api, "GET", "/v1/state", "0".repeat(64), null).status, "чужой токен")

        val add = call(api, "POST", "/v1/commands", token, CanonicalJson.write(CommandCodec.encode(Command.AddTask("из dpai", "цель", key = "cli-key-00001"))))
        assertEquals(200, add.status, add.body.toString())
        val task = IdKinds.TASK.parse(str(add.body, "value")).valueOrNull()!!
        // Зависимости и область — при создании (D-42, п. 1).
        val after = call(api, "POST", "/v1/commands", token, CanonicalJson.write(CommandCodec.encode(Command.AddTask("после", "цель 2", dependencies = setOf(task), scope = setOf("src/main.kt"), key = "cli-key-00002"))))
        assertEquals(200, after.status, after.body.toString())
        val second = o.tasks.tasks.getValue(IdKinds.TASK.parse(str(after.body, "value")).valueOrNull()!!)
        assertEquals(setOf(task), second.dependencies)
        assertEquals(setOf("src/main.kt"), second.scope)
        val bad = call(api, "POST", "/v1/commands", token, """{"command":"task.add","title":"x","objective":"y","dependencies":["не-id"]}""")
        assertEquals(400, bad.status, "зависимость — только TaskId")
        val state = call(api, "GET", "/v1/state", token, null)
        assertTrue("из dpai" in CanonicalJson.write(state.body))
        assertFalse(""""usage"""" in CanonicalJson.write(state.body), "ходов не было — расхода нет, а не нули")
        assertEquals(400, call(api, "POST", "/v1/commands", token, """{"command":"task.hold","task":"${task.value}","лишнее":1}""").status, "лишнее поле — отказ")
        val cancel = CanonicalJson.write(CommandCodec.encode(Command.Cancel(task)))
        assertEquals(409, call(api, "POST", "/v1/commands", token, cancel).status, "опасное без --yes")
        val done = call(api, "POST", "/v1/commands", token, CanonicalJson.write(CommandCodec.encode(Command.Cancel(task), confirmed = true)))
        assertEquals(200, done.status, done.body.toString())
        assertEquals(TaskState.CANCELLED, o.tasks.tasks.getValue(task).state)
        assertEquals(200, call(api, "GET", "/v1/tasks/${task.value}/messages", token, null).status)
        // Изменения задачи (D-42): у проекта без репозитория — отказ с причиной, неверный id — 400.
        val diff = call(api, "GET", "/v1/tasks/${task.value}/diff", token, null)
        assertEquals(409, diff.status, diff.body.toString())
        assertTrue("репозитория" in CanonicalJson.write(diff.body))
        assertEquals(400, call(api, "GET", "/v1/tasks/не-id/diff", token, null).status)

        // Лимит попыток: шестая — отказ и для верного кода.
        val (code2, _) = pairing.newCode()
        repeat(Pairing.MAX_ATTEMPTS) { call(api, "POST", "/v1/pair", null, """{"code":"999999"}""") }
        assertEquals(403, call(api, "POST", "/v1/pair", null, """{"code":"$code2"}""").status)
        // Срок кода.
        val (code3, _) = pairing.newCode()
        clock.advance(3.minutes)
        assertEquals(403, call(api, "POST", "/v1/pair", null, """{"code":"$code3"}""").status)
    }

    /** Поиск (ТЗ, §75) для `dpai`: его даёт оболочка; запрос проверяется здесь, нет поиска или контура — 503 с причиной. */
    @Test
    fun searchIsTheShellsAndQueryIsChecked() {
        val o = boot()
        val pairing = Pairing(NioVfs(dir.resolve("app").also { it.toFile().mkdirs() }), RandomSource.seeded(10), clock)
        val (code, _) = pairing.newCode()
        val bare = api(o, pairing)
        val token = str(call(bare, "POST", "/v1/pair", null, """{"code":"$code"}""").body, "token")
        assertEquals(503, call(bare, "POST", "/v1/search", token, """{"query":"задача"}""").status, "поиск не подключён")
        val asked = mutableListOf<String>()
        val api = ControlApi(pairing, { o }, { q -> asked += q; if (q == "нет контура") null else JsonObject.of("hits" to JsonString(q)) }) { c ->
            ControlApi.OperationOutcome.Done(o.submit(c).also { o.runCommands() }.get())
        }
        assertEquals(401, call(api, "POST", "/v1/search", null, """{"query":"задача"}""").status, "без токена — отказ")
        val ok = call(api, "POST", "/v1/search", token, """{"query":"  задача  "}""")
        assertEquals(200, ok.status)
        assertEquals("задача", str(ok.body, "hits"))
        assertEquals(400, call(api, "POST", "/v1/search", token, """{"query":"я"}""").status, "короче двух знаков")
        assertEquals(400, call(api, "POST", "/v1/search", token, """{"query":"${"x".repeat(ControlApi.MAX_QUERY + 1)}"}""").status)
        assertEquals(400, call(api, "POST", "/v1/search", token, """{"q":"задача"}""").status, "нет поля query")
        assertEquals(503, call(api, "POST", "/v1/search", token, """{"query":"нет контура"}""").status)
        assertEquals(listOf("задача", "нет контура"), asked, "до поиска доходит только проверенный запрос")
    }

    /**
     * D-91: «да» на решающий запрос и исключение из канала не применяются —
     * `202`, запрос снова показан на телефоне, задача не тронута; «решу
     * позже» и прочее — сразу, как прежде.
     */
    @Test
    fun decisiveFromChannelWaitsForPhone() {
        val o = boot(profiled = false, scenario = MockScenario.SUCCESS)
        o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
        val task = o.addTask("задача", "цель").valueOrNull()!!
        repeat(4) {
            o.tick()
            for (s in host.runIds()) while (runner.advance(s)) continue
            o.tick()
            clock.advance(1.minutes)
        }
        assertEquals(TaskState.COMPLETION_UNVERIFIED, o.tasks.tasks.getValue(task).state)
        val r = o.pendingRequests().single { it.task == task }
        assertEquals("task.unverified", r.purpose)

        val shown = mutableListOf<Command>()
        val pairing = Pairing(NioVfs(dir.resolve("app").also { it.toFile().mkdirs() }), RandomSource.seeded(11), clock)
        val api = api(o, pairing) { shown += it }
        val (code, _) = pairing.newCode()
        val token = str(call(api, "POST", "/v1/pair", null, """{"code":"$code"}""").body, "token")
        fun answer(option: String, confirmed: Boolean = false) = call(
            api, "POST", "/v1/commands", token,
            CanonicalJson.write(CommandCodec.encode(Command.AnswerRequest(RequestAction(r.id, option, r.hash).toUri(), null, ControlApi.BY, "cli"), confirmed)),
        )

        assertEquals(409, answer("waive").status, "опасное — сперва --yes, как прежде")
        val waive = answer("waive", confirmed = true)
        assertEquals(202, waive.status, waive.body.toString())
        assertEquals("CONFIRM_ON_PHONE", str(waive.body, "status"))
        assertEquals(202, answer("verify").status, "«проверить снова» — тоже «да» на решающий запрос")
        val byCommand = call(api, "POST", "/v1/commands", token, CanonicalJson.write(CommandCodec.encode(Command.Waive(task, "проверил", ControlApi.BY), confirmed = true)))
        assertEquals(202, byCommand.status, "исключение командой — тоже через телефон")
        assertEquals(TaskState.COMPLETION_UNVERIFIED, o.tasks.tasks.getValue(task).state, "задача не тронута")
        assertTrue(o.requests.requests.getValue(r.id).state !in setOf(RequestState.ANSWERED, RequestState.FAILED), "запрос ждёт ответа на телефоне")
        assertEquals(3, shown.size, "каждый раз — показ на телефоне")
        assertTrue(shown.last() is Command.Waive)

        val later = answer("later")
        assertEquals(200, later.status, "«решу позже» — сразу: ${later.body}")
        assertEquals(RequestState.ANSWERED, o.requests.requests.getValue(r.id).state)
        assertEquals(3, shown.size)
    }

    /** A-21: канал принимает `dpai`, а не страницу браузера и не DNS rebinding. */
    @Test
    fun onlyLoopbackWithoutOriginIsAccepted() {
        assertTrue(ControlApi.fromCli("127.0.0.1:47777", null))
        assertTrue(ControlApi.fromCli("localhost:9000", null), "туннель SSH с ПК — тоже loopback")
        assertTrue(ControlApi.fromCli("[::1]:47777", null))
        assertFalse(ControlApi.fromCli("127.0.0.1:47777", "https://evil.example"), "страница браузера шлёт Origin")
        assertFalse(ControlApi.fromCli("evil.example:47777", null), "DNS rebinding: чужое имя в Host")
        assertFalse(ControlApi.fromCli("127.0.0.1.evil.example", null))
        assertFalse(ControlApi.fromCli(null, null), "без Host — отказ")
    }

    @Test
    fun codecRoundTripForEveryExternalCommand() {
        val t = ids.next(IdKinds.TASK)
        val s = ids.next(IdKinds.SESSION)
        val commands = listOf(
            Command.AnswerRequest("dpai://answer/x/y?hash=sha256:00", "текст", ControlApi.BY, "cli"),
            Command.Probe(com.dpsmpx.dpai.notifications.RequestKind.CHOICE, 5.minutes, "k-00000001"),
            Command.AddTask("t", "o", com.dpsmpx.dpai.tasks.Priority.HIGH, com.dpsmpx.dpai.tasks.TaskPolicy(taskClass = com.dpsmpx.dpai.tasks.TaskClass.TRIVIAL, model = "haiku", effort = "low", maxBudgetUsdCents = 50), key = "k-00000002"),
            Command.SetPriority(t, com.dpsmpx.dpai.tasks.Priority.LOW), Command.SetPolicy(t, com.dpsmpx.dpai.tasks.TaskClass.ARCHITECTURE, "opus", null, 100),
            Command.Hold(t), Command.Resume(t), Command.Cancel(t), Command.Retry(t, "ответ"), Command.Verify(t), Command.Waive(t, "проверил", ControlApi.BY),
            Command.StopSession(s), Command.SendToSession(s, "ещё", "sonnet", "high"), Command.SendToSession(s, null, fresh = true),
            Command.SetNearPercent(ids.next(IdKinds.SUBSCRIPTION), 90), Command.SetTargetEnabled(ids.next(IdKinds.TARGET), false),
            Command.SetSlotEnabled(ids.next(IdKinds.ACCOUNT), true), Command.SetSessionLimit(4), Command.SetSessionLimit(null),
            Command.Archive(t, true), Command.ArchiveFinished("k-00000003"),
            Command.AddTask("с областью", "o", scope = setOf("src/a", "docs"), dependencies = setOf(t, ids.next(IdKinds.TASK)), key = "k-00000004"),
            Command.SetScope(t, setOf("src/main")), Command.SetScope(t, emptySet()),
            Command.SetDependencies(t, setOf(ids.next(IdKinds.TASK))), Command.SetDependencies(t, emptySet()),
            // Роли ревью (D-73): в новой задаче; в политике — заданы, сняты или «как были» (`null`).
            Command.AddTask("с ревью", "o", policy = com.dpsmpx.dpai.tasks.TaskPolicy(reviews = setOf(ReviewRole.SECURITY, ReviewRole.CODE)), key = "k-00000005"),
            Command.SetPolicy(t, com.dpsmpx.dpai.tasks.TaskClass.ARCHITECTURE, null, null, null, setOf(ReviewRole.CODE)),
            Command.SetPolicy(t, com.dpsmpx.dpai.tasks.TaskClass.ARCHITECTURE, null, null, null, emptySet()),
            // «Запустить первой» и «Перенести» (D-77).
            Command.RunFirst(t, "k-00000006"), Command.Reassign(s, "k-00000007"),
        )
        for (c in commands) {
            val back = CommandCodec.decode(CommandCodec.encode(c), ControlApi.BY)
            assertEquals(c, back.valueOrNull()?.command, "$c: ${back.diagnostics}")
        }
        // Не строка в области и не TaskId в зависимостях — отказ с местом, а не пропуск.
        val badScope = com.dpsmpx.dpai.core.serialization.JsonParser().parse("""{"command":"task.scope","task":"${t.value}","scope":[1]}""").valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject
        assertTrue(CommandCodec.decode(badScope, ControlApi.BY).diagnostics.any { it.subject == "$.scope[0]" })
        val badDep = com.dpsmpx.dpai.core.serialization.JsonParser().parse("""{"command":"task.dependencies","task":"${t.value}","dependsOn":["x"]}""").valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject
        assertTrue(CommandCodec.decode(badDep, ControlApi.BY) is com.dpsmpx.dpai.core.result.Outcome.Failure)
        val badRole = com.dpsmpx.dpai.core.serialization.JsonParser().parse("""{"command":"task.policy","task":"${t.value}","taskClass":"ARCHITECTURE","reviews":["CODE","STYLE"]}""").valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject
        assertTrue(CommandCodec.decode(badRole, ControlApi.BY).diagnostics.any { it.subject == "$.reviews[1]" }, "неизвестная роль — отказ с местом")
        val trivial = com.dpsmpx.dpai.core.serialization.JsonParser().parse("""{"command":"task.add","title":"t","objective":"o","taskClass":"TRIVIAL","reviews":["CODE"]}""").valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject
        assertTrue(CommandCodec.decode(trivial, ControlApi.BY) is com.dpsmpx.dpai.core.result.Outcome.Failure, "у TRIVIAL ревью нет (D-73)")
    }
}

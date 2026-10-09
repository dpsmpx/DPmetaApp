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
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.control.CommandCodec
import com.dpsmpx.dpai.orchestrator.control.ControlApi
import com.dpsmpx.dpai.orchestrator.control.Pairing
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun boot(): Orchestrator {
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "journal.log") as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(1), host) { _, _ -> MockScenario.SLOW_OUTPUT }
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        return Orchestrator(
            clock, ids, RandomSource.seeded(444), journal, runner, ProjectConfig(project, null, dir.toString(), profile), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts, documents = DocumentStore(vfs, "docs"),
        )
    }

    private fun api(o: Orchestrator, pairing: Pairing) = ControlApi(pairing, { o }) { c ->
        ControlApi.OperationOutcome.Done(o.submit(c).also { o.runCommands() }.get())
    }

    private fun str(j: JsonObject, k: String) = (j[k] as JsonString).value

    @Test
    fun pairingStateCommandsAndConfirmation() {
        val o = boot()
        val pairing = Pairing(NioVfs(dir.resolve("app").also { it.toFile().mkdirs() }), RandomSource.seeded(9), clock)
        val api = api(o, pairing)
        assertEquals(401, api.handle("GET", "/v1/state", null, null).status, "без токена — отказ")
        assertEquals(403, api.handle("POST", "/v1/pair", null, """{"code":"123456"}""").status, "кода нет")
        val (code, _) = pairing.newCode()
        assertEquals(403, api.handle("POST", "/v1/pair", null, """{"code":"000000x"}""").status)
        val ok = api.handle("POST", "/v1/pair", null, """{"code":"$code"}""")
        assertEquals(200, ok.status, ok.body.toString())
        val token = "Bearer " + str(ok.body, "token")
        assertEquals(403, api.handle("POST", "/v1/pair", null, """{"code":"$code"}""").status, "код одноразовый")
        assertEquals(401, api.handle("GET", "/v1/state", "Bearer " + "0".repeat(64), null).status, "чужой токен")

        val add = api.handle("POST", "/v1/commands", token, CanonicalJson.write(CommandCodec.encode(Command.AddTask("из dpai", "цель", key = "cli-key-00001"))))
        assertEquals(200, add.status, add.body.toString())
        val task = IdKinds.TASK.parse(str(add.body, "value")).valueOrNull()!!
        val state = api.handle("GET", "/v1/state", token, null)
        assertTrue("из dpai" in CanonicalJson.write(state.body))
        assertEquals(400, api.handle("POST", "/v1/commands", token, """{"command":"task.hold","task":"${task.value}","лишнее":1}""").status, "лишнее поле — отказ")
        val cancel = CanonicalJson.write(CommandCodec.encode(Command.Cancel(task)))
        assertEquals(409, api.handle("POST", "/v1/commands", token, cancel).status, "опасное без --yes")
        val done = api.handle("POST", "/v1/commands", token, CanonicalJson.write(CommandCodec.encode(Command.Cancel(task), confirmed = true)))
        assertEquals(200, done.status, done.body.toString())
        assertEquals(TaskState.CANCELLED, o.tasks.tasks.getValue(task).state)
        assertEquals(200, api.handle("GET", "/v1/tasks/${task.value}/messages", token, null).status)

        // Лимит попыток: шестая — отказ и для верного кода.
        val (code2, _) = pairing.newCode()
        repeat(Pairing.MAX_ATTEMPTS) { api.handle("POST", "/v1/pair", null, """{"code":"999999"}""") }
        assertEquals(403, api.handle("POST", "/v1/pair", null, """{"code":"$code2"}""").status)
        // Срок кода.
        val (code3, _) = pairing.newCode()
        clock.advance(3.minutes)
        assertEquals(403, api.handle("POST", "/v1/pair", null, """{"code":"$code3"}""").status)
    }

    @Test
    fun codecRoundTripForEveryExternalCommand() {
        val t = ids.next(IdKinds.TASK)
        val s = ids.next(IdKinds.SESSION)
        val commands = listOf(
            Command.AnswerRequest("dpai://answer/x/y?hash=sha256:00", "текст", ControlApi.BY, "cli"),
            Command.Probe(com.dpsmpx.dpai.notifications.RequestKind.CHOICE, 5.minutes, "k-00000001"),
            Command.AddTask("t", "o", com.dpsmpx.dpai.tasks.Priority.HIGH, com.dpsmpx.dpai.tasks.TaskPolicy(taskClass = com.dpsmpx.dpai.tasks.TaskClass.TRIVIAL, model = "haiku", effort = "low", maxBudgetUsdCents = 50), "k-00000002"),
            Command.SetPriority(t, com.dpsmpx.dpai.tasks.Priority.LOW), Command.SetPolicy(t, com.dpsmpx.dpai.tasks.TaskClass.ARCHITECTURE, "opus", null, 100),
            Command.Hold(t), Command.Resume(t), Command.Cancel(t), Command.Retry(t, "ответ"), Command.Verify(t), Command.Waive(t, "проверил", ControlApi.BY),
            Command.StopSession(s), Command.SendToSession(s, "ещё", "sonnet", "high"), Command.SendToSession(s, null, fresh = true),
            Command.SetNearPercent(ids.next(IdKinds.SUBSCRIPTION), 90), Command.SetTargetEnabled(ids.next(IdKinds.TARGET), false),
            Command.SetSlotEnabled(ids.next(IdKinds.ACCOUNT), true), Command.SetSessionLimit(4), Command.SetSessionLimit(null),
        )
        for (c in commands) {
            val back = CommandCodec.decode(CommandCodec.encode(c), ControlApi.BY)
            assertEquals(c, back.valueOrNull()?.command, "$c: ${back.diagnostics}")
        }
    }
}

package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Изоляция проектов и аккаунтов на одном target (критерий этапа 18; ТЗ,
 * §66, §67, §111; T6, T7): синтетические проекты A и B работают через
 * одного агента, и ни контекст, ни окружение слота, ни журнал одного не
 * содержат данных другого.
 */
class IsolationTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(401))
    private val target: ExecutionTargetId = ids.next(IdKinds.TARGET)
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(402)))

    private fun project(name: String, secret: String, configDir: String,
        contextProfile: com.dpsmpx.dpai.contexts.ContextProfile = com.dpsmpx.dpai.contexts.ContextProfile()): Pair<Orchestrator, EventJournal> {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, name, clock.now()).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, target, configDir, clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org-$name", "$name@example.org"), "max")
        acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        val projectId = ids.next(IdKinds.PROJECT)
        val sources = listOf(ContextSource("doc:$name.md", SourceLevel.DOCUMENT, projectId, Sensitivity.INTERNAL, Origin.REPOSITORY, "заметка проекта $name: $secret"))
        val journal = (EventJournal.open(NioVfs(Files.createTempDirectory(dir, name)), "journal.log") as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(name.hashCode().toLong()), host) { _, _ -> MockScenario.SUCCESS }
        val profile = VerificationProfile(projectId, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(name.length.toLong()), journal, runner,
            ProjectConfig(projectId, null, dir.toString(), profile, sources = { sources }, contextProfile = { Outcome.success(contextProfile) }),
            listOf(TargetSlot(target, emptySet(), 2)), null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), acc,
        )
        return o to journal
    }

    @Test fun restrictedContextBlocksNativeLaunchWithoutAttemptsOrRetryLoop() {
        val profile = com.dpsmpx.dpai.contexts.ContextProfile(com.dpsmpx.dpai.contexts.ContextMode.SCOPED, listOf("src"))
        val (o, _) = project("scope", "local", "/home/dev/.claude-scope", profile)
        val task = o.addTask("ограниченная задача", "исправить src").valueOrNull()!!
        repeat(6) { o.tick() }
        val record = o.tasks.tasks.getValue(task)
        assertEquals(TaskState.BLOCKED, record.state)
        assertEquals(0, record.retries)
        assertTrue(host.runIds().isEmpty(), "нативный CLI не запускался")
        assertTrue(o.problems.any { d -> d.code == com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED })
    }

    @Test
    fun projectsOnOneTargetDoNotSeeEachOther() {
        val (a, journalA) = project("a", "alpha-7731", "/home/dev/.claude-a")
        val (b, journalB) = project("b", "beta-4410", "/home/dev/.claude-b")
        val ta = a.addTask("задача A", "работа A").valueOrNull()!!
        val tb = b.addTask("задача B", "работа B").valueOrNull()!!
        repeat(4) { a.tick(); b.tick() }
        assertEquals(TaskState.DONE, a.tasks.tasks.getValue(ta).state)
        assertEquals(TaskState.DONE, b.tasks.tasks.getValue(tb).state)

        val runsA = a.sessions.sessions.keys
        val runsB = b.sessions.sessions.keys
        assertTrue((runsA intersect runsB).isEmpty())
        assertEquals(runsA + runsB, host.runIds(), "на общем target только запуски A и B")
        for ((runs, own, other, dirOwn) in listOf(
            Quad(runsA, "alpha-7731", "beta-4410", "/home/dev/.claude-a"),
            Quad(runsB, "beta-4410", "alpha-7731", "/home/dev/.claude-b"),
        )) {
            for (r in runs) {
                val spec = host.spec(r)!!
                assertTrue(own in spec.stdin.orEmpty(), "свой контекст в prompt")
                assertFalse(other in spec.stdin.orEmpty(), "чужой контекст в prompt")
                assertEquals(dirOwn, spec.setEnv[SlotEnvironment.CONFIG_DIR], "каталог настроек — своего слота")
                assertTrue(spec.unsetEnv.containsAll(SlotEnvironment.OVERRIDING), "перекрывающие вход переменные удалены")
            }
        }
        val textA = journalA.records().joinToString("\n") { it.event.toJson().toString() }
        val textB = journalB.records().joinToString("\n") { it.event.toJson().toString() }
        assertFalse("beta-4410" in textA || runsB.any { it.value in textA }, "в журнале A нет данных B")
        assertFalse("alpha-7731" in textB || runsA.any { it.value in textB }, "в журнале B нет данных A")
    }

    private data class Quad(val runs: Set<com.dpsmpx.dpai.core.ids.SessionId>, val own: String, val other: String, val dir: String)
}

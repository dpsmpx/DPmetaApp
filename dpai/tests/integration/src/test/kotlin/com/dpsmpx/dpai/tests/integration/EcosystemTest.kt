package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.ecosystem.EcosystemProvider
import com.dpsmpx.dpai.ecosystem.ManifestEcosystem
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.ecosystem.EcosystemRun
import com.dpsmpx.dpai.orchestrator.ecosystem.EcosystemSources
import com.dpsmpx.dpai.orchestrator.ecosystem.RunPolicy
import com.dpsmpx.dpai.orchestrator.verification.ProcessStepRunner
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Экосистема и глобальный режим (критерии этапов 15, 16; ТЗ, §43–§46,
 * §148): прогон видит проекты, создаёт подтверждённые задачи, формирует
 * контекст из документов проекта, исполняет, проверяет и записывает итог;
 * без экосистемы — обычная работа.
 */
class EcosystemTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(201))
    private val host = LocalProcessHost()

    private fun orchestrator(project: ProjectId, slots: Int, sources: (com.dpsmpx.dpai.core.ids.TaskId) -> List<com.dpsmpx.dpai.contexts.ContextSource> = { emptyList() }): Orchestrator {
        val accountsLogic = Accounts()
        var accounts = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, accountsLogic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        val targets = List(slots) { i ->
            val target = ids.next(IdKinds.TARGET)
            val slot = ids.next(IdKinds.ACCOUNT)
            accounts = Accounts.applyAll(accounts, accountsLogic.registerSlot(accounts, slot, sub, target, "/home/dev/.claude-$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            accounts = Accounts.applyAll(accounts, accountsLogic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
            TargetSlot(target, emptySet(), 1)
        }
        val java = File(File(System.getProperty("java.home"), "bin"), if (File.separatorChar == '\\') "java.exe" else "java").path
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("сборка", listOf(java, "-version")))))
        val runner = MockRunner.create(clock, RandomSource.seeded(ids.next(IdKinds.OPERATION).hashCode().toLong())) { _, _ -> MockScenario.SUCCESS }
        val home = Files.createTempDirectory(dir, "o")
        val journal = (EventJournal.open(NioVfs(home), "journal.log") as Outcome.Success).value
        return Orchestrator(
            clock, ids, RandomSource.seeded(202), journal, runner,
            ProjectConfig(project, null, home.toString(), profile, sources = sources),
            targets, null, Verifier(ProcessStepRunner(host), clock), accounts,
        )
    }

    private val manifest = """
        {"format":"dpai.ecosystem","version":1,"projects":[
          {"key":"dplang","name":"DPlang","repository":"/src/dplang","state":{"branch":"main","verification":"red"},
           "documents":["CONTRACT.md"],
           "suggestions":[
             {"key":"fix-contract","title":"Согласовать контракт X","objective":"привести контракт X к DPmeta","priority":"HIGH","operation":"fix"},
             {"key":"docs","title":"Обновить документы","objective":"описать контракт X","priority":"NORMAL","operation":"docs"},
             {"key":"wipe","title":"Удалить историю","objective":"переписать историю ветки","priority":"CRITICAL","operation":"destructive"}
           ]},
          {"key":"dpmeta","name":"DPmeta","state":{},"suggestions":[
             {"key":"x","title":"Чужое","objective":"не разрешено","priority":"CRITICAL","operation":"fix"}]}
        ]}
    """.trimIndent()

    @Test
    fun ecosystemRunInspectsSelectsExecutesVerifiesRecords() {
        val docs = Files.createTempDirectory(dir, "docs")
        File(docs.toFile(), "CONTRACT.md").writeText("Контракт X: версия 2, поле id обязательно.\n")
        val projectId = ids.next(IdKinds.PROJECT)
        val provider = ManifestEcosystem { manifest }
        val project = provider.projects().valueOrNull()!!.single { it.key == "dplang" }
        val o = orchestrator(projectId, slots = 2) { EcosystemSources.of(project, projectId) { f -> File(docs.toFile(), f).takeIf { it.exists() }?.readText() } }
        val resultsDir = Files.createTempDirectory(dir, "res")
        val run = EcosystemRun(
            provider, mapOf("dplang" to o, "dpmeta" to orchestrator(ids.next(IdKinds.PROJECT), 1)),
            RunPolicy(allowedProjects = setOf("dplang"), allowedOperations = setOf("fix", "docs"), maxConcurrency = 1, maxTasks = 5, confirm = { _, _ -> true }),
            DocumentStore(NioVfs(resultsDir), "results"), clock,
        )
        val records = run.run()
        assertEquals(listOf("fix-contract", "docs"), records.map { it.suggestion }, "по приоритету, только разрешённое")
        assertTrue(records.all { it.state == TaskState.DONE.name && it.verdict == "PASSED" && it.reportHash != null }, records.toString())
        assertTrue(run.notes.any { it.code == DiagCode.OPERATION_NOT_ALLOWED && "destructive" in it.message })
        assertTrue(run.notes.any { it.code == DiagCode.OPERATION_NOT_ALLOWED && "dpmeta" in it.message })
        // Контекст задачи собран из документов проекта и состояния экосистемы.
        val ctx = o.lastContext(records.first().task)!!
        assertTrue(ctx.sources.any { "Контракт X" in it.content }, "документ проекта в контексте")
        assertTrue(ctx.sources.any { "verification: red" in it.content }, "состояние экосистемы в контексте")
        // Итог записан обратно.
        val doc = DocumentStore(NioVfs(resultsDir), "results").read(EcosystemRun.RESULTS_KEY).valueOrNull()!!
        assertEquals(2, (doc.content["records"] as com.dpsmpx.dpai.core.serialization.JsonArray).items.size)
    }

    @Test
    fun unconfirmedSuggestionIsNotCreated() {
        val o = orchestrator(ids.next(IdKinds.PROJECT), 1)
        val run = EcosystemRun(
            ManifestEcosystem { manifest }, mapOf("dplang" to o),
            RunPolicy(setOf("dplang"), setOf("fix", "docs"), confirm = { _, s -> s.key == "docs" }),
            DocumentStore(NioVfs(Files.createTempDirectory(dir, "r")), "results"), clock,
        )
        assertEquals(listOf("docs"), run.run().map { it.suggestion })
    }

    @Test
    fun withoutEcosystemTheOrchestratorStillWorks() {
        val o = orchestrator(ids.next(IdKinds.PROJECT), 1)
        for (provider in listOf<EcosystemProvider>(EcosystemProvider.None, ManifestEcosystem { null }, ManifestEcosystem { "не json" })) {
            val run = EcosystemRun(provider, mapOf("p" to o), RunPolicy(setOf("p"), setOf("fix"), confirm = { _, _ -> true }), DocumentStore(NioVfs(Files.createTempDirectory(dir, "n")), "results"), clock)
            run.run()
            assertTrue(run.records.isEmpty())
        }
        assertEquals(DiagCode.ECOSYSTEM_DISABLED, EcosystemProvider.None.projects().diagnostics.single().code)
        assertEquals(DiagCode.ECOSYSTEM_UNAVAILABLE, (ManifestEcosystem { null }.projects() as Outcome.Failure).errors.single().code)
        // Ручная задача идёт обычным путём.
        val t = o.addTask("ручная", "без экосистемы").valueOrNull()!!
        repeat(5) { o.tick() }
        assertEquals(TaskState.DONE, o.tasks.tasks.getValue(t).state)
    }

    @Test
    fun manifestIsStrict() {
        val bad = ManifestEcosystem { """{"format":"dpai.ecosystem","version":1,"projects":[{"key":"a","name":"A","unknown":1}]}""" }
        assertEquals(DiagCode.FIELD_UNKNOWN, (bad.projects() as Outcome.Failure).errors.single().code)
        val newer = ManifestEcosystem { """{"format":"dpai.ecosystem","version":2,"projects":[]}""" }
        assertEquals(DiagCode.FORMAT_VERSION_NEWER, (newer.projects() as Outcome.Failure).errors.single().code)
        val p: EcosystemProject = ManifestEcosystem { manifest }.projects().valueOrNull()!!.first()
        assertEquals(mapOf("branch" to "main", "verification" to "red"), p.state)
    }
}

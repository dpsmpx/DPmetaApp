package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.automation.*
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.operation.Actor
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.operation.Provenance
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.DevelopmentTools
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DevelopmentFlowTest {
    @TempDir lateinit var dir: Path

    @Test fun userAndAgentRunActualProjectAnalysisThroughTheSameWorkflowAndSavedEvidence() {
        for (actor in listOf(ActorKind.USER, ActorKind.AGENT)) {
            val project = dir.resolve(actor.name).toFile().apply { mkdirs() }
            project.resolve("build.gradle.kts").writeText("plugins {}")
            project.resolve("src").mkdirs(); project.resolve("src/App.kt").writeText("class App")
            val tree = LocalTree(project.toPath())
            val catalog = AutomationCatalog.of(DevelopmentTools.describe(project.path, TargetPlatform.POSIX, true)).valueOrNull()!!
            val structure = catalog.byId("development.structure")!!
            val duplicate = catalog.byId("development.duplicates")!!
            val context = Hashing.sha256(actor.name).hex
            val workflow = Workflow("inspect-project", "Исследовать проект", context,
                listOf(structure, duplicate).map { WorkflowStep(it.id, it.revision) }, enabled = true)
            val store = WorkflowStore(NioVfs(dir.resolve("private-${actor.name}")))
            assertTrue(store.save(workflow, null).isSuccess)
            val event = AutomationEvent("read-once", AutomationTrigger.MANUAL, context, context)
            val engine = WorkflowEngine(store, Clock.SYSTEM)
            var calls = 0
            fun run() = engine.run(workflow.id, workflow.revision, event, { false }, { Outcome.success(catalog) },
                { _, _ -> Outcome.success(Unit) }, { definition, isCancelled, _ ->
                    calls++
                    Outcome.success(AutomationJson.result(catalog.invoke(definition.id, definition.revision, definition.parameters,
                        Provenance(Actor(actor, "integration")), IdGenerator(Clock.SYSTEM, RandomSource.seeded(223)), Clock.SYSTEM,
                        isCancelled, { Outcome.success(Unit) }, { d, values -> DevelopmentTools.run(d.id, values, tree, project.path, Clock.SYSTEM, cancelled = isCancelled) })))
                })
            val result = run().valueOrNull()!!
            assertEquals(JsonString("PASSED"), result["status"])
            assertEquals(2, calls)
            assertEquals(result, run().valueOrNull())
            assertEquals(2, calls, "повтор события читает долговечное доказательство, не вызывает инструмент повторно")
            assertEquals("class App", project.resolve("src/App.kt").readText())
            assertEquals(2, tree.list("").valueOrNull()!!.size)
        }
    }

    @Test fun actualGitDiffIncludesStagedChangesAndDoesNotExpandGlobOrRunExternalDrivers() {
        val root = dir.resolve("git").toFile().apply { mkdirs() }
        val host = LocalProcessHost()
        fun git(vararg args: String): String {
            val done = host.run(ProcessSpec(listOf("git", "-C", root.path) + args), 10.seconds).valueOrNull()!!
            assertEquals(0, done.exitCode, done.stderr.joinToString("\n"))
            return done.stdout.joinToString("\n").trim()
        }
        git("init")
        git("config", "user.email", "fixture@example.invalid"); git("config", "user.name", "Fixture")
        root.resolve("*").writeText("before\n"); root.resolve(".env").writeText("private-before\n")
        root.resolve(".gitattributes").writeText("* diff=synthetic\n")
        git("add", "--all"); git("commit", "-m", "fixture")
        root.resolve("*").writeText("staged-change\n"); root.resolve(".env").writeText("private-after\n")
        git("add", "--all")
        git("config", "diff.external", "must-not-execute")
        git("config", "diff.synthetic.textconv", "must-not-execute")
        val head = git("rev-parse", "HEAD")
        val service = GitService(host, GitCommands(ApprovalLedger()), Clock.SYSTEM)
        val result = service.run(GitOp.DIFF_FILES, root.path, listOf(head, "./*"), maxStdoutChars = 16000).valueOrNull()!!
        val patch = result.stdout.joinToString("\n")
        assertTrue("staged-change" in patch)
        assertFalse("private-after" in patch)
        assertFalse(result.truncated)
    }
}

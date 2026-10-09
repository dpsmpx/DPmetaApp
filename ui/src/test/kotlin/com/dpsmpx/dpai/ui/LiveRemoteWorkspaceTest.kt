package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.claude.WorkspaceTools
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.orchestrator.agent.WorkspaceMcp
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Маршрут D-50: Claude — на телефоне (Termux, вход его слота), проект и
 * деревья — на ПК без каталога Claude. Ход получает только MCP-сервер
 * дерева по SSH; «Claude» правит файл через него (`WorkspaceMcp`, как
 * сделал бы `dpai agent mcp` на ПК). Git и проверка — через агента ПК,
 * слияние — по одобрению.
 */
class LiveRemoteWorkspaceTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(93))
    private val phoneAgent = MockAgentHost(clock, ids)
    private val pcAgent = MockAgentHost(clock, ids)
    private val local = LocalProcessHost()

    private val pcHost = object : ProcessHost {
        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit) =
            if (spec.command.first() == "git") local.start(spec, onOutput) else pcAgent.start(spec, onOutput)
    }

    private fun git(vararg argv: String) {
        val r = local.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    @Test
    fun claudeOnPhoneEditsTreeOnPcThroughMcp() {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path.replace('\\', '/')
        git("init", "-q", "-b", "main", root)
        git("-C", root, "config", "user.email", "dpai-test@example.org")
        git("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        git("-C", root, "add", "README.md")
        git("-C", root, "commit", "-q", "-m", "начало")

        val turns = mutableListOf<ProcessSpec>()
        phoneAgent.onStart = { run, spec, _ ->
            when {
                spec.command.take(3) == listOf("claude", "auth", "status") -> {
                    phoneAgent.output(run, """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}""")
                    phoneAgent.exit(run, 0)
                }
                spec.command.first() == "claude" -> {
                    turns += spec
                    // «Claude» на телефоне: команда сервера дерева из --mcp-config — и вызов его инструмента.
                    val c = spec.command
                    val config = JsonParser().parse(c[c.indexOf("--mcp-config") + 1]).valueOrNull() as JsonObject
                    val server = (config["mcpServers"] as JsonObject)[WorkspaceTools.SERVER] as JsonObject
                    val remote = ((server["args"] as JsonArray).items.last() as JsonString).value
                    val tree = remote.substringAfter(" --root ")
                    val mcp = WorkspaceMcp(Path.of(tree), WorkspaceMcp.defaultShell(false), "test")
                    val reply = mcp.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"write","arguments":{"path":"feature.txt","content":"новая возможность\n"}}}""")!!
                    assertTrue("\"isError\":false" in reply, reply)
                    MockRunner.script(MockScenario.SUCCESS, c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]).forEach { phoneAgent.output(run, it) }
                    phoneAgent.exit(run, 0, ExitReason.EXITED)
                }
                else -> error("на телефоне — только Claude: ${spec.command}")
            }
        }
        pcAgent.onStart = { run, spec, _ ->
            assertTrue(spec.command.first() != "claude", "на ПК Claude не запускается и вход не проверяется")
            pcAgent.output(run, "проверка в ${spec.workDir}: ok")
            pcAgent.exit(run, 0)
        }
        val phone = TargetForm.validate(
            ids.next(IdKinds.TARGET), "телефон", "127.0.0.1", "8022", "u0_a1", false,
            "/data/data/com.termux/files/home/dpai/bin/dpai", "/data/data/com.termux/files/home/.claude-dpai",
        ).valueOrNull()!!
        val pc = TargetForm.validate(ids.next(IdKinds.TARGET), "ПК", "pc.lan", "22", "dev", false, "/opt/dpai/bin/dpai").valueOrNull()!!
        val worktrees = dir.resolve("trees").toString().replace('\\', '/')
        val project = ProjectForm.validate(ids, pc, root, worktrees, "main", "make test", claudeOn = phone, identityFile = ProjectForm.DEFAULT_IDENTITY).valueOrNull()!!
        assertEquals(project, ProjectBook.fromJson(ProjectBook.toJson(project)).valueOrNull(), "маршрут переживает сохранение")
        val hosts = mapOf("127.0.0.1" to phoneAgent, "pc.lan" to pcHost)
        val s = LiveSystem(clock, NioVfs(dir.resolve("ctl").also { it.toFile().mkdirs() }), listOf(phone, pc), { e, _ -> hosts.getValue(e.host) }, "/known_hosts", RandomSource.seeded(94), project = project)
        assertTrue(s.notes.isEmpty(), "${s.notes}")
        val task = s.addTask("фича", "добавить возможность").valueOrNull()!!
        repeat(6) {
            s.step()
            assertEquals(emptyList(), s.orchestrator.invariants())
            clock.advance(10.seconds)
        }
        val record = s.orchestrator.tasks.tasks.getValue(task)
        assertEquals(TaskState.DONE, record.state, s.problems.toString() + s.orchestrator.problems)
        assertEquals("PASSED", record.verification!!.verdict)
        val session = s.orchestrator.sessions.sessions.values.single()
        assertEquals(phone.id, session.target, "сессия — на телефоне, со слотом телефона")
        val turn = turns.single()
        assertNull(turn.workDir, "процесс Claude — в каталоге агента телефона, не в пути ПК")
        assertEquals("", turn.command[turn.command.indexOf("--tools") + 1], "встроенных инструментов нет")
        assertTrue("--strict-mcp-config" in turn.command)
        val tree = s.orchestrator.worktrees.worktrees.values.single()
        assertTrue(tree.path.startsWith(worktrees), "дерево — на ПК, в каталоге деревьев проекта")
        assertTrue(File(root, "feature.txt").exists().not())
        val request = s.orchestrator.pendingRequests().single { it.purpose == "task.merge" }
        assertEquals(com.dpsmpx.dpai.core.operation.OperationStatus.SUCCEEDED, s.orchestrator.answer(request.id, "merge", null, request.hash, "автор").status)
        assertEquals("новая возможность\n", File(root, "feature.txt").readText(), "правка через MCP слита в main")
    }

    @Test
    fun projectFormRoute() {
        val phone = TargetForm.validate(ids.next(IdKinds.TARGET), "телефон", "127.0.0.1", "8022", "u0_a1", false, "/data/dpai", "/data/.claude").valueOrNull()!!
        val pc = TargetForm.validate(ids.next(IdKinds.TARGET), "ПК", "pc.lan", "22", "dev", true, "C:/Users/dev/dpai-agent.cmd").valueOrNull()!!
        fun form(claudeOn: TargetEntry?, key: String = ProjectForm.DEFAULT_IDENTITY) = ProjectForm.validate(ids, pc, "C:/src/app", "C:/src/trees", "main", "", claudeOn, key)
        assertEquals("claude", form(null).diagnostics.single().subject, "у ПК нет каталога Claude — нужен target хода")
        assertEquals("identityFile", form(phone, "relative/key").diagnostics.single().subject)
        assertEquals("identityFile", form(phone, "/root/.ssh/it's").diagnostics.single().subject)
        val p = form(phone).valueOrNull()!!
        assertEquals(ClaudeRoute(phone.id, ProjectForm.DEFAULT_IDENTITY), p.claude)
        // v1 (без маршрута) читается.
        val v1 = JsonObject(ProjectBook.toJson(p.copy(claude = null)).fields + ("version" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(1)))
        assertEquals(p.copy(claude = null), ProjectBook.fromJson(v1).valueOrNull())
    }
}

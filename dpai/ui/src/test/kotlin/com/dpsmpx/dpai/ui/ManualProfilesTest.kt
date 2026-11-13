package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.agent.RunStatus
import com.dpsmpx.dpai.execution.agent.StopResult
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.junit.jupiter.api.io.TempDir

class ManualProfilesTest {
    @TempDir lateinit var dir: Path
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(134))
    private val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Linux", "/root/.claude-slot-1").valueOrNull()!!
    private val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, "/root/repo", "/root/trees", "main", emptyList())
    private val profile = ManualProfile.form(ManualProfileKind.BUILD, "./gradlew 'assembleDebug'", "60").valueOrNull()!!
    private fun store() = ManualProfiles(DocumentStore(NioVfs(dir), "profiles"), ids)
    private val location get() = ProjectWorkspaceView.of(project, listOf(target), true).main

    private class Host : ProcessHost {
        var starts = 0
        var state = RunState.RUNNING
        /** Ход агента: растёт на каждый принятый start, как у настоящего агента. */
        var turn = 1
        var exitCode = 0
        val commands = mutableListOf<String>()
        var offline = false
        var startReplyLost = false
        var stopPending = false
        var survivors = emptyList<Long>()
        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            val command = spec.command[2]
            if (command == "start") starts++
            if (offline || command == "start" && startReplyLost) return Outcome.failure(DiagCode.PROCESS_START_FAILED, "связь потеряна")
            if (command == "start") {
                if (commands.isNotEmpty()) turn++
                commands += (com.dpsmpx.dpai.core.serialization.JsonParser().parse(spec.stdin!!).valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject)["command"].toString()
                state = RunState.RUNNING
            }
            val run = spec.command.indexOf("--run").takeIf { it >= 0 }?.let { IdKinds.SESSION.parse(spec.command[it + 1]).valueOrNull() }
            val status = run?.let { RunStatus(it, state, exitCode = if (state == RunState.EXITED) exitCode else null, reason = if (state == RunState.EXITED) ExitReason.EXITED else null, turn = turn) }
            val reply = when (command) {
                "profiles" -> AgentReply(command, true)
                "fence-stop" -> AgentReply(command, true, run = status!!.copy(state = if (stopPending) RunState.STARTING else RunState.EXITED), stop = StopResult(false, survivors))
                else -> AgentReply(command, true, run = status)
            }
            onOutput(OutputLine(OutputStream.STDOUT, CanonicalJson.write(reply.toJson())))
            return Outcome.success(object : RunningProcess {
                override val pid: Long? = null
                override val isAlive = false
                override fun awaitExit(timeout: Duration) = 0
                override fun stop(grace: Duration) = StopReport(0, false, emptyList())
            })
        }
        fun client() = AgentClient(this, listOf("dpai"), null, "test")
    }

    @Test fun formatCorpusAcceptsV1AndRefusesUnknownVersion() {
        fun sample(name: String) = (com.dpsmpx.dpai.core.serialization.JsonParser().parse(javaClass.getResource("/manual-profiles/$name.json")!!.readText()).valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject)
        assertEquals(ManualProfileKind.CHECK, ManualProfile.read(sample("check-v1"))!!.kind)
        assertEquals(null, ManualProfile.read(sample("unknown-version")))
        assertEquals(listOf("Настройка", "Сборка"), ManualProfile.read(sample("build-v2"))!!.steps.map { it.name })
    }

    @Test fun parsingAndProfilePersistenceDoNotNeedRepositoryFiles() {
        val s = store()
        assertIs<Outcome.Success<Unit>>(s.save(project, profile))
        assertEquals(profile, store().profile(project, ManualProfileKind.BUILD).valueOrNull())
        assertIs<Outcome.Failure>(ManualProfile.form(ManualProfileKind.CHECK, "echo 'bad", "60"))
        assertIs<Outcome.Failure>(ManualProfile.form(ManualProfileKind.CHECK, "echo ok", "0"))
        assertIs<Outcome.Failure>(ManualProfile.form(ManualProfileKind.CHECK, "", "60"))
    }

    @Test fun lostStartReplyAndRestartNeverRepeatCommandAndKeepPause() {
        val s = store(); val host = Host().apply { startReplyLost = true }
        val run = assertIs<Outcome.Success<ManualRun>>(s.prepare(project, target, location, profile)).value
        s.launch(run, host.client())
        val restored = store()
        assertTrue(restored.blocked)
        assertEquals(run, restored.snapshot().active)
        assertTrue(restored.poll(host.client()))
        assertEquals(1, host.starts)
        host.offline = true
        assertTrue(restored.stop(host.client()))
        assertTrue(store().blocked)
        host.offline = false
        assertFalse(restored.stop(host.client()))
        assertFalse(store().blocked)
        assertEquals(1, host.starts)
    }

    @Test fun lostSupervisorPendingStopAndSurvivorsDoNotReleaseProject() {
        val s = store(); val host = Host().apply { state = RunState.LOST }
        s.prepare(project, target, location, profile)
        assertTrue(s.poll(host.client()))
        host.stopPending = true
        assertTrue(s.stop(host.client()))
        host.stopPending = false; host.survivors = listOf(123L)
        assertTrue(s.stop(host.client()))
        assertTrue(store().blocked)
        host.survivors = emptyList()
        assertFalse(s.stop(host.client()))
    }

    @Test fun resultAndWriteGateSurviveRestartAndDoNotChangeTaskState() {
        val s = store(); val host = Host()
        val run = s.prepare(project, target, location, profile).valueOrNull()!!
        assertIs<Outcome.Failure>(s.edit { Outcome.success(Unit) })
        assertIs<Outcome.Failure>(s.prepare(project, target, location, profile))
        assertIs<Outcome.Failure>(s.save(project, profile))
        s.launch(run, host.client()); host.state = RunState.EXITED
        assertFalse(s.poll(host.client()))
        assertFalse(store().blocked)
        assertTrue(store().snapshot().note.contains("код выхода 0"))
        assertIs<Outcome.Success<Unit>>(s.edit { Outcome.success(Unit) })
    }

    @Test fun corruptAnchorIsPausedRatherThanAssumedIdle() {
        val s = store(); s.prepare(project, target, location, profile)
        dir.resolve("profiles/active").toFile().writeText("broken")
        val restored = store()
        assertTrue(restored.blocked)
        assertEquals(null, restored.snapshot().active)
    }

    private val steps = ManualProfile.form(ManualProfileKind.BUILD, listOf(
        Triple("Настройка", "cmake -S . -B build", "60"),
        Triple("Сборка", "cmake --build build", "60"),
        Triple("Тесты", "ctest --test-dir build", "60"),
    )).valueOrNull()!!

    @Test fun stepsFormatV2RoundTripsAndV1ReadsAsOneStep() {
        assertEquals(steps, ManualProfile.read(steps.toJson()))
        assertEquals(listOf("Настройка", "Сборка", "Тесты"), steps.steps.map { it.name })
        val v1 = ManualProfile.read(JsonObject.of("format" to JsonString("dpai.manual-profile"), "version" to JsonNumber.of(1), "kind" to JsonString("BUILD"),
            "command" to JsonArray(listOf(JsonString("make"))), "timeout" to JsonNumber.of(60)))!!
        assertEquals(listOf(ManualStep(ManualStep.DEFAULT_NAME, listOf("make"), 60)), v1.steps)
        val bad = ManualProfile.form(ManualProfileKind.BUILD, listOf(Triple("a", "make", "60"), Triple("b", "echo 'x", "60")))
        assertTrue((bad as Outcome.Failure).diagnostics.first().message.startsWith("шаг 2"))
        assertIs<Outcome.Failure>(ManualProfile.form(ManualProfileKind.BUILD, emptyList()))
        assertIs<Outcome.Failure>(ManualProfile.form(ManualProfileKind.BUILD, List(ManualProfile.MAX_STEPS + 1) { Triple("s", "make", "60") }))
        // Пустое имя — «Шаг N»; готовые наборы — правильные профили.
        assertEquals("Шаг 1", ManualProfile.form(ManualProfileKind.CHECK, listOf(Triple(" ", "make", "60"))).valueOrNull()!!.steps[0].name)
        for (kind in ManualProfileKind.entries) for (t in ManualProfile.templates(kind)) assertIs<Outcome.Success<ManualProfile>>(ManualProfile.form(kind, t.steps), t.title)
        assertEquals("cmake --build build '-DX=a b' 'it'\"'\"'s'", ManualProfile.quote(listOf("cmake", "--build", "build", "-DX=a b", "it's")))
        assertEquals(listOf("cmake", "--build", "build", "-DX=a b", "it's"), ProjectForm.words(ManualProfile.quote(listOf("cmake", "--build", "build", "-DX=a b", "it's"))))
    }

    @Test fun stepsRunInOrderAsTurnsAndStopAfterFirstFailure() {
        val s = store(); val host = Host()
        val run = s.prepare(project, target, location, steps).valueOrNull()!!
        s.launch(run, host.client())
        assertEquals(1, host.starts)
        assertTrue(s.poll(host.client()), "шаг идёт")
        host.state = RunState.EXITED
        assertTrue(s.poll(host.client()), "шаг 1 кончился с кодом 0 — запущен шаг 2")
        assertEquals(2, host.starts)
        assertEquals(2, s.snapshot().step)
        assertTrue(host.commands[1].contains("--build"))
        host.state = RunState.EXITED; host.exitCode = 2
        assertFalse(s.poll(host.client()), "шаг 2 упал — шаг 3 не запускается, сборка кончена")
        assertEquals(2, host.starts)
        val note = store().snapshot().note
        assertTrue(note.contains("1. Настройка — успешно") && note.contains("2. Сборка — код выхода 2") && note.contains("3. Тесты — не запускался"), note)
        assertTrue(s.snapshot().output.contains("── Шаг 2 из 3: Сборка ──"))
        assertFalse(store().blocked)
    }

    @Test fun allStepsSucceed() {
        val s = store(); val host = Host()
        s.launch(s.prepare(project, target, location, steps).valueOrNull()!!, host.client())
        repeat(2) { host.state = RunState.EXITED; assertTrue(s.poll(host.client())) }
        host.state = RunState.EXITED
        assertFalse(s.poll(host.client()))
        assertEquals(3, host.starts)
        assertEquals(listOf(true, true, true), s.snapshot().results.map { it.ok })
    }

    @Test fun restartAfterStepExitedStartsNextStepExactlyOnce() {
        val s = store(); val host = Host()
        s.launch(s.prepare(project, target, location, steps).valueOrNull()!!, host.client())
        host.state = RunState.EXITED
        // Перезапуск приложения до опроса: намерение — шаг 1, ход 1 кончился с кодом 0.
        val restored = store()
        assertEquals(1, restored.snapshot().step)
        assertTrue(restored.poll(host.client()))
        assertEquals(2, host.starts)
        // Ещё один перезапуск, пока шаг 2 идёт: ничего не запускается повторно.
        val again = store()
        assertTrue(again.poll(host.client()))
        assertEquals(2, host.starts)
    }

    @Test fun lostStartOfNextStepIsNeverRepeatedAfterRestart() {
        val s = store(); val host = Host()
        s.launch(s.prepare(project, target, location, steps).valueOrNull()!!, host.client())
        host.state = RunState.EXITED
        host.startReplyLost = true
        // Намерение «шаг 2» записано, ответ на start потерян: агент так и остался на ходе 1.
        assertTrue(s.poll(host.client()))
        assertEquals(2, host.starts)
        host.startReplyLost = false
        val restored = store()
        assertEquals(2, restored.snapshot().step)
        assertFalse(restored.poll(host.client()), "неизвестно, дошёл ли start: сборка останавливается, шаг 2 не повторяется")
        assertEquals(2, host.starts)
        assertTrue(store().snapshot().note.contains("не подтверждён"))
        assertFalse(store().blocked)
    }

    @Test fun stopBetweenStepsDoesNotStartTheNextOne() {
        val s = store(); val host = Host()
        s.launch(s.prepare(project, target, location, steps).valueOrNull()!!, host.client())
        host.state = RunState.EXITED
        assertFalse(s.stop(host.client()))
        assertEquals(1, host.starts)
        assertTrue(store().snapshot().note.contains("остановлено пользователем"))
    }
}

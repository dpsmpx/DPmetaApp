package com.dpsmpx.dpai.ui

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
    private val target = TargetEntry(ids.next(IdKinds.TARGET), "Linux", "", 22, "", TargetPlatform.POSIX, "dpai", configDir = "/root/.claude-slot-1", inApp = true)
    private val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, "/root/repo", "/root/trees", "main", emptyList())
    private val profile = ManualProfile.form(ManualProfileKind.BUILD, "./gradlew 'assembleDebug'", "60").valueOrNull()!!
    private fun store() = ManualProfiles(DocumentStore(NioVfs(dir), "profiles"), ids)
    private val location get() = ProjectWorkspaceView.of(project, listOf(target), true).main

    private class Host : ProcessHost {
        var starts = 0
        var state = RunState.RUNNING
        var offline = false
        var startReplyLost = false
        var stopPending = false
        var survivors = emptyList<Long>()
        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            val command = spec.command[2]
            if (command == "start") starts++
            if (offline || command == "start" && startReplyLost) return Outcome.failure(DiagCode.PROCESS_START_FAILED, "связь потеряна")
            val run = spec.command.indexOf("--run").takeIf { it >= 0 }?.let { IdKinds.SESSION.parse(spec.command[it + 1]).valueOrNull() }
            val status = run?.let { RunStatus(it, state, exitCode = if (state == RunState.EXITED) 0 else null, reason = if (state == RunState.EXITED) ExitReason.EXITED else null) }
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
}

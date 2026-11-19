package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionLog
import com.dpsmpx.dpmeta.actions.ActionStatus
import com.dpsmpx.dpmeta.actions.Actions
import com.dpsmpx.dpmeta.app.screens.CommandActions
import com.dpsmpx.dpmeta.app.screens.CommandActions.Facts
import com.dpsmpx.dpmeta.app.screens.CommandActions.Linux
import com.dpsmpx.dpmeta.formats.CommandFormat
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.git.Clones
import com.dpsmpx.dpmeta.model.ActionLevel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Команды реестра в Linux-среде (F-40, F-43; D-82): место, готовность по шагам, уровень 1 — с журналом. */
class CommandActionsTest {
    @TempDir
    lateinit var dir: Path

    private val registry = listOf("example/Hub", "example/Engine", "example/Tools", "example/Old")
        .fold(ProjectList()) { list, repo -> list.add(RepoRef.parse(repo).valueOrNull()!!).valueOrNull()!! }.forSync()

    private val commands = CommandFormat.parse(
        """
        schema = 1

        [[command]]
        id = "engine.check"
        title = "Проверки Engine"
        project = "Engine"
        workdir = "tools"
        path = "/bin/bash"
        args = ["check.sh"]
        plan = "background"
        level = 0
        packages = ["bash", "python3"]

        [[command]]
        id = "tools.next"
        title = "План"
        project = "Tools"
        path = "dpl"
        plan = "background"
        level = 0

        [[command]]
        id = "repo.ff"
        title = "Догнать origin"
        project = "*"
        path = "/usr/bin/git"
        args = ["merge", "--ff-only", "@{upstream}"]
        plan = "background"
        level = 1
        packages = ["git"]
        expect = "Fast-forward"
        timeout = 2
        """.trimIndent(),
    ).valueOrNull()!!

    private val check = commands.byId("engine.check")!!
    private val next = commands.byId("tools.next")!!
    private val ff = commands.byId("repo.ff")!!

    @Test
    fun subsystemRunsInItsDirOfTheControlClone() {
        val s = CommandActions.spot(check, registry, null).valueOrNull()!!
        assertEquals("example/Hub", "${s.repo}")
        assertEquals("/root/projects/example/Engine/tools", s.guestDir)
        assertEquals("dpmeta/tools/check.sh", CommandActions.scriptInClone(check, s))
        assertEquals(listOf("/bin/bash", "check.sh"), CommandActions.argv(check))
        val own = CommandActions.spot(next, registry, null).valueOrNull()!!
        assertEquals("/root/projects/example/Tools", own.guestDir)
        assertEquals(listOf("./dpl"), CommandActions.argv(next), "программа клона без «/» — от каталога, не из PATH")
        assertEquals("dpl", CommandActions.scriptInClone(next, own))
    }

    @Test
    fun anyProjectIsChosenAmongOwnClonesOnly() {
        assertEquals(DiagCode.FIELD_MISSING, (CommandActions.spot(ff, registry, null) as Outcome.Failure).errors.single().code)
        assertEquals("example/Engine", CommandActions.spot(ff, registry, "Engine").valueOrNull()!!.repo.toString())
        assertEquals("example/Old", CommandActions.spot(ff, registry, "Old").valueOrNull()!!.repo.toString(), "выбранный проект не скрывается ролью")
        assertEquals("/root/projects/example/Tools", CommandActions.spot(ff, registry, "Tools").valueOrNull()!!.guestDir)
        assertEquals(listOf("Hub"), CommandActions.anyProjects(registry) { "$it" == "example/Hub" })
        assertEquals(null, CommandActions.scriptInClone(ff, CommandActions.spot(ff, registry, "Tools").valueOrNull()!!), "у git скрипта нет")
    }

    @Test
    fun readinessExplainsTheFirstMissingStep() {
        val s = CommandActions.spot(check, registry, null).valueOrNull()!!
        val ok = Facts(Linux.READY, emptyList(), clone = true, workdir = true, script = true)
        assertTrue(CommandActions.ready(CommandActions.readiness(check, s, ok)))
        val noLinux = CommandActions.readiness(check, s, ok.copy(linux = Linux.NOT_INSTALLED))
        assertFalse(CommandActions.ready(noLinux))
        assertTrue(noLinux.first().hint!!.contains("Linux в приложении"))
        val noPython = CommandActions.readiness(check, s, ok.copy(missing = listOf("python3")))
        assertEquals("Нет пакетов: python3", noPython.first { !it.ok }.title)
        val noClone = CommandActions.readiness(check, s, ok.copy(clone = false, workdir = false, script = false))
        assertTrue(noClone.first { !it.ok }.hint!!.contains("Клоны"))
        val noScript = CommandActions.readiness(check, s, ok.copy(script = false))
        assertEquals("Скрипта dpmeta/tools/check.sh нет", noScript.single { !it.ok }.title)
    }

    @Test
    fun treeLineSaysWhereTheCommandRuns() {
        assertTrue("как на GitHub" in CommandActions.treeLine(Clones.Worktree("main", "a".repeat(40), "a".repeat(40))))
        assertTrue("на GitHub — bbbbbbb" in CommandActions.treeLine(Clones.Worktree("main", "a".repeat(40), "b".repeat(40))))
        assertTrue("без ветки" in CommandActions.treeLine(Clones.Worktree(null, "a".repeat(40), null)))
    }

    /** Уровень 1 (D-07): одно нажатие с показом, запись в журнал; код не 0 — «не выполнено». */
    @Test
    fun levelOneGoesThroughActionsAndJournal() {
        val clock = ManualClock()
        val log = ActionLog(NioVfs(dir), clock, RandomSource.seeded(1))
        val actions = Actions(log, clock, RandomSource.seeded(2))
        val s = CommandActions.spot(ff, registry, "Tools").valueOrNull()!!
        val tree = Clones.Worktree("main", "a".repeat(40), "b".repeat(40))
        val c = CommandActions.command(ff, s, tree)
        assertEquals(ActionKind.COMMAND_RUN to ActionLevel.REVERSIBLE, c.kind to c.level)
        assertEquals("a".repeat(40), c.params["before"])
        assertTrue(CommandActions.preview(ff, s, tree).any { "GitHub не меняется" in it })
        val done = actions.run(c, actions.shown(c)) { CommandActions.execute(c) { Outcome.success(CommandActions.Exit(0)) } }
        assertTrue(done is Outcome.Success)
        val refused = actions.run(c, actions.shown(c)) { CommandActions.execute(c) { Outcome.success(CommandActions.Exit(1)) } }
        assertEquals(DiagCode.PROCESS_EXIT_NONZERO, assertIs<Outcome.Failure>(refused).errors.single().code)
        val timed = actions.run(c, actions.shown(c)) { CommandActions.execute(c) { Outcome.success(CommandActions.Exit(null, timedOut = true)) } }
        assertEquals(DiagCode.PROCESS_TIMEOUT, assertIs<Outcome.Failure>(timed).errors.single().code)
        val entries = log.entries().valueOrNull()!!.filter { it.kind == "command.run" }
        assertEquals(listOf(ActionStatus.DONE, ActionStatus.FAILED, ActionStatus.FAILED), entries.map { it.status }.takeLast(3))
        assertEquals("repo.ff", entries.last().params["command"])
    }

    @Test
    fun levelZeroIsNotAJournalCommand() {
        val s = CommandActions.spot(check, registry, null).valueOrNull()!!
        assertTrue(runCatching { CommandActions.command(check, s, null) }.isFailure)
    }

    @Test
    fun tailKeepsTheLastLinesAndCountsTheRest() {
        val lines = (1..(CommandActions.MAX_LINES + 5)).map { "строка $it" }
        val t = CommandActions.tail(lines)
        assertEquals("… выше — ещё строк: 5", t.first())
        assertEquals("строка ${CommandActions.MAX_LINES + 5}", t.last())
        assertEquals(listOf("x"), CommandActions.tail(listOf("x")))
        assertEquals("Код возврата 2 (3 с).", CommandActions.exitLine(CommandActions.Exit(2), 3))
    }
}

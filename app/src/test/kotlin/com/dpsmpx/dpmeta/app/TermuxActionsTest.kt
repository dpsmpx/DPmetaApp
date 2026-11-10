package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.CommandActions
import com.dpsmpx.dpmeta.app.screens.TermuxActions
import com.dpsmpx.dpmeta.app.screens.TermuxActions.Facts
import com.dpsmpx.dpmeta.app.screens.TermuxActions.Result
import com.dpsmpx.dpmeta.formats.CommandFormat
import com.dpsmpx.dpmeta.formats.CommandSpec
import com.dpsmpx.dpmeta.formats.RegistryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Мост Termux (F-41, F-42; D-83, D-84): вызов из команды реестра, проба, готовность по шагам, итог. */
class TermuxActionsTest {
    private val registry = RegistryFormat.parse(
        """
        schema = 1

        [[repo]]
        name = "DPmetaApp"
        slug = "dpsmpx/DPmetaApp"
        place = "control"

        [[repo]]
        name = "DPmeta"
        slug = "dpsmpx/DPmeta"
        place = "sync"
        dir = "dpmeta"

        [[repo]]
        name = "DPlearn"
        slug = "dpsmpx/DPlearn"
        place = "core"
        """.trimIndent(),
    ).valueOrNull()!!

    private val commands = CommandFormat.parse(
        """
        schema = 1

        [[command]]
        id = "dpmeta.check"
        title = "Проверки DPmeta"
        project = "DPmeta"
        path = "/bin/bash"
        args = ["tools/check.sh"]
        plan = "background"
        level = 0
        packages = ["bash", "python3", "git"]

        [[command]]
        id = "dpmeta.activity"
        title = "Кто работает"
        project = "DPmeta"
        path = "/usr/bin/python3"
        args = ["tools/activity.py", "--root", "/root/repos"]
        plan = "background"
        level = 0
        packages = ["python3", "git"]

        [[command]]
        id = "dplearn.next"
        title = "План"
        project = "DPlearn"
        path = "tools/dpl"
        args = ["next"]
        plan = "foreground"
        level = 0
        """.trimIndent(),
    ).valueOrNull()!!

    private val folder = TermuxActions.DEFAULT_FOLDER
    private val prefix = TermuxActions.PREFIX
    private val check = commands.byId("dpmeta.check")!!
    private val activity = commands.byId("dpmeta.activity")!!
    private val next = commands.byId("dplearn.next")!!
    private val checkSpot = CommandActions.spot(check, registry, null).valueOrNull()!!
    private val nextSpot = CommandActions.spot(next, registry, null).valueOrNull()!!

    @Test
    fun registryCommandBecomesTermuxCall() {
        val c = TermuxActions.call(check, checkSpot, folder, "DPmetaApp").valueOrNull()!!
        assertEquals("$prefix/bin/bash", c.executable, "программа гостя из /bin — та же программа Termux")
        assertEquals(listOf("tools/check.sh"), c.args)
        assertEquals("$folder/DPmetaApp/dpmeta", c.workdir, "подсистема — в своём каталоге клона DPmetaApp")
        assertTrue(c.background)
        val a = TermuxActions.call(activity, checkSpot, folder, "DPmetaApp").valueOrNull()!!
        assertEquals("$prefix/bin/python3", a.executable)
        assertEquals(listOf("tools/activity.py", "--root", folder), a.args, "корень клонов Linux-среды — папка проектов")
        val n = TermuxActions.call(next, nextSpot, folder, "dplearn").valueOrNull()!!
        assertEquals("$folder/dplearn/tools/dpl", n.executable, "программа из клона — от каталога команды")
        assertFalse(n.background)
    }

    @Test
    fun onlyGuestBinariesAndNoOtherGuestPaths() {
        val opt = check.copy(path = "/opt/tool/run")
        assertEquals(DiagCode.UNSUPPORTED, assertIs<Outcome.Failure>(TermuxActions.call(opt, checkSpot, folder, "DPmetaApp")).errors.single().code)
        val guestArg = check.copy(args = listOf("--root", "/root/models"))
        assertEquals(DiagCode.UNSUPPORTED, assertIs<Outcome.Failure>(TermuxActions.call(guestArg, checkSpot, folder, "DPmetaApp")).errors.single().code)
        assertEquals("$prefix/bin/git", TermuxActions.call(check.copy(path = "/usr/local/bin/git", args = emptyList()), checkSpot, folder, "DPmetaApp").valueOrNull()!!.executable)
        val upward = check.copy(path = "/bin/../etc/passwd")
        assertIs<Outcome.Failure>(TermuxActions.call(upward, checkSpot, folder, "DPmetaApp"))
    }

    /** F-41: уровень 2 — только на переднем плане. */
    @Test
    fun levelTwoOnlyInForeground() {
        val two = CommandSpec("x.two", "Два", "DPlearn", null, "/bin/sh", listOf("tools/x.sh"), CommandSpec.Plan.BACKGROUND, 2, emptyList(), null, 1)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, assertIs<Outcome.Failure>(TermuxActions.call(two, nextSpot, folder, "DPlearn")).errors.single().code)
        assertIs<Outcome.Success<*>>(TermuxActions.call(two.copy(plan = CommandSpec.Plan.FOREGROUND), nextSpot, folder, "DPlearn"))
    }

    @Test
    fun folderSettingIsNormalizedAndChecked() {
        assertEquals("${TermuxActions.HOME}/Projects", TermuxActions.normalizeFolder("  ~/Projects/ "))
        assertEquals(TermuxActions.HOME, TermuxActions.normalizeFolder("~"))
        assertNull(TermuxActions.folderProblem(TermuxActions.normalizeFolder("/storage/emulated/0/Projects/")))
        for (bad in listOf("", "Projects", "/", "/a/../b", "/a//b", "/a/./b", "/a\nb")) {
            assertTrue(TermuxActions.folderProblem(bad) != null, "папка «$bad» не годится")
        }
        assertIs<Outcome.Failure>(TermuxActions.call(check, checkSpot, "Projects", "DPmetaApp"))
    }

    @Test
    fun versionFromFDroidOrGitHubNotGooglePlay() {
        assertTrue(TermuxActions.versionOk("0.118.1"))
        assertTrue(TermuxActions.versionOk("0.109"))
        assertTrue(TermuxActions.versionOk("0.118.0+github-debug"))
        assertTrue(TermuxActions.versionOk("1.0.0"))
        assertFalse(TermuxActions.versionOk("0.108.9"))
        assertFalse(TermuxActions.versionOk("googleplay.2024.01.11"))
        assertFalse(TermuxActions.versionOk(null))
        assertFalse(TermuxActions.versionOk("0"))
    }

    /** Проба только читает: `ls -1 -d -p --` путей папки, клона под обоими именами, программы и скрипта. */
    @Test
    fun probeListsOnlyPaths() {
        val paths = TermuxActions.probePaths(check, checkSpot, folder)
        assertEquals(
            listOf(folder, "$folder/DPmetaApp", "$prefix/bin/bash", "$folder/DPmetaApp/dpmeta/tools/check.sh", "$folder/dpmetaapp", "$folder/dpmetaapp/dpmeta/tools/check.sh"),
            paths,
        )
        val p = TermuxActions.probe(paths)
        assertEquals("$prefix/bin/ls", p.executable)
        assertEquals(listOf("-1", "-d", "-p", "--") + paths, p.args)
        assertNull(p.workdir, "проба — без рабочего каталога: Termux не создаёт его")
        assertTrue(p.background)
    }

    private val ok = Facts(installed = true, versionName = "0.118.1", runCommand = true, permitted = true, probe = null, probeSent = false)

    private fun listed(vararg paths: String): Result = Result(paths.joinToString("\n") + "\n", "ls: cannot access …", 2, TermuxActions.ERR_NONE, null)

    @Test
    fun readinessStepsInOrder() {
        assertEquals("Termux не установлен", TermuxActions.readiness(check, checkSpot, folder, ok.copy(installed = false)).steps.single().title)
        val play = TermuxActions.readiness(check, checkSpot, folder, ok.copy(versionName = "googleplay.2024.01.11", runCommand = false))
        assertTrue(play.steps.single().hint!!.contains("F-Droid"))
        val noPerm = TermuxActions.readiness(check, checkSpot, folder, ok.copy(permitted = false))
        assertEquals(TermuxActions.NO_PERMISSION, noPerm.steps.last().title)
        assertFalse(noPerm.ready)
        val silent = TermuxActions.readiness(check, checkSpot, folder, ok.copy(probeSent = true))
        assertTrue(silent.steps.last().title.startsWith("Termux не ответил"))
        val refused = Result("", "", null, 1, "Termux requires `allow-external-apps` property to be set to `true` in `~/.termux/termux.properties` file.")
        val ext = TermuxActions.readiness(check, checkSpot, folder, ok.copy(probe = refused, probeSent = true))
        assertTrue(ext.steps.last().hint!!.contains("allow-external-apps = true"))
        val noFolder = TermuxActions.readiness(check, checkSpot, folder, ok.copy(probe = listed(), probeSent = true))
        assertEquals("Папки $folder в Termux нет", noFolder.steps.last().title)
        val noClone = TermuxActions.readiness(check, checkSpot, folder, ok.copy(probe = listed("$folder/"), probeSent = true))
        assertTrue(noClone.steps.last().hint!!.contains("git clone https://github.com/dpsmpx/DPmetaApp $folder/DPmetaApp"))
        val noBash = TermuxActions.readiness(check, checkSpot, folder, ok.copy(probe = listed("$folder/", "$folder/dpmetaapp/"), probeSent = true))
        assertEquals("dpmetaapp", noBash.clone, "клон строчными — тоже клон, как у DPmeta")
        assertEquals("В Termux: pkg install bash python git", noBash.steps.last().hint, "python3 Alpine — python Termux")
        val noScript = TermuxActions.readiness(check, checkSpot, folder, ok.copy(probe = listed("$folder/", "$folder/DPmetaApp/", "$prefix/bin/bash"), probeSent = true))
        assertEquals("Скрипта dpmeta/tools/check.sh нет", noScript.steps.last().title)
        assertFalse(noScript.ready)
        val all = TermuxActions.readiness(check, checkSpot, folder, ok.copy(probe = listed("$folder/", "$folder/DPmetaApp/", "$prefix/bin/bash", "$folder/DPmetaApp/dpmeta/tools/check.sh"), probeSent = true))
        assertTrue(all.ready)
        assertEquals("$folder/DPmetaApp/dpmeta", all.call!!.workdir)
        val fg = TermuxActions.readiness(next, nextSpot, folder, ok.copy(probe = listed("$folder/", "$folder/DPlearn/", "$folder/DPlearn/tools/dpl"), probeSent = true))
        assertTrue(fg.ready)
        assertTrue(fg.steps.any { "Поверх других окон" in it.title }, "передний план — шаг о «поверх других окон»")
    }

    @Test
    fun resultIsCleanedAndTruncationSaid() {
        val r = Result("\u001B[32m✓ ok\u001B[0m\r\nвторая\u0007\n", "warn\n", 0, TermuxActions.ERR_NONE, null, stdoutLength = 150_000)
        val lines = TermuxActions.lines(r)
        assertTrue(lines.first().startsWith("… Termux обрезал вывод"))
        assertEquals(listOf("✓ ok", "вторая", "warn"), lines.drop(1))
        assertEquals(0, TermuxActions.exit(r).valueOrNull()!!.code)
        assertEquals(3, TermuxActions.exit(r.copy(exitCode = 3)).valueOrNull()!!.code)
        assertEquals(DiagCode.PROCESS_START_FAILED, assertIs<Outcome.Failure>(TermuxActions.exit(r.copy(exitCode = null))).errors.single().code)
        val missing = Result("", "", null, 1, "The executable file at path \"$prefix/bin/bash\" does not exist.")
        assertTrue("does not exist" in assertIs<Outcome.Failure>(TermuxActions.exit(missing)).errors.single().message)
        assertEquals(DiagCode.PROCESS_TIMEOUT, assertIs<Outcome.Failure>(TermuxActions.noResult(10)).errors.single().code)
        assertEquals(setOf(folder, "$prefix/bin/bash"), TermuxActions.existing(Result("$folder/\n$prefix/bin/bash\nне путь\n", "", 0, TermuxActions.ERR_NONE, null)))
    }

    /** Уровень 1 в Termux (D-84): журнал называет место и каталог Termux, коммита клона пульта нет. */
    @Test
    fun journalNamesTermux() {
        val ff = CommandSpec("repo.ff", "Догнать", "*", null, "/usr/bin/git", listOf("merge", "--ff-only", "@{upstream}"), CommandSpec.Plan.BACKGROUND, 1, listOf("git"), null, 2)
        val s = CommandActions.spot(ff, registry, "DPlearn").valueOrNull()!!
        val c = CommandActions.command(ff, s, null, CommandActions.Where.TERMUX, "$folder/DPlearn")
        assertEquals("termux", c.params["place"])
        assertEquals("$folder/DPlearn", c.params["dir"])
        assertNull(c.params["before"])
        assertTrue(CommandActions.preview(ff, s, null, CommandActions.Where.TERMUX, "$folder/DPlearn").any { "клона в Termux" in it })
    }
}

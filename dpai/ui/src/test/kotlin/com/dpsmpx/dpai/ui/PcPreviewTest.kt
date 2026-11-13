package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.TargetPlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Предпросмотр на ПК (D-169): шаблоны init-скрипта и теста, команда для POSIX — настоящим
 * `sh` с двойником `gradlew`, для Windows — сценарий PowerShell, разбор `index.tsv`.
 * Живой рендер — [realProjectRenders] на Android-проекте с `gradlew` (`-Ddpai.test.preview`).
 */
class PcPreviewTest {
    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    @Test
    fun templatesCarryTheContract() {
        val init = PcPreview.initScript()
        for (key in listOf("dpai.preview.module", "dpai.preview.src", "dpai.preview.out", "dpai.preview.compose", "dpai.preview.layouts", "robolectric:4.17", "includeAndroidResources")) assertTrue(key in init, key)
        val test = PcPreview.testSource()
        assertTrue("package dpai.preview;" in test && "class DpaiRenderPreviews" in test, "класс — ${PcPreview.TEST_CLASS}")
        assertTrue("index.tsv" in test && "GraphicsMode.Mode.NATIVE" in test)
        assertEquals(":app", PcPreview.modulePath("app"))
        assertEquals(":feature:home", PcPreview.modulePath("/feature/home/"))
    }

    @Test
    fun locatesModuleAndProject() {
        val files = setOf("settings.gradle.kts", "app/build.gradle.kts", "android/settings.gradle", "android/feature/home/build.gradle", "solo/settings.gradle", "solo/build.gradle")
        assertEquals(PcPreview.Place("", ":app"), PcPreview.locate("app/src/main/java/a/Main.kt", files::contains))
        assertEquals(PcPreview.Place("android", ":feature:home"), PcPreview.locate("android/feature/home/src/main/res/layout/card.xml", files::contains))
        assertEquals(PcPreview.Place("solo", ":"), PcPreview.locate("solo/src/main/kotlin/A.kt", files::contains))
        assertNull(PcPreview.locate("lib/src/A.kt", setOf("lib/build.gradle")::contains), "нет settings — не проект")
        assertNull(PcPreview.locate("x/A.kt", emptySet<String>()::contains))
        assertEquals(":testDebugUnitTest", ok(PcPreview.command(TargetPlatform.POSIX, "/p", ":", "i", "s", "o", listOf("a.BKt.P"), emptyList()))[7])
        assertEquals("C:/Users/me/proj/android", PcPreview.pcDir(TargetPlatform.WINDOWS, "C:\\Users\\me\\proj\\", "C:/Users/me", "android"))
        assertEquals("/home/me/proj", PcPreview.pcDir(TargetPlatform.POSIX, "~/proj", "/home/me", ""))
        assertEquals("/home/me/x", PcPreview.pcDir(TargetPlatform.POSIX, "~", "/home/me/", "x"))
        assertEquals("/srv/p", PcPreview.pcDir(TargetPlatform.POSIX, "/srv/p/", "/home/me", ""))
    }

    @Test
    fun posixCommandRunsWrapperWithArguments(@TempDir dir: File) {
        val project = File(dir, "my project").apply { mkdirs() }
        File(project, "gradlew").apply { writeText("#!/bin/sh\nprintf '%s\\n' \"\$PWD\" \"\$@\"\n"); setExecutable(true) }
        val argv = ok(PcPreview.command(TargetPlatform.POSIX, project.path, ":app", "/h/.dpmetaapp/preview/init.gradle", "/h/.dpmetaapp/preview/src", "/h/.dpmetaapp/preview/out", listOf("a.b.MainKt.P1", "a.b.MainKt.P2"), listOf("card")))
        val r = ok(LocalProcessHost().run(ProcessSpec(argv), 30.seconds))
        assertTrue(r.ok, r.toString())
        assertEquals(
            listOf(project.path, "-q", "-I", "/h/.dpmetaapp/preview/init.gradle", ":app:testDebugUnitTest", "--tests", PcPreview.TEST_CLASS,
                "-Pdpai.preview.module=:app", "-Pdpai.preview.src=/h/.dpmetaapp/preview/src", "-Pdpai.preview.out=/h/.dpmetaapp/preview/out",
                "-Pdpai.preview.compose=a.b.MainKt.P1,a.b.MainKt.P2", "-Pdpai.preview.layouts=card"),
            r.stdout,
        )
        assertIs<Outcome.Failure>(PcPreview.command(TargetPlatform.POSIX, "/p", ":app", "i", "s", "o", listOf("x; rm"), emptyList()))
        assertIs<Outcome.Failure>(PcPreview.command(TargetPlatform.POSIX, "/p", ":app", "i", "s", "o", emptyList(), listOf("Bad-Name")))
        assertIs<Outcome.Failure>(PcPreview.command(TargetPlatform.POSIX, "/p", "app;x", "i", "s", "o", emptyList(), listOf("card")))
        assertIs<Outcome.Failure>(PcPreview.command(TargetPlatform.POSIX, "/p", ":app", "i", "s", "o", emptyList(), emptyList()))
    }

    @Test
    fun windowsCommandIsEncodedPowerShell() {
        val argv = ok(PcPreview.command(TargetPlatform.WINDOWS, "C:\\Users\\O'Neil\\proj", ":app", "C:/Users/O'Neil/.dpmetaapp/preview/init.gradle", "C:/s", "C:/o", listOf("a.MainKt.P"), emptyList()))
        assertEquals("powershell.exe", argv.first())
        val script = String(java.util.Base64.getDecoder().decode(argv.last()), Charsets.UTF_16LE)
        assertTrue("Set-Location -LiteralPath 'C:\\Users\\O''Neil\\proj'" in script, script)
        assertTrue("& .\\gradlew.bat -q -I 'C:/Users/O''Neil/.dpmetaapp/preview/init.gradle' ':app:testDebugUnitTest'" in script, script)
        assertTrue("'-Pdpai.preview.compose=a.MainKt.P'" in script && "exit \$LASTEXITCODE" in script, script)
        val joined = com.dpsmpx.dpai.execution.ssh.RemoteShell.join(TargetPlatform.WINDOWS, argv)
        assertIs<Outcome.Success<String>>(joined)
        assertEquals("C:/Users/me", PcPreview.localPath(TargetPlatform.WINDOWS, "/C:/Users/me"))
        assertEquals("/home/me", PcPreview.localPath(TargetPlatform.POSIX, "/home/me"))
    }

    @Test
    fun indexParsesRenderedAndFailed() {
        val rows = PcPreview.parseIndex(
            "compose\tcom.example.demo.GreetingKt.GreetingPreview\tcompose-GreetingPreview.png\tok\n" +
                "compose\tcom.example.demo.GreetingKt.Missing\t\tNoSuchMethodException: …\n" +
                "layout\tcard\tlayout-card.png\tok\n" +
                "layout\tx\t../../etc/passwd\tok\n" +
                "мусор\n",
        )
        assertEquals(4, rows.size)
        assertEquals("compose-GreetingPreview.png", rows[0].file)
        assertNull(rows[1].file)
        assertTrue(rows[1].error!!.startsWith("NoSuchMethodException"))
        assertEquals("layout-card.png", rows[2].file)
        assertNull(rows[3].file, "путь за пределы out — не файл")
    }

    @Test
    fun cleanupRemovesOnlyTheFinishedRun(@TempDir home: File) {
        val nonce = "a".repeat(32)
        val run = File(home, "${PcPreview.DIR}/$nonce").apply { mkdirs() }
        File(run, "picture.png").writeText("image")
        val keep = File(home, "${PcPreview.DIR}/${"b".repeat(32)}").apply { mkdirs() }
        File(keep, "picture.png").writeText("other run")
        val command = ok(PcPreview.cleanupCommand(TargetPlatform.POSIX, home.path, nonce))
        assertTrue(ok(LocalProcessHost().run(ProcessSpec(command), 1.minutes)).ok)
        assertFalse(run.exists())
        assertTrue(keep.exists())
        assertIs<Outcome.Failure>(PcPreview.cleanupCommand(TargetPlatform.POSIX, "/home/me/..", nonce))
        assertIs<Outcome.Failure>(PcPreview.cleanupCommand(TargetPlatform.POSIX, home.path, "../other"))
        val windows = ok(PcPreview.cleanupCommand(TargetPlatform.WINDOWS, "C:/Users/O'Neil", nonce))
        val script = String(java.util.Base64.getDecoder().decode(windows.last()), Charsets.UTF_16LE)
        assertTrue("-LiteralPath 'C:/Users/O''Neil/${PcPreview.DIR}/$nonce'" in script)
    }

    @Test
    fun imageBatchFitsMobileMemoryEvenForLargeOrHostileDimensions() {
        for (count in 1..PcPreview.MAX_ITEMS) {
            for ((width, height) in listOf(720 to 4000, 4096 to 4096, 4095 to 4097, Int.MAX_VALUE to Int.MAX_VALUE)) {
                val sample = PcPreview.imageSample(width, height, count) ?: continue
                val w = (width.toLong() + sample - 1) / sample
                val h = (height.toLong() + sample - 1) / sample
                assertTrue(w * h * count <= PcPreview.MAX_PIXELS, "все $count изображений помещаются в общий предел")
            }
        }
        assertEquals(1, PcPreview.imageSample(720, 160, 20))
        assertNull(PcPreview.imageSample(-1, 100, 1))
        assertNull(PcPreview.imageSample(Int.MAX_VALUE, Int.MAX_VALUE, 1))
    }

    /**
     * Вся цепочка на настоящем проекте: шаблоны — в «домашний» каталог, команда
     * [PcPreview.command] — настоящим `sh`, итог — `index.tsv` и PNG. Проект — каталог
     * `-Ddpai.test.preview` (Android-модуль с Compose `@Preview` и разметкой `res/layout`,
     * `gradlew` в корне); первый файл с `@Preview` и первая разметка — из него же.
     */
    @Test
    fun realProjectRenders(@TempDir home: File) {
        val sample = System.getProperty("dpai.test.preview")
        assumeTrue(sample != null, "нужен -Ddpai.test.preview=<Android-проект с gradlew>")
        val root = File(sample!!)
        val kt = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && "/build/" !in it.path && "@Preview" in it.readText() }.first()
        val rel = kt.relativeTo(root).path
        val composables = ComposePreviews.find(kt.name, kt.readText()).previews.map { it.composable }
        val layout = root.walkTopDown().filter { it.isFile && it.parentFile.name == "layout" && "/build/" !in it.path }.first().name.removeSuffix(".xml")
        val dir = File(home, PcPreview.DIR)
        File(dir, PcPreview.INIT).apply { parentFile.mkdirs(); writeText(PcPreview.initScript()) }
        File(dir, PcPreview.TEST).apply { parentFile.mkdirs(); writeText(PcPreview.testSource()) }
        val out = File(dir, PcPreview.OUT).apply { mkdirs() }
        File(out, "compose-Stale.png").writeText("прошлый запуск")
        File(out, PcPreview.INDEX).writeText("")
        val place = assertNotNullPlace(PcPreview.locate(rel) { File(root, it).isFile })
        val argv = ok(PcPreview.command(TargetPlatform.POSIX, PcPreview.pcDir(TargetPlatform.POSIX, root.path, home.path, place.project), place.module,
            File(dir, PcPreview.INIT).path, File(dir, PcPreview.SRC).path, out.path, composables + "${composables.first().substringBeforeLast('.')}.NoSuchPreview", listOf(layout, "no_such_layout")))
        val r = ok(LocalProcessHost().run(ProcessSpec(argv), 15.minutes))
        assertTrue(r.ok, (r.stdout + r.stderr).takeLast(40).joinToString("\n"))
        val rows = PcPreview.parseIndex(File(out, PcPreview.INDEX).readText())
        assertEquals(composables.size + 3, rows.size, rows.toString())
        for (row in rows.filter { it.file != null }) {
            val png = File(out, row.file!!).readBytes()
            assertTrue(png.size > 1000 && png[1] == 'P'.code.toByte(), "PNG ${row.file}")
        }
        assertEquals(composables.size + 1, rows.count { it.file != null }, rows.toString())
        assertTrue(rows.single { it.name.endsWith("NoSuchPreview") }.error!!.startsWith("NoSuchMethodException"), rows.toString())
        assertTrue("no_such_layout" in rows.single { it.name == "no_such_layout" }.error!!, rows.toString())
        assertFalse(File(out, "compose-Stale.png").exists(), "картинки прошлого запуска убраны")
    }

    private fun assertNotNullPlace(p: PcPreview.Place?): PcPreview.Place = p ?: error("проект Gradle не найден")
}

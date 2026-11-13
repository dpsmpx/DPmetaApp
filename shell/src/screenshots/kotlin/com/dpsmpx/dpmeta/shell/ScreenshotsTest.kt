package com.dpsmpx.dpmeta.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.ui.Loader
import com.dpsmpx.dpmeta.app.ui.PultApp
import com.dpsmpx.dpmeta.app.ui.PultTheme
import com.dpsmpx.dpmeta.app.ui.PultView
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.Sync
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class ScreenshotsTest {
    private val dir = File(System.getProperty("screenshots.dir") ?: error("нет screenshots.dir")).apply { mkdirs() }
    private val corpus = Path.of(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem"))
    private val now = Timestamp(Instant.parse("2026-11-13T09:00:00Z").toEpochMilli())

    private fun state(): EcosystemState {
        val reg = (RegistryFormat.parse(Files.readString(corpus.resolve("registry.toml"))) as Outcome.Success).value
        val r = Sync.run(Sync.plan(reg), DirectoryReader(corpus), ManualClock(now))
        return EcosystemState.derive(reg, r.observations, r.failures, null, now)
    }

    private val loader = object : Loader {
        override fun directory(project: String, dir: String, done: (Outcome<List<com.dpsmpx.dpmeta.github.GitHub.Entry>>) -> Unit) = done(Outcome.success(emptyList()))
        override fun document(project: String, path: String, done: (Outcome<String>) -> Unit) = done(Outcome.success(""))
    }

    private fun pult(goTo: com.dpsmpx.dpai.ui.AppSection?, name: String) {
        val view = PultView(
            state(), "", hasToken = true, busy = false,
            ai = Screens.AiCounts(true, Measure.observed(2L), Measure.observed(1L), Measure.observed(0L)),
        )
        shoot(name) {
            PultTheme {
                PultApp(
                    view, loader, onSync = {}, onSaveToken = {}, onForgetToken = {}, onAi = {}, onIde = {}, goTo = goTo,
                    settingsDocument = { query, projects -> com.dpsmpx.dpai.android.SettingsDocument(projects, query) {} },
                )
            }
        }
    }

    private fun ai(tab: com.dpsmpx.dpai.ui.AiTab, name: String) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val model = com.dpsmpx.dpai.android.DemoModel.get(app)
        val controller = com.dpsmpx.dpai.android.Controller.get(app.filesDir)
        shoot(name) {
            com.dpsmpx.dpai.android.DpaiTheme {
                com.dpsmpx.dpai.android.DpaiApp(model, controller, com.dpsmpx.dpai.android.Incoming(tab = tab.name))
            }
        }
    }

    @Test
    fun pultSummary() = pult(null, "pult-summary")

    @Test
    fun pultProjects() = pult(com.dpsmpx.dpai.ui.AppSection.PROJECTS, "pult-projects")

    @Test
    fun pultSettings() = pult(com.dpsmpx.dpai.ui.AppSection.SETTINGS, "settings")

    @Test
    fun pultHelp() = pult(com.dpsmpx.dpai.ui.AppSection.HELP, "help")

    @Test
    fun aiSummary() = ai(com.dpsmpx.dpai.ui.AiTab.SUMMARY, "ai-summary")

    @Test
    fun aiTasks() = ai(com.dpsmpx.dpai.ui.AiTab.TASKS, "ai-tasks")

    @Test
    fun aiFiles() = ai(com.dpsmpx.dpai.ui.AiTab.FILES, "ide-files")

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent(content = content)
        val looper = shadowOf(Looper.getMainLooper())
        repeat(20) { looper.idleFor(java.time.Duration.ofMillis(100)) }
        save(activity, name)
    }

    private fun save(activity: ComponentActivity, name: String) {
        val v = activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package com.dpsmpx.dpmeta.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.ui.Loader
import com.dpsmpx.dpmeta.app.ui.PultApp
import com.dpsmpx.dpmeta.app.ui.PultTheme
import com.dpsmpx.dpmeta.app.ui.PultView
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpai.core.serialization.JsonParser
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
        val reg = ProjectList.parse(JsonParser().parse(Files.readString(corpus.resolve("projects.json"))).valueOrNull()!!).valueOrNull()!!.forSync()
        val r = Sync.run(Sync.plan(reg), DirectoryReader(corpus), ManualClock(now))
        return EcosystemState.derive(reg, r.observations, r.failures, null, now)
    }

    private val loader = object : Loader {
        override fun directory(project: String, dir: String, done: (Outcome<List<com.dpsmpx.dpmeta.github.GitHub.Entry>>) -> Unit) = done(Outcome.success(emptyList()))
        override fun document(project: String, path: String, done: (Outcome<String>) -> Unit) = done(Outcome.success(""))
    }

    /** Снимок: имя PNG и экран. */
    private class Shot(val name: String, val prepare: () -> Unit = {}, val content: @Composable () -> Unit)

    private fun pult(goTo: com.dpsmpx.dpai.ui.AppSection?, name: String): Shot {
        val view = PultView(
            state(), "", hasToken = true, busy = false,
            ai = Screens.AiCounts(true, Measure.observed(2L), Measure.observed(1L), Measure.observed(0L)),
        )
        return Shot(name) {
            PultTheme {
                PultApp(
                    view, loader, onSync = {}, onSaveToken = {}, onForgetToken = {}, onAi = {}, onIde = {}, goTo = goTo,
                    settingsDocument = { query, projects -> com.dpsmpx.dpai.android.SettingsDocument(projects, query) {} },
                )
            }
        }
    }

    private fun ai(tab: com.dpsmpx.dpai.ui.AiTab, name: String): Shot {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val model = com.dpsmpx.dpai.android.DemoModel.get(app)
        val controller = com.dpsmpx.dpai.android.Controller.get(app.filesDir)
        return Shot(name) {
            com.dpsmpx.dpai.android.DpaiTheme {
                com.dpsmpx.dpai.android.DpaiApp(model, controller, com.dpsmpx.dpai.android.Incoming(tab = tab.name))
            }
        }
    }

    /** Локальное синтетическое дерево: без установки Linux, SSH и исполнения команд. */
    private fun workspace(full: Boolean): Shot {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val model = com.dpsmpx.dpai.android.DemoModel.get(app)
        val controller = com.dpsmpx.dpai.android.Controller.get(app.filesDir)
        return Shot(if (full) "workspace-full" else "workspace-simple", prepare = {
            if (controller.project() == null) {
                val target = controller.addInApp(com.dpsmpx.dpai.core.ids.IdKinds.TARGET.trusted("tgt_" + "0".repeat(26))).valueOrNull()!!
                val project = com.dpsmpx.dpai.ui.ProjectEntry(
                    com.dpsmpx.dpai.core.ids.IdKinds.PROJECT.trusted("prj_" + "0".repeat(26)), com.dpsmpx.dpai.core.ids.IdKinds.REPOSITORY.trusted("rep_" + "0".repeat(26)),
                    target.id, "/root/projects/sample", "/root/worktrees/sample", "feature/sample", emptyList())
                check(controller.putProject(project) is Outcome.Success)
                File(com.dpsmpx.dpai.android.GuestPaths.home(app), "projects/sample").apply { mkdirs() }
                    .resolve("README.md").writeText("# Sample project\n")
            }
            val look = com.dpsmpx.dpai.android.Looks.read(app)
            com.dpsmpx.dpai.android.Looks.write(app, look.copy(presentation = if (full) com.dpsmpx.dpai.ui.UiLook.Presentation.FULL else com.dpsmpx.dpai.ui.UiLook.Presentation.SIMPLE))
        }) {
            com.dpsmpx.dpai.android.DpaiTheme {
                com.dpsmpx.dpai.android.DpaiApp(model, controller, com.dpsmpx.dpai.android.Incoming(tab = "FILES", workspace = true))
            }
        }
    }

    /**
     * Все экраны — одним тестом в одном окне: диспетчер Compose запоминает Choreographer
     * первого теста песочницы Robolectric, и в следующих тестах эффекты (переход пульта,
     * вкладка «AI», загрузка данных) не идут. Каждый экран — своя композиция (`key`):
     * состояние прежнего снимка не переносится.
     */
    @Test
    fun screens() {
        val shots = listOf(
            pult(null, "pult-summary"),
            pult(com.dpsmpx.dpai.ui.AppSection.PROJECTS, "pult-projects"),
            pult(com.dpsmpx.dpai.ui.AppSection.SETTINGS, "settings"),
            pult(com.dpsmpx.dpai.ui.AppSection.HELP, "help"),
            ai(com.dpsmpx.dpai.ui.AiTab.SUMMARY, "ai-summary"),
            ai(com.dpsmpx.dpai.ui.AiTab.TASKS, "ai-tasks"),
            workspace(false),
            workspace(true),
            // Редактор с открытым файлом — позже: в песочнице список файлов демо не загружается, а крутилка
            // загрузки — бесконечная анимация, на каждом кадре Robolectric двигает часы (docs/SCREENSHOTS.md).
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var current by mutableStateOf(shots.first())
        activity.setContent { key(current.name) { current.content() } }
        for (shot in shots) {
            shot.prepare()
            current = shot
            settle()
            save(activity, shot.name)
        }
    }

    /**
     * Дать экрану устояться: эффекты Compose идут по главному циклу (его часы — фиктивные),
     * данные «AI» грузятся в фоновых потоках по настоящему времени. Главный цикл
     * прокручивается шагами, между ними — настоящее ожидание (без системных часов и сна).
     */
    private fun settle(seconds: Int = 4) {
        val looper = shadowOf(Looper.getMainLooper())
        val tick = java.util.concurrent.Semaphore(0)
        repeat(seconds * 20) {
            looper.idleFor(java.time.Duration.ofMillis(50))
            tick.tryAcquire(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        looper.idle()
    }

    private fun save(activity: ComponentActivity, name: String) {
        val v = activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

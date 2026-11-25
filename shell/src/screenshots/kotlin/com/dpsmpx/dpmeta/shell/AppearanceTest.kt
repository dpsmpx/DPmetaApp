package com.dpsmpx.dpmeta.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.dpsmpx.dpai.android.DpaiTheme
import com.dpsmpx.dpai.android.LookCard
import com.dpsmpx.dpai.android.Looks
import com.dpsmpx.dpai.ui.UiLook
import com.dpsmpx.dpmeta.app.ui.PultTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Обе оболочки читают одни настройки; проверяется реальная композиция, а не копия палитры. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi", application = Application::class)
class AppearanceTest {
    @Test fun themesAndPresentationsAgreeAcrossShellsAndNativeChrome() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val revision = mutableStateOf(0)
        var composed = -1
        var pultScheme: androidx.compose.material3.ColorScheme? = null
        var pultDensity: androidx.compose.ui.unit.Density? = null
        activity.setContent {
            key(revision.value) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    PultTheme {
                        val scheme = MaterialTheme.colorScheme
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        SideEffect { pultScheme = scheme; pultDensity = density }
                    }
                    DpaiTheme {
                        val ide = MaterialTheme.colorScheme
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val minimum = androidx.compose.ui.platform.LocalViewConfiguration.current.minimumTouchTargetSize
                        SideEffect {
                            val native = Looks.palette(app)
                            assertEquals(pultScheme?.background, ide.background)
                            assertEquals(pultScheme?.primary, ide.primary)
                            assertEquals(pultDensity?.density, density.density)
                            assertEquals(pultDensity?.fontScale, density.fontScale)
                            assertEquals(native.background.toInt(), ide.background.toArgb())
                            assertEquals(native.primary.toInt(), ide.primary.toArgb())
                            kotlin.test.assertTrue(minimum.width.value * UiLook.Density.COMPACT.factor >= 48f)
                            composed = revision.value
                        }
                        Surface(Modifier.fillMaxSize()) {
                            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { LookCard() }
                        }
                    }
                }
            }
        }
        val dir = File(System.getProperty("screenshots.dir"), "appearance").apply { mkdirs() }
        for (theme in UiLook.Theme.entries) for (presentation in UiLook.Presentation.entries) {
            Looks.write(app, UiLook(theme = theme, accent = UiLook.Accent.WALLPAPER, text = UiLook.TextSize.XL,
                density = UiLook.Density.COMPACT, presentation = presentation))
            revision.value++
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertEquals(revision.value, composed, "композиция должна обновиться после смены оформления")
            val native = Looks.palette(app)
            // Проверяем настоящий internal-адаптер Android, не расширяя продуктовый API ради теста.
            val languages = Class.forName("com.dpsmpx.dpai.android.EditorLanguages")
            val singleton = languages.getField("INSTANCE").get(null)
            check(languages.getMethod("prepare", android.content.Context::class.java).invoke(singleton, app) is com.dpsmpx.dpai.core.result.Outcome.Success<*>)
            val scheme = languages.getMethod("scheme", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                com.dpsmpx.dpai.ui.AppPalette.Scheme::class.java).invoke(singleton, Looks.dark(app), true, native)
            // Повторная привязка Sora сбрасывает свои цвета: палитра приложения должна сохраниться.
            val baseScheme = Class.forName("io.github.rosemoe.sora.widget.schemes.EditorColorScheme")
            baseScheme.getMethod("applyDefault").invoke(scheme)
            for ((role, expected) in listOf("WHOLE_BACKGROUND" to native.background, "LINE_NUMBER_BACKGROUND" to native.background,
                "TEXT_NORMAL" to native.onSurface, "SELECTION_INSERT" to native.primary)) {
                val id = baseScheme.getField(role).getInt(null)
                assertEquals(expected.toInt(), baseScheme.getMethod("getColor", Int::class.javaPrimitiveType).invoke(scheme, id))
            }
            if (theme == UiLook.Theme.BLACK) assertEquals(0xff000000.toInt(), native.background.toInt())
            if (theme == UiLook.Theme.LIGHT) assertEquals(0xffffffff.toInt(), native.background.toInt())
            assertNotEquals(native.background, native.onBackground)
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(dir, "${theme.key}-${presentation.key}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        activity.finish()
    }
}

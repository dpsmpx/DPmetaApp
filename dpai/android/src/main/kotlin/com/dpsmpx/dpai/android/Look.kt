package com.dpsmpx.dpai.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import com.dpsmpx.dpai.ui.AppPalette
import com.dpsmpx.dpai.ui.UiLook

/** Оформление в дереве Compose: шрифт редактора и прочее, что читают экраны (`DPmetaApp:D-115`). */
val LocalUiLook = staticCompositionLocalOf { UiLook() }

/** Настройка «Оформление» — файл [UiLook.PREFS], общий с пультом. */
object Looks {
    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(UiLook.PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): UiLook = UiLook.fromMap(prefs(context).all)

    fun write(context: Context, look: UiLook) = prefs(context).edit { look.toMap().forEach { (k, v) -> putString(k, v) } }

    /** Текущее оформление; меняется сразу во всех открытых окнах приложения. */
    @Composable
    fun remember(): UiLook {
        val context = LocalContext.current.applicationContext
        var look by remember { mutableStateOf(read(context)) }
        DisposableEffect(context) {
            // Слушатель держится ссылкой: SharedPreferences хранит слабую.
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> look = read(context) }
            prefs(context).registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs(context).unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return look
    }
}

/**
 * Тема всего приложения (`DPmetaApp:D-115`; D-145): нейтральные цвета прототипа и
 * акцент из «Оформления»; «Обои телефона» — цвета обоев (Android 12+, D-74); тема,
 * размер текста и плотность — оттуда же. Смысловые цвета — `toneColor` (`AppPalette`).
 */
@Composable
fun DpaiTheme(content: @Composable () -> Unit) {
    val look = Looks.remember()
    val dark = when (look.theme) {
        UiLook.Theme.SYSTEM -> isSystemInDarkTheme()
        UiLook.Theme.LIGHT -> false
        UiLook.Theme.DARK -> true
    }
    val context = LocalContext.current
    val scheme = if (look.accent == UiLook.Accent.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        schemeOf(UiLook.scheme(look.accent, dark), dark)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Значки строки состояния — под выбранную тему, а не под тему телефона.
            view.context.activity()?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val d = LocalDensity.current
    // Плотность сжимает dp, а размер текста — только sp: деление возвращает тексту выбранный размер.
    val density = Density(d.density * look.density.factor, d.fontScale * look.text.factor / look.density.factor)
    CompositionLocalProvider(LocalDensity provides density, LocalUiLook provides look) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Схема Material 3 из ролей `AppPalette.Scheme` — все роли, а не три цвета поверх фиолетовых по умолчанию (UX-5). */
fun schemeOf(p: AppPalette.Scheme, dark: Boolean): ColorScheme {
    fun c(v: Long) = Color(v)
    return if (dark) {
        darkColorScheme(
            primary = c(p.primary), onPrimary = c(p.onPrimary), primaryContainer = c(p.primaryContainer), onPrimaryContainer = c(p.onPrimaryContainer),
            secondary = c(p.secondary), onSecondary = c(p.onSecondary), secondaryContainer = c(p.secondaryContainer), onSecondaryContainer = c(p.onSecondaryContainer),
            tertiary = c(p.tertiary), onTertiary = c(p.onTertiary), tertiaryContainer = c(p.tertiaryContainer), onTertiaryContainer = c(p.onTertiaryContainer),
            background = c(p.background), onBackground = c(p.onBackground), surface = c(p.surface), onSurface = c(p.onSurface),
            surfaceVariant = c(p.surfaceVariant), onSurfaceVariant = c(p.onSurfaceVariant), outline = c(p.outline), outlineVariant = c(p.outlineVariant),
            error = c(p.error), onError = c(p.onError), errorContainer = c(p.errorContainer), onErrorContainer = c(p.onErrorContainer),
            surfaceContainerLowest = c(p.surfaceContainerLowest), surfaceContainerLow = c(p.surfaceContainerLow), surfaceContainer = c(p.surfaceContainer),
            surfaceContainerHigh = c(p.surfaceContainerHigh), surfaceContainerHighest = c(p.surfaceContainerHighest),
            inverseSurface = c(p.inverseSurface), inverseOnSurface = c(p.inverseOnSurface), inversePrimary = c(p.inversePrimary),
        )
    } else {
        lightColorScheme(
            primary = c(p.primary), onPrimary = c(p.onPrimary), primaryContainer = c(p.primaryContainer), onPrimaryContainer = c(p.onPrimaryContainer),
            secondary = c(p.secondary), onSecondary = c(p.onSecondary), secondaryContainer = c(p.secondaryContainer), onSecondaryContainer = c(p.onSecondaryContainer),
            tertiary = c(p.tertiary), onTertiary = c(p.onTertiary), tertiaryContainer = c(p.tertiaryContainer), onTertiaryContainer = c(p.onTertiaryContainer),
            background = c(p.background), onBackground = c(p.onBackground), surface = c(p.surface), onSurface = c(p.onSurface),
            surfaceVariant = c(p.surfaceVariant), onSurfaceVariant = c(p.onSurfaceVariant), outline = c(p.outline), outlineVariant = c(p.outlineVariant),
            error = c(p.error), onError = c(p.onError), errorContainer = c(p.errorContainer), onErrorContainer = c(p.onErrorContainer),
            surfaceContainerLowest = c(p.surfaceContainerLowest), surfaceContainerLow = c(p.surfaceContainerLow), surfaceContainer = c(p.surfaceContainer),
            surfaceContainerHigh = c(p.surfaceContainerHigh), surfaceContainerHighest = c(p.surfaceContainerHighest),
            inverseSurface = c(p.inverseSurface), inverseOnSurface = c(p.inverseOnSurface), inversePrimary = c(p.inversePrimary),
        )
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

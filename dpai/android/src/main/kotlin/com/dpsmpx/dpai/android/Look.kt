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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    fun dark(context: Context, look: UiLook = read(context)): Boolean = look.dark(
        context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)

    /** Нативные окна используют те же нейтральные поверхности и выбранный акцент. */
    fun palette(context: Context, look: UiLook = read(context)): AppPalette.Scheme {
        val dark = dark(context, look)
        val adaptive = if (look.accent == UiLook.Accent.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
            (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary.toArgb().toLong() and 0xffffffffL
        } else null
        return look.palette(dark, adaptive)
    }


    /** Нативные элементы: значения/выделение и обработчики не пересоздаются. */
    fun styleNative(view: android.view.View, palette: AppPalette.Scheme) {
        if (view is TerminalView) { view.applyPalette(palette); return }
        if (view is android.widget.TextView) {
            view.setTextColor((if (view.isSelected) palette.primary else palette.onSurface).toInt())
            view.setHintTextColor(palette.onSurfaceVariant.toInt())
        }
        if (view is android.widget.EditText) {
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.primary.toInt())
        } else if (view.background != null) {
            // Сохраняем форму, ripple и состояния фокуса исходного drawable.
            view.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.surfaceContainerHigh.toInt())
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) styleNative(view.getChildAt(i), palette)
    }

    fun nativeContext(context: Context): Context = android.view.ContextThemeWrapper(context,
        if (dark(context)) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)

    fun styleDialog(dialog: android.app.AlertDialog) {
        val palette = palette(dialog.context)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.surfaceContainerHigh.toInt()))
            styleNative(decorView, palette)
        }
        for (which in listOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE, android.content.DialogInterface.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.setTextColor(palette.primary.toInt())
        }
    }

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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DpaiTheme(content: @Composable () -> Unit) {
    val look = Looks.remember()
    val dark = look.dark(isSystemInDarkTheme())
    val context = LocalContext.current
    val scheme = schemeOf(Looks.palette(context, look), dark)
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
    val original = androidx.compose.ui.platform.LocalViewConfiguration.current
    val minimum = (48f / look.density.factor).dp
    val config = remember(original, minimum) { object : androidx.compose.ui.platform.ViewConfiguration by original {
        override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize(minimum, minimum)
    } }
    CompositionLocalProvider(LocalDensity provides density, LocalUiLook provides look,
        androidx.compose.ui.platform.LocalViewConfiguration provides config,
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides minimum) {
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

/** Смысловые цвета и нативный редактор следуют выбранной теме, включая чёрную. */
@Composable internal fun appDark(): Boolean = LocalUiLook.current.dark(isSystemInDarkTheme())

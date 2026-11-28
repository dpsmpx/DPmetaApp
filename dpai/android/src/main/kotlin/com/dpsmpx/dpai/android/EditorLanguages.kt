package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.CodeSyntax
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

/** Локальные данные; независимые палитры не меняют подсветку соседнего поля/просмотра (D-138). */
internal object EditorLanguages {
    private class Palette(val grammars: GrammarRegistry, val themes: ThemeRegistry) {
        val scheme: StudioScheme by lazy { StudioScheme(themes) }
    }
    private val palettes = linkedMapOf<String, Palette>()
    @Volatile private var ready = false
    @Synchronized fun prepare(context: Context): Outcome<Unit> {
        if (ready) return Outcome.success(Unit)
        return try {
            val assets = context.applicationContext.assets
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            palettes.clear()
            for (mode in listOf("dark", "light", "dark-plain", "light-plain")) {
                val model = ThemeModel(IThemeSource.fromInputStream(assets.open("editor/$mode.theme.json"), "$mode.theme.json", Charsets.UTF_8), "studio-$mode")
                val themes = ThemeRegistry().apply { loadTheme(model) }
                val grammars = GrammarRegistry(null).apply { setTheme(model) }
                for (id in listOf("cpp", "kotlin", "java", "python", "json")) {
                    grammars.loadGrammar(DefaultGrammarDefinition.withLanguageConfiguration(
                        IGrammarSource.fromInputStream(assets.open("editor/$id.json"), "$id.json", Charsets.UTF_8),
                        "editor/$id.config.json", id, "source.$id"))
                }
                palettes[mode] = Palette(grammars, themes)
            }
            ready = true
            Outcome.success(Unit)
        } catch (_: Exception) { Outcome.failure(DiagCode.UNSUPPORTED, "Подсветка не загружена; текст доступен для правки") }
    }
    private fun palette(dark: Boolean, highlighted: Boolean) = palettes.getValue((if (dark) "dark" else "light") + if (highlighted) "" else "-plain")
    fun language(path: String, width: Int, tabs: Boolean, dark: Boolean, highlighted: Boolean): Language {
        val id = when (CodeSyntax.language(path)) { "C/C++" -> "cpp"; "Kotlin" -> "kotlin"; "Java" -> "java"; "Python" -> "python"; "JSON" -> "json"; else -> return EmptyLanguage() }
        val palette = palette(dark, highlighted)
        return TextMateLanguage.create("source.$id", palette.grammars, palette.themes, false).apply {
            setAutoCompleteEnabled(false)
            setTabSize(width)
            useTab(tabs)
        }
    }
    /** Цвета токенов — из грамматики; поверхности и действия — из общей темы студии. */
    fun scheme(dark: Boolean, highlighted: Boolean, chrome: com.dpsmpx.dpai.ui.AppPalette.Scheme): TextMateColorScheme =
        palette(dark, highlighted).scheme.apply { this.chrome = chrome }

    /** Sora повторно вызывает applyDefault при attach: общая тема должна пережить этот сброс. */
    private class StudioScheme(themes: ThemeRegistry) : TextMateColorScheme(themes, themes.currentThemeModel) {
        var chrome: com.dpsmpx.dpai.ui.AppPalette.Scheme? = null
            set(value) { field = value; applyChrome() }
        override fun applyDefault() { super.applyDefault(); applyChrome() }
        private fun applyChrome() {
            // Базовый конструктор вызывает virtual applyDefault до инициализации полей Kotlin.
            val p = chrome ?: return
            fun color(id: Int, argb: Long) = setColor(id, argb.toInt())
            color(EditorColorScheme.WHOLE_BACKGROUND, p.background)
            color(EditorColorScheme.MINIMAP_BACKGROUND, p.background)
            color(EditorColorScheme.TEXT_NORMAL, p.onSurface)
            color(EditorColorScheme.LINE_NUMBER_BACKGROUND, p.background)
            color(EditorColorScheme.LINE_NUMBER, p.onSurfaceVariant)
            color(EditorColorScheme.LINE_NUMBER_CURRENT, p.primary)
            color(EditorColorScheme.LINE_DIVIDER, p.outlineVariant)
            color(EditorColorScheme.CURRENT_LINE, p.surfaceContainerHigh)
            color(EditorColorScheme.SELECTION_INSERT, p.primary)
            color(EditorColorScheme.SELECTION_HANDLE, p.primary)
            color(EditorColorScheme.SELECTED_TEXT_BACKGROUND, p.primaryContainer)
            color(EditorColorScheme.TEXT_SELECTED, p.onPrimaryContainer)
            color(EditorColorScheme.HOVER_BACKGROUND, p.surfaceContainerHigh)
            color(EditorColorScheme.HOVER_TEXT_NORMAL, p.onSurface)
            color(EditorColorScheme.HOVER_BORDER, p.outline)
            color(EditorColorScheme.SIGNATURE_BACKGROUND, p.surfaceContainerHigh)
            color(EditorColorScheme.SIGNATURE_TEXT_NORMAL, p.onSurface)
            color(EditorColorScheme.SIGNATURE_TEXT_HIGHLIGHTED_PARAMETER, p.primary)
            color(EditorColorScheme.SIGNATURE_BORDER, p.outline)
            color(EditorColorScheme.HOVER_TEXT_HIGHLIGHTED, p.primary)
            color(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, p.surfaceContainerHigh)
            color(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, p.onSurface)
            color(EditorColorScheme.LINE_NUMBER_PANEL, p.surfaceContainerHigh)
            color(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, p.onSurface)
            color(EditorColorScheme.SCROLL_BAR_TRACK, p.background)
            color(EditorColorScheme.SCROLL_BAR_THUMB, p.outline)
            color(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, p.primary)
            color(EditorColorScheme.COMPLETION_WND_BACKGROUND, p.surfaceContainerHigh)
            color(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, p.onSurface)
            color(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, p.onSurfaceVariant)
            color(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BACKGROUND, p.surfaceContainerHigh)
            color(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG, p.onSurface)
            color(EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG, p.onSurfaceVariant)
            color(EditorColorScheme.DIAGNOSTIC_TOOLTIP_ACTION, p.primary)
        }
    }
}

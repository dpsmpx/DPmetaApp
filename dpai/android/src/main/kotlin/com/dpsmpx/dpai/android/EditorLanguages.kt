package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.CodeSyntax
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
        val scheme: TextMateColorScheme by lazy { TextMateColorScheme.create(themes) }
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
    fun scheme(dark: Boolean, highlighted: Boolean = true): TextMateColorScheme = palette(dark, highlighted).scheme
}

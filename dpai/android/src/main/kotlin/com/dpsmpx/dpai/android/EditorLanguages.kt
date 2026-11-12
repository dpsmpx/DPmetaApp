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

/** Все грамматики — локальные собственные данные APK. Подготовка вне UI, сеть не используется (D-136). */
internal object EditorLanguages {
    @Volatile private var ready = false
    @Synchronized fun prepare(context: Context): Outcome<Unit> {
        if (ready) return Outcome.success(Unit)
        return try {
            val assets = context.applicationContext.assets
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            val grammars = GrammarRegistry.getInstance()
            for (id in listOf("cpp", "kotlin", "java", "python", "json")) {
                grammars.loadGrammar(DefaultGrammarDefinition.withLanguageConfiguration(
                    IGrammarSource.fromInputStream(assets.open("editor/$id.json"), "$id.json", Charsets.UTF_8),
                    "editor/$id.config.json", id, "source.$id"))
            }
            for (id in listOf("dark", "light", "dark-plain", "light-plain")) {
                ThemeRegistry.getInstance().loadTheme(ThemeModel(IThemeSource.fromInputStream(
                    assets.open("editor/$id.theme.json"), "$id.theme.json", Charsets.UTF_8), "studio-$id"))
            }
            ready = true
            Outcome.success(Unit)
        } catch (_: Exception) { Outcome.failure(DiagCode.UNSUPPORTED, "Подсветка не загружена; текст доступен для правки") }
    }
    fun language(path: String, width: Int, tabs: Boolean): Language {
        val id = when (CodeSyntax.language(path)) { "C/C++" -> "cpp"; "Kotlin" -> "kotlin"; "Java" -> "java"; "Python" -> "python"; "JSON" -> "json"; else -> return EmptyLanguage() }
        return TextMateLanguage.create("source.$id", false).apply {
            setAutoCompleteEnabled(false)
            setTabSize(width)
            useTab(tabs) // Тот же объект использует обработчик Enter, не только внешнее поле.
        }
    }
    fun scheme(dark: Boolean, highlighted: Boolean = true): TextMateColorScheme {
        ThemeRegistry.getInstance().setTheme((if (dark) "studio-dark" else "studio-light") + if (highlighted) "" else "-plain")
        return TextMateColorScheme.create(ThemeRegistry.getInstance())
    }
}

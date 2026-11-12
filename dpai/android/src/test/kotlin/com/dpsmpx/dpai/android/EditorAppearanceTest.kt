package com.dpsmpx.dpai.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.eclipse.tm4e.core.internal.theme.Theme
import org.eclipse.tm4e.core.internal.theme.raw.RawThemeReader
import org.eclipse.tm4e.core.registry.IThemeSource

class EditorAppearanceTest {
    @Test fun plainThemesKeepReadableForegroundWithoutSyntaxFontStyles() {
        for ((mode, foreground) in listOf("dark" to "#E6E1E5", "light" to "#1C1B1F")) {
            val file = File(System.getProperty("dpai.editor.assets"), "editor/$mode-plain.theme.json")
            val theme = file.inputStream().use { input ->
                Theme.createFromRawTheme(RawThemeReader.readTheme(IThemeSource.fromInputStream(input, file.name, Charsets.UTF_8)), null)
            }
            assertEquals(foreground, theme.colorMap[theme.defaults.foregroundId].uppercase())
            assertEquals(0, theme.defaults.fontStyle)
        }
    }
}

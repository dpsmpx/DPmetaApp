package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.app.screens.Links
import com.dpsmpx.dpmeta.app.screens.Links.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Переходы в документах (F-09): пути, якоря, `Проект:путь`. */
class LinksTest {
    @Test
    fun relativeAbsoluteAndAnchors() {
        assertEquals(Target.File("A", "docs/PROCESS.md", null), Links.resolve("A", "docs/direction/DECISIONS.md", "../PROCESS.md"))
        assertEquals(Target.File("A", "ROADMAP/ROADMAP.md", "task-3"), Links.resolve("A", "docs/x.md", "/ROADMAP/ROADMAP.md#task-3"))
        assertEquals(Target.File("A", "TODO.md", null), Links.resolve("A", "README.md", "./TODO.md"))
        assertEquals(Target.Anchor("d-53"), Links.resolve("A", "README.md", "#d-53"))
        assertEquals(Target.External("https://example.org/x"), Links.resolve("A", "README.md", "https://example.org/x"))
        assertNull(Links.resolve("A", "README.md", "../../etc/passwd"), "выход за корень — нет ссылки")
        assertNull(Links.resolve("A", "README.md", ""))
    }

    @Test
    fun crossRepositoryReferences() {
        val names = mapOf("DPmeta" to "DPmeta", "VoxelRPG" to "Galdhold", "Galdhold" to "Galdhold")
        assertEquals(Target.File("DPmeta", "docs/CONTRACTS.md", null), Links.crossRef("DPmeta:docs/CONTRACTS.md", names))
        assertEquals(Target.File("Galdhold", "README.md", "build"), Links.crossRef("VoxelRPG:README.md#build", names), "прежнее имя — тот же проект")
        assertNull(Links.crossRef("DPmeta:M-08", names), "номер решения — не путь к файлу")
        assertNull(Links.crossRef("Unknown:docs/x.md", names))
        assertNull(Links.crossRef("DPmeta:../x.md", names))
        assertNull(Links.crossRef("http://x", names))
    }

    @Test
    fun slugsAndCache() {
        assertEquals("d-53--один-apk-собирает-корень", Links.slug("D-53 — Один APK собирает корень"))
        assertEquals("этап-3-интерфейс-mvp--т", Links.slug("Этап 3. Интерфейс MVP — `Т`"))
        assertTrue(Links.cacheable("docs/PROCESS.md"))
        assertFalse(Links.cacheable("core/net/src/main/kotlin/Net.kt"), "код — без кэша")
    }
}

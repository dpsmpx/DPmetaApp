package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.app.screens.Panels
import com.dpsmpx.dpmeta.formats.Panel
import com.dpsmpx.dpmeta.github.GitHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Пункты панели проекта (F-10): строки из синтетических файлов и каталогов. */
class PanelsTest {
    private fun item(kind: Panel.Kind, glob: String? = null, id: String? = null, block: String? = null) =
        Panel.Item("Пункт", kind, "x", glob, id, block)

    @Test
    fun countsEntriesByMask() {
        val entries = listOf(GitHub.Entry("F-0001.md", "f/F-0001.md", false), GitHub.Entry("F-0002.md", "f/F-0002.md", false), GitHub.Entry("README.md", "f/README.md", false), GitHub.Entry("CI-0001-a", "f/CI-0001-a", true))
        assertEquals(listOf("2"), Panels.fromDirectory(item(Panel.Kind.COUNT, glob = "F-*.md"), entries))
        assertEquals(listOf("1"), Panels.fromDirectory(item(Panel.Kind.COUNT, glob = "CI-*"), entries), "объект контента — каталог")
        assertEquals(listOf("4"), Panels.fromDirectory(item(Panel.Kind.COUNT), entries), "без маски — все записи")
    }

    @Test
    fun parsesFilesWithTheSameParsers() {
        val decisions = "# Решения\n\n### A-1 — Первое\n\n**Статус.** Принято.\n\n### A-2 — Второе\n\n**Статус.** Принято.\n"
        assertEquals(listOf("решений 2", "A-1 — Первое", "A-2 — Второе"), Panels.fromText(item(Panel.Kind.DECISIONS, id = "A-\\d+"), decisions))
        val readme = "# X\n\n<!-- progress:begin -->\nэтап 2 из 5\n<!-- progress:end -->\n"
        assertEquals(listOf("progress: этап 2 из 5"), Panels.fromText(item(Panel.Kind.README_BLOCK, block = "progress"), readme))
        assertEquals(listOf("блока «other» нет"), Panels.fromText(item(Panel.Kind.README_BLOCK, block = "other"), readme))
    }

    @Test
    fun unparsedFileIsAReasonNotEmptiness() {
        val lines = Panels.fromText(item(Panel.Kind.DECISIONS, id = "A-\\d+"), "# Пусто\n")
        assertTrue(lines.single().startsWith("не прочитано: "), lines.toString())
    }
}

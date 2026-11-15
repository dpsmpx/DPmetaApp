package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.app.data.Licenses
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «О программе» (F-50, A-12): лицензии компонентов — строки документов происхождения, как в APK. */
class LicensesTest {
    @Test
    fun tablesWithLicenseColumnExceptIdeas() {
        val md = """
            # Происхождение
            ## Зависимости
            | Компонент | Версия | Лицензия | Зачем |
            |---|---|---|---|
            | RE2/J (`com.google.re2j:re2j`) | 1.8 | BSD-3-Clause | поиск |
            | [JSch](https://github.com/mwiede/jsch) | 2.27.3 | **BSD-3-Clause** | SSH |
            ## Двоичные файлы
            | Файл | SHA-256 | Лицензия |
            |:---|---|---:|
            | proot | abc | GPL-2.0 |
            ## Идеи
            | Идея | Откуда | Лицензия, дата | Где здесь |
            |---|---|---|---|
            | очередь | DP-Verify | свой код | здесь |
            Обычный текст | с чертой
        """.trimIndent()
        val rows = Licenses.parse("AI", md)
        assertEquals(
            listOf(
                Licenses.Row("AI · Зависимости", "RE2/J (com.google.re2j:re2j)", "1.8", "BSD-3-Clause"),
                Licenses.Row("AI · Зависимости", "JSch", "2.27.3", "BSD-3-Clause"),
                Licenses.Row("AI · Двоичные файлы", "proot", null, "GPL-2.0"),
            ),
            rows,
        )
    }

    /** A-12: документы репозитория (они же — в APK) дают лицензию каждой строке. */
    @Test
    fun repositoryProvenanceHasLicenseForEveryRow() {
        val root = File(System.getProperty("corpus.repo") ?: error("нет corpus.repo"))
        val rows = listOf("docs/PROVENANCE.md" to "DPmetaApp", "dpai/docs/PROVENANCE.md" to "AI").flatMap { (path, name) -> Licenses.parse(name, File(root, path).readText()) }
        assertTrue(rows.size > 30, "строк лицензий: ${rows.size}")
        assertEquals(emptyList(), rows.filter { it.license.isBlank() }, "у каждой строки — лицензия")
        assertTrue(rows.any { "JGit" in it.component } && rows.any { "Sora" in it.component || "sora" in it.component.lowercase() }, rows.map { it.component }.toString())
    }
}

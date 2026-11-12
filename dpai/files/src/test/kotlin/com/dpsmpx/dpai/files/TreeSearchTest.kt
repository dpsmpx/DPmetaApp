package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeSearchTest {
    private val tree = MemoryTree(
        mapOf(
            "src/main.cpp" to "#include \"shape.hpp\"\n\nint main() {\r\n    Shape shape; // Shape дважды\r\n}\n",
            "src/shape.hpp" to "struct Shape {};\n",
            "notes/main-notes.md" to "о main и о форме\n",
            "Main.kt" to "fun main() = Unit",
            ".git/config" to "main = да\n",
            "build/gen/main.cpp" to "int main() {}\n",
            "node_modules/x/main.js" to "main()\n",
        ),
    )

    private fun <T> ok(r: Outcome<T>): T = (r as Outcome.Success).value

    @Test
    fun namesRankNameStartThenNameThenDirectory() {
        val r = ok(TreeSearch.names(tree, "MAIN"))
        assertEquals(listOf("Main.kt", "src/main.cpp", "notes/main-notes.md"), r.hits)
        assertFalse(r.limited)
        assertEquals(4, r.scanned) // служебные каталоги не пройдены
        assertEquals(listOf("src/main.cpp", "src/shape.hpp"), ok(TreeSearch.names(tree, "src")).hits)
        assertEquals(listOf("src/main.cpp"), ok(TreeSearch.names(tree, " cpp  src ")).hits)
        assertEquals(emptyList(), ok(TreeSearch.names(tree, "   ")).hits)
        assertEquals(listOf("src/main.cpp", "src/shape.hpp"), ok(TreeSearch.names(tree, "p", dir = "src")).hits)
    }

    @Test
    fun namesAreCutToLimitAndSayIt() {
        val many = MemoryTree((1..30).associate { "f$it.txt" to "" })
        val r = ok(TreeSearch.names(many, "f", limit = 10))
        assertEquals(10, r.hits.size)
        assertTrue(r.limited)
    }

    @Test
    fun textGivesOneHitPerLineWithPlace() {
        val r = ok(TreeSearch.text(tree, "shape"))
        assertEquals(
            listOf(
                TreeSearch.TextHit("src/main.cpp", 1, 11, "#include \"shape.hpp\""),
                TreeSearch.TextHit("src/main.cpp", 4, 5, "Shape shape; // Shape дважды"),
                TreeSearch.TextHit("src/shape.hpp", 1, 8, "struct Shape {};"),
            ),
            r.hits,
        )
        assertEquals(4, r.scanned)
        assertFalse(r.limited)
        // С учётом регистра — другая находка в той же строке и без первой строки.
        assertEquals(listOf(4 to 11), ok(TreeSearch.text(tree, "shape;", ignoreCase = false)).hits.map { it.line to it.column })
        assertEquals(listOf("Main.kt", "notes/main-notes.md", "src/main.cpp"), ok(TreeSearch.text(tree, "main")).hits.map { it.path }.distinct())
        assertEquals(emptyList(), ok(TreeSearch.text(tree, "")).hits)
        // Кириллица без учёта регистра и последняя строка без перевода.
        assertEquals(listOf(TreeSearch.TextHit("notes/main-notes.md", 1, 12, "о main и о форме")), ok(TreeSearch.text(tree, "ФОРМЕ")).hits)
        assertEquals(listOf(TreeSearch.TextHit("Main.kt", 1, 5, "fun main() = Unit")), ok(TreeSearch.text(tree, "main()", dir = "", ignoreCase = false)).hits.filter { it.path == "Main.kt" })
    }

    @Test
    fun binaryAndHugeFilesAreSkippedAndCounted() {
        val t = MemoryTree(mapOf("a.txt" to "needle\n", "big.txt" to "needle\n" + "x".repeat((TreeSearch.MAX_FILE_BYTES).toInt()), "bin.dat" to "needle\u0000\n"))
        val r = ok(TreeSearch.text(t, "needle"))
        assertEquals(listOf("a.txt"), r.hits.map { it.path })
        assertEquals(1, r.scanned)
        assertEquals(2, r.skipped)
    }

    @Test
    fun hitLimitAndCancelStopTheWalk() {
        val t = MemoryTree((1..50).associate { "f%02d.txt".format(it) to "x\nx\nx\n" })
        val r = ok(TreeSearch.text(t, "x", limit = 7))
        assertEquals(7, r.hits.size)
        assertTrue(r.limited)
        var calls = 0
        val cancelled = ok(TreeSearch.text(t, "x", cancelled = { ++calls > 5 }))
        assertTrue(cancelled.limited)
        assertTrue(cancelled.hits.size < 150)
    }

    @Test
    fun longLinePreviewKeepsTheHitVisible() {
        val line = "    " + "a".repeat(500) + "НАЙДИ" + "b".repeat(500)
        val p = TreeSearch.preview(line, line.indexOf("НАЙДИ"), 5)
        assertTrue("НАЙДИ" in p && p.startsWith("…") && p.endsWith("…"), p)
        assertTrue(p.length <= TreeSearch.MAX_PREVIEW + 2)
        assertEquals("int x;", TreeSearch.preview("\t\tint x;", 6, 1))
    }

    @Test
    fun unreadableRootIsFailureButUnreadableSubdirOnlyMarksResult() {
        val broken = object : FileTree by tree {
            override fun list(dir: String): Outcome<List<FileEntry>> =
                if (dir == "src" || dir == "нет") Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_UNAVAILABLE, "каталог не читается", dir) else tree.list(dir)
        }
        assertTrue(TreeSearch.names(broken, "x", dir = "нет") is Outcome.Failure)
        val r = ok(TreeSearch.names(broken, "main"))
        assertEquals(listOf("Main.kt", "notes/main-notes.md"), r.hits)
        assertTrue(r.limited, "часть дерева не прочитана — итог неполон")
    }

    @Test
    fun regexFindsByPatternAndBadPatternsAreRefused() {
        val r = ok(TreeSearch.text(tree, "sha\\w+ \\w+;", regex = true))
        assertEquals(listOf(TreeSearch.TextHit("src/main.cpp", 4, 5, "Shape shape; // Shape дважды")), r.hits)
        assertEquals(listOf(1), ok(TreeSearch.text(tree, "^struct", regex = true)).hits.map { it.line })
        assertEquals(emptyList(), ok(TreeSearch.text(tree, "^STRUCT", ignoreCase = false, regex = true)).hits)
        // Буквальный поиск не понимает выражений: «.» — это точка.
        assertEquals(listOf("src/main.cpp"), ok(TreeSearch.text(tree, "shape.hpp")).hits.map { it.path })
        for (bad in listOf("(", "[a-", "x*", "", "a".repeat(501))) {
            assertTrue(TreeSearch.Query.of(bad, regex = true) is Outcome.Failure, "«${bad.take(10)}» принят")
        }
    }

    @Test
    fun runawayRegexSkipsTheFileInsteadOfHanging() {
        val t = MemoryTree(mapOf("evil.txt" to "a".repeat(40) + "c\n", "ok.txt" to "aaa\n"))
        TreeSearch.regexSteps = 2_000_000
        val r = try {
            ok(TreeSearch.text(t, "(a*)*\\1b|^aaa$", regex = true))
        } finally {
            TreeSearch.regexSteps = TreeSearch.MAX_REGEX_STEPS
        }
        // Катастрофический перебор на evil.txt упирается в предел шагов: файл пропущен, остальные найдены.
        assertEquals(listOf("ok.txt"), r.hits.map { it.path })
        assertEquals(1, r.skipped)
    }

    @Test
    fun replaceRewritesEveryHitAndKeepsBomAndLineEndings() {
        val t = MemoryTree(mapOf("a.cpp" to "\uFEFFint total = total + 1;\r\n", "b/c.h" to "// Total и total\n", "build/x.cpp" to "total\n", "none.txt" to "ничего\n"))
        val r = ok(TreeSearch.replace(t, "total", "sum", ignoreCase = false))
        assertEquals(TreeSearch.Replaced(2, 3, emptyList(), false, 0), r)
        assertEquals("\uFEFFint sum = sum + 1;\r\n", t.text("a.cpp"))
        assertEquals("// Total и sum\n", t.text("b/c.h"))
        assertEquals("total\n", t.text("build/x.cpp"), "служебные каталоги не трогаются")
        // Повтор ничего не меняет; только в папке — только в ней.
        assertEquals(0, ok(TreeSearch.replace(t, "total", "sum", ignoreCase = false)).files)
        assertEquals(1, ok(TreeSearch.replace(t, "sum", "s", dir = "b")).files)
        assertEquals("\uFEFFint sum = sum + 1;\r\n", t.text("a.cpp"))
    }

    @Test
    fun regexReplaceUsesGroupsAndLiteralReplaceDoesNot() {
        val t = MemoryTree(mapOf("a.txt" to "get_width() get_height()\n", "b.txt" to "цена $5\n"))
        assertEquals(2, ok(TreeSearch.replace(t, "get_(\\w+)\\(\\)", "$1", regex = true)).replacements)
        assertEquals("width height\n", t.text("a.txt"))
        // В буквальной замене «$1» и «\\» — просто знаки.
        ok(TreeSearch.replace(t, "width", "$1\\n"))
        assertEquals("$1\\n height\n", t.text("a.txt"))
        ok(TreeSearch.replace(t, "\\$(\\d)", "\\$$1.00", regex = true))
        assertEquals("цена $5.00\n", t.text("b.txt"))
        for (bad in listOf("$2", "$", "x\\", "\${name}")) {
            assertTrue(TreeSearch.replace(t, "(h)eight", bad, regex = true) is Outcome.Failure, "замена «$bad» принята")
        }
        assertEquals("$1.00\\n height\n", t.text("a.txt"), "отказ замены ничего не записал")
    }

    @Test
    fun failedWriteIsReportedAndOthersAreWritten() {
        val t = MemoryTree(mapOf("a.txt" to "x\n", "b.txt" to "x\n"))
        val picky = object : FileTree by t {
            override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> =
                if (path == "a.txt") Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_IO_FAILED, "диск занят", path) else t.write(path, bytes)
        }
        val r = ok(TreeSearch.replace(picky, "x", "y"))
        assertEquals(1, r.files)
        assertEquals(listOf("a.txt" to "диск занят"), r.failed)
        assertEquals("x\n", t.text("a.txt"))
        assertEquals("y\n", t.text("b.txt"))
    }
}

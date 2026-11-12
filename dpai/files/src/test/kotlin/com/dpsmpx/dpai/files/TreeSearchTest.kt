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
}

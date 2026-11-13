package com.dpsmpx.dpai.contexts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Карта кода (D-171): отбор объявлений и импортов из вывода `git grep -n`, пределы, детерминизм. */
class CodeMapTest {
    private val grep = listOf(
        "app/src/Main.kt:1:import android.os.Bundle",
        "app/src/Main.kt:2:import androidx.compose.runtime.Composable",
        "app/src/Main.kt:5:class MainActivity : ComponentActivity() {",
        "app/src/Main.kt:6:    override fun onCreate(savedInstanceState: Bundle?) {",
        "app/src/Main.kt:9:        fun deep() = 1",
        "app/src/Main.kt:12:// class Commented",
        "app/src/Main.kt:14:private const val KEY = \"sk-secret\"",
        "app/src/Main.kt:15:fun greet(name: String = \"мир\"): String = \"Привет, \$name\"",
        "app/src/Main.kt:16:val x = listOf(1).map { it }.also { println(\"a class of fun\") }",
        "lib/util.py:1:from collections import OrderedDict",
        "lib/util.py:3:class Cache(OrderedDict):",
        "lib/util.py:4:    def get(self, key, default=None):",
        "lib/util.py:8:async def fetch(url):",
        "native/core.hpp:1:#include <vector>",
        "native/core.hpp:3:namespace core {",
        "native/core.hpp:4:struct Point { int x; };",
        "src/lib.rs:1:use std::collections::HashMap;",
        "src/lib.rs:3:pub fn parse(input: &str) -> Result<Ast, Error> {",
        "src/lib.rs:9:impl Display for Ast {",
        "README.md:3:class names here are not code",
        "bad line without numbers",
    )

    @Test
    fun picksDeclarationsAndImportsPerFile() {
        val map = CodeMap.render(grep, truncated = false)!!
        val expected = """
            app/src/Main.kt — импорты: android.os.Bundle, androidx.compose.runtime.Composable
              5: class MainActivity : ComponentActivity()
              6:   override fun onCreate(savedInstanceState: Bundle?)
              15: fun greet(name: String = "мир"): String
            lib/util.py — импорты: collections
              3: class Cache(OrderedDict)
              4:   def get(self, key, default=None)
              8: async def fetch(url)
            native/core.hpp — импорты: vector
              3: namespace core
              4: struct Point
            src/lib.rs — импорты: std::collections::HashMap
              3: pub fn parse(input: &str) -> Result<Ast, Error>
              9: impl Display for Ast
        """.trimIndent()
        assertEquals(expected, map)
        assertFalse("sk-secret" in map, "значения констант не попадают в карту")
        assertFalse("deep" in map, "глубже одного уровня — мимо")
        assertEquals(map, CodeMap.render(grep.shuffled(kotlin.random.Random(7)), truncated = false), "порядок вывода не меняет карту")
    }

    @Test
    fun limitsAreNamed() {
        val many = (1..30).flatMap { f -> (1..40).map { d -> "src/F$f.kt:$d:fun f$d()" } }
        val map = CodeMap.render(many, truncated = true, CodeMap.Limits(maxFiles = 3, maxDeclarations = 5, maxImports = 0, maxChars = 8_000))!!
        assertEquals(3, map.lines().count { it.startsWith("src/") })
        assertTrue("  … ещё 35" in map && "… ещё файлов: 27" in map && "карта неполная" in map, map)
        // Первый файл не влез целиком — его начало; дальше — счёт не вошедших.
        val small = CodeMap.render(many, truncated = false, CodeMap.Limits(maxChars = 300))!!
        assertTrue(small.length < 400 && small.startsWith("src/F1.kt\n") && "файл не вошёл целиком" in small && small.endsWith("… ещё файлов: 29"), small)
        // Четвёртый файл не влез (блоки по ~64 знака) — он и остальные названы числом.
        val few = CodeMap.render(many, truncated = false, CodeMap.Limits(maxDeclarations = 3, maxChars = 200))!!
        assertTrue(few.endsWith("… ещё файлов: 27") && few.lines().count { it.startsWith("src/") } == 3 && "не вошёл целиком" !in few, few)
        assertNull(CodeMap.render(listOf("notes/a.md:1:class x", "a.kt:1:val y = 1"), truncated = false))
        assertNull(CodeMap.render(emptyList(), truncated = false))
    }
}

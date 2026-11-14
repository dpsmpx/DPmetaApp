package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.LocalStreamHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Клиент против настоящего clangd (D-137): проект из двух файлов с
 * `compile_commands.json`, свой флаг сборки `-DANSWER=42` и свой каталог
 * заголовков — без них ответы были бы другими. Путь к clangd —
 * `-Ddpai.test.clangd=/usr/bin/clangd-18`; без него тест пропускается.
 */
class ClangdLiveTest {
    private class Seen : LspClient.Listener {
        val diagnostics = LinkedBlockingQueue<Triple<String, Int?, List<LspDiagnostic>>>()

        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) {
            diagnostics += Triple(uri, version, items)
        }

        override fun stopped(reason: Diagnostic) = Unit
    }

    private fun <T> ok(r: Outcome<T>): T = (r as? Outcome.Success)?.value ?: error("отказ: $r")

    /** Диагностика [uri] версии [version] — clangd шлёт её после разбора именно этого текста. */
    private fun diagnosticsOf(seen: Seen, uri: String, version: Int): List<LspDiagnostic> {
        repeat(40) {
            val (u, v, items) = seen.diagnostics.poll(60, TimeUnit.SECONDS) ?: error("clangd не прислал диагностику")
            if (u == uri && v == version) return items
        }
        error("диагностики версии $version нет")
    }

    /** Поиск проекта — настоящим `sh`: сценарий тот же, что уйдёт в Linux приложения. */
    @Test
    fun projectIsLocatedUpwardsByCompileCommandsOrGit(@TempDir dir: File) {
        assumeTrue(File("/bin/sh").canExecute(), "нет /bin/sh")
        fun locate(fileDir: File): Clangd.Project? {
            val done = ok(LocalProcessHost().run(ProcessSpec(Clangd.locateCommand(fileDir.path, dir.path)), 10.seconds))
            return Clangd.parseProject(done.stdout)
        }
        val withBuild = File(dir, "a b/проект").apply { File(this, "src/deep").mkdirs(); File(this, "build").mkdirs() }
        File(withBuild, "build/compile_commands.json").writeText("[]")
        assertEquals(Clangd.Project(withBuild.path, "${withBuild.path}/build"), locate(File(withBuild, "src/deep")))
        val inRoot = File(dir, "flat").apply { mkdirs() }
        File(inRoot, "compile_commands.json").writeText("[]")
        assertEquals(Clangd.Project(inRoot.path, inRoot.path), locate(inRoot))
        val gitOnly = File(dir, "repo").apply { File(this, "src").mkdirs(); File(this, ".git").mkdirs() }
        assertEquals(Clangd.Project(gitOnly.path, null), locate(File(gitOnly, "src")))
        // Ничего не найдено до границы — корнем остаётся каталог файла; выше границы поиск не идёт.
        val lonely = File(dir, "x/y").apply { mkdirs() }
        assertEquals(Clangd.Project(lonely.path, null), locate(lonely))
        assertEquals(null, Clangd.parseProject(listOf("root=relative", "мусор")))
    }

    @Test
    fun manualIndexFindsReferencesInUnopenedTranslationUnit(@TempDir dir: File) {
        val clangd = System.getProperty("dpai.test.clangd")
        assumeTrue(clangd != null, "dpai.test.clangd не задан")
        val main = File(dir, "main.cpp").apply { writeText("int twice(int);\nint main() { return twice(21); }\n") }
        val other = File(dir, "other.cpp").apply { writeText("int twice(int value) { return value * 2; }\n") }
        File(dir, "compile_commands.json").writeText(listOf(main, other).joinToString(",", "[", "]") {
            """{"directory":"${dir.path}","file":"${it.path}","arguments":["clang++","-c","${it.path}"]}"""
        })
        val process = ok(LocalStreamHost().open(ProcessSpec(listOf(clangd!!) + Clangd.command(dir.path, backgroundIndex = true).drop(1), workDir = dir.path)))
        val seen = Seen()
        val client = LspClient(process.stdout, process.stdin, DocumentUri.ofPath(dir.path), seen)
        try {
            ok(client.start())
            val uri = DocumentUri.ofPath(main.path)
            val text = main.readText()
            ok(client.open(uri, "cpp", text))
            assertEquals(emptyList(), diagnosticsOf(seen, uri, 1).filter { it.severity == LspSeverity.ERROR })
            val at = LineIndex(text).position(text.lastIndexOf("twice") + 1)
            var references = ok(client.references(uri, at))
            // Фоновая очередь асинхронна; ждём именно появления закрытого файла, а не произвольную задержку.
            for (attempt in 1..300) {
                if (references.any { it.uri == DocumentUri.ofPath(other.path) }) break
                java.util.concurrent.CountDownLatch(1).await(100, TimeUnit.MILLISECONDS)
                references = ok(client.references(uri, at))
            }
            assertEquals(setOf(uri, DocumentUri.ofPath(other.path)), references.map { it.uri }.toSet())
            val rename = ok(client.rename(uri, at, "doubled"))
            assertEquals(setOf(uri, DocumentUri.ofPath(other.path)), rename.changes.keys)
            assertTrue(ok(TextEdits.apply(other.readText(), rename.changes.getValue(DocumentUri.ofPath(other.path)))).contains("int doubled(int"))
        } finally {
            client.stop()
            process.stop(2.seconds)
        }
    }

    @Test
    fun realClangdAnswersWithProjectFlags(@TempDir dir: File) {
        val clangd = System.getProperty("dpai.test.clangd")
        assumeTrue(clangd != null, "dpai.test.clangd не задан")
        File(dir, "include").mkdirs()
        File(dir, "include/shape.hpp").writeText("#pragma once\nstruct Shape {\n    int width = 0;\n    int height = 0;\n    int area() const { return width * height; }\n};\nint twice(int value);\n")
        val main = "#include \"shape.hpp\"\n\nint twice(int value) { return value * 2; }\n\nint main() {\n    Shape shape;\n    int total = shape.area() + ANSWER;\n    return twice(total);\n}\n"
        val mainFile = File(dir, "main.cpp").apply { writeText(main) }
        File(dir, "compile_commands.json").writeText(
            """[{"directory":"${dir.path}","file":"${mainFile.path}","arguments":["clang++","-std=c++17","-DANSWER=42","-I${dir.path}/include","-c","${mainFile.path}"]}]""",
        )
        assertEquals("", Clangd.compileCommandsDir(listOf("main.cpp", "compile_commands.json")))
        val process = ok(LocalStreamHost().open(ProcessSpec(listOf(clangd!!) + Clangd.command(dir.path).drop(1), workDir = dir.path)))
        val seen = Seen()
        val client = LspClient(process.stdout, process.stdin, DocumentUri.ofPath(dir.path), seen)
        try {
            val info = ok(client.start())
            assertTrue(info.capabilities.incrementalSync && info.capabilities.completion && info.capabilities.rename && info.capabilities.formatting, info.toString())
            val uri = DocumentUri.ofPath(mainFile.path)
            val header = DocumentUri.ofPath(File(dir, "include/shape.hpp").path)
            ok(client.open(uri, "cpp", main))
            // Флаги из compile_commands.json дошли: заголовок найден, ANSWER определён — ошибок нет.
            assertEquals(emptyList(), diagnosticsOf(seen, uri, 1).filter { it.severity == LspSeverity.ERROR })

            // Дополнение членов Shape после точки.
            val broken = main.replace("shape.area()", "shape.")
            ok(client.update(uri, broken))
            val dot = LineIndex(broken).position(broken.indexOf("= shape.") + "= shape.".length)
            val items = ok(client.completion(uri, dot, trigger = '.')).items
            assertTrue(items.map { it.insertText }.containsAll(listOf("width", "height", "area")), items.map { it.label }.toString())
            // Ошибка разбора показана в месте правки и с версией текста.
            val errors = diagnosticsOf(seen, uri, 2).filter { it.severity == LspSeverity.ERROR }
            assertTrue(errors.any { it.range.start.line == 6 }, errors.toString())
            ok(client.update(uri, main))
            assertEquals(emptyList(), diagnosticsOf(seen, uri, 3).filter { it.severity == LspSeverity.ERROR })

            val index = LineIndex(main)
            val areaAt = index.position(main.indexOf("area()") + 1)
            // Подсказка и переход в заголовок проекта.
            assertTrue(assertNotNull(ok(client.hover(uri, areaAt))).text.contains("area"))
            val def = ok(client.definition(uri, areaAt)).single()
            assertEquals(header, def.uri)
            assertEquals(4, def.range.start.line)
            // Экран (D-105): определение в заголовке того же корня — вкладка на строке 5.
            assertEquals(Assist.Target.Tab("include/shape.hpp", 5), Assist.target(def, uri, dir.path))
            // Подпись функции внутри скобок вызова.
            val help = assertNotNull(ok(client.signatureHelp(uri, index.position(main.indexOf("twice(total") + "twice(".length))))
            assertTrue(help.signatures.single().label.contains("int value"), help.toString())
            val line = assertNotNull(Assist.signature(help))
            assertEquals("int value", line.label.substring(assertNotNull(line.parameter, line.toString())), line.toString())
            // Использования и переименование локальной переменной: обе точки, текст сходится.
            val totalAt = index.position(main.indexOf("total"))
            assertEquals(2, ok(client.references(uri, totalAt)).size)
            val rename = ok(client.rename(uri, totalAt, "sum"))
            assertEquals(setOf(uri), rename.changes.keys)
            assertEquals(main.replace("total", "sum"), ok(TextEdits.apply(main, rename.changes.getValue(uri))))
            assertEquals(main.replace("total", "sum"), ok(Assist.applyEdits(main, 0, ok(Assist.editsForFile(rename, uri)))).text)
            // Функция объявлена в заголовке: переименование задевает два файла — экран отказывает целиком.
            val wide = ok(client.rename(uri, index.position(main.indexOf("twice(int")), "doubled"))
            // Без индекса проекта (D-105) clangd вернул правки только main.cpp, без объявления в shape.hpp, —
            // такое переименование было бы частичным. Экран сверяет объявление: оно в заголовке — отказ.
            assertEquals(setOf(uri), wide.changes.keys, "служба без индекса не видит заголовок — поэтому и нужна сверка")
            val twiceAt = index.position(main.indexOf("twice(int"))
            val places = ok(client.declaration(uri, twiceAt)) + ok(client.definition(uri, twiceAt))
            assertTrue(places.any { it.uri == header }, places.toString())
            val refused = Assist.renameScope(uri, places)
            assertTrue(refused is Outcome.Failure && "shape.hpp" in refused.errors.single().message, refused.toString())
            // Локальная переменная — объявлена здесь: переименование разрешено.
            assertTrue(Assist.renameScope(uri, ok(client.declaration(uri, totalAt)) + ok(client.definition(uri, totalAt))) is Outcome.Success)
            // Символ вокруг места (D-165): метод area внутри Shape в заголовке, функция twice в main.cpp.
            assertEquals("twice", Assist.enclosing(ok(client.symbols(uri)), index.position(main.indexOf("value * 2"))))
            assertEquals("main", Assist.enclosing(ok(client.symbols(uri)), index.position(main.indexOf("Shape shape"))))
            // Форматирование: правки службы приводят текст к её стилю, повторное — пусто.
            val ugly = main.replace("int main() {", "int   main( ){")
            ok(client.update(uri, ugly))
            val formatted = ok(TextEdits.apply(ugly, ok(client.format(uri, 4, spaces = true))))
            assertTrue(formatted.contains("int main() {"), formatted)
            ok(client.update(uri, formatted))
            assertEquals(emptyList(), ok(client.format(uri, 4, spaces = true)))
            // Исправление с готовой правкой: clang предлагает заменить опечатку.
            val typo = main.replace("return twice(total);", "return twise(total);")
            ok(client.update(uri, typo))
            val problem = diagnosticsOf(seen, uri, client.version(uri)!!).first { it.severity == LspSeverity.ERROR }
            val fix = ok(client.codeActions(uri, problem.range, listOf(problem))).firstOrNull { it.edit != null }
            assertEquals(main, ok(TextEdits.apply(typo, assertNotNull(fix, "исправления нет").edit!!.changes.getValue(uri))))
            val fixes = Assist.fixes(ok(client.codeActions(uri, problem.range, listOf(problem))))
            assertEquals(main, ok(Assist.applyEdits(typo, 0, ok(Assist.editsForFile(fixes.applicable.first().edit!!, uri)))).text)
        } finally {
            client.stop()
            process.stop(2.seconds)
        }
    }
}

package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.LocalStreamHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.StreamProcess
import com.dpsmpx.dpai.execution.process.run
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Службы Python против настоящих программ (D-142, DPmetaApp:D-110): pylsp
 * или pyright вместе с ruff на проекте из двух файлов и собственном venv с
 * модулем, которого нет в системе, — без интерпретатора проекта служба его
 * не нашла бы. Пути — `-Ddpai.test.pylsp`, `-Ddpai.test.ruff`, для pyright —
 * `-Ddpai.test.pyright` (каталог распакованного пакета npm) и
 * `-Ddpai.test.node`; без них тест пропускается.
 */
class PythonLiveTest {
    private fun <T> ok(r: Outcome<T>): T = (r as? Outcome.Success)?.value ?: error("отказ: $r")

    private class Board : LspClient.Listener {
        val board = DiagnosticsBoard()
        var source = 0
        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) {
            board.put(source, uri, items)
        }
        override fun stopped(reason: Diagnostic) = Unit
    }

    private val main = "import os\nimport extlib\nfrom pkg.util import twice\n\n\ndef main( ):\n    total = twice(21)\n    print(extlib.from_env())\n    return twice(total)\n"

    /** Проект и venv с модулем `extlib`; возвращает интерпретатор venv. */
    private fun project(dir: File): String {
        File(dir, "pkg").mkdirs()
        File(dir, "pkg/__init__.py").writeText("")
        File(dir, "pkg/util.py").writeText("def twice(value: int) -> int:\n    \"\"\"Удвоить.\"\"\"\n    return value * 2\n")
        File(dir, "main.py").writeText(main)
        val venv = File(dir, ".dpai-venv")
        val made = LocalProcessHost().run(ProcessSpec(listOf("python3", "-m", "venv", "--without-pip", venv.path)), 120.seconds)
        assumeTrue((made as? Outcome.Success)?.value?.ok == true, "нет python3 -m venv: $made")
        val site = venv.walkTopDown().first { it.name == "site-packages" }
        File(site, "extlib.py").writeText("def from_env() -> str:\n    return \"venv\"\n")
        return File(venv, "bin/python").path
    }

    private fun open(launch: ServerLaunch, dir: File, listener: LspClient.Listener): Pair<LspClient, StreamProcess> {
        val process = ok(LocalStreamHost().open(ProcessSpec(launch.command, workDir = dir.path, environment = launch.environment.ifEmpty { null })))
        val client = LspClient(process.stdout, process.stdin, DocumentUri.ofPath(dir.path), listener, settings = launch.settings)
        ok(client.start(60.seconds))
        return client to process
    }

    private fun awaitDiagnostics(board: DiagnosticsBoard, uri: String, what: String, test: (List<LspDiagnostic>) -> Boolean): List<LspDiagnostic> {
        repeat(300) {
            val now = board.merged(uri)
            if (test(now)) return now
            Thread.sleep(200)
        }
        error("не дождались диагностики: $what; есть ${board.merged(uri)}")
    }

    private fun exercise(primary: ServerLaunch, ruffPath: String, dir: File, typed: Boolean) {
        val uri = DocumentUri.ofPath(File(dir, "main.py").path)
        val util = DocumentUri.ofPath(File(dir, "pkg/util.py").path)
        val listener = Board()
        val (mainClient, mainProcess) = open(primary, dir, listener)
        val ruffListener = Board().also { it.source = 1 }
        val (ruff, ruffProcess) = open(PythonServers.ruff().let { it.copy(command = listOf(ruffPath) + it.command.drop(1)) }, dir, object : LspClient.Listener {
            override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) {
                listener.board.put(1, uri, items)
            }
            override fun stopped(reason: Diagnostic) = Unit
        })
        assertEquals(1, ruffListener.source)
        val session = LanguageSession(mainClient, ruff)
        try {
            ok(session.open(uri, "python", main))
            // Замечание ruff о лишнем импорте — и только одно: свои проверки стиля у основной службы выключены.
            val unused = awaitDiagnostics(listener.board, uri, "F401") { d -> d.any { it.code == "F401" } }
            assertEquals(1, unused.count { "os" in it.message && (it.code == "F401" || it.source == "pyflakes") }, unused.toString())
            val index = LineIndex(main)
            // Дополнение модуля из venv проекта: служба видит интерпретатор запуска.
            val dot = main.indexOf("extlib.from_env") + "extlib.".length
            val items = ok(session.completion(uri, index.position(dot), '.')).items
            assertTrue(items.any { it.label.startsWith("from_env") }, items.map { it.label }.toString())
            val twiceAt = index.position(main.indexOf("twice(21)") + 1)
            assertTrue(assertNotNull(ok(session.hover(uri, twiceAt))).text.contains("twice"))
            val help = assertNotNull(ok(session.signatureHelp(uri, index.position(main.indexOf("twice(21)") + "twice(".length))))
            val line = assertNotNull(Assist.signature(help))
            assertEquals("value", line.label.substring(assertNotNull(line.parameter, line.toString())).substringBefore(':'), line.toString())
            // Переход в модуль проекта — вкладка того же корня.
            val def = ok(session.definition(uri, twiceAt)).first()
            assertEquals(util, def.uri)
            assertEquals(Assist.Target.Tab("pkg/util.py", 1), Assist.target(def, uri, dir.path))
            val refs = ok(session.references(uri, twiceAt))
            assertTrue(refs.any { it.uri == util } && refs.count { it.uri == uri } >= 2, refs.toString())
            // Локальная переменная — символ этого файла: правка одного файла.
            val totalAt = index.position(main.indexOf("total"))
            assertTrue(Assist.renameScope(uri, ok(session.declaration(uri, totalAt)) + ok(session.definition(uri, totalAt))) is Outcome.Success)
            val local = ok(Assist.editsForFile(ok(session.rename(uri, totalAt, "amount")), uri))
            assertEquals(main.replace("total", "amount"), ok(Assist.applyEdits(main, 0, local)).text)
            // Функция модуля объявлена в pkg/util.py. pylsp вернул бы правки двух файлов, pyright в режиме
            // открытых файлов — только main.py, без самого объявления: частичная правка. Экран сверяет
            // объявление и определение до переименования и отказывает с именем файла.
            val scope = Assist.renameScope(uri, ok(session.declaration(uri, twiceAt)) + ok(session.definition(uri, twiceAt)))
            assertTrue(scope is Outcome.Failure && "util.py" in scope.errors.single().message, scope.toString())
            val wide = ok(session.rename(uri, twiceAt, "doubled"))
            if (typed) assertEquals(setOf(uri), wide.changes.keys, "pyright видит только открытые файлы — поэтому и нужна сверка")
            // Исправление ruff готовой правкой и формат ruff.
            val problem = unused.first { it.code == "F401" }
            val fixes = Assist.fixes(ok(session.codeActions(uri, problem.range, listOf(problem))))
            val fixed = ok(Assist.applyEdits(main, 0, ok(Assist.editsForFile(assertNotNull(fixes.applicable.first().edit), uri)))).text
            assertTrue(!fixed.startsWith("import os\n") && fixed.contains("import extlib"), fixed)
            assertEquals(ruff, session.formatter)
            val formatted = ok(TextEdits.apply(main, ok(session.format(uri, 4, true))))
            assertTrue(formatted.contains("def main():"), formatted)
            if (typed) {
                // Проверка типов — только у тяжёлой службы.
                val wrong = main.replace("twice(21)", "twice(\"21\")")
                ok(session.open(uri, "python", wrong))
                awaitDiagnostics(listener.board, uri, "ошибка типа аргумента") { d -> d.any { it.severity == LspSeverity.ERROR && it.code == "reportArgumentType" } }
            }
        } finally {
            session.stop()
            mainProcess.stop(2.seconds)
            ruffProcess.stop(2.seconds)
        }
    }

    @Test
    fun lightServicePylspWithRuff(@TempDir dir: File) {
        val pylsp = System.getProperty("dpai.test.pylsp")
        val ruff = System.getProperty("dpai.test.ruff")
        assumeTrue(pylsp != null && ruff != null, "dpai.test.pylsp/ruff не заданы")
        val python = project(dir)
        exercise(PythonServers.pylsp(python).let { it.copy(command = listOf(pylsp!!)) }, ruff!!, dir, typed = false)
    }

    @Test
    fun heavyServicePyrightWithRuff(@TempDir dir: File) {
        val pyright = System.getProperty("dpai.test.pyright")
        val node = System.getProperty("dpai.test.node")
        val ruff = System.getProperty("dpai.test.ruff")
        assumeTrue(pyright != null && node != null && ruff != null, "dpai.test.pyright/node/ruff не заданы")
        val python = project(dir)
        val launch = PythonServers.pyright(pyright!!, python)
        exercise(launch.copy(command = listOf(node!!) + launch.command.drop(1)), ruff!!, dir, typed = true)
    }

}

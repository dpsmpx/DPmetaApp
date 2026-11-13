package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.security.MessageDigest

/**
 * Что отлаживать (`DPmetaApp:D-124`; D-159): файл C или C++ — сборка для отладки
 * (`-g -O0`, проверка тулчейна D-140) во внутренний `/tmp/dpai-debug`, затем gdb;
 * файл Python — debugpy из окружения проекта; программа проекта — команда страницы
 * «Запуск», если это одна программа с аргументами. Пути и аргументы — значениями,
 * без оболочки (D-138): разбор команды отказывает на операторах оболочки.
 */
object DebugTargets {
    enum class Engine(val title: String) { GDB("gdb"), DEBUGPY("debugpy") }

    /**
     * Цель: [build] — команда сборки перед отладкой (`null` — не нужна), [adapter] —
     * процесс адаптера DAP, [program] и [args] — что запускать, [cwd] — рабочая папка,
     * [source] — файл, с которого начали (для точек по умолчанию).
     */
    data class Target(
        val engine: Engine,
        val build: List<String>?,
        val adapter: List<String>,
        val program: String,
        val args: List<String>,
        val cwd: String,
        val source: String?,
    )

    /** Пакет Alpine отладчика C и C++ — ставится кнопкой. */
    const val GDB_PACKAGE = "gdb"

    /** Модуль Python отладчика — ставится кнопкой в окружение проекта. */
    const val DEBUGPY_MODULE = "debugpy"

    /** Каталог программ, собранных для отладки, — внутренний `/tmp` гостя. */
    const val BUILD_DIR = "/tmp/dpai-debug"

    val GDB_ADAPTER: List<String> = listOf("gdb", "-i=dap", "-q")

    /** Можно ли отлаживать файл [path]: C и C++ — gdb, Python — debugpy; остальное — нет. */
    fun engineOf(path: String): Engine? = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "c", "cpp", "cc", "cxx", "c++" -> Engine.GDB
        "py", "pyw" -> Engine.DEBUGPY
        else -> null
    }

    /** Отладка файла [file] дерева [root]. */
    fun forFile(root: String, file: String): Outcome<Target> {
        if (!ProjectRuntime.validPaths(root, file)) return Outcome.failure(DiagCode.VALUE_INVALID, "нужны путь файла и папка дерева в Linux", file)
        val cwd = file.substringBeforeLast('/').ifEmpty { "/" }
        return when (engineOf(file)) {
            Engine.GDB -> {
                val program = "$BUILD_DIR/${key(file)}"
                val c = file.substringAfterLast('.').lowercase() == "c"
                val compile = if (c) "clang -g -O0 -std=c17" else "clang++ -g -O0 -std=c++20"
                val script = CppToolchain.preflight + "\nmkdir -p $BUILD_DIR || exit\nexec $compile -fuse-ld=lld \"\$DPAI_FILE\" -o \"\$1\""
                Outcome.success(Target(Engine.GDB, ProjectRuntime.args(root, file, script, listOf(program)), GDB_ADAPTER, program, emptyList(), cwd, file))
            }
            Engine.DEBUGPY -> Outcome.success(Target(Engine.DEBUGPY, null, PythonRuntime.debugAdapterCommand(root, file), file, emptyList(), cwd, file))
            null -> Outcome.failure(DiagCode.UNSUPPORTED, "отладка — у файлов C, C++ и Python", file)
        }
    }

    /**
     * Отладка программы проекта по команде страницы «Запуск» (D-155): [command] —
     * одна программа с аргументами, [cwd] — её папка. `python[3] файл.py …` — debugpy
     * (окружение проекта дерева [root]), иначе — gdb. Путь программы без `/` ищется
     * так, как его нашла бы оболочка: с `./` — от папки, иначе — в `PATH` у gdb.
     */
    fun forCommand(root: String, cwd: String, command: String): Outcome<Target> {
        if (!ProjectRuntime.validPaths(root, cwd)) return Outcome.failure(DiagCode.VALUE_INVALID, "нужна папка запуска в Linux", cwd)
        val argv = when (val r = argv(command)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val first = argv.first().substringAfterLast('/')
        if (PYTHON.matches(first) && argv.size >= 2 && argv[1].endsWith(".py")) {
            val program = absolute(cwd, argv[1])
            return Outcome.success(Target(Engine.DEBUGPY, null, PythonRuntime.debugAdapterCommand(root, program), program, argv.drop(2), cwd, program))
        }
        val program = if ('/' in argv.first()) absolute(cwd, argv.first()) else argv.first()
        return Outcome.success(Target(Engine.GDB, null, GDB_ADAPTER, program, argv.drop(1), cwd, null))
    }

    /**
     * Команда как argv: слова через пробелы, кавычки `'…'` и `"…"`, `\` — экранирование.
     * Операторы и подстановки оболочки (`;`, `&`, `|`, `<`, `>`, `$`, `` ` ``, скобки) —
     * отказ словами: под отладчиком идёт одна программа, а не сценарий оболочки.
     */
    fun argv(command: String): Outcome<List<String>> {
        if (command.isBlank() || command.length > MAX_COMMAND || command.any { it.code < 32 && it != '\t' }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "команда пуста, слишком длинная или с управляющими знаками")
        }
        val out = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                quote == '\'' -> if (c == '\'') quote = null else word.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = null
                    c == '\\' && i + 1 < command.length && command[i + 1] in "\"\\" -> { word.append(command[i + 1]); i++ }
                    c == '$' || c == '`' -> return shell(c)
                    else -> word.append(c)
                }
                c == '\'' || c == '"' -> { quote = c; inWord = true }
                c == '\\' && i + 1 < command.length -> { word.append(command[i + 1]); inWord = true; i++ }
                c == ' ' || c == '\t' -> if (inWord) { out += word.toString(); word.clear(); inWord = false }
                c in SHELL -> return shell(c)
                else -> { word.append(c); inWord = true }
            }
            i++
        }
        if (quote != null) return Outcome.failure(DiagCode.VALUE_INVALID, "кавычка не закрыта")
        if (inWord) out += word.toString()
        if (out.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "в команде нет программы")
        if (out.first().contains('=') && !out.first().startsWith('/') && !out.first().startsWith('.')) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "переменные окружения перед программой под отладчиком не задаются: «${out.first()}»")
        }
        return Outcome.success(out)
    }

    private fun shell(c: Char): Outcome.Failure = Outcome.failure(
        DiagCode.UNSUPPORTED,
        "под отладчиком — одна программа с аргументами; «$c» — знак оболочки: уберите его или отладьте файл программы",
    )

    private fun absolute(cwd: String, path: String): String {
        if (path.startsWith('/')) return path
        val parts = cwd.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in path.split('/')) when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += seg
        }
        return "/" + parts.joinToString("/")
    }

    /** Имя собранной программы — по пути файла: свой файл у каждого исходника, повтор — тот же. */
    private fun key(file: String): String =
        MessageDigest.getInstance("SHA-256").digest(file.toByteArray()).take(8).joinToString("") { "%02x".format(it) } +
            "-" + file.substringAfterLast('/').substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)

    private val PYTHON = Regex("^python(?:3(?:\\.\\d+)?)?$")
    private const val SHELL = ";&|<>$`()"
    private const val MAX_COMMAND = 4096
}

/**
 * Точки останова (D-159): по путям гостя, строки с единицы, не больше [MAX_PER_FILE]
 * на файл. Правка текста точки не сдвигает — они остаются на своих номерах строк.
 */
data class Breakpoints(val byFile: Map<String, Set<Int>> = emptyMap()) {
    fun lines(path: String): Set<Int> = byFile[path].orEmpty()

    /** Поставить или снять точку [line] файла [path]; сверх предела — без изменений. */
    fun toggle(path: String, line: Int): Breakpoints {
        if (line < 1) return this
        val now = lines(path)
        val next = if (line in now) now - line else if (now.size >= MAX_PER_FILE) return this else now + line
        return copy(byFile = if (next.isEmpty()) byFile - path else byFile + (path to next))
    }

    fun clear(path: String? = null): Breakpoints = if (path == null) Breakpoints() else copy(byFile = byFile - path)

    /** Для запуска отладки: файл → строки по порядку. */
    fun forLaunch(): Map<String, List<Int>> = byFile.mapValues { (_, l) -> l.sorted() }

    val count: Int get() = byFile.values.sumOf { it.size }

    companion object {
        const val MAX_PER_FILE = 200
    }
}

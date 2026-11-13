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
    /** Отладчик: gdb и debugpy — адаптеры DAP в Linux приложения; JDWP — своё приложение на телефоне (D-161). */
    enum class Engine(val title: String) { GDB("gdb"), DEBUGPY("debugpy"), JDWP("Kotlin/Java") }

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

    /** Можно ли отлаживать файл [path]: C и C++ — gdb, Python — debugpy, Kotlin и Java — по JDWP в APK; остальное — нет. */
    fun engineOf(path: String): Engine? = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "c", "cpp", "cc", "cxx", "c++" -> Engine.GDB
        "py", "pyw" -> Engine.DEBUGPY
        "kt", "java" -> Engine.JDWP
        else -> null
    }

    /**
     * Отладка установленного приложения [pkg] по JDWP (`DPmetaApp:D-126`; D-161):
     * исходники — дерево [root]; программа — имя пакета.
     */
    fun forApk(root: String, pkg: String): Outcome<Target> {
        if (!ProjectRuntime.validPaths(root, root)) return Outcome.failure(DiagCode.VALUE_INVALID, "нужна папка дерева в Linux", root)
        return when (val p = Adb.packageName(pkg)) {
            is Outcome.Failure -> p
            is Outcome.Success -> Outcome.success(Target(Engine.JDWP, null, emptyList(), p.value, emptyList(), root, null))
        }
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
            Engine.JDWP -> Outcome.failure(DiagCode.UNSUPPORTED, "Kotlin и Java отлаживаются в своём APK: «Сборка» → «Артефакты» → «Отладка» у APK", file)
            null -> Outcome.failure(DiagCode.UNSUPPORTED, "отладка — у файлов C, C++, Python, Kotlin и Java", file)
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
     * Программа страницы «Запуск» на ПК-target (`DPmetaApp:D-129`; D-164): адаптер DAP
     * работает на самом ПК. `python… файл.py` (на Windows и `py -3 …`) — debugpy того же
     * интерпретатора; иначе на POSIX — `gdb -i=dap`; на Windows — отказ: отладчик программ
     * MSVC протокол DAP не говорит. [root] и [cwd] — пути ПК ([pcPath]).
     */
    fun forPcCommand(root: String, cwd: String, command: String, windows: Boolean): Outcome<Target> {
        val dir = pcPath(cwd)
        if (pcPath(root) == null || dir == null) return Outcome.failure(DiagCode.VALUE_INVALID, "нужна абсолютная папка запуска на ПК", cwd)
        val argv = when (val r = argv(command, escapes = !windows)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val script = argv.indexOfFirst { it.lowercase().endsWith(".py") || it.lowercase().endsWith(".pyw") }
        val interpreter = if (script >= 1) argv.subList(0, script) else emptyList()
        val launcher = interpreter.firstOrNull()?.let { name(it, windows) }
        val python = launcher != null && (PYTHON.matches(launcher) || (windows && launcher == "py")) && interpreterFlags(interpreter.drop(1))
        if (python) {
            val program = pcAbsolute(dir, argv[script].replace('\\', '/'))
            return Outcome.success(Target(Engine.DEBUGPY, null, interpreter + listOf("-m", "debugpy.adapter"), program, argv.drop(script + 1), dir, program))
        }
        if (windows) {
            return Outcome.failure(DiagCode.UNSUPPORTED, "на Windows-ПК отлаживается только Python (debugpy): отладчик программ MSVC протокол DAP не поддерживает")
        }
        val program = if ('/' in argv.first()) absolute(dir, argv.first()) else argv.first()
        return Outcome.success(Target(Engine.GDB, null, GDB_ADAPTER, program, argv.drop(1), dir, null))
    }

    /**
     * Путь папки или файла на ПК: POSIX — `/…`; Windows — `C:/…` (SFTP показывает `/C:/…`,
     * обратные косые — к прямым). Иное, управляющие знаки и слишком длинное — `null`.
     */
    fun pcPath(path: String): String? {
        val p = path.replace('\\', '/').let { if (SFTP_DRIVE.containsMatchIn(it)) it.drop(1) else it }
        return when {
            p.length > 1024 || p.any { it.code < 32 } -> null
            DRIVE.containsMatchIn(p) -> if (p.length > 3) p.trimEnd('/') else p
            p.startsWith('/') -> if (p.length > 1) p.trimEnd('/') else p
            else -> null
        }
    }

    /** Тот же файл ПК: косые и регистр буквы диска не в счёт. */
    fun samePcPath(a: String, b: String): Boolean {
        val x = pcPath(a) ?: return a == b
        val y = pcPath(b) ?: return false
        return if (DRIVE.containsMatchIn(x) && DRIVE.containsMatchIn(y)) x.equals(y, ignoreCase = true) else x == y
    }

    /** Проверка отладчика на ПК одной командой без оболочки; ответ разбирает [pcReady]. */
    fun pcCheck(target: Target): List<String> = when (target.engine) {
        Engine.GDB -> listOf(target.adapter.first(), "--batch", "-ex", "python print('$GDB_MARK ' + gdb.VERSION)")
        Engine.DEBUGPY -> target.adapter.dropLast(2) + listOf("-c", "import debugpy; print('$DEBUGPY_MARK ' + debugpy.__version__)")
        Engine.JDWP -> throw IllegalArgumentException("APK отлаживается на телефоне, не на ПК")
    }

    /**
     * Готов ли отладчик ПК по выводу [pcCheck]: версия — или что поставить, словами.
     * gdb — 14 или новее и с Python (без них нет режима DAP).
     */
    fun pcReady(target: Target, output: List<String>): Outcome<String> {
        when (target.engine) {
            Engine.GDB -> {
                val version = output.firstNotNullOfOrNull { it.trim().takeIf { l -> l.startsWith("$GDB_MARK ") }?.removePrefix("$GDB_MARK ")?.trim() }
                    ?: return Outcome.failure(DiagCode.UNSUPPORTED, "на ПК нет gdb с Python: поставьте gdb 14 или новее из пакетов системы (например, «sudo apt install gdb»)")
                val major = version.substringBefore('.').filter { it.isDigit() }.toIntOrNull() ?: 0
                return if (major >= 14) {
                    Outcome.success("gdb $version")
                } else {
                    Outcome.failure(DiagCode.UNSUPPORTED, "на ПК gdb $version: режим отладки DAP есть с gdb 14 — обновите gdb")
                }
            }
            Engine.DEBUGPY -> {
                val version = output.firstNotNullOfOrNull { it.trim().takeIf { l -> l.startsWith("$DEBUGPY_MARK ") }?.removePrefix("$DEBUGPY_MARK ")?.trim() }
                val python = target.adapter.dropLast(2).joinToString(" ")
                return version?.let { Outcome.success("debugpy $it") }
                    ?: Outcome.failure(DiagCode.UNSUPPORTED, "на ПК у «$python» нет debugpy: поставьте его командой «$python -m pip install debugpy» в окружение программы")
            }
            Engine.JDWP -> return Outcome.failure(DiagCode.UNSUPPORTED, "APK отлаживается на телефоне, не на ПК")
        }
    }

    /** Флаги интерпретатора перед файлом: `-X utf8` и `-W …` — со значением; `-m` и `-c` — уже не файл программы. */
    private fun interpreterFlags(flags: List<String>): Boolean {
        var i = 0
        while (i < flags.size) {
            val f = flags[i]
            if (!f.startsWith("-") || f == "-m" || f == "-c") return false
            i += if (f == "-X" || f == "-W") 2 else 1
        }
        return i == flags.size
    }

    private const val GDB_MARK = "DPAI-GDB"
    private const val DEBUGPY_MARK = "DPAI-DEBUGPY"
    private val DRIVE = Regex("^[A-Za-z]:/")
    private val SFTP_DRIVE = Regex("^/[A-Za-z]:/")

    private fun name(program: String, windows: Boolean): String {
        val base = program.replace('\\', '/').substringAfterLast('/')
        return if (windows) base.lowercase().removeSuffix(".exe") else base
    }

    private fun pcAbsolute(cwd: String, path: String): String = when {
        DRIVE.containsMatchIn(path) -> path
        DRIVE.containsMatchIn(cwd) -> {
            val drive = cwd.take(2)
            if (path.startsWith('/')) drive + path else drive + absolute(cwd.drop(2), path)
        }
        else -> absolute(cwd, path)
    }

    /**
     * Команда как argv: слова через пробелы, кавычки `'…'` и `"…"`, `\` — экранирование
     * (без [escapes] — обычный знак: на Windows это разделитель пути).
     * Операторы и подстановки оболочки (`;`, `&`, `|`, `<`, `>`, `$`, `` ` ``, скобки) —
     * отказ словами: под отладчиком идёт одна программа, а не сценарий оболочки.
     */
    fun argv(command: String, escapes: Boolean = true): Outcome<List<String>> {
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
                    escapes && c == '\\' && i + 1 < command.length && command[i + 1] in "\"\\" -> { word.append(command[i + 1]); i++ }
                    c == '$' || c == '`' -> return shell(c)
                    else -> word.append(c)
                }
                c == '\'' || c == '"' -> { quote = c; inWord = true }
                escapes && c == '\\' && i + 1 < command.length -> { word.append(command[i + 1]); inWord = true; i++ }
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

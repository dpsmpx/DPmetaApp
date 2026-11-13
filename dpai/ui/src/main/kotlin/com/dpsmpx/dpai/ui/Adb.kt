package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * adb к самому телефону (`DPmetaApp:D-125`; D-160): команды гостя Linux приложения.
 * Адрес — всегда `127.0.0.1`, порт — беспроводной отладки. Сервер adb живёт, пока
 * идёт команда (proot с `--kill-on-exit`), поэтому подключение и действие — одним
 * сценарием `sh`; порт, pid, уровень и пакет — аргументами, не текстом сценария
 * (D-138). Разовый код сопряжения идёт в stdin `adb pair`.
 */
object Adb {
    /** Пакет Alpine с adb — ставится кнопкой. */
    const val PACKAGE = "android-tools"

    /** Файл гостя с портом подключения: терминал и ИИ подключаются по нему. */
    const val PORT_FILE = "/root/.android/dpmetaapp-port"

    /** Ключ adb гостя — его создаёт adb при первом сопряжении. */
    val KEY_FILES = listOf("/root/.android/adbkey", "/root/.android/adbkey.pub")

    const val HOST = "127.0.0.1"

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val CODE = Regex("^[0-9]{6}$")

    /** Проверка, что adb есть в госте. */
    val check: List<String> = listOf("/bin/sh", "-c", "command -v adb >/dev/null 2>&1")

    fun port(text: String): Outcome<Int> {
        val p = text.trim().toIntOrNull()
        return if (p != null && p in 1..65535) Outcome.success(p) else Outcome.failure(DiagCode.VALUE_INVALID, "порт — число от 1 до 65535: «$text»")
    }

    fun code(text: String): Outcome<String> {
        val c = text.filter { !it.isWhitespace() }
        return if (CODE.matches(c)) Outcome.success(c) else Outcome.failure(DiagCode.VALUE_INVALID, "код сопряжения — шесть цифр")
    }

    fun packageName(text: String): Outcome<String> {
        val p = text.trim()
        return if (p.length <= 255 && PACKAGE_NAME.matches(p)) Outcome.success(p) else Outcome.failure(DiagCode.VALUE_INVALID, "имя пакета — вида com.example.app: «$text»")
    }

    /** Сопряжение: код — в stdin ([pairInput]), не в аргументах. */
    fun pair(port: Int): List<String> = listOf("adb", "pair", "$HOST:$port")

    fun pairInput(code: String): String = code + "\n"

    /** Подключиться и вывести состояние (`device` — готово). */
    fun state(port: Int): List<String> = script("adb connect \"$HOST:\$1\" >/dev/null 2>&1; exec adb -s \"$HOST:\$1\" get-state", port.toString())

    /**
     * Поток logcat с последних [tail] строк: [level] — нижний уровень (`V D I W E F`),
     * [pid] — только этот процесс; формат `threadtime`.
     */
    fun logcat(port: Int, level: Logcat.Level, pid: Int?, tail: Int = 500): List<String> =
        script(
            "adb connect \"$HOST:\$1\" >/dev/null 2>&1 || exit 1\n" +
                "if test -n \"\$2\"; then exec adb -s \"$HOST:\$1\" logcat -v threadtime -T \"\$3\" --pid=\"\$2\" \"*:\$4\"; fi\n" +
                "exec adb -s \"$HOST:\$1\" logcat -v threadtime -T \"\$3\" \"*:\$4\"",
            port.toString(), pid?.toString().orEmpty(), tail.coerceIn(1, 5000).toString(), level.letter.toString(),
        )

    /** pid процесса пакета (пусто — не запущен). */
    fun pidof(port: Int, pkg: String): List<String> =
        script("adb connect \"$HOST:\$1\" >/dev/null 2>&1 || exit 1\nexec adb -s \"$HOST:\$1\" shell pidof \"\$2\"", port.toString(), pkg)

    /** Запустить приложение пакета, как значок на главном экране. */
    fun launch(port: Int, pkg: String): List<String> =
        script(
            "adb connect \"$HOST:\$1\" >/dev/null 2>&1 || exit 1\n" +
                "exec adb -s \"$HOST:\$1\" shell monkey -p \"\$2\" -c android.intent.category.LAUNCHER 1",
            port.toString(), pkg,
        )

    private fun script(body: String, vararg args: String): List<String> = listOf("/bin/sh", "-c", body, "dpai-adb") + args

    /** Итог `adb pair` словами: ключ adb сохраняется сам, текст ошибки — как есть. */
    fun pairResult(output: List<String>, code: Int): Outcome<Unit> =
        if (code == 0 && output.any { it.contains("Successfully paired", ignoreCase = true) }) {
            Outcome.success(Unit)
        } else {
            Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сопряжение не прошло: " + (output.lastOrNull { it.isNotBlank() }?.take(200) ?: "код $code"))
        }
}

/**
 * Журнал Android (D-160): разбор `logcat -v threadtime`, фильтры и трассировки.
 * Строка без заголовка (продолжение сообщения, «--------- beginning of») — своей
 * строкой с уровнем предыдущей.
 */
object Logcat {
    enum class Level(val letter: Char, val title: String) {
        VERBOSE('V', "Все"), DEBUG('D', "Отладка"), INFO('I', "Сведения"), WARN('W', "Предупреждения"), ERROR('E', "Ошибки"), FATAL('F', "Сбои");

        companion object {
            fun of(c: Char): Level? = entries.firstOrNull { it.letter == c } ?: if (c == 'A') FATAL else null
        }
    }

    /** Строка: [header] — `false` у продолжения; [pid] и [tag] — у продолжения от предыдущей. */
    data class Line(val time: String, val pid: Int?, val tid: Int?, val level: Level, val tag: String, val message: String, val header: Boolean = true) {
        val text: String get() = if (header) "$time $pid $tid ${level.letter} $tag: $message" else message
    }

    /** Место кода из трассировки: класс, файл, строка. */
    data class Frame(val className: String, val file: String, val line: Int) {
        /** Путь файла по пакету класса: `com/example/app/MainActivity.kt` — для поиска в дереве. */
        val pathSuffix: String get() = (className.substringBeforeLast('.', "").replace('.', '/').let { if (it.isEmpty()) "" else "$it/" }) + file
    }

    private val HEADER = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*:\\s?(.*)$")
    private val FRAME = Regex("\\bat\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\.[\\w$<>-]+\\(([\\w$-]+\\.(?:kt|java|kts)):(\\d+)\\)")

    /** Разобрать строку; [previous] — для продолжения. */
    fun parse(raw: String, previous: Line? = null): Line {
        val text = raw.trimEnd('\r').take(MAX_LINE)
        val m = HEADER.matchEntire(text)
        if (m != null) {
            val (time, pid, tid, level, tag, message) = m.destructured
            return Line(time, pid.toIntOrNull(), tid.toIntOrNull(), Level.of(level[0]) ?: Level.VERBOSE, tag.trim(), message)
        }
        return Line(previous?.time.orEmpty(), previous?.pid, previous?.tid, previous?.level ?: Level.VERBOSE, previous?.tag.orEmpty(), text, header = false)
    }

    /** Фильтр экрана: уровень не ниже [level], процесс [pid], текст [query] без учёта регистра. */
    data class Filter(val level: Level = Level.VERBOSE, val pid: Int? = null, val query: String = "") {
        fun matches(l: Line): Boolean =
            l.level.ordinal >= level.ordinal && (pid == null || l.pid == pid) &&
                (query.isBlank() || l.message.contains(query, ignoreCase = true) || l.tag.contains(query, ignoreCase = true))
    }

    /** Место кода в строке трассировки Java/Kotlin (`at a.b.C.m(C.kt:42)`) или `null`. */
    fun frame(message: String): Frame? = FRAME.find(message)?.let { m ->
        val line = m.groupValues[3].toIntOrNull() ?: return null
        if (line < 1) null else Frame(m.groupValues[1].substringBefore('$'), m.groupValues[2], line)
    }

    /** Буфер экрана: не больше [MAX_LINES] последних строк. */
    fun append(buffer: List<Line>, raw: List<String>): List<Line> {
        var prev = buffer.lastOrNull()
        val added = raw.map { parse(it, prev).also { l -> prev = l } }
        val all = buffer + added
        return if (all.size > MAX_LINES) all.takeLast(MAX_LINES) else all
    }

    const val MAX_LINES = 5000
    private const val MAX_LINE = 4000
}

package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * adb к самому телефону (`DPmetaApp:D-125`; D-160): команды гостя Linux приложения.
 * Адрес — всегда `127.0.0.1`, порт — беспроводной отладки. Сервер adb живёт, пока
 * идёт команда (proot с `--kill-on-exit`), поэтому подключение и действие — одним
 * сценарием `sh`; порт, pid, уровень и пакет — аргументами, не текстом сценария
 * (D-138). Разовый код сопряжения идёт в stdin `adb pair`, а от клиента к серверу
 * adb — по сокету в данных приложения (`ADB_SERVER_SOCKET` гостя, угроза T23), не
 * через общий loopback телефона.
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

    /**
     * Отладка приложения [pkg] (`DPmetaApp:D-126`; D-161): проверить, что оно
     * отлаживаемое (`DEBUGGABLE`), остановить, перезапустить с ожиданием отладчика
     * (`am set-debug-app -w` — на один запуск), дождаться pid и его регистрации в
     * `adb jdwp`, открыть пересылку `tcp:0 → jdwp:<pid>` и сказать строкой
     * `DPAI-JDWP <порт> <pid>` ([debugLine]); дальше ждать закрытия stdin и снять
     * пересылку. Сервер adb живёт, пока идёт эта команда. Отказ — строка `DPAI-ERROR …`.
     */
    fun debugApk(port: Int, pkg: String): List<String> =
        script(
            """
            s="$HOST:${'$'}1"
            adb connect "${'$'}s" >/dev/null 2>&1 || { echo "DPAI-ERROR нет связи с adb — подключите его в «Logcat»"; exit 1; }
            adb -s "${'$'}s" shell dumpsys package "${'$'}2" | grep -q 'flags=\[.*DEBUGGABLE' || { echo "DPAI-ERROR приложение не установлено или собрано без отладки — нужна сборка debug"; exit 1; }
            adb -s "${'$'}s" shell am force-stop "${'$'}2"
            adb -s "${'$'}s" shell am set-debug-app -w "${'$'}2" >/dev/null || { echo "DPAI-ERROR am set-debug-app не прошла"; exit 1; }
            adb -s "${'$'}s" shell monkey -p "${'$'}2" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || { echo "DPAI-ERROR приложение не запустилось"; exit 1; }
            pid=""; i=0
            while [ ${'$'}i -lt 60 ]; do
              pid=${'$'}(adb -s "${'$'}s" shell pidof "${'$'}2" | tr -d '\r' | cut -d' ' -f1)
              [ -n "${'$'}pid" ] && break
              sleep 0.5; i=${'$'}((i+1))
            done
            [ -n "${'$'}pid" ] || { echo "DPAI-ERROR процесс приложения не появился за 30 с"; exit 1; }
            i=0
            while [ ${'$'}i -lt 20 ]; do
              timeout 1 adb -s "${'$'}s" jdwp 2>/dev/null | tr -d '\r' | grep -qx "${'$'}pid" && break
              i=${'$'}((i+1))
            done
            fwd=${'$'}(adb -s "${'$'}s" forward tcp:0 "jdwp:${'$'}pid" | tr -d '\r')
            [ -n "${'$'}fwd" ] || { echo "DPAI-ERROR пересылка jdwp не открылась"; exit 1; }
            echo "DPAI-JDWP ${'$'}fwd ${'$'}pid"
            read -r _
            adb -s "${'$'}s" forward --remove "tcp:${'$'}fwd" >/dev/null 2>&1
            adb -s "${'$'}s" shell am clear-debug-app >/dev/null 2>&1
            """.trimIndent(),
            port.toString(), pkg,
        )

    /** Активность предпросмотра Compose из `ui-tooling` отладочной сборки (D-167). */
    const val PREVIEW_ACTIVITY = "androidx.compose.ui.tooling.PreviewActivity"

    private val COMPOSABLE = Regex("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$")

    /**
     * Открыть функцию `@Preview` [composable] (`класс.функция`) в приложении [pkg]
     * (`DPmetaApp:D-132`; D-167): `PreviewActivity` его отладочной сборки, `am start -W`.
     */
    fun preview(port: Int, pkg: String, composable: String): Outcome<List<String>> {
        if (packageName(pkg) is Outcome.Failure) return Outcome.failure(DiagCode.VALUE_INVALID, "имя пакета — вида com.example.app: «$pkg»")
        if (!COMPOSABLE.matches(composable) || composable.length > 500) return Outcome.failure(DiagCode.VALUE_INVALID, "имя функции предпросмотра недопустимо: «$composable»")
        return Outcome.success(
            script(
                "adb connect \"$HOST:\$1\" >/dev/null 2>&1 || exit 1\n" +
                    "exec adb -s \"$HOST:\$1\" shell am start -W -n \"\$2/$PREVIEW_ACTIVITY\" --es composable \"\$3\"",
                // adb shell склеивает аргументы в строку для оболочки телефона: `$` вложенного класса
                // (`Экран$Вложенный.Preview`) она бы раскрыла — имя едет в одинарных кавычках (их в имени нет).
                port.toString(), pkg, "'$composable'",
            ),
        )
    }

    /** Итог `am start` предпросмотра: нет активности — подсказка про `ui-tooling`. */
    fun previewResult(output: List<String>, code: Int): Outcome<Unit> {
        val text = output.joinToString("\n")
        return when {
            "does not exist" in text || "Unable to resolve" in text -> Outcome.failure(
                DiagCode.OPERATION_NOT_ALLOWED,
                "в отладочной сборке нет предпросмотра Compose: добавьте в модуль debugImplementation(\"androidx.compose.ui:ui-tooling\"), соберите и установите APK заново",
            )
            code == 0 && "Error" !in text -> Outcome.success(Unit)
            else -> Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "предпросмотр не открылся: " + (output.lastOrNull { it.isNotBlank() }?.take(200) ?: "код $code"))
        }
    }

    /** Снимок экрана телефона PNG — из потока процесса гостя, не строками (D-167). */
    fun screencap(port: Int): List<String> =
        script("adb connect \"$HOST:\$1\" >/dev/null 2>&1 || exit 1\nexec adb -s \"$HOST:\$1\" exec-out screencap -p", port.toString())

    /** Предел снимка экрана: больше — не PNG экрана телефона. */
    const val MAX_SCREENCAP: Int = 16 shl 20

    /** Итог строки [debugApk]: готово — порт и pid; отказ — слова; прочее — `null`. */
    sealed interface DebugLine {
        data class Ready(val port: Int, val pid: Int) : DebugLine

        data class Error(val message: String) : DebugLine
    }

    fun debugLine(line: String): DebugLine? {
        val t = line.trim()
        if (t.startsWith("DPAI-ERROR ")) return DebugLine.Error(t.removePrefix("DPAI-ERROR ").take(300))
        if (!t.startsWith("DPAI-JDWP ")) return null
        val parts = t.split(' ')
        val port = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val pid = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return DebugLine.Ready(port, pid)
    }

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

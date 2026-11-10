package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.formats.CommandSpec

/**
 * Мост Termux (ТЗ, F-41, F-42; D-83, D-84) — второе место для команд реестра:
 * Termux из F-Droid и клоны в его папке проектов. Здесь — только смысл: как
 * команда реестра становится вызовом `RUN_COMMAND`, проба готовности, шаги
 * готовности и разбор итога. Намерения и `PendingIntent` — у оболочки
 * (`TermuxBridge`). Чистый Kotlin.
 *
 * В Termux уходят только две вещи (D-84): команда реестра — её программа,
 * аргументы и каталог из `config/commands.toml` в APK — и проба готовности
 * `ls -1 -d -p -- <пути>`, только чтение. Ни текста человека, ни текста модели
 * или репозитория; `stdin` не передаётся никогда.
 */
object TermuxActions {
    // Пути чужого приложения — Termux, а не свои: их задаёт сам Termux (`TermuxConstants`,
    // пользователь 0), и `getFilesDir()` пульта их не даст.
    @Suppress("SdCardPath")
    const val HOME: String = "/data/data/com.termux/files/home"

    @Suppress("SdCardPath")
    const val PREFIX: String = "/data/data/com.termux/files/usr"

    /** Папка проектов по умолчанию — у автора (D-83). */
    const val DEFAULT_FOLDER: String = "$HOME/Projects"

    /** Итог команды в `PendingIntent` — с Termux 0.109 (ТЗ, §21: вики Termux, `RUN_COMMAND`). */
    private val MIN_VERSION = listOf(0, 109)
    const val MIN_VERSION_TEXT: String = "0.109"

    /** Шаг «нет разрешения» — экран ставит рядом «Разрешить». */
    const val NO_PERMISSION: String = "Нет разрешения «Запуск команд в Termux»"

    /** Сколько ждать ответа пробы: Termux отвечает за доли секунды, если принимает команды. */
    const val PROBE_SECONDS: Int = 15

    /** `err` итога без внутренней ошибки Termux — `Activity.RESULT_OK`. */
    const val ERR_NONE: Int = -1

    /**
     * Вызов `RUN_COMMAND`: программа (абсолютный путь в Termux), аргументы как есть,
     * рабочий каталог (`null` — домашний Termux), фоном ли и подпись для журнала Termux.
     */
    data class Call(val executable: String, val args: List<String>, val workdir: String?, val background: Boolean, val label: String)

    /** Итог от Termux: вывод, код возврата, внутренняя ошибка (`err` ≠ [ERR_NONE]) и длины вывода до обрезки. */
    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val err: Int,
        val errmsg: String?,
        val stdoutLength: Int? = null,
        val stderrLength: Int? = null,
    ) {
        val failed: Boolean get() = err != ERR_NONE
    }

    /** Папка проектов из ввода человека: пробелы по краям — прочь, `~/` — домашний каталог Termux, «/» в конце — прочь. */
    fun normalizeFolder(input: String): String {
        val t = input.trim()
        val home = when {
            t == "~" -> HOME
            t.startsWith("~/") -> HOME + t.substring(1)
            else -> t
        }
        return if (home.length > 1) home.trimEnd('/') else home
    }

    /** Что не так с папкой [folder] (после [normalizeFolder]); годится — `null`. */
    fun folderProblem(folder: String): String? = when {
        folder.isEmpty() -> "папка не задана"
        !folder.startsWith("/") -> "путь — от корня, например $DEFAULT_FOLDER"
        folder == "/" -> "корень — не папка проектов"
        folder.length > 300 -> "путь длиннее 300 знаков"
        folder.any { it.isISOControl() } -> "в пути — управляющие знаки"
        folder.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." } -> "в пути — «.», «..» или «//»"
        else -> null
    }

    /** Имена клона в папке, в порядке поиска: как у репозитория и строчными (как ищет DPmeta). */
    fun cloneNames(spot: CommandActions.Spot): List<String> = listOf(spot.repo.name, spot.repo.name.lowercase()).distinct()

    /** Каталог команды в Termux: клон [clone] в папке и каталог внутри клона. */
    fun dir(folder: String, clone: String, spot: CommandActions.Spot): String =
        "$folder/$clone" + if (spot.inClone.isEmpty()) "" else "/${spot.inClone}"

    /** Программа гостя из системы (`/bin/x`, `/usr/bin/x`, `/usr/local/bin/x`) — та же программа Termux. */
    private val GUEST_BIN = Regex("^/(?:usr/(?:local/)?)?bin/([A-Za-z0-9._+-]+)$")

    /**
     * Вызов команды [spec] в клоне [clone] папки [folder]. Программа гостя из `/bin` —
     * из `$PREFIX/bin` Termux; программа из клона — от каталога команды. Аргумент
     * `/root/repos` (корень клонов Linux-среды) — папка проектов; другие пути гостя
     * `/root/…` в Termux не годятся — отказ. Уровень 2 — только на переднем плане (F-41).
     */
    fun call(spec: CommandSpec, spot: CommandActions.Spot, folder: String, clone: String): Outcome<Call> {
        folderProblem(folder)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "папка проектов: $it", folder) }
        if (spec.level >= 2 && spec.plan == CommandSpec.Plan.BACKGROUND) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "команда уровня 2 — только на переднем плане Termux", spec.id)
        }
        val dir = dir(folder, clone, spot)
        val executable = if (spec.absolute) {
            val name = GUEST_BIN.matchEntire(spec.path)?.groupValues?.get(1)?.takeIf { n -> n.any { it != '.' } }
                ?: return Outcome.failure(DiagCode.UNSUPPORTED, "программа ${spec.path} — из Linux-среды, в Termux её нет", spec.id)
            "$PREFIX/bin/$name"
        } else {
            "$dir/${spec.path}"
        }
        val args = spec.args.map { a ->
            when {
                a == CommandActions.GUEST_ROOT -> folder
                a.startsWith("/root/") -> return Outcome.failure(DiagCode.UNSUPPORTED, "аргумент $a — путь Linux-среды, в Termux его нет", spec.id)
                else -> a
            }
        }
        return Outcome.success(Call(executable, args, dir, spec.plan == CommandSpec.Plan.BACKGROUND, spec.title))
    }

    /**
     * Пути пробы готовности для команды [spec]: папка, клон под каждым именем
     * ([cloneNames]), программа и скрипт в клоне. Порядок — для показа; проверка — по множеству.
     */
    fun probePaths(spec: CommandSpec, spot: CommandActions.Spot, folder: String): List<String> {
        val out = linkedSetOf(folder)
        for (name in cloneNames(spot)) {
            out += "$folder/$name"
            call(spec, spot, folder, name).valueOrNull()?.let { out += it.executable }
            CommandActions.scriptInClone(spec, spot)?.let { out += "$folder/$name/$it" }
        }
        return out.toList()
    }

    /**
     * Проба готовности: `ls` из Termux только читает и печатает те из [paths], что
     * есть (каталоги — с «/»); нет какого-то — код 2 и строка в stderr, это не отказ пробы.
     */
    fun probe(paths: List<String>): Call =
        Call("$PREFIX/bin/ls", listOf("-1", "-d", "-p", "--") + paths, null, true, "DPmetaApp: проверка папки проектов")

    /** Что есть из путей пробы — по выводу `ls -1 -d -p`: строка — путь, «/» в конце снят. */
    fun existing(r: Result): Set<String> =
        r.stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.startsWith("/") }.map { if (it.length > 1) it.trimEnd('/') else it }.toSet()

    /** Отказ Termux из-за `allow-external-apps` — по тексту его ошибки (Termux так и называет свойство). */
    fun externalAppsRefused(r: Result): Boolean = r.failed && r.errmsg?.contains("allow-external-apps") == true

    /** Версия Termux из `versionName` («0.118.1», «0.118.1+…»): годится ли для итога в `PendingIntent`; не число — нет (сборка Google Play). */
    fun versionOk(versionName: String?): Boolean {
        val parts = versionName?.substringBefore('+')?.substringBefore('-')?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
        if (parts.size < 2) return false
        for (i in MIN_VERSION.indices) {
            val v = parts.getOrElse(i) { 0 }
            if (v != MIN_VERSION[i]) return v > MIN_VERSION[i]
        }
        return true
    }

    /** Пакеты Termux для подсказки: имена Alpine, где Termux зовёт иначе, — его именами. */
    fun termuxPackages(alpine: List<String>): List<String> = alpine.map { if (it == "python3") "python" else it }.distinct()

    /** Факты о Termux от оболочки: установлен ли, версия, объявлено ли разрешение, выдано ли, итог пробы (`null` — не было или нет ответа). */
    data class Facts(
        val installed: Boolean,
        val versionName: String?,
        val runCommand: Boolean,
        val permitted: Boolean,
        val probe: Result?,
        val probeSent: Boolean,
    )

    /** Готовность: шаги и найденный клон (имя в папке) — для запуска. */
    data class Ready(val steps: List<CommandActions.Step>, val clone: String?, val call: Call?) {
        val ready: Boolean get() = CommandActions.ready(steps) && call != null
    }

    /**
     * Готовность команды в Termux по шагам (F-42): Termux, версия, разрешение,
     * `allow-external-apps`, «поверх других окон» для переднего плана, папка, клон,
     * программа и скрипт. Первый непройденный шаг говорит, что сделать.
     */
    fun readiness(spec: CommandSpec, spot: CommandActions.Spot, folder: String, f: Facts): Ready {
        val steps = mutableListOf<CommandActions.Step>()
        fun add(title: String, ok: Boolean, hint: String? = null) {
            steps += CommandActions.Step(title, ok, hint)
        }
        folderProblem(folder)?.let {
            add("Папка проектов: $it", false, "Задайте папку проектов Termux выше.")
            return Ready(steps, null, null)
        }
        if (!f.installed) {
            add("Termux не установлен", false, "Поставьте Termux из F-Droid (сборка из Google Play команд от приложений не принимает).")
            return Ready(steps, null, null)
        }
        val version = f.versionName ?: "?"
        if (!f.runCommand || !versionOk(f.versionName)) {
            add("Termux $version не возвращает итог команд", false, "Нужен Termux $MIN_VERSION_TEXT или новее из F-Droid или GitHub; сборка из Google Play устарела.")
            return Ready(steps, null, null)
        }
        add("Termux $version", true)
        if (!f.permitted) {
            add(NO_PERMISSION, false, "Нажмите «Разрешить»; если окна нет — «Настройки приложения» → «Разрешения» → «Другие разрешения».")
            return Ready(steps, null, null)
        }
        add("Разрешение «Запуск команд в Termux» есть", true)
        val r = f.probe
        if (r == null) {
            if (f.probeSent) {
                add("Termux не ответил за $PROBE_SECONDS с", false, "Откройте Termux один раз (он мог быть выгружен) и нажмите «Проверить снова».")
            } else {
                add("Termux ещё не проверен", false)
            }
            return Ready(steps, null, null)
        }
        when {
            externalAppsRefused(r) -> {
                add("Termux не принимает команды приложений", false, "В Termux: mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings")
                return Ready(steps, null, null)
            }
            r.failed -> {
                add("Termux отказал: ${firstLine(r.errmsg)}", false, "Проверьте Termux и нажмите «Проверить снова».")
                return Ready(steps, null, null)
            }
        }
        add("Termux принимает команды приложений", true)
        if (spec.plan == CommandSpec.Plan.FOREGROUND) {
            // Чужое разрешение отсюда не проверить: без него Termux просит нажать на своё уведомление.
            add("Передний план: Termux нужно «Поверх других окон» (Настройки → Приложения → Termux)", true)
        }
        val have = existing(r)
        if (folder !in have) {
            add("Папки $folder в Termux нет", false, "Создайте её в Termux (mkdir -p $folder) или задайте другую выше.")
            return Ready(steps, null, null)
        }
        add("Папка проектов $folder есть", true)
        val clone = cloneNames(spot).firstOrNull { "$folder/$it" in have }
        if (clone == null) {
            add("Клона ${spot.repo.name} в папке нет", false, "В Termux: git clone https://github.com/${spot.repo} $folder/${spot.repo.name}")
            return Ready(steps, null, null)
        }
        add("Клон $folder/$clone есть", true)
        val call = when (val c = call(spec, spot, folder, clone)) {
            is Outcome.Failure -> {
                add("Команда не для Termux: ${c.errors.first().message}", false)
                return Ready(steps, clone, null)
            }
            is Outcome.Success -> c.value
        }
        val packages = termuxPackages(spec.packages)
        val pkg = if (packages.isEmpty()) null else "В Termux: pkg install ${packages.joinToString(" ")}"
        if (call.executable !in have) {
            add("Программы ${call.executable} нет", false, pkg ?: "Программы нет в Termux.")
            return Ready(steps, clone, call)
        }
        add("Программа ${call.executable} есть", true)
        CommandActions.scriptInClone(spec, spot)?.let { script ->
            val path = "$folder/$clone/$script"
            if (path in have) {
                add("Скрипт $script есть", true)
            } else {
                add("Скрипта $script нет", false, "В клоне Termux его нет — в Termux: git -C $folder/$clone pull --ff-only.")
            }
        }
        return Ready(steps, clone, call)
    }

    /** Первая непустая строка текста Termux, без управляющих знаков. */
    fun firstLine(text: String?): String = clean(text.orEmpty()).lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: "без причины"

    /** Последовательности ESC (цвет, курсор) — из вывода прочь: на экране они мусор. */
    private val ANSI = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])")

    /** Текст из Termux для экрана: без ESC-последовательностей и управляющих знаков, кроме перевода строки и табуляции. */
    fun clean(text: String): String =
        ANSI.replace(text.replace("\r\n", "\n").replace('\r', '\n'), "").filter { it == '\n' || it == '\t' || !it.isISOControl() }

    /**
     * Строки вывода итога: stdout, затем stderr; Termux обрезал вывод (до 100 КБ на
     * оба, с начала) — первой строкой сказано, сколько знаков было.
     */
    fun lines(r: Result): List<String> {
        val out = mutableListOf<String>()
        val cut = listOfNotNull(
            r.stdoutLength?.takeIf { it > r.stdout.length }?.let { "stdout ${r.stdout.length} из $it" },
            r.stderrLength?.takeIf { it > r.stderr.length }?.let { "stderr ${r.stderr.length} из $it" },
        )
        if (cut.isNotEmpty()) out += "… Termux обрезал вывод с начала: ${cut.joinToString(", ")} знаков"
        for (part in listOf(r.stdout, r.stderr)) {
            val text = clean(part).trimEnd('\n')
            if (text.isNotEmpty()) out += text.split('\n')
        }
        return out
    }

    /**
     * Итог запуска в Termux для `:core:actions` и экрана: внутренняя ошибка Termux —
     * отказ с его причиной; иначе — код возврата (нет кода — отказ).
     */
    fun exit(r: Result): Outcome<CommandActions.Exit> = when {
        externalAppsRefused(r) -> Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "Termux не принимает команды приложений (allow-external-apps)", "termux")
        r.failed -> Outcome.failure(DiagCode.PROCESS_START_FAILED, "Termux: ${firstLine(r.errmsg)}", "termux")
        r.exitCode == null -> Outcome.failure(DiagCode.PROCESS_START_FAILED, "Termux не сообщил код возврата", "termux")
        else -> Outcome.success(CommandActions.Exit(r.exitCode))
    }

    /** Итога нет за предел времени: Termux команду не останавливает — она могла остаться идти. */
    fun noResult(minutes: Int): Outcome<CommandActions.Exit> =
        Outcome.failure(DiagCode.PROCESS_TIMEOUT, "итога от Termux нет за $minutes мин — команда могла остаться идти в Termux", "termux")
}

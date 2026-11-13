package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.jsch.JschShell
import com.dpsmpx.dpai.jsch.ShellChannel
import com.dpsmpx.dpai.terminal.TerminalListener
import com.dpsmpx.dpai.terminal.TerminalSession
import com.dpsmpx.dpai.terminal.TerminalTransport
import com.dpsmpx.dpai.ui.TargetEntry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Вкладки своего терминала (D-71): живут, пока жив процесс приложения, —
 * выход с экрана терминала их не закрывает. Экран и прокрутка назад —
 * только в памяти: через терминал проходит вход в Claude (D-27); ввод
 * нигде не журналируется.
 */
object TerminalHub {
    /** Экран, который сейчас показывает вкладки; события — из потоков сети. */
    interface Observer {
        fun screen(tab: Tab)
        fun tabs()
        fun bell(tab: Tab)
        fun clipboard(tab: Tab, text: String)
    }

    @Volatile
    var observer: Observer? = null

    val tabs = CopyOnWriteArrayList<Tab>()

    /** Число вкладок для экранов Compose («Терминалы (N)»): обычный список они не наблюдают. */
    val count = androidx.compose.runtime.mutableIntStateOf(0)

    @Volatile
    var current: Tab? = null

    /** Контекст приложения — для службы терминала при закрытии вкладки (терминал:A-1). */
    @Volatile
    private var app: Context? = null

    private var nextId = 1

    /**
     * Вкладка: к target по SSH ([target]) или к Linux-среде в приложении
     * ([target] — `null`, D-75, шаг 5).
     */
    /**
     * Запуск из IDE: [file] — файл редактора (пусто у запуска программы проекта, D-155),
     * [root] — рабочая папка запуска, [treeRoot] — корень дерева, от которого считаются
     * пути вкладок редактора [editorRootKey] (D-153).
     */
    class EditorRun(val file: String, val root: String, val command: String, val editorRootKey: String?, val treeRoot: String = root) {
        val language: com.dpsmpx.dpai.ui.RunLanguage get() = com.dpsmpx.dpai.ui.RunLanguage.of(file)
        internal var output = com.dpsmpx.dpai.ui.EditorRunOutput(file)
        @Volatile var report: com.dpsmpx.dpai.ui.RunDiagnosis? = null
            internal set
        internal var offered = false
        internal fun begin() { output = com.dpsmpx.dpai.ui.EditorRunOutput(file); report = null; offered = false }
    }

    class Tab internal constructor(val id: Int, val target: TargetEntry?, val command: List<String>, label: String, val tmux: String? = null, val editorRun: EditorRun? = null) : TerminalListener {
        /**
         * Связь оборвалась, а не программа кончилась (D-111): закрытие без
         * кода выхода или неудачное подключение. Такую вкладку с оболочкой
         * или с сессией в `tmux` приложение переподключает само, когда
         * возвращается сеть.
         */
        @Volatile
        internal var lost = false

        /** Когда приложение в последний раз само переподключало вкладку (монотонные мс): не чаще [AUTO_EVERY_MS]. */
        @Volatile
        internal var autoAt = Long.MIN_VALUE / 2

        /** Подключение идёт — для экрана: «◌» и без «Переподключить». */
        val busy: Boolean get() = connecting

        /** Вкладку закрыли (под `synchronized(this)`): подключение после этого не начинается и не привязывается. */
        internal var removed = false

        /** Последнее подключение — только вход в сессию tmux: код 75 тогда — от скрипта, а не от программы. */
        @Volatile
        internal var resumed = false

        /** Сервер `tmux` вкладки — свой (`-L`) у target «в приложении»: у каждой вкладки свой proot. */
        val tmuxSocket: String? get() = tmux?.takeIf { target?.inApp == true }

        /** Сама возвращается при сети: оболочка или сессия в `tmux`; CLI слота без `tmux` — только по кнопке. */
        val autoReconnect: Boolean get() = target != null && (tmux != null || command.isEmpty())

        val linux: Boolean get() = target == null

        /** Записи программы в буфер обмена (OSC 52) — без вопроса до закрытия вкладки (терминал:A-24). */
        @Volatile
        var clipboardAllowed = false

        /** Строка о вкладке сверх состояния соединения (терминал:A-24): «прежняя сессия потеряна…». */
        @Volatile
        var notice: String? = null

        /** Имя ярлыка; меняется «Переименовать» в меню ярлыка (терминал:A-21). */
        @Volatile
        var label: String = label

        val session = TerminalSession(80, 24, listener = this)

        @Volatile
        internal var transport: ChannelTransport? = null

        /** Идёт подключение: второе нажатие «переподключить» его не дублирует. */
        internal var connecting = false

        /** Пусто — на связи; иначе — что с соединением («подключение…», отказ, закрыто). */
        @Volatile
        var status: String = CONNECTING
            internal set

        @Volatile
        var title: String = ""
            private set

        val open: Boolean get() = transport?.isOpen == true

        override fun changed() {
            observer?.screen(this)
        }

        override fun title(title: String) {
            this.title = title
            observer?.tabs()
        }

        /** Сигнал (звонок, OSC 9/777) в вкладке, которую сейчас не видят (терминал:A-8): точка у ярлыка. */
        @Volatile
        var attention: Boolean = false

        override fun bell() {
            val screen = observer
            if (screen == null || current !== this) {
                // Ярлыки и уведомление — только при первом сигнале: поток BEL (`cat` двоичного файла) не
                // перерисовывает ряд вкладок тысячи раз в секунду.
                val first = !attention
                attention = true
                if (first) {
                    screen?.tabs()
                    // Экрана терминала нет — строкой уведомления службы, без текста программы (T8).
                    if (screen == null) app?.let(TerminalService::attention)
                }
            }
            screen?.bell(this)
        }

        override fun clipboard(text: String) {
            observer?.clipboard(this, text)
        }

        override fun closed(exitCode: Int?) = Unit
    }

    /** Вкладка к Linux-среде в приложении: `sh -l` гостя (D-75, шаг 5). */
    /** Состояние вкладки во время подключения: экран по нему рисует «◌» и прячет «Переподключить». */
    const val CONNECTING = "подключение…"

    fun openLinux(context: Context): Tab = open(context, null, emptyList(), "Linux")

    /** Ещё одна вкладка туда же, куда [like]: оболочка. */
    fun openLike(context: Context, like: Tab): Tab {
        val target = like.target?.let { fresh(context, it) }
        return open(context, target, emptyList(), target?.label ?: "Linux", kind = SHELL)
    }

    /** Target, каким он записан сейчас (настройка tmux могла смениться, пока вкладка открыта); из памяти контроллера. */
    fun fresh(context: Context, t: TargetEntry): TargetEntry =
        runCatching { Controller.get(context.applicationContext.filesDir).targets().firstOrNull { it.id == t.id } }.getOrNull() ?: t

    /** Вид вкладки оболочки для имени сессии `tmux` (D-111); CLI слота — ключ исполнителя. */
    const val SHELL = "sh"

    /**
     * Новая вкладка. [kind] — вид для сессии `tmux` (D-111): [SHELL] или ключ
     * исполнителя; `null` — без `tmux` (вход в аккаунт, Linux без target,
     * папка «Файлов»). Сессия — только на POSIX target с включённой
     * настройкой; номер — наименьший свободный среди открытых вкладок того же
     * target и вида: после перезапуска приложения вкладка возвращается в ту
     * же сессию.
     */
    @Synchronized
    fun open(context: Context, target: TargetEntry?, command: List<String>, label: String, kind: String? = null, editorRun: EditorRun? = null): Tab {
        val session = if (kind != null && target != null && target.tmux && target.platform == com.dpsmpx.dpai.execution.target.TargetPlatform.POSIX) {
            // Занятые: открытые вкладки и сессии, которые ещё не удалось завершить (закрыты без сети).
            pendingLoad(context.applicationContext)
            val used = tabs.mapNotNull { it.tmux }.toSet() + pendingEnds.map { it.second }
            val device = device(context)
            generateSequence(1) { it + 1 }.map { com.dpsmpx.dpai.ui.TerminalLaunch.tmuxName(device, target.id.value, kind, it) }.first { it !in used }
        } else {
            null
        }
        val tab = Tab(nextId++, target, command, label, session, editorRun)
        tabs += tab
        count.intValue = tabs.size
        current = tab
        app = context.applicationContext
        watchNetwork(context.applicationContext)
        // Пока есть вкладки — служба переднего плана держит процесс и сеансы (терминал:A-1).
        TerminalService.sync(context)
        connect(context, tab)
        return tab
    }

    @Volatile
    private var watching = false

    /**
     * Метка устройства в имени сессии `tmux` (D-111): у установки — своя,
     * постоянная; копия настроек на другом телефоне (D-51, D-107) не входит в
     * сессии этого.
     */
    private fun device(context: Context): String {
        deviceId?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences("terminal", Context.MODE_PRIVATE)
        val made = prefs.getString(DEVICE, null) ?: com.dpsmpx.dpai.core.clock.RandomSource.secure().nextBytes(2).joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString(DEVICE, it).apply() }
        deviceId = made
        return made
    }

    @Volatile
    private var deviceId: String? = null

    private const val DEVICE = "tmux_device"
    private const val PENDING = "tmux_pending"

    /**
     * Прочитать заранее то, что вкладке нужно с диска (метка устройства,
     * targets, незавершённые сессии): экран терминала зовёт это не в главном
     * потоке, и «+» и открытие вкладки потом берут из памяти.
     */
    fun warm(context: Context) {
        val app = context.applicationContext
        device(app)
        runCatching { Controller.get(app.filesDir).targets() }
        pendingLoad(app)
    }

    @Volatile
    private var pendingLoaded = false

    /** Незавершённые сессии закрытых вкладок — и после перезапуска приложения: иначе новая вкладка вошла бы в «закрытую». */
    @Synchronized
    private fun pendingLoad(app: Context) {
        if (pendingLoaded) return
        pendingLoaded = true
        val saved = app.getSharedPreferences("terminal", Context.MODE_PRIVATE).getStringSet(PENDING, null).orEmpty()
        if (saved.isEmpty()) return
        val targets = runCatching { Controller.get(app.filesDir).targets() }.getOrDefault(emptyList())
        for (line in saved) {
            val (id, name) = line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: continue
            targets.firstOrNull { it.id.value == id }?.let { t -> if (pendingEnds.none { it.second == name }) pendingEnds += t to name }
        }
    }

    private fun pendingSave(app: Context) {
        runCatching {
            app.getSharedPreferences("terminal", Context.MODE_PRIVATE).edit()
                .putStringSet(PENDING, pendingEnds.map { "${it.first.id.value}\t${it.second}" }.toSet()).apply()
        }
    }

    /** Сессии закрытых вкладок, которые не удалось завершить (нет сети): повтор, когда сеть вернётся. */
    private val pendingEnds = CopyOnWriteArrayList<Pair<TargetEntry, String>>()

    /**
     * Сеть вернулась (D-111) — вкладки с оборванной связью, которые можно
     * вернуть без потерь (оболочка, сессия в `tmux`), переподключаются сами
     * с «переподключаюсь…»; CLI слота без `tmux` молча заново не
     * запускается — его строка предлагает «Переподключить».
     */
    private fun watchNetwork(app: Context) {
        if (watching) return
        watching = true
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    for (tab in tabs) auto(app, tab, force = true)
                    pendingLoad(app)
                    for (p in pendingEnds) {
                        pendingEnds.remove(p)
                        endSession(app, p.first, p.second)
                    }
                    pendingSave(app)
                }
            })
        }
    }

    /**
     * Вернуть вкладку с оборванной связью, если можно без потерь (D-111).
     * [force] — сеть только что появилась; иначе — не чаще раза в
     * [AUTO_EVERY_MS] (сервер рвёт связь сразу после входа — не по кругу).
     */
    private fun auto(app: Context, tab: Tab, force: Boolean) {
        if (!tab.lost || !tab.autoReconnect || tab.open || tab.connecting || tab !in tabs) return
        val now = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() / 1_000_000
        if (!force && now - tab.autoAt < AUTO_EVERY_MS) return
        tab.autoAt = now
        connect(app, tab, RECONNECTING)
    }

    /** Есть ли сейчас сеть с выходом наружу. */
    private fun online(app: Context): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    private const val AUTO_EVERY_MS = 15_000L

    /**
     * Завершить сессию `tmux` вкладки на target (D-111): отдельной командой
     * `tmux kill-session`; нет tmux или сессии — тихо. Имя — только
     * латиница, цифры и `-` ([com.dpsmpx.dpai.ui.TerminalLaunch.tmuxName]).
     */
    private fun endSession(app: Context, target: TargetEntry, name: String) {
        if (!Regex("[a-z0-9-]+").matches(name)) return
        // Только точное имя (`=имя`): иначе tmux возьмёт и сессию с тем же началом (…-1 и …-10).
        val cmd = com.dpsmpx.dpai.execution.ssh.TmuxSession.kill(name, name.takeIf { target.inApp })
        thread(name = "dpai-term-tmux-end", isDaemon = true) {
            val r: Outcome<*>? = runCatching {
                if (target.inApp) {
                    com.dpsmpx.dpai.android.linux.LinuxSetup.shell(app, com.dpsmpx.dpai.execution.linux.WindowSize(80, 24), cmd)
                        .map { child -> LocalChannel(child, onOutput = { _, _ -> }, onClosed = {}) }
                } else {
                    target.toTarget(Controller.get(app.filesDir).knownHostsFile).ssh?.let { endpoint ->
                        // Команда кончается — канал и сессия SSH закрываются сами.
                        JschShell(endpoint, target.platform, identities = listOf(KeystoreKey.identity())).open(80, 24, cmd, onOutput = { _, _ -> }, onClosed = {})
                    }
                }
            }.getOrNull()
            // Не дошло по сети — повторить, когда сеть вернётся: номер сессии до того не занимается. Ключ хоста, вход,
            // нет адреса SSH — повтор не поможет: номер не держится вечно.
            val network = r is Outcome.Failure && r.diagnostics.first().code.retry == com.dpsmpx.dpai.core.diagnostics.Retryability.RETRYABLE
            if (network && !target.inApp && pendingEnds.none { it.second == name }) {
                pendingEnds += target to name
                pendingSave(app)
            }
        }
    }

    /** Состояние вкладки, пока приложение само возвращает связь (D-111). */
    const val RECONNECTING = "переподключаюсь…"

    /** Подключить (и переподключить) вкладку: экран сохраняется, канал — новый. */
    fun connect(context: Context, tab: Tab, status: String = CONNECTING) {
        val t = ChannelTransport()
        // Проверка, отметка и новый канал — под одним замком с [close]: закрытая вкладка не подключается.
        synchronized(tab) {
            if (tab.removed || tab.open || tab.connecting) return
            tab.connecting = true
            tab.transport = t
        }
        tab.lost = false
        val app = context.applicationContext
        // Автопереподключение CLI слота (D-111): только вход в живую сессию tmux, заново программа не запускается.
        val resumeOnly = status == RECONNECTING && tab.command.isNotEmpty()
        tab.resumed = resumeOnly && tab.tmux != null
        tab.status = status
        tab.editorRun?.begin()
        tab.session.attach(t)
        observer?.tabs()
        thread(name = "dpai-term-connect", isDaemon = true) {
            val (cols, rows) = tab.session.read { it.cols to it.rows }
            val target = tab.target
            // Сессия в tmux (D-111): `tmux new -A -s имя` — та же сессия после обрыва; нет tmux на target — строка и прежняя команда.
            val command = tab.tmux?.let { name -> com.dpsmpx.dpai.ui.TerminalLaunch.inTmux(tab.command, name, tab.tmuxSocket, resumeOnly).valueOrNull() } ?: tab.command
            val o: Outcome<TermChannel>? = if (target == null || target.inApp) {
                // Linux-среда в приложении (и target «в приложении»): псевдотерминал через JNI, без SSH (D-75, шаг 5).
                com.dpsmpx.dpai.android.linux.LinuxSetup.shell(app, com.dpsmpx.dpai.execution.linux.WindowSize(cols, rows), command).map { child ->
                    LocalChannel(child, onOutput = { b, n ->
                        tab.session.output(b, 0, n)
                        if (tab.transport === t) tab.editorRun?.let { run ->
                            // Отчёт — и при успехе с предупреждениями: места в коде нужны и тогда (D-153).
                            run.output.feed(b, n)?.takeIf { it.useful || it.problems.items.isNotEmpty() }?.let { report -> run.report = report; observer?.tabs() }
                        }
                    }, onClosed = { code -> if (tab.transport === t) closed(tab, code) })
                }
            } else {
                val controller = Controller.get(app.filesDir)
                target.toTarget(controller.knownHostsFile).ssh?.let { endpoint ->
                    JschShell(endpoint, target.platform, identities = listOf(KeystoreKey.identity())).open(
                        cols, rows, command,
                        onOutput = { b, n ->
                            tab.session.output(b, 0, n)
                            // Запуск программы проекта на ПК (D-155): тот же разбор кода выхода и ошибок, что у Linux в приложении.
                            if (tab.transport === t) tab.editorRun?.let { run ->
                                run.output.feed(b, n)?.takeIf { it.useful || it.problems.items.isNotEmpty() }?.let { report -> run.report = report; observer?.tabs() }
                            }
                        },
                        onClosed = { code -> if (tab.transport === t) closed(tab, code) },
                    ).map { SshChannel(it) }
                }
            }
            when (o) {
                // Вкладку закрыли, пока шло подключение: канал закрывается сразу, а не живёт без вкладки.
                is Outcome.Success -> if (t.bind(o.value)) {
                    // Размер мог смениться, пока шло подключение.
                    tab.session.read { it.cols to it.rows }.let { (c, r) -> o.value.resize(c, r) }
                    tab.status = ""
                }
                is Outcome.Failure -> {
                    tab.session.closed(null)
                    // Сети нет или target не ответил — вернётся сам, когда сеть появится (D-111); ключ хоста или вход — нет.
                    tab.lost = o.diagnostics.first().code.retry == com.dpsmpx.dpai.core.diagnostics.Retryability.RETRYABLE
                    tab.status = Redactor.STANDARD.redact("не подключился: ${com.dpsmpx.dpai.core.text.Words.reason(o.diagnostics.first().code.name)} — ${o.diagnostics.first().message}")
                }
                null -> {
                    tab.session.closed(null)
                    tab.status = "у target нет адреса SSH"
                }
            }
            val ended = synchronized(tab) {
                tab.connecting = false
                tab.removed
            }
            // Вкладку закрыли, пока шло подключение: сессия tmux завершается теперь — иначе new -A создал бы её после kill.
            val name = tab.tmux
            if (ended && target != null && name != null) endSession(app, target, name)
            observer?.tabs()
        }
    }

    private fun closed(tab: Tab, code: Int?) {
        tab.session.closed(code)
        // Без кода выхода — оборвалась связь, а не кончилась программа (D-111).
        tab.lost = code == null
        tab.status = when {
            code == com.dpsmpx.dpai.execution.ssh.TmuxSession.NOT_RESUMED_CODE && tab.resumed -> "сессии tmux нет — «Переподключить» запустит программу заново"
            code != null -> "соединение закрыто, код $code"
            tab.autoReconnect -> "связь потеряна — вернусь, когда появится сеть"
            else -> "связь потеряна — «Переподключить» запустит программу заново"
        }
        tab.session.output("\r\n\u001b[2m[${tab.status}]\u001b[0m\r\n".toByteArray())
        observer?.tabs()
        // Сеть уже есть (связь оборвалась на смене сети и обнаружилась позже) — вернуть сразу (D-111).
        app?.let { a -> if (tab.lost && online(a)) auto(a, tab, force = false) }
    }

    fun close(tab: Tab) {
        // Канал закрывается и тогда, когда подключение ещё идёт (ChannelTransport помнит закрытие).
        val connecting = synchronized(tab) {
            tab.removed = true
            tab.transport?.close()
            tab.transport = null
            tab.connecting
        }
        // Закрыть вкладку — намерение, а не обрыв (D-111): её сессия в tmux на target завершается;
        // подключение ещё идёт — после него (иначе new -A создаст сессию уже после kill).
        val target = tab.target
        val session = tab.tmux
        if (!connecting && target != null && session != null) app?.let { endSession(it, target, session) }
        tab.session.close()
        tabs -= tab
        count.intValue = tabs.size
        if (current === tab) current = tabs.lastOrNull()
        app?.let(TerminalService::sync)
        observer?.tabs()
    }
}

/** Канал вкладки к программе: SSH к target или псевдотерминал Linux-среды в приложении. */
internal interface TermChannel {
    val isOpen: Boolean

    fun write(bytes: ByteArray)

    fun resize(cols: Int, rows: Int)

    fun close()
}

internal class SshChannel(private val c: ShellChannel) : TermChannel {
    override val isOpen: Boolean get() = c.isOpen

    override fun write(bytes: ByteArray) = c.write(bytes)

    override fun resize(cols: Int, rows: Int) = c.resize(cols, rows)

    override fun close() = c.close()
}

/**
 * Оболочка гостя под proot с псевдотерминалом (D-75, шаг 5): вывод читает
 * свой поток; закрытие — HUP лидеру сеанса, proot уносит гостя с собой.
 */
internal class LocalChannel(
    private val child: com.dpsmpx.dpai.execution.linux.NativeChild,
    onOutput: (ByteArray, Int) -> Unit,
    onClosed: (Int?) -> Unit,
) : TermChannel {
    @Volatile
    private var done = false

    /**
     * Ввод — своим потоком (аудит надёжности 2 ноября 2026, A-7): запись в
     * псевдотерминал блокируется, пока гость не читает (`apk add`, `npm i`),
     * а пишут из главного потока под замком транспорта — вставка больше
     * буфера ядра давала ANR. Очередь ограничена: гость не читает совсем —
     * лишний ввод отбрасывается, а не копится в памяти.
     */
    private val input = java.util.concurrent.LinkedBlockingQueue<ByteArray>(MAX_PENDING_WRITES)

    init {
        thread(name = "dpai-term-linux-in", isDaemon = true) {
            try {
                while (!done) {
                    val b = input.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    child.stdin.write(b)
                    child.stdin.flush()
                }
            } catch (_: java.io.IOException) {
                // Оболочка вышла: поток вывода сообщит закрытие.
            } catch (_: InterruptedException) {
                // Канал закрыт.
            }
        }
        thread(name = "dpai-term-linux", isDaemon = true) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = child.stdout.read(buf)
                    if (n < 0) break
                    if (n > 0) onOutput(buf, n)
                }
            } catch (_: java.io.IOException) {
                // Псевдотерминал закрыт: оболочка вышла или её закрыли — итог ниже.
            }
            val code = runCatching { child.waitFor() }.getOrNull()
            // Дескрипторы псевдотерминала — сразу после выхода: иначе по два на каждую закрытую вкладку (аудит безопасности, A-11).
            runCatching { child.stdin.close() }
            runCatching { child.stdout.close() }
            done = true
            onClosed(code)
        }
    }

    override val isOpen: Boolean get() = !done

    override fun write(bytes: ByteArray) {
        if (!done) input.offer(bytes)
    }

    override fun resize(cols: Int, rows: Int) = child.resize(com.dpsmpx.dpai.execution.linux.WindowSize(cols, rows))

    override fun close() {
        if (!done) com.dpsmpx.dpai.android.linux.LinuxSetup.hangUp(child)
    }

    private companion object {
        /** Кусков ввода в очереди: вставка — несколько кусков, нажатия — по одному. */
        const val MAX_PENDING_WRITES = 1024
    }
}

/**
 * Канал вкладки: до конца подключения ответы эмулятора копятся (немного),
 * потом уходят в канал по порядку.
 */
internal class ChannelTransport : TerminalTransport {
    private val pending = ArrayList<ByteArray>()
    private var channel: TermChannel? = null
    private var closed = false

    val isOpen: Boolean @Synchronized get() = channel?.isOpen == true

    /** Канал открылся. `false` — транспорт уже закрыт: канал закрывается здесь же. */
    @Synchronized
    fun bind(c: TermChannel): Boolean {
        if (closed) {
            c.close()
            return false
        }
        channel = c
        pending.forEach(c::write)
        pending.clear()
        return true
    }

    @Synchronized
    override fun write(bytes: ByteArray) {
        val c = channel
        if (c != null) c.write(bytes) else if (pending.size < MAX_PENDING) pending += bytes.copyOf()
    }

    @Synchronized
    override fun resize(cols: Int, rows: Int) {
        channel?.resize(cols, rows)
    }

    @Synchronized
    override fun close() {
        closed = true
        pending.clear()
        channel?.close()
        channel = null
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}

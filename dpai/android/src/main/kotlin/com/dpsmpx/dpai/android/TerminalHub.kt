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

    private var nextId = 1

    /**
     * Вкладка: к target по SSH ([target]) или к Linux-среде в приложении
     * ([target] — `null`, D-75, шаг 5).
     */
    class Tab internal constructor(val id: Int, val target: TargetEntry?, val command: List<String>, val label: String) : TerminalListener {
        val linux: Boolean get() = target == null

        val session = TerminalSession(80, 24, listener = this)

        @Volatile
        internal var transport: ChannelTransport? = null

        /** Идёт подключение: второе нажатие «переподключить» его не дублирует. */
        internal var connecting = false

        /** Пусто — на связи; иначе — что с соединением («подключение…», отказ, закрыто). */
        @Volatile
        var status: String = "подключение…"
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

        override fun bell() {
            observer?.bell(this)
        }

        override fun clipboard(text: String) {
            observer?.clipboard(this, text)
        }

        override fun closed(exitCode: Int?) = Unit
    }

    /** Вкладка к Linux-среде в приложении: `sh -l` гостя (D-75, шаг 5). */
    fun openLinux(context: Context): Tab = open(context, null, emptyList(), "Linux")

    /** Ещё одна вкладка туда же, куда [like]. */
    fun openLike(context: Context, like: Tab): Tab = open(context, like.target, emptyList(), like.target?.label ?: "Linux")

    @Synchronized
    fun open(context: Context, target: TargetEntry?, command: List<String>, label: String): Tab {
        val tab = Tab(nextId++, target, command, label)
        tabs += tab
        count.intValue = tabs.size
        current = tab
        connect(context, tab)
        return tab
    }

    /** Подключить (и переподключить) вкладку: экран сохраняется, канал — новый. */
    fun connect(context: Context, tab: Tab) {
        synchronized(tab) {
            if (tab.open || tab.connecting) return
            tab.connecting = true
        }
        val app = context.applicationContext
        val t = ChannelTransport()
        tab.transport = t
        tab.status = "подключение…"
        tab.session.attach(t)
        observer?.tabs()
        thread(name = "dpai-term-connect", isDaemon = true) {
            val (cols, rows) = tab.session.read { it.cols to it.rows }
            val target = tab.target
            val o: Outcome<TermChannel>? = if (target == null || target.inApp) {
                // Linux-среда в приложении (и target «в приложении»): псевдотерминал через JNI, без SSH (D-75, шаг 5).
                com.dpsmpx.dpai.android.linux.LinuxSetup.shell(app, com.dpsmpx.dpai.execution.linux.WindowSize(cols, rows), tab.command).map { child ->
                    LocalChannel(child, onOutput = { b, n -> tab.session.output(b, 0, n) }, onClosed = { code -> if (tab.transport === t) closed(tab, code) })
                }
            } else {
                val controller = Controller.get(app.filesDir, null)
                target.toTarget(controller.knownHostsFile).ssh?.let { endpoint ->
                    JschShell(endpoint, target.platform, identities = listOf(KeystoreKey.identity())).open(
                        cols, rows, tab.command,
                        onOutput = { b, n -> tab.session.output(b, 0, n) },
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
                    tab.status = Redactor.STANDARD.redact("не подключился: ${com.dpsmpx.dpai.core.text.Words.reason(o.diagnostics.first().code.name)} — ${o.diagnostics.first().message}")
                }
                null -> {
                    tab.session.closed(null)
                    tab.status = "у target нет адреса SSH"
                }
            }
            synchronized(tab) { tab.connecting = false }
            observer?.tabs()
        }
    }

    private fun closed(tab: Tab, code: Int?) {
        tab.session.closed(code)
        tab.status = "соединение закрыто" + (code?.let { ", код $it" } ?: "")
        tab.session.output("\r\n\u001b[2m[${tab.status}]\u001b[0m\r\n".toByteArray())
        observer?.tabs()
    }

    fun close(tab: Tab) {
        // Канал закрывается и тогда, когда подключение ещё идёт (ChannelTransport помнит закрытие).
        tab.transport?.close()
        tab.transport = null
        tab.session.close()
        tabs -= tab
        count.intValue = tabs.size
        if (current === tab) current = tabs.lastOrNull()
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

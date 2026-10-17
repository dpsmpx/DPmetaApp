package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.jsch.JschShell
import com.dpsmpx.dpai.jsch.ShellChannel
import com.dpsmpx.dpai.terminal.TerminalListener
import com.dpsmpx.dpai.terminal.TerminalSession
import com.dpsmpx.dpai.terminal.TerminalTransport
import com.dpsmpx.dpai.ui.TargetEntry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Вкладки своего терминала (D-57): живут, пока жив процесс приложения, —
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

    @Volatile
    var current: Tab? = null

    private var nextId = 1

    class Tab internal constructor(val id: Int, val target: TargetEntry, val command: List<String>, val label: String) : TerminalListener {
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

    @Synchronized
    fun open(context: Context, target: TargetEntry, command: List<String>, label: String): Tab {
        val tab = Tab(nextId++, target, command, label)
        tabs += tab
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
            val controller = Controller.get(app.filesDir, null)
            val endpoint = tab.target.toTarget(controller.knownHostsFile).ssh
            val (cols, rows) = tab.session.read { it.cols to it.rows }
            val o = if (endpoint == null) {
                null
            } else {
                JschShell(endpoint, tab.target.platform, identities = listOf(KeystoreKey.identity())).open(
                    cols, rows, tab.command,
                    onOutput = { b, n -> tab.session.output(b, 0, n) },
                    onClosed = { code -> if (tab.transport === t) closed(tab, code) },
                )
            }
            when (o) {
                is Outcome.Success -> {
                    t.bind(o.value)
                    // Размер мог смениться, пока шло подключение.
                    tab.session.read { it.cols to it.rows }.let { (c, r) -> o.value.resize(c, r) }
                    tab.status = ""
                }
                is Outcome.Failure -> {
                    tab.session.closed(null)
                    tab.status = Redactor.STANDARD.redact("не подключился: ${o.diagnostics.first().code.name} — ${o.diagnostics.first().message}")
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
        tab.transport = null
        tab.session.close()
        tabs -= tab
        if (current === tab) current = tabs.lastOrNull()
        observer?.tabs()
    }
}

/**
 * Канал вкладки: до конца подключения ответы эмулятора копятся (немного),
 * потом уходят в канал SSH по порядку.
 */
internal class ChannelTransport : TerminalTransport {
    private val pending = ArrayList<ByteArray>()
    private var channel: ShellChannel? = null

    val isOpen: Boolean @Synchronized get() = channel?.isOpen == true

    @Synchronized
    fun bind(c: ShellChannel) {
        channel = c
        pending.forEach(c::write)
        pending.clear()
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
        channel?.close()
        channel = null
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}

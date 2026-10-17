package com.dpsmpx.dpai.android

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.terminal.Key
import com.dpsmpx.dpai.terminal.Mod
import com.dpsmpx.dpai.ui.TerminalLaunch
import kotlin.concurrent.thread

/**
 * Свой терминал (D-57): вкладки [TerminalHub], экран [TerminalView], ряд
 * клавиш, которых нет на экранной клавиатуре (Esc, Ctrl, Alt, Tab,
 * стрелки). Открывается с карточки target: оболочка или `claude` слота.
 */
class TerminalActivity : ComponentActivity(), TerminalHub.Observer {
    private lateinit var view: TerminalView
    private lateinit var tabsRow: LinearLayout
    private lateinit var statusRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var reconnect: TextView
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private var ctrl = false
    private var alt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Отступы системных панелей и клавиатуры — свои: экран терминала сжимается над клавиатурой.
        enableEdgeToEdge()
        setContentView(layout())
        if (savedInstanceState == null) handle(intent)
        if (TerminalHub.tabs.isEmpty() && intent?.getStringExtra(EXTRA_TARGET) == null) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        TerminalHub.observer = this
        show(TerminalHub.current)
        // Клавиатура — когда окно уже прикреплено: до этого контроллера вставок нет.
        view.post { view.showKeyboard() }
    }

    override fun onStop() {
        if (TerminalHub.observer === this) TerminalHub.observer = null
        super.onStop()
    }

    /** Новая вкладка по просьбе карточки target: target читается с диска — не в потоке интерфейса. */
    private fun handle(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_TARGET) ?: return
        val claude = intent.getBooleanExtra(EXTRA_CLAUDE, false)
        thread(name = "dpai-term-open", isDaemon = true) {
            val target = Controller.get(filesDir, null).targets().firstOrNull { it.id.value == id }
            runOnUiThread {
                if (target == null) {
                    if (TerminalHub.tabs.isEmpty()) finish()
                    return@runOnUiThread
                }
                val command = if (claude) (TerminalLaunch.claude(target) as? Outcome.Success)?.value.orEmpty() else emptyList()
                val tab = TerminalHub.open(this, target, command, if (claude) "${target.label} · claude" else target.label)
                show(tab)
            }
        }
    }

    private fun show(tab: TerminalHub.Tab?) {
        TerminalHub.current = tab
        view.tab = tab
        renderTabs()
    }

    // ---- TerminalHub.Observer: вызовы из потоков сети ----

    override fun screen(tab: TerminalHub.Tab) {
        if (tab === TerminalHub.current) view.screenChanged()
    }

    override fun tabs() {
        runOnUiThread { renderTabs() }
    }

    override fun bell(tab: TerminalHub.Tab) {
        view.post { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    }

    /** OSC 52: программа просит записать в буфер обмена — решает человек. */
    override fun clipboard(tab: TerminalHub.Tab, text: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Запись в буфер обмена")
                .setMessage("Программа на «${tab.label}» просит записать в буфер обмена:\n\n${text.take(PREVIEW)}")
                .setPositiveButton("Записать") { _, _ ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("терминал", text))
                }
                .setNegativeButton("Нет", null)
                .show()
        }
    }

    // ---- разметка ----

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.LTGRAY)
        typeface = Typeface.MONOSPACE
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        minWidth = dp(40)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun scrollRow(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(0xff1a1a1a.toInt())
        addView(row)
    }

    private fun layout(): View {
        view = TerminalView(this)
        view.stickyMods = ::consumeMods
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = TextView(this).apply {
            setTextColor(0xffffcc66.toInt())
            textSize = 13f
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        reconnect = chip("↻ Переподключить") { TerminalHub.current?.let { TerminalHub.connect(this, it) } }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xff262626.toInt())
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reconnect)
        }
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrlKey = chip("CTRL") { ctrl = !ctrl; markMods() }
        altKey = chip("ALT") { alt = !alt; markMods() }
        keys.addView(chip("ESC") { view.key(Key.ESCAPE) })
        keys.addView(ctrlKey)
        keys.addView(altKey)
        keys.addView(chip("TAB") { view.key(Key.TAB) })
        keys.addView(chip("←") { view.key(Key.LEFT) })
        keys.addView(chip("↓") { view.key(Key.DOWN) })
        keys.addView(chip("↑") { view.key(Key.UP) })
        keys.addView(chip("→") { view.key(Key.RIGHT) })
        keys.addView(chip("HOME") { view.key(Key.HOME) })
        keys.addView(chip("END") { view.key(Key.END) })
        keys.addView(chip("PGUP") { view.key(Key.PAGE_UP) })
        keys.addView(chip("PGDN") { view.key(Key.PAGE_DOWN) })
        for (s in listOf("-", "/", "|", "~", "\\")) keys.addView(chip(s) { view.input(s) })
        keys.addView(chip("Вставить") { paste() })
        keys.addView(chip("⌨") { view.toggleKeyboard() })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(scrollRow(tabsRow))
            addView(statusRow)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(scrollRow(keys))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
        return root
    }

    private fun consumeMods(): Int {
        val m = (if (ctrl) Mod.CTRL else 0) or (if (alt) Mod.ALT else 0)
        if (m != 0) {
            ctrl = false
            alt = false
            view.post(::markMods)
        }
        return m
    }

    private fun markMods() {
        ctrlKey.setTextColor(if (ctrl) ACTIVE else Color.LTGRAY)
        altKey.setTextColor(if (alt) ACTIVE else Color.LTGRAY)
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isNotEmpty()) TerminalHub.current?.session?.paste(text)
    }

    private fun renderTabs() {
        tabsRow.removeAllViews()
        val current = TerminalHub.current
        for (t in TerminalHub.tabs) {
            val name = t.label + (t.title.takeIf { it.isNotBlank() }?.let { " · ${it.take(24)}" } ?: "")
            tabsRow.addView(chip(name) { show(t) }.apply { setTextColor(if (t === current) Color.WHITE else Color.GRAY) })
        }
        current?.let { c ->
            tabsRow.addView(chip("+") {
                show(TerminalHub.open(this, c.target, emptyList(), c.target.label))
            })
            tabsRow.addView(chip("×") {
                TerminalHub.close(c)
                if (TerminalHub.tabs.isEmpty()) finish() else show(TerminalHub.current)
            })
        }
        val s = current?.status.orEmpty()
        status.text = s
        statusRow.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        reconnect.visibility = if (current != null && !current.open && s != "подключение…") View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_TARGET = "com.dpsmpx.dpai.terminal.TARGET"
        const val EXTRA_CLAUDE = "com.dpsmpx.dpai.terminal.CLAUDE"
        private const val PREVIEW = 500
        private val ACTIVE = 0xff66ccff.toInt()

        /** Открыть терминал target: оболочку или `claude` слота; без target — открытые вкладки. */
        fun start(context: Context, targetId: String?, claude: Boolean = false) {
            val i = Intent(context, TerminalActivity::class.java)
            if (targetId != null) i.putExtra(EXTRA_TARGET, targetId).putExtra(EXTRA_CLAUDE, claude)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}

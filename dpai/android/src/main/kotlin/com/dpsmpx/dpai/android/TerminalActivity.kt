package com.dpsmpx.dpai.android

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.app.AlertDialog
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
import android.widget.Toast
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.terminal.Key
import com.dpsmpx.dpai.terminal.Mod
import com.dpsmpx.dpai.ui.TerminalLaunch
import kotlin.concurrent.thread

/**
 * Свой терминал (D-71): вкладки [TerminalHub], экран [TerminalView], ряд
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
    private lateinit var keysRow: View
    private var ctrl = false
    private var alt = false

    /** Строка поиска по истории (терминал:A-4) и «↓ к концу»; где поиск остановился — сквозной номер строки. */
    private lateinit var searchRow: LinearLayout
    private lateinit var searchField: android.widget.EditText
    private lateinit var toEnd: TextView
    private var searchAt = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Экран терминала — единственное место, где видны код входа Claude (`/login`) и содержимое
        // сессии: в превью «недавних» и в снимках экрана его быть не должно (D-86, T8).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // «Не гасить экран» — настройкой в «⋯» (терминал:A-24), по умолчанию — да.
        if (TerminalView.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Отступы системных панелей и клавиатуры — свои: экран терминала сжимается над клавиатурой.
        // Терминал всегда тёмный: значки панелей — светлые и при светлой теме телефона (терминал:A-20).
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContentView(layout())
        applyKeyboard()
        // После смерти процесса Android восстанавливает экран, а вкладок (только в памяти, D-27) уже нет:
        // открыть заново ту, с которой экран начинался, а не показывать мёртвый чёрный экран.
        val lost = savedInstanceState != null && TerminalHub.tabs.isEmpty()
        if (savedInstanceState == null || TerminalHub.tabs.isEmpty()) handle(intent)
        // Новая сессия вместо прежней — словами, а не молча (терминал:A-24).
        if (lost) {
            TerminalHub.current?.let { t ->
                t.notice = "прежняя сессия потеряна: приложение перезапускалось"
                // Строка — на время, а не навсегда: дальше вкладка как вкладка.
                view.postDelayed({
                    t.notice = null
                    renderTabs()
                }, NOTICE_MS)
            }
        }
        if (TerminalHub.tabs.isEmpty() && intent?.getStringExtra(EXTRA_TARGET) == null && intent?.getBooleanExtra(EXTRA_LINUX, false) != true) finish()
    }

    // Приложение на экране — окно запроса поверх него не нужно (D-44), как у MainActivity: иначе
    // оно всплывало бы над терминалом и забирало клавиатуру.
    override fun onResume() {
        super.onResume()
        RequestOverlay.appVisible = true
    }

    override fun onPause() {
        RequestOverlay.appVisible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        TerminalHub.observer = this
        show(TerminalHub.current)
        // Клавиатура — когда окно уже прикреплено: до этого контроллера вставок нет. Открывается при первом
        // показе и если была открыта при уходе; иначе — касанием (терминал:A-24): не закрывает полэкрана вывода.
        if (keyboardWas) view.post { view.showKeyboard() } else view.post { view.requestFocus() }
    }

    override fun onStop() {
        keyboardWas = view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
        if (TerminalHub.observer === this) TerminalHub.observer = null
        super.onStop()
    }

    /** Новая вкладка по просьбе карточки target: target читается с диска — не в потоке интерфейса. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CLOSE_ALL, false) == true) {
            askCloseAll()
            return
        }
        // Linux-среда в приложении (D-75, шаг 5): вкладка без target, подключение — в потоке вкладки.
        if (intent?.getBooleanExtra(EXTRA_LINUX, false) == true) {
            // «Открыть в терминале» вкладки «Файлы» (D-99): оболочка гостя сразу в этой папке.
            val dir = intent.getStringExtra(EXTRA_DIR)
            show(
                if (dir == null) {
                    TerminalHub.openLinux(this)
                } else {
                    TerminalHub.open(this, null, listOf("/bin/sh", "-c", "cd \"$1\" && exec /bin/sh -l", "sh", dir), "Linux · ${dir.substringAfterLast('/')}")
                },
            )
            return
        }
        val id = intent?.getStringExtra(EXTRA_TARGET) ?: return
        val claude = intent.getBooleanExtra(EXTRA_CLAUDE, false)
        val login = intent.getBooleanExtra(EXTRA_LOGIN, false)
        thread(name = "dpai-term-open", isDaemon = true) {
            val target = Controller.get(filesDir).targets().firstOrNull { it.id.value == id }
            runOnUiThread {
                if (target == null) {
                    if (TerminalHub.tabs.isEmpty()) finish()
                    return@runOnUiThread
                }
                // Claude не запустить (Windows, нет каталога слота) — сказать, а не открыть вместо него оболочку (D-05).
                val command = if (!claude && !login) {
                    emptyList()
                } else {
                    when (val c = if (login) TerminalLaunch.login(target) else TerminalLaunch.claude(target)) {
                        is Outcome.Success -> c.value
                        is Outcome.Failure -> {
                            Toast.makeText(this, c.diagnostics.first().message, Toast.LENGTH_LONG).show()
                            if (TerminalHub.tabs.isEmpty()) finish()
                            return@runOnUiThread
                        }
                    }
                }
                // Сессия в tmux (D-111): оболочка и CLI слота; вход в аккаунт — разовый, без неё.
                val kind = when {
                    login -> null
                    claude -> target.executor.key
                    else -> TerminalHub.SHELL
                }
                val tab = TerminalHub.open(this, target, command, if (claude || login) "${target.label} · ${target.executor.key}${if (login) " · вход" else ""}" else target.label, kind)
                show(tab)
            }
        }
    }

    private fun show(tab: TerminalHub.Tab?) {
        TerminalHub.current = tab
        // Открытая вкладка — увиденная: точка сигнала снимается (терминал:A-8).
        if (tab?.attention == true) {
            tab.attention = false
            TerminalService.refresh(this)
        }
        view.tab = tab
        renderTabs()
    }

    /** Поиск по истории (терминал:A-4): строка над экраном, совпадение — выделением. */
    private fun openSearch() {
        searchRow.visibility = View.VISIBLE
        searchAt = -1
        searchField.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(searchField, 0)
    }

    private fun closeSearch() {
        searchRow.visibility = View.GONE
        searchAt = -1
        view.requestFocus()
    }

    private fun find(up: Boolean) {
        val tab = TerminalHub.current ?: return
        val q = searchField.text.toString()
        if (q.isEmpty()) return
        val from = if (searchAt >= 0) searchAt else if (up) tab.session.read { it.lineCount } else view.firstVisibleLine() - 1
        val m = tab.session.read { it.find(q, from, up) }
        if (m == null) {
            Toast.makeText(this, if (up) "Выше не найдено" else "Ниже не найдено", Toast.LENGTH_SHORT).show()
            return
        }
        searchAt = m.line
        view.reveal(m)
    }

    /**
     * Поле составления (терминал:A-5): текст для Claude — с подсказками и
     * голосом клавиатуры, но без её обучения (A-11); «Отправить» — вставкой
     * (скобки 2004, фильтр управляющих знаков), Enter — отдельно.
     */
    private fun compose() {
        val tab = TerminalHub.current ?: return
        val field = android.widget.EditText(this).apply {
            minLines = 4
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Текст в «${tab.label}»")
            .setView(field)
            .setPositiveButton("Отправить и Enter") { _, _ ->
                field.text.toString().takeIf { it.isNotEmpty() }?.let { tab.session.paste(it) }
                tab.session.key(Key.ENTER)
            }
            .setNeutralButton("Отправить") { _, _ -> field.text.toString().takeIf { it.isNotEmpty() }?.let { tab.session.paste(it) } }
            .setNegativeButton("Отмена", null)
            .create()
        // У диалога своё окно: FLAG_SECURE активности его не закрывает (D-86).
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    // ---- TerminalHub.Observer: вызовы из потоков сети ----

    override fun screen(tab: TerminalHub.Tab) {
        if (tab === TerminalHub.current) view.screenChanged()
    }

    override fun tabs() {
        runOnUiThread { renderTabs() }
    }

    override fun bell(tab: TerminalHub.Tab) {
        // Вибрация — только от текущей вкладки; фоновая — точкой у ярлыка (терминал:A-8).
        if (tab === TerminalHub.current) view.post { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    }

    /** OSC 52: программа просит записать в буфер обмена — решает человек. */
    override fun clipboard(tab: TerminalHub.Tab, text: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // Буфер обмена идёт через binder: сотни КиБ не пройдут и уронили бы приложение.
            if (text.length > MAX_CLIP) {
                Toast.makeText(this, "Программа на «${tab.label}» прислала для буфера обмена ${text.length} знаков — слишком много, не записано", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            fun write() {
                try {
                    // Экран терминала несёт секреты (D-86): с пометкой «чувствительное» (терминал:A-11).
                    Clipboard.copy(this, "терминал", text, sensitive = true)
                } catch (e: RuntimeException) {
                    Toast.makeText(this, "Не записано в буфер обмена: ${com.dpsmpx.dpai.core.text.Words.failure(e)}", Toast.LENGTH_LONG).show()
                }
            }
            // Разрешено до закрытия вкладки (терминал:A-24) — без вопроса; предел — тот же.
            if (tab.clipboardAllowed) {
                write()
                return@runOnUiThread
            }
            val always = android.widget.CheckBox(this).apply { this.text = "Разрешать этой вкладке до закрытия" }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Запись в буфер обмена")
                .setMessage("Программа на «${tab.label}» просит записать в буфер обмена:\n\n${text.take(PREVIEW)}")
                .setView(android.widget.FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(always) })
                .setPositiveButton("Записать") { _, _ ->
                    if (always.isChecked) tab.clipboardAllowed = true
                    write()
                }
                .setNegativeButton("Нет", null)
                .create()
            // У диалога своё окно, и FLAG_SECURE активности его не закрывает (D-86, как `Ui.secure` DPpass).
            dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
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
        // Цель касания — 48 dp (терминал:A-12): строка 15 sp с отступами давала около 36 dp.
        minHeight = dp(48)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Названия знаков ряда для TalkBack (терминал:A-12): «стрелка влево» вместо глифа, «Escape» вместо «ESC». */
    private fun spoken(label: String): String = when (label) {
        "←" -> "Влево"
        "→" -> "Вправо"
        "↑" -> "Вверх"
        "↓" -> "Вниз"
        "ESC" -> "Escape"
        "TAB" -> "Tab"
        "PGUP" -> "Page Up"
        "PGDN" -> "Page Down"
        "HOME" -> "Home"
        "END" -> "End"
        "~" -> "тильда"
        "|" -> "вертикальная черта"
        else -> label
    }

    /**
     * Клавиша ряда (терминал:A-3): касание — [action]; у стрелок удержание
     * дольше [REPEAT_DELAY_MS] — повтор каждые [REPEAT_EVERY_MS], как у
     * клавиатуры; смахивание вверх — второй знак [secondary] ([onUp]), он же
     * мелко над подписью. Палец ушёл (CANCEL) — ничего не отправлено:
     * смахивание не должно двигать выбор в меню Claude (ревью 18 октября 2026).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // нажатие для TalkBack — тот же onClick
    private fun key(primary: String, secondary: String, repeat: Boolean = false, onUp: () -> Unit, action: () -> Unit): TextView = chip(primary, action).apply {
        text = android.text.SpannableString("$secondary\n$primary").apply {
            setSpan(android.text.style.RelativeSizeSpan(0.55f), 0, secondary.length, 0)
            setSpan(android.text.style.ForegroundColorSpan(Color.GRAY), 0, secondary.length, 0)
        }
        contentDescription = "${spoken(primary)}; смахнуть вверх — ${spoken(secondary)}"
        var downY = 0f
        var swiped = false
        var repeated = false
        val again = object : Runnable {
            override fun run() {
                repeated = true
                action()
                postDelayed(this, REPEAT_EVERY_MS)
            }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    downY = e.y
                    swiped = false
                    repeated = false
                    if (repeat) v.postDelayed(again, REPEAT_DELAY_MS)
                }
                android.view.MotionEvent.ACTION_MOVE -> if (!swiped && downY - e.y > dp(SWIPE_DP)) {
                    swiped = true
                    v.removeCallbacks(again)
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(again)
                    when {
                        swiped -> onUp()
                        !repeated -> action()
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(again)
                }
            }
            true
        }
    }

    private fun moreChip(): TextView = chip("⋯") {}.apply {
        contentDescription = "Ещё клавиши"
        setOnClickListener { v -> more(v) }
    }

    /** Меню ярлыка (терминал:A-21): закрыть с вопросом, переименовать, переподключить. */
    private fun tabMenu(anchor: View, t: TerminalHub.Tab) {
        val items = buildList<Pair<String, () -> Unit>> {
            add("Закрыть…" to { confirmClose(t) })
            add("Переименовать…" to { rename(t) })
            if (!t.open && !t.busy) add("Переподключить" to { TerminalHub.connect(this@TerminalActivity, t) })
        }
        popup(anchor, items)
    }

    /** «+» (терминал:A-21): такая же вкладка, оболочка или CLI слота на том же target, Linux в приложении. */
    private fun newTab(anchor: View, like: TerminalHub.Tab) {
        // Target — как записан сейчас: настройка tmux могла смениться, пока вкладка открыта.
        val target = like.target?.let { TerminalHub.fresh(this, it) }
        val items = buildList<Pair<String, () -> Unit>> {
            add("Такая же" to { show(TerminalHub.openLike(this@TerminalActivity, like)) })
            if (target != null) {
                add("Оболочка: ${target.label}" to { show(TerminalHub.open(this@TerminalActivity, target, emptyList(), target.label, kind = TerminalHub.SHELL)) })
                TerminalLaunch.claude(target).valueOrNull()?.let { cmd ->
                    add("${target.executor.title}: ${target.label}" to { show(TerminalHub.open(this@TerminalActivity, target, cmd, "${target.label} · ${target.executor.key}", kind = target.executor.key)) })
                }
            }
            if (com.dpsmpx.dpai.android.linux.LinuxSetup.available(this@TerminalActivity)) add("Linux в приложении" to { show(TerminalHub.openLinux(this@TerminalActivity)) })
        }
        popup(anchor, items)
    }

    private fun popup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val popup = android.widget.PopupMenu(this, anchor)
        items.forEachIndexed { i, (title, _) -> popup.menu.add(android.view.Menu.NONE, i, i, title) }
        popup.setOnMenuItemClickListener { item -> items.getOrNull(item.itemId)?.second?.invoke(); true }
        popup.show()
    }

    /** Закрыть вкладку; открытую — с вопросом (аудит удобства, A-18): в ней может идти claude или сборка. */
    private fun confirmClose(t: TerminalHub.Tab) {
        fun closeTab() {
            TerminalHub.close(t)
            if (TerminalHub.tabs.isEmpty()) finish() else show(TerminalHub.current)
        }
        // Сессия в tmux живёт на target и без связи (D-111): её закрытие — тоже с вопросом.
        if (!t.open && t.tmux == null) {
            closeTab()
            return
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Закрыть вкладку «${t.label}»?")
            .setMessage(
                if (t.tmux != null) {
                    "Сессия tmux на target завершится; программа в ней (claude, сборка) получит сигнал завершения."
                } else {
                    "Соединение закроется; программа в ней (claude, сборка) получит сигнал завершения."
                },
            )
            .setPositiveButton("Закрыть") { _, _ -> closeTab() }
            .setNegativeButton("Отмена", null)
            .create()
        // У диалога своё окно: FLAG_SECURE активности его не закрывает (D-86).
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    /** Новое имя ярлыка: от 1 до 40 знаков; уведомление службы — с ним же. */
    private fun rename(t: TerminalHub.Tab) {
        val field = android.widget.EditText(this).apply {
            setText(t.label)
            setSelection(text.length)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(LABEL_MAX))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Имя вкладки")
            .setView(field)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = field.text.toString().trim().filterNot { it.isISOControl() }
                if (name.isNotEmpty()) {
                    t.label = name
                    renderTabs()
                    TerminalService.refresh(this)
                }
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    /** «⋯» ряда (терминал:A-3): то, что не поместилось, — Вставить, клавиатура, знаки, листание. */
    private fun more(anchor: View) {
        val shiftEnter = TerminalView.shiftEnterNewline(this)
        val items = listOf<Pair<String, () -> Unit>>(
            "Вставить" to ::paste,
            "Клавиатура" to { view.toggleKeyboard() },
            // Перенос строки без отправки (терминал:A-6) и поле составления (A-5).
            "⏎ строка" to { view.newLine() },
            "Поле составления…" to ::compose,
            "Найти…" to ::openSearch,
            // Шрифт (терминал:A-9): размер запоминается.
            "Шрифт крупнее" to { view.fontStep(true) },
            "Шрифт мельче" to { view.fontStep(false) },
            "Шрифт: 80 столбцов" to { view.fitColumns(80) },
            (if (shiftEnter) "Shift+Enter — новая строка: да" else "Shift+Enter — новая строка: нет") to { TerminalView.setShiftEnterNewline(this, !shiftEnter) },
            // Экран не гаснет, пока открыт терминал (терминал:A-24): выключается здесь.
            (if (TerminalView.keepScreenOn(this)) "Не гасить экран: да" else "Не гасить экран: нет") to {
                val on = !TerminalView.keepScreenOn(this)
                TerminalView.setKeepScreenOn(this, on)
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            },
            // Палец — мышь программы: протяжка в tmux и vim (терминал:A-22).
            (if (view.mouseToProgram) "Мышь программе: да" else "Мышь программе: нет") to { view.mouseToProgram = !view.mouseToProgram },
            // Жирный восьми основных цветов — ярким (терминал:A-18).
            (if (view.boldBright) "Жирный ярче: да" else "Жирный ярче: нет") to { view.boldBright = !view.boldBright },
            // Закрыть вкладку — и здесь, не только долгим нажатием на ярлык (терминал:A-21).
            "Закрыть вкладку…" to { TerminalHub.current?.let(::confirmClose) },
            "HOME" to { view.key(Key.HOME) },
            "END" to { view.key(Key.END) },
            "PGUP" to { view.key(Key.PAGE_UP) },
            "PGDN" to { view.key(Key.PAGE_DOWN) },
        ) + listOf("-", "/", "\\", "|", "~").map { s -> s to { view.input(s) } }
        val popup = android.widget.PopupMenu(this, anchor)
        items.forEachIndexed { i, (title, _) -> popup.menu.add(android.view.Menu.NONE, i, i, title) }
        popup.setOnMenuItemClickListener { item -> items.getOrNull(item.itemId)?.second?.invoke(); true }
        popup.show()
    }

    /** Подключена ли внешняя клавиатура: тогда ряд клавиш скрыт (терминал:A-3, §4, п. 2). */
    private fun hardKeyboard(): Boolean = resources.configuration.let {
        it.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            it.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun applyKeyboard() {
        keysRow.visibility = if (hardKeyboard()) View.GONE else View.VISIBLE
        renderTabs()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Клавиатуру подключили или отключили — без пересоздания экрана (configChanges keyboard|keyboardHidden).
        if (::keysRow.isInitialized) applyKeyboard()
    }

    private fun scrollRow(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(0xff1a1a1a.toInt())
        addView(row)
    }

    private fun layout(): View {
        view = TerminalView(this)
        view.stickyMods = ::consumeMods
        view.onPaste = ::pasteAsk
        // Сочетания внешней клавиатуры (терминал:A-14): вкладки и поиск — здесь.
        view.onShortcut = { s ->
            val c = TerminalHub.current
            val i = TerminalHub.tabs.indexOf(c)
            when (s) {
                TerminalView.Shortcut.NEW_TAB -> c?.let { show(TerminalHub.openLike(this, it)) }
                TerminalView.Shortcut.NEXT_TAB -> TerminalHub.tabs.getOrNull((i + 1) % TerminalHub.tabs.size.coerceAtLeast(1))?.let(::show)
                TerminalView.Shortcut.PREV_TAB -> TerminalHub.tabs.getOrNull((i - 1 + TerminalHub.tabs.size) % TerminalHub.tabs.size.coerceAtLeast(1))?.let(::show)
                TerminalView.Shortcut.SEARCH -> openSearch()
            }
        }
        toEnd = chip("↓ к концу") { view.toEnd() }.apply {
            setBackgroundColor(0xcc262626.toInt())
            visibility = View.GONE
        }
        view.onAway = { n ->
            toEnd.visibility = if (n == null) View.GONE else View.VISIBLE
            toEnd.text = if (n != null && n > 0) "↓ к концу (+$n)" else "↓ к концу"
        }
        searchField = android.widget.EditText(this).apply {
            hint = "Найти в истории"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setOnEditorActionListener { _, _, _ -> find(up = true); true }
        }
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xff262626.toInt())
            visibility = View.GONE
            addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip("▲") { find(up = true) }.apply { contentDescription = "Найти выше" })
            addView(chip("▼") { find(up = false) }.apply { contentDescription = "Найти ниже" })
            addView(chip("✕") { closeSearch() }.apply { contentDescription = "Закрыть поиск" })
        }
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
        // Ряд клавиш (терминал:A-3, §4, п. 1): девять клавиш видны целиком, без прокрутки; смахивание вверх по
        // клавише — второй знак (ESC ~, TAB |, ← HOME, ↓ PGDN, ↑ PGUP, → END); «⋯» — остальное.
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrlKey = chip("CTRL") { ctrl = !ctrl; markMods() }.apply { contentDescription = "Control" }
        altKey = chip("ALT") { alt = !alt; markMods() }.apply { contentDescription = "Alt" }
        markMods()
        val row = listOf(
            key("ESC", "~", onUp = { view.input("~") }) { view.key(Key.ESCAPE) },
            ctrlKey,
            altKey,
            key("TAB", "|", onUp = { view.input("|") }) { view.key(Key.TAB) },
            key("←", "HOME", repeat = true, onUp = { view.key(Key.HOME) }) { view.key(Key.LEFT) },
            key("↓", "PGDN", repeat = true, onUp = { view.key(Key.PAGE_DOWN) }) { view.key(Key.DOWN) },
            key("↑", "PGUP", repeat = true, onUp = { view.key(Key.PAGE_UP) }) { view.key(Key.UP) },
            key("→", "END", repeat = true, onUp = { view.key(Key.END) }) { view.key(Key.RIGHT) },
            moreChip(),
        )
        for (k in row) {
            k.setPadding(dp(2), dp(8), dp(2), dp(8))
            k.minWidth = 0
            keys.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        keys.setBackgroundColor(0xff1a1a1a.toInt())
        keysRow = keys
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(scrollRow(tabsRow))
            addView(statusRow)
            addView(searchRow)
            // «↓ к концу» — поверх экрана, у правого нижнего угла (терминал:A-4).
            addView(
                android.widget.FrameLayout(this@TerminalActivity).apply {
                    addView(view)
                    addView(toEnd, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(keys)
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
        // Залипание — не только цветом (терминал:A-12): подчёркнуто и сказано TalkBack.
        for ((v, on) in listOf(ctrlKey to ctrl, altKey to alt)) {
            v.paintFlags = if (on) v.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG else v.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            v.stateDescription = if (on) "включён" else "выключен"
        }
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isNotEmpty()) pasteAsk(text)
    }

    /**
     * Вставка (терминал:A-7): несколько строк без вставки в скобках или
     * большой текст — с вопросом и первыми строками; без скобок 2004 каждая
     * строка выполняется сразу, а в буфере легко оказывается чужое.
     */
    private fun pasteAsk(text: String) {
        val tab = TerminalHub.current ?: return
        val bracketed = tab.session.read { it.bracketedPaste }
        if (!com.dpsmpx.dpai.terminal.PasteGuard.needsConfirm(text, bracketed)) {
            tab.session.paste(text)
            return
        }
        // Причина — то условие, что сработало: однострочный большой текст без скобок — не «выполнит каждую» (ревью PR #170).
        val lines = com.dpsmpx.dpai.terminal.PasteGuard.lines(text)
        val why = if (!bracketed && lines > 1) "Строк: $lines — программа на «${tab.label}» выполнит каждую сразу." else "Текст большой: ${text.length} знаков."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Вставить в терминал?")
            .setMessage("$why\n\n${text.take(PREVIEW)}")
            .setPositiveButton("Вставить") { _, _ -> tab.session.paste(text) }
            .setNegativeButton("Нет", null)
            .create()
        // У диалога своё окно, и FLAG_SECURE активности его не закрывает (D-86): в буфере бывают секреты.
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    /** «Закрыть все» — с подтверждением (терминал:A-1): одно касание в шторке не обрывает все сеансы. */
    private fun askCloseAll() {
        if (TerminalHub.tabs.isEmpty()) return
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Закрыть все вкладки терминала?")
            .setMessage("Вкладок: ${TerminalHub.tabs.size}. Соединения закроются, программы в них (claude, сборка) получат сигнал завершения; экран и история пропадут.")
            .setPositiveButton("Закрыть все") { _, _ ->
                TerminalHub.tabs.toList().forEach(TerminalHub::close)
                finish()
            }
            .setNegativeButton("Отмена", null)
            .create()
        // У диалога своё окно: FLAG_SECURE активности его не закрывает (D-86).
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun renderTabs() {
        tabsRow.removeAllViews()
        val current = TerminalHub.current
        for (t in TerminalHub.tabs) {
            // Значок состояния у ярлыка (терминал:A-21, §4, п. 3): ● на связи, ◌ подключается, ✕ закрыта;
            // точка в конце — фоновая вкладка подала сигнал (A-8). Заголовок программы — только у текущей.
            val connecting = !t.open && t.busy
            val state = when {
                t.open -> "●"
                connecting -> "◌"
                else -> "✕"
            }
            val title = if (t === current) t.title.takeIf { it.isNotBlank() }?.let { " · " + if (it.length > TITLE_MAX) it.take(TITLE_MAX - 1) + "…" else it }.orEmpty() else ""
            val bell = t.attention && t !== current
            // Текущая вкладка — и подчёркиванием, и для TalkBack, а не только цветом (терминал:A-12).
            tabsRow.addView(chip("$state ${t.label}$title${if (bell) " •" else ""}") { show(t) }.apply {
                setTextColor(if (t === current) Color.WHITE else Color.GRAY)
                if (t === current) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                stateDescription = listOfNotNull(
                    if (t === current) "выбрана" else null,
                    when {
                        t.open -> "на связи"
                        connecting -> "подключается"
                        else -> "закрыта"
                    },
                    if (bell) "ждёт" else null,
                ).joinToString(", ")
                // Закрыть, переименовать, переподключить — долгим нажатием на ярлык (терминал:A-21).
                setOnLongClickListener { v ->
                    tabMenu(v, t)
                    true
                }
            })
        }
        if (current != null) {
            tabsRow.addView(chip("+") {}.apply {
                contentDescription = "Новая вкладка"
                setOnClickListener { v -> newTab(v, current) }
            })
        }
        // Внешняя клавиатура — ряд клавиш скрыт, «⋯» — в ряду вкладок (терминал:A-3, §4, п. 2).
        if (hardKeyboard()) tabsRow.addView(moreChip())
        val s = current?.status.orEmpty()
        status.text = listOfNotNull(s.ifEmpty { null }, current?.notice).joinToString(" · ")
        statusRow.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        reconnect.visibility = if (current != null && !current.open && !current.busy) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_TARGET = "com.dpsmpx.dpai.terminal.TARGET"
        const val EXTRA_CLAUDE = "com.dpsmpx.dpai.terminal.CLAUDE"
        const val EXTRA_LOGIN = "com.dpsmpx.dpai.terminal.LOGIN"
        const val EXTRA_LINUX = "com.dpsmpx.dpai.terminal.LINUX"
        const val EXTRA_DIR = "com.dpsmpx.dpai.terminal.DIR"

        /**
         * Была ли открыта клавиатура при уходе с экрана (терминал:A-24): при
         * возврате она открывается только тогда; первый показ — с клавиатурой.
         */
        private var keyboardWas = true

        /** Сколько видна строка «прежняя сессия потеряна», мс. */
        private const val NOTICE_MS = 20_000L


        /** Заголовок программы у ярлыка — не длиннее, с «…». */
        private const val TITLE_MAX = 24

        /** Имя вкладки — не длиннее. */
        private const val LABEL_MAX = 40

        /** «Закрыть все» из уведомления службы терминала — подтверждение здесь (терминал:A-1). */
        const val EXTRA_CLOSE_ALL = "com.dpsmpx.dpai.terminal.CLOSE_ALL"

        /** Сколько смахнуть вверх по клавише ряда, чтобы получить её второй знак, dp. */
        private const val SWIPE_DP = 24
        private const val PREVIEW = 500
        private const val MAX_CLIP = 100_000
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_EVERY_MS = 60L
        private val ACTIVE = 0xff66ccff.toInt()

        /** Открыть терминал target: оболочку или `claude` слота; без target — открытые вкладки. */
        fun start(context: Context, targetId: String?, claude: Boolean = false, login: Boolean = false) {
            val i = Intent(context, TerminalActivity::class.java)
            if (targetId != null) i.putExtra(EXTRA_TARGET, targetId).putExtra(EXTRA_CLAUDE, claude).putExtra(EXTRA_LOGIN, login)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        /** Открыть терминал Linux-среды в приложении (D-75, шаг 5); [dir] — каталог гостя, в котором начать (D-99). */
        fun startLinux(context: Context, dir: String? = null) {
            val i = Intent(context, TerminalActivity::class.java).putExtra(EXTRA_LINUX, true)
            if (dir != null) i.putExtra(EXTRA_DIR, dir)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}

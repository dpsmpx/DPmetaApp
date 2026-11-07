package com.dpsmpx.dpai.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.terminal.Attr
import com.dpsmpx.dpai.terminal.Key
import com.dpsmpx.dpai.terminal.Mod
import com.dpsmpx.dpai.terminal.MouseButton
import com.dpsmpx.dpai.terminal.MouseMode
import com.dpsmpx.dpai.terminal.Style
import com.dpsmpx.dpai.terminal.TermColor
import com.dpsmpx.dpai.terminal.TerminalEmulator
import com.dpsmpx.dpai.terminal.TerminalRow
import com.dpsmpx.dpai.terminal.TerminalSelection
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Экран своего терминала (D-71): сетка эмулятора на Canvas, курсор,
 * прокрутка назад, выделение и копирование, масштаб щипком, ввод с
 * экранной и внешней клавиатуры. Свой `View`, а не Compose: экранной
 * клавиатуре нужен свой `InputConnection` — без подсказок и
 * автоисправлений, по символу.
 */
class TerminalView(context: Context) : View(context) {
    var tab: TerminalHub.Tab? = null
        set(value) {
            field = value
            scrollBack = 0
            selection = null
            menu?.finish()
            anchored = false
            // Инерция прежней вкладки не листает новую (стрелки в vim, колесо в TUI).
            flinger.forceFinished(true)
            scrollAccum = 0f
            regrid()
            invalidate()
        }

    /** Залипающие Ctrl и Alt ряда клавиш: читаются и сбрасываются при вводе. */
    var stickyMods: () -> Int = { 0 }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fill = Paint()
    private var cellW = 1f
    private var cellH = 1f
    private var ascent = 0f

    /** На сколько строк экран отведён назад в историю (0 — низ). */
    private var scrollBack = 0

    /**
     * Счётчики истории эмулятора на последней отрисовке: пока человек читает
     * историю, новые строки сдвигают [scrollBack], а не текст под глазами.
     * `false` — сверить заново (смена вкладки или размера).
     */
    private var anchored = false
    private var seenAdded = 0L
    private var seenDropped = 0L
    private var scrollAccum = 0f

    /** Выделение: сквозные строки и столбцы эмулятора (начало, конец). */
    private var selection: IntArray? = null
    private var selecting = false

    /** Какую ручку выделения тянет палец: [HANDLE_START], [HANDLE_END]; 0 — никакую (терминал:A-2). */
    private var dragging = 0

    /** Меню выделения — плавающая панель Android: Копировать, Вставить, Выделить всё, ссылка, Поделиться. */
    private var menu: ActionMode? = null

    /** Прокрутка истории, пока палец с ручкой у края экрана: −1 — назад, +1 — к концу, 0 — нет. */
    private var edge = 0
    private var edgeX = 0f
    private var edgeY = 0f
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (edge == 0 || !selecting) return
            val history = tab?.session?.read { it.scrollbackSize } ?: return
            scrollBack = (scrollBack - edge).coerceIn(0, history)
            moveDragged(edgeX, edgeY)
            postDelayed(this, EDGE_MS)
        }
    }

    /** Касания ссылок (DPmetaApp:D-75): одно правило на всё приложение — `LinkTaps`. */
    private val linkTaps = com.dpsmpx.dpai.ui.LinkTaps()

    /** Инерция прокрутки истории после смахивания. */
    private val flinger = android.widget.OverScroller(context)
    private var flingLastY = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            flinger.forceFinished(true)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (selecting) return true
            flingLastY = 0
            flinger.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            lastFling?.recycle()
            lastFling = MotionEvent.obtain(e2)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selection != null) {
                clearSelection()
                return true
            }
            val t = tab ?: return true
            val mouse = t.session.read { it.mouseMode } != MouseMode.NONE
            val (c, r) = cell(e)
            if (mouse) {
                t.session.mouse(MouseButton.LEFT, c, r, pressed = true, motion = false)
                t.session.mouse(MouseButton.LEFT, c, r, pressed = false, motion = false)
                return true
            }
            // Ссылка под пальцем (адрес входа Claude из `/login`): первое касание — копия, второе — браузер
            // (DPmetaApp:D-75); иначе — клавиатура.
            val link = t.session.read { it.linkAt(it.scrollbackSize - scrollBack + r, c) }
            if (link != null) tapLink(link) else showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (selecting) return true
            scrollAccum += dy
            val lines = (scrollAccum / cellH).toInt()
            if (lines == 0) return true
            scrollAccum -= lines * cellH
            scrollLines(lines, e2)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val t = tab ?: return
            val (c, r) = cell(e)
            // Долгое нажатие выделяет слово, как принято в Android (терминал:A-2); палец дальше тянет конец.
            val (line, word) = t.session.read { val l = it.scrollbackSize - scrollBack + r; l to TerminalSelection.word(it.line(l), c) }
            menu?.finish()
            selection = intArrayOf(line, word.first, line, word.last)
            selecting = true
            dragging = HANDLE_END
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            fontPx = (fontPx * d.scaleFactor).coerceIn(sp(MIN_SP), sp(MAX_SP))
            regrid()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (fontPx <= 0f) fontPx = sp(DEFAULT_SP)
        metrics()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun metrics() {
        paint.textSize = fontPx
        cellW = paint.measureText("M").coerceAtLeast(1f)
        val fm = paint.fontMetrics
        cellH = ceil(fm.descent - fm.ascent).coerceAtLeast(1f)
        ascent = -fm.ascent
    }

    /** Размер сетки по размеру окна и шрифту; программа узнаёт о нём по SIGWINCH. */
    private fun regrid() {
        metrics()
        val t = tab ?: return
        if (width <= 0 || height <= 0) return
        val cols = (width / cellW).toInt().coerceIn(1, TerminalEmulator.MAX_COLS)
        val rows = (height / cellH).toInt().coerceIn(1, TerminalEmulator.MAX_ROWS)
        if (t.session.read { it.cols != cols || it.rows != rows }) {
            // Строки перестроены: прежние номера строк истории и выделения ничего не значат.
            t.session.resize(cols, rows)
            scrollBack = 0
            selection = null
            menu?.finish()
            anchored = false
            flinger.forceFinished(true)
            scrollAccum = 0f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        regrid()
    }

    /** Новый вывод: перерисовать; во время синхронного вывода (2026) — с задержкой. */
    fun screenChanged() {
        val sync = tab?.session?.read { it.syncOutput } == true
        if (sync) postInvalidateDelayed(SYNC_REDRAW_MS) else postInvalidateOnAnimation()
    }

    /** Последнее смахивание: место пальца для колеса мыши и стрелок во время инерции. */
    private var lastFling: MotionEvent? = null

    override fun computeScroll() {
        if (!flinger.computeScrollOffset()) return
        val y = flinger.currY
        scrollAccum += (y - flingLastY).toFloat()
        flingLastY = y
        val lines = (scrollAccum / cellH).toInt()
        if (lines != 0) {
            scrollAccum -= lines * cellH
            lastFling?.let { scrollLines(lines, it) }
            // Упёрлись в край истории в сторону движения — инерция кончается, а не крутит впустую.
            val (history, app) = tab?.session?.read { it.scrollbackSize to (it.altScreen || it.mouseMode != MouseMode.NONE) } ?: (0 to false)
            if (!app && ((lines > 0 && scrollBack == 0) || (lines < 0 && scrollBack >= history))) flinger.forceFinished(true)
        }
        postInvalidateOnAnimation()
    }

    private fun openLink(url: String) {
        try {
            // Схема — строчными: «HTTPS://» проходит LinkTaps, а фильтры браузеров сравнивают её с учётом регистра.
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url).normalizeScheme()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Нет приложения для ссылки — скопируйте её долгим нажатием", Toast.LENGTH_LONG).show()
        }
    }

    private fun cell(e: MotionEvent): Pair<Int, Int> = cellAt(e.x, e.y)

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val t = tab
        val cols = t?.session?.read { it.cols } ?: 1
        val rows = t?.session?.read { it.rows } ?: 1
        return (x / cellW).toInt().coerceIn(0, cols - 1) to (y / cellH).toInt().coerceIn(0, rows - 1)
    }

    private fun scrollLines(lines: Int, e: MotionEvent) {
        val t = tab ?: return
        val (alt, mouse, history) = t.session.read { Triple(it.altScreen, it.mouseMode != MouseMode.NONE, it.scrollbackSize) }
        when {
            mouse -> {
                val (c, r) = cell(e)
                val b = if (lines < 0) MouseButton.WHEEL_UP else MouseButton.WHEEL_DOWN
                repeat(abs(lines)) { t.session.mouse(b, c, r, pressed = true, motion = false) }
            }
            // Второй экран (редакторы, less): истории нет — прокрутка стрелками, как в Termux.
            alt -> repeat(abs(lines)) { t.session.key(if (lines < 0) Key.UP else Key.DOWN) }
            else -> {
                scrollBack = (scrollBack - lines).coerceIn(0, history)
                invalidate()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        // Палец на ручке выделения — тянуть её (терминал:A-2); выделение — по порядку, чтобы ручки не путались.
        val sel = selection
        if (!selecting && sel != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val h = handleAt(event.x, event.y)
            if (h != 0) {
                selection = ordered(sel)
                dragging = h
                selecting = true
                menu?.finish()
                return true
            }
        }
        if (selecting && event.actionMasked == MotionEvent.ACTION_MOVE) {
            moveDragged(event.x, event.y)
            // У края — прокрутка истории, пока палец там: блок длиннее экрана выделяется целиком.
            edge = when {
                event.y < cellH -> -1
                event.y > height - cellH -> 1
                else -> 0
            }
            edgeX = event.x
            edgeY = event.y
            removeCallbacks(edgeScroll)
            if (edge != 0) postDelayed(edgeScroll, EDGE_MS)
            return true
        }
        if (selecting && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            selecting = false
            dragging = 0
            edge = 0
            removeCallbacks(edgeScroll)
            // Отпускание не копирует: выделение можно поправить ручками, копирование — из меню (терминал:A-2).
            showMenu()
            return true
        }
        if (scale.isInProgress) return true
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /** Тянуть ручку [dragging] к ячейке под (x, y). */
    private fun moveDragged(x: Float, y: Float) {
        val t = tab ?: return
        val (c, r) = cellAt(x, y)
        val line = t.session.read { it.scrollbackSize - scrollBack + r }
        selection?.let {
            if (dragging == HANDLE_START) {
                it[0] = line
                it[1] = c
            } else {
                it[2] = line
                it[3] = c
            }
        }
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Центры ручек на экране: начало — под левым краем первой ячейки, конец — под правым краем последней. */
    private fun handles(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val s = ordered(selection ?: return null)
        val first = tab?.session?.read { it.scrollbackSize - scrollBack } ?: return null
        val r = dp(HANDLE_DP)
        return (s[1] * cellW to (s[0] - first + 1) * cellH + r) to ((s[3] + 1) * cellW to (s[2] - first + 1) * cellH + r)
    }

    private fun handleAt(x: Float, y: Float): Int {
        val (start, end) = handles() ?: return 0
        val reach = dp(HANDLE_REACH_DP)
        fun near(p: Pair<Float, Float>) = abs(p.first - x) < reach && abs(p.second - y) < reach
        return when {
            near(end) -> HANDLE_END
            near(start) -> HANDLE_START
            else -> 0
        }
    }

    private fun clearSelection() {
        selection = null
        selecting = false
        dragging = 0
        menu?.finish()
        invalidate()
    }

    private fun selectedText(): String {
        val t = tab ?: return ""
        val s = ordered(selection ?: return "")
        return t.session.read { it.text(s[0], s[1], s[2], s[3]) }
    }

    /**
     * Меню выделения (терминал:A-2, A-19) — плавающая панель Android над
     * выделением: Копировать, Вставить, Выделить всё, Открыть и Копировать
     * ссылку (если выделен адрес `http(s)`), Поделиться.
     */
    private fun showMenu() {
        if (selection == null) return
        menu?.finish()
        menu = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, m: Menu): Boolean {
                m.add(Menu.NONE, M_COPY, 0, "Копировать")
                m.add(Menu.NONE, M_PASTE, 1, "Вставить")
                m.add(Menu.NONE, M_ALL, 2, "Выделить всё")
                if (TerminalSelection.link(selectedText()) != null) {
                    m.add(Menu.NONE, M_OPEN, 3, "Открыть ссылку")
                    m.add(Menu.NONE, M_COPY_LINK, 4, "Копировать ссылку")
                }
                m.add(Menu.NONE, M_SHARE, 5, "Поделиться")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, m: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    M_COPY -> {
                        copySelection()
                        clearSelection()
                    }
                    M_PASTE -> {
                        clearSelection()
                        pasteClipboard()
                    }
                    M_ALL -> {
                        val t = tab ?: return true
                        selection = t.session.read { intArrayOf(0, 0, it.lineCount - 1, it.cols - 1) }
                        invalidate()
                        mode.invalidateContentRect()
                    }
                    M_OPEN -> {
                        // «Открыть» из меню — явный выбор; открывается только https (DPmetaApp:D-75).
                        TerminalSelection.link(selectedText())?.let { url ->
                            if (com.dpsmpx.dpai.ui.LinkTaps.openable(url)) openLink(url) else Toast.makeText(context, "Открывается только https", Toast.LENGTH_LONG).show()
                        }
                        clearSelection()
                    }
                    M_COPY_LINK -> {
                        TerminalSelection.link(selectedText())?.let { Clipboard.copy(context, "ссылка", it) }
                        clearSelection()
                    }
                    M_SHARE -> {
                        share(selectedText())
                        clearSelection()
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (menu === mode) menu = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val (start, end) = handles() ?: return
                val top = (minOf(start.second, end.second) - cellH - dp(HANDLE_DP)).coerceIn(0f, height.toFloat())
                val bottom = (maxOf(start.second, end.second)).coerceIn(top, height.toFloat())
                outRect.set(0, top.toInt(), width, bottom.toInt())
            }
        }, ActionMode.TYPE_FLOATING)
    }

    /** «Вставить» меню — как кнопка ряда: с вопросом у многострочной вставки (терминал:A-7). */
    private fun pasteClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isEmpty()) return
        onPaste?.invoke(text) ?: tab?.session?.paste(text)
    }

    /** «Поделиться» выделенным — системным окном; человек сам выбирает, кому. */
    private fun share(text: String) {
        if (text.isEmpty()) return
        if (text.length > MAX_SHARE) {
            Toast.makeText(context, "Выделено ${text.length} знаков — для «Поделиться» слишком много; скопируйте", Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Ссылка по касанию (DPmetaApp:D-75): первое — копия и адрес целиком на
     * экране, второе той же — открыть, только `https` (`LinkTaps`).
     */
    private fun tapLink(link: String) {
        when (val act = linkTaps.tap(link, Clock.SYSTEM.monotonicNanos() / 1_000_000)) {
            is com.dpsmpx.dpai.ui.LinkTaps.Act.Copy -> {
                // Адрес — не секрет (DPmetaApp:D-75): без «чувствительного» флага, превью буфера его покажет.
                Clipboard.copy(context, "ссылка", link)
                Toast.makeText(context, act.text, Toast.LENGTH_LONG).show()
            }
            is com.dpsmpx.dpai.ui.LinkTaps.Act.Open -> openLink(act.url)
            is com.dpsmpx.dpai.ui.LinkTaps.Act.Refuse -> Toast.makeText(context, act.text, Toast.LENGTH_LONG).show()
        }
    }

    /** Начало и конец выделения по порядку. */
    private fun ordered(s: IntArray): IntArray =
        if (s[0] < s[2] || (s[0] == s[2] && s[1] <= s[3])) s.copyOf() else intArrayOf(s[2], s[3], s[0], s[1])

    private fun copySelection() {
        val t = tab ?: return
        val s = ordered(selection ?: return)
        val text = t.session.read { it.text(s[0], s[1], s[2], s[3]) }
        if (text.isEmpty()) return
        // Код входа, вставленный пароль — с пометкой «чувствительное» (D-86, терминал:A-11).
        Clipboard.copy(context, "терминал", text, sensitive = true)
        if (!Clipboard.systemConfirms) Toast.makeText(context, "Скопировано: ${text.length} симв.", Toast.LENGTH_SHORT).show()
    }

    fun showKeyboard() {
        requestFocus()
        windowInsetsController?.show(WindowInsets.Type.ime())
    }

    fun toggleKeyboard() {
        if (rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) windowInsetsController?.hide(WindowInsets.Type.ime()) else showKeyboard()
    }

    // ---- ввод ----

    private fun toBottom() {
        if (scrollBack != 0) {
            scrollBack = 0
            invalidate()
        }
    }

    /** Вставка с вопросом у экрана терминала (терминал:A-7); `null` — сразу. */
    var onPaste: ((String) -> Unit)? = null

    /** Текст с экранной клавиатуры или ряда клавиш. */
    fun input(text: String) {
        val t = tab ?: return
        val mods = stickyMods()
        toBottom()
        // Несколько строк или управляющие знаки разом — вставка из буфера клавиатуры, а не набор:
        // через фильтр и скобки 2004, как «Вставить» (ревью 17 октября 2026).
        if (text.length > 1 && text.any { it == '\n' || it == '\r' || it.isISOControl() }) {
            // Тот же вопрос, что у «Вставить» (терминал:A-7): без скобок 2004 строки выполнятся сразу.
            onPaste?.invoke(text) ?: t.session.paste(text)
            return
        }
        text.codePoints().forEach { cp ->
            when {
                cp == '\n'.code -> t.session.key(Key.ENTER, mods)
                mods == 0 -> t.session.type(String(Character.toChars(cp)))
                else -> t.session.type(String(Character.toChars(cp)), mods)
            }
        }
    }

    fun key(key: Key, extraMods: Int = 0) {
        val t = tab ?: return
        toBottom()
        t.session.key(key, extraMods or stickyMods())
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Без подсказок и автоисправлений: клавиатура шлёт символы, а не слова.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // Без обучения клавиатуры: тип «видимый пароль» его не запрещает, а здесь набирают коды и пароли (терминал:A-11).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return object : BaseInputConnection(this, true) {
            private fun flush() {
                val e = editable ?: return
                if (e.isEmpty()) return
                val s = e.toString()
                e.clear()
                input(s)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val e = editable
                if (e != null && e.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
                repeat(beforeLength.coerceAtLeast(1)) { key(Key.BACKSPACE) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val t = tab ?: return super.onKeyDown(keyCode, event)
        var mods = 0
        if (event.isShiftPressed) mods = mods or Mod.SHIFT
        if (event.isAltPressed) mods = mods or Mod.ALT
        if (event.isCtrlPressed) mods = mods or Mod.CTRL
        val k = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> Key.HOME
            KeyEvent.KEYCODE_MOVE_END -> Key.END
            KeyEvent.KEYCODE_PAGE_UP -> Key.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Key.PAGE_DOWN
            KeyEvent.KEYCODE_INSERT -> Key.INSERT
            KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
            KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
            KeyEvent.KEYCODE_TAB -> Key.TAB
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
            KeyEvent.KEYCODE_ESCAPE -> Key.ESCAPE
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Key.valueOf("F${keyCode - KeyEvent.KEYCODE_F1 + 1}")
            else -> null
        }
        if (k != null) {
            key(k, mods)
            return true
        }
        // Символ без Ctrl и Alt — их смысл добавляет KeyEncoder; Shift уже в символе.
        val plain = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        // AltGr (правый Alt) раскладки даёт свой знак (@, [, ~ в немецкой) — это символ, а не Alt+клавиша.
        if (event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0 && !event.isCtrlPressed) {
            val third = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
            if (third > 0 && third != plain) {
                toBottom()
                t.session.type(String(Character.toChars(third)), stickyMods())
                return true
            }
        }
        val cp = plain
        if (cp > 0) {
            toBottom()
            val all = (mods and Mod.SHIFT.inv()) or stickyMods()
            t.session.type(String(Character.toChars(cp)), all)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        tab?.session?.focus(gainFocus)
        invalidate()
    }

    // ---- рисование ----

    override fun onDraw(canvas: Canvas) {
        val t = tab
        if (t == null) {
            canvas.drawColor(0xff000000.toInt())
            return
        }
        t.session.read { emu -> draw(canvas, emu) }
    }

    private fun draw(canvas: Canvas, emu: TerminalEmulator) {
        val defFg = 0xff000000.toInt() or (if (emu.reverseVideo) emu.backgroundRgb else emu.foregroundRgb)
        val defBg = 0xff000000.toInt() or (if (emu.reverseVideo) emu.foregroundRgb else emu.backgroundRgb)
        canvas.drawColor(defBg)
        if (anchored) {
            val dropped = (emu.historyDropped - seenDropped).toInt()
            if (scrollBack > 0) scrollBack += (emu.historyAdded - seenAdded).toInt()
            selection?.let { s ->
                s[0] -= dropped
                s[2] -= dropped
                if (s[0] < 0 || s[2] < 0) {
                    selection = null
                    post { menu?.finish() }
                }
            }
        }
        anchored = true
        seenAdded = emu.historyAdded
        seenDropped = emu.historyDropped
        scrollBack = scrollBack.coerceIn(0, emu.scrollbackSize)
        val first = emu.scrollbackSize - scrollBack
        val sel = selection?.let(::ordered)
        for (y in 0 until emu.rows) {
            val line = first + y
            if (line >= emu.lineCount) break
            val row = emu.line(line)
            for (x in 0 until minOf(emu.cols, row.cols)) {
                val cp = row.codePoint(x)
                if (cp == TerminalRow.WIDE_TAIL) continue
                val selected = sel != null && inSelection(sel, line, x)
                drawCell(canvas, row, x, y, row.style(x), selected, defFg, defBg)
            }
        }
        if (scrollBack == 0 && emu.cursorVisible) drawCursor(canvas, emu, defFg, defBg)
        if (sel != null) drawHandles(canvas, sel, first)
    }

    /** Ручки выделения (терминал:A-2): кружки под началом и концом, их тянут пальцем. */
    private fun drawHandles(canvas: Canvas, s: IntArray, first: Int) {
        fill.color = HANDLE_COLOR
        val r = dp(HANDLE_DP)
        for ((x, line) in listOf(s[1] * cellW to s[0], (s[3] + 1) * cellW to s[2])) {
            val y = (line - first + 1) * cellH
            if (y < 0 || y > height) continue
            canvas.drawRect(x - 1f, y - cellH, x + 1f, y, fill)
            canvas.drawCircle(x, y + r, r, fill)
        }
    }

    private fun inSelection(s: IntArray, line: Int, col: Int): Boolean {
        if (line < s[0] || line > s[2]) return false
        if (line == s[0] && col < s[1]) return false
        if (line == s[2] && col > s[3]) return false
        return true
    }

    private fun color(c: Int, fg: Boolean, bold: Boolean, defFg: Int, defBg: Int): Int = when {
        c == TermColor.DEFAULT -> if (fg) defFg else defBg
        TermColor.isRgb(c) -> 0xff000000.toInt() or TermColor.rgbValue(c)
        else -> {
            var i = TermColor.index(c)
            // Жирный — ярче для восьми основных цветов, как в xterm.
            if (fg && bold && i < 8) i += 8
            0xff000000.toInt() or TermColor.PALETTE[i]
        }
    }

    private fun drawCell(canvas: Canvas, row: TerminalRow, x: Int, y: Int, style: Style, selected: Boolean, defFg: Int, defBg: Int) {
        val bold = style.has(Attr.BOLD)
        var fg = color(style.fg, true, bold, defFg, defBg)
        var bg = color(style.bg, false, false, defFg, defBg)
        if (style.has(Attr.INVERSE) != selected) {
            val k = fg
            fg = bg
            bg = k
        }
        if (style.has(Attr.DIM)) fg = (fg and 0x00ffffff) or 0x99000000.toInt()
        val wide = x + 1 < row.cols && row.codePoint(x + 1) == TerminalRow.WIDE_TAIL
        val left = x * cellW
        val top = y * cellH
        val right = left + cellW * (if (wide) 2 else 1)
        if (bg != defBg) {
            fill.color = bg
            canvas.drawRect(left, top, right, top + cellH, fill)
        }
        val cp = row.codePoint(x)
        if (cp == TerminalRow.EMPTY || style.has(Attr.INVISIBLE)) return
        paint.color = fg
        paint.isFakeBoldText = bold
        paint.textSkewX = if (style.has(Attr.ITALIC)) -0.25f else 0f
        val text = row.text(x)
        val base = top + ascent
        // Знак шрифта бывает шире своих ячеек (широкие, эмодзи): сжать, чтобы не налезал на соседа.
        val need = right - left
        val w = paint.measureText(text)
        if (w > need * 1.05f) {
            canvas.save()
            canvas.scale(need / w, 1f, left, base)
            canvas.drawText(text, left, base, paint)
            canvas.restore()
        } else {
            canvas.drawText(text, left, base, paint)
        }
        if (style.has(Attr.UNDERLINE) || style.has(Attr.DOUBLE_UNDERLINE)) {
            fill.color = fg
            canvas.drawRect(left, top + cellH - 2f, right, top + cellH - 1f, fill)
            if (style.has(Attr.DOUBLE_UNDERLINE)) canvas.drawRect(left, top + cellH - 4f, right, top + cellH - 3f, fill)
        }
        if (style.has(Attr.STRIKE)) {
            fill.color = fg
            canvas.drawRect(left, top + cellH / 2, right, top + cellH / 2 + 1f, fill)
        }
        if (style.has(Attr.OVERLINE)) {
            fill.color = fg
            canvas.drawRect(left, top, right, top + 1f, fill)
        }
    }

    private fun drawCursor(canvas: Canvas, emu: TerminalEmulator, defFg: Int, defBg: Int) {
        val x = emu.cursorX
        val y = emu.cursorY
        val left = x * cellW
        val top = y * cellH
        val row = emu.screenRow(y)
        val wide = x + 1 < row.cols && row.codePoint(x + 1) == TerminalRow.WIDE_TAIL
        val right = left + cellW * (if (wide) 2 else 1)
        fill.color = defFg
        if (!hasFocus()) {
            fill.style = Paint.Style.STROKE
            canvas.drawRect(left + 0.5f, top + 0.5f, right - 0.5f, top + cellH - 0.5f, fill)
            fill.style = Paint.Style.FILL
            return
        }
        when (emu.cursorShape) {
            3, 4 -> canvas.drawRect(left, top + cellH - 3f, right, top + cellH, fill)
            5, 6 -> canvas.drawRect(left, top, left + 3f, top + cellH, fill)
            else -> {
                canvas.drawRect(left, top, right, top + cellH, fill)
                val cp = row.codePoint(x)
                if (cp != TerminalRow.EMPTY && cp != TerminalRow.WIDE_TAIL) {
                    paint.color = defBg
                    paint.isFakeBoldText = false
                    paint.textSkewX = 0f
                    canvas.drawText(row.text(x), left, top + ascent, paint)
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_SP = 13f
        private const val MIN_SP = 6f
        private const val MAX_SP = 40f
        private const val SYNC_REDRAW_MS = 150L

        private const val HANDLE_START = 1
        private const val HANDLE_END = 2

        /** Радиус ручки и сколько вокруг неё ловит палец, dp. */
        private const val HANDLE_DP = 9f
        private const val HANDLE_REACH_DP = 28f
        private const val HANDLE_COLOR = 0xff4fc3f7.toInt()

        /** Шаг прокрутки истории, пока палец с ручкой у края. */
        private const val EDGE_MS = 60L

        /** Больше — «Поделиться» не проходит через binder: только копировать. */
        private const val MAX_SHARE = 100_000

        private const val M_COPY = 1
        private const val M_PASTE = 2
        private const val M_ALL = 3
        private const val M_OPEN = 4
        private const val M_COPY_LINK = 5
        private const val M_SHARE = 6

        /** Размер шрифта — общий для вкладок, пока жив процесс. */
        private var fontPx = 0f
    }
}

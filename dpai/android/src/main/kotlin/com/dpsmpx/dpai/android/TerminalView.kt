package com.dpsmpx.dpai.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import com.dpsmpx.dpai.terminal.BoxDrawing
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
import kotlin.math.roundToInt

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

    /** Левый край сетки (терминал:A-24): поле не меньше 4 dp, остаток ширины — поровну слева и справа. */
    private var originX = 0f

    /** Рамки и блоки — по сетке, а не шрифтом (терминал:A-18): толщина тонкой линии от ячейки. */
    private var boxLight = 1
    private var boxCanvas: Canvas? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()
    private val boxSink = object : BoxDrawing.Sink {
        override fun rect(l: Float, t: Float, r: Float, b: Float, alpha: Float) {
            val c = boxCanvas ?: return
            if (alpha >= 1f) {
                c.drawRect(l, t, r, b, fill)
                return
            }
            val keep = fill.alpha
            fill.alpha = (keep * alpha).roundToInt()
            c.drawRect(l, t, r, b, fill)
            fill.alpha = keep
        }

        override fun arc(l: Float, t: Float, r: Float, b: Float, startDeg: Float, stroke: Float) {
            val c = boxCanvas ?: return
            this@TerminalView.stroke.color = fill.color
            this@TerminalView.stroke.strokeWidth = stroke
            oval.set(l, t, r, b)
            c.drawArc(oval, startDeg, 90f, false, this@TerminalView.stroke)
        }

        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, stroke: Float) {
            val c = boxCanvas ?: return
            this@TerminalView.stroke.color = fill.color
            this@TerminalView.stroke.strokeWidth = stroke
            c.drawLine(x0, y0, x1, y1, this@TerminalView.stroke)
        }
    }

    /** Нарисовать знак рамки или блока цветом [color]; `false` — знак рисует шрифт. */
    private fun box(canvas: Canvas, row: TerminalRow, x: Int, left: Float, top: Float, w: Float, color: Int): Boolean {
        val cp = row.codePoint(x)
        if (!BoxDrawing.covers(cp) || row.combiningAt(x) != null) return false
        fill.color = color
        boxCanvas = canvas
        BoxDrawing.draw(cp, left, top, w, cellH, boxLight, boxSink)
        boxCanvas = null
        return true
    }

    /**
     * Мигание курсора (терминал:A-18) — когда его просит программа
     * (DECSCUSR, режим 12), экран в фокусе и анимации не выключены в
     * системе; после ввода курсор сразу виден.
     */
    private var blinkOn = true
    private var blinkPosted = false
    private val blinker = Runnable {
        blinkPosted = false
        blinkOn = !blinkOn
        invalidate()
    }

    /** «Жирный ярче» (терминал:A-18): жирный текст восьми основных цветов — яркими, как в xterm; настройка. */
    var boldBright: Boolean = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(BOLD_BRIGHT, true) }.getOrDefault(true)
        set(value) {
            field = value
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(BOLD_BRIGHT, value).apply() }
            invalidate()
        }
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
            // Долгое нажатие выделяет слово, как принято в Android (терминал:A-2); палец дальше тянет конец.
            if (!selectWord(e)) return
            selecting = true
            dragging = HANDLE_END
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    })

    /** Масштаб рисования во время щипка (терминал:A-9): сетка не перестраивается на каждом шаге жеста. */
    private var pinch = 1f

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinch = (pinch * d.scaleFactor).coerceIn(sp(MIN_SP) / fontPx, sp(MAX_SP) / fontPx)
            invalidate()
            return true
        }

        // Сетка и SIGWINCH программе — один раз, в конце жеста (терминал:A-9).
        override fun onScaleEnd(d: ScaleGestureDetector) {
            setFont(fontPx * pinch)
            pinch = 1f
        }
    })

    /** Действия экрана терминала с клавиатуры (терминал:A-14): вкладки и поиск — у активности. */
    enum class Shortcut { NEW_TAB, NEXT_TAB, PREV_TAB, SEARCH }

    var onShortcut: ((Shortcut) -> Unit)? = null

    /** Шрифт — в пределах и запомненным (терминал:A-9): размер переживает перезапуск приложения. */
    fun setFont(px: Float) {
        fontPx = px.coerceIn(sp(MIN_SP), sp(MAX_SP))
        regrid()
        invalidate()
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(FONT_PX, fontPx).apply() }
    }

    /** Шрифт на шаг крупнее или мельче (Ctrl+= и Ctrl+-, ⋮ экрана). */
    fun fontStep(up: Boolean) = setFont(fontPx * if (up) FONT_STEP else 1 / FONT_STEP)

    /** Шрифт по ширине: [columns] столбцов во всю ширину окна (⋮ «80 столбцов»). */
    fun fitColumns(columns: Int = 80) {
        if (width > 0) setFont(fontPx * width / (columns * cellW))
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (fontPx <= 0f) fontPx = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(FONT_PX, 0f) }.getOrDefault(0f).takeIf { it > 0f } ?: sp(DEFAULT_SP)
        metrics()
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun metrics() {
        paint.textSize = fontPx
        cellW = paint.measureText("M").coerceAtLeast(1f)
        val fm = paint.fontMetrics
        cellH = ceil(fm.descent - fm.ascent).coerceAtLeast(1f)
        ascent = -fm.ascent
        boxLight = BoxDrawing.lightWidth(cellW)
    }

    /** Размер сетки по размеру окна и шрифту; программа узнаёт о нём по SIGWINCH. */
    private fun regrid() {
        metrics()
        val t = tab ?: return
        if (width <= 0 || height <= 0) return
        val cols = ((width - 2 * dp(SIDE_DP)) / cellW).toInt().coerceIn(1, TerminalEmulator.MAX_COLS)
        val rows = (height / cellH).toInt().coerceIn(1, TerminalEmulator.MAX_ROWS)
        originX = ((width - cols * cellW) / 2f).coerceAtLeast(0f)
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

    /** Выделить слово под [e] (долгое нажатие, правая кнопка мыши); вкладки нет — `false`. */
    private fun selectWord(e: MotionEvent): Boolean {
        val t = tab ?: return false
        val (c, r) = cell(e)
        val (line, word) = t.session.read { val l = it.scrollbackSize - scrollBack + r; l to TerminalSelection.word(it.line(l), c) }
        menu?.finish()
        selection = intArrayOf(line, word.first, line, word.last)
        return true
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val t = tab
        val cols = t?.session?.read { it.cols } ?: 1
        val rows = t?.session?.read { it.rows } ?: 1
        return ((x - originX) / cellW).toInt().coerceIn(0, cols - 1) to (y / cellH).toInt().coerceIn(0, rows - 1)
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

    /**
     * «Мышь программе» (терминал:A-22): пока включено и программа слушает
     * мышь, палец — это мышь: нажатие, протяжка и отпускание уходят
     * программе (выделение в `tmux` и `vim`, границы панелей). Выключено —
     * касание и прокрутка, как всегда; выделение — долгим нажатием.
     */
    var mouseToProgram = false
        set(value) {
            field = value
            mouseCell = null
        }

    /** Ячейка последнего события мыши программе: движение — только при смене ячейки. */
    private var mouseCell: Pair<Int, Int>? = null

    private fun programMouse(event: MotionEvent): Boolean {
        val t = tab ?: return false
        if (!mouseToProgram || selecting || event.pointerCount > 1 || scale.isInProgress) {
            if (mouseCell != null && event.pointerCount > 1) {
                // Второй палец (щипок) — отпустить кнопку, а не оставить её зажатой в программе.
                mouseCell?.let { (c, r) -> t.session.mouse(MouseButton.LEFT, c, r, pressed = false, motion = false) }
                mouseCell = null
            }
            return false
        }
        if (t.session.read { it.mouseMode } == MouseMode.NONE) return false
        val (c, r) = cellAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toBottom()
                mouseCell = c to r
                t.session.mouse(MouseButton.LEFT, c, r, pressed = true, motion = false)
            }
            MotionEvent.ACTION_MOVE -> if (mouseCell != null && mouseCell != (c to r)) {
                mouseCell = c to r
                t.session.mouse(MouseButton.LEFT, c, r, pressed = true, motion = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (mouseCell != null) {
                mouseCell = null
                t.session.mouse(MouseButton.LEFT, c, r, pressed = false, motion = false)
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (programMouse(event)) return true
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
        return (originX + s[1] * cellW to (s[0] - first + 1) * cellH + r) to (originX + (s[3] + 1) * cellW to (s[2] - first + 1) * cellH + r)
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

    /** Новых строк, пока экран не внизу (терминал:A-4). */
    private var newLines = 0
    private var awayShown: Int? = null

    /** Экран ушёл от конца: число новых строк с тех пор; вернулся — `null`. Для «↓ к концу». */
    var onAway: ((Int?) -> Unit)? = null

    /** К концу — «↓ к концу» экрана. */
    fun toEnd() = toBottom()

    /** Верх видимой части — сквозной номер строки: поиск идёт от неё. */
    fun firstVisibleLine(): Int = tab?.session?.read { it.scrollbackSize - scrollBack } ?: 0

    /** Совпадение поиска — на экране, выделением (терминал:A-4). */
    fun reveal(m: com.dpsmpx.dpai.terminal.TerminalEmulator.Match) {
        val t = tab ?: return
        val (history, rows) = t.session.read { it.scrollbackSize to it.rows }
        val top = (m.line - rows / 2).coerceIn(0, history)
        scrollBack = history - top
        menu?.finish()
        selection = intArrayOf(m.line, m.cols.first, m.line, m.cols.last)
        invalidate()
    }

    private fun toBottom() {
        // Ввод — курсор виден сразу, мигание начинается заново.
        if (!blinkOn || blinkPosted) {
            blinkOn = true
            removeCallbacks(blinker)
            blinkPosted = false
            invalidate()
        }
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
        // Сочетания терминала (терминал:A-14) — до программы: Ctrl+Shift+буква программе не нужна.
        if (event.isCtrlPressed && event.isShiftPressed && !event.isAltPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_C -> { copySelection(); return true }
                KeyEvent.KEYCODE_V -> { pasteClipboard(); return true }
                KeyEvent.KEYCODE_T -> { onShortcut?.invoke(Shortcut.NEW_TAB); return true }
                KeyEvent.KEYCODE_F -> { onShortcut?.invoke(Shortcut.SEARCH); return true }
                KeyEvent.KEYCODE_TAB -> { onShortcut?.invoke(Shortcut.PREV_TAB); return true }
            }
        }
        if (event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_TAB -> { onShortcut?.invoke(Shortcut.NEXT_TAB); return true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> { fontStep(true); return true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { fontStep(false); return true }
            }
        }
        // Shift+Enter — новая строка в запросе Claude Code (терминал:A-6): ESC CR, как Alt+Enter; Enter отправляет.
        if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && shiftEnterNewline(context)) {
            newLine()
            return true
        }
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

    /** Новая строка без отправки (терминал:A-6): ESC CR — Claude Code и readline понимают его как перенос. */
    fun newLine() = key(Key.ENTER, Mod.ALT)

    /**
     * Мышь и тачпад (терминал:A-14): колесо — та же прокрутка, что пальцем;
     * правая кнопка — слово под указателем и меню выделения.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) {
                        scrollLines((-v * WHEEL_LINES).toInt().let { if (it == 0) (if (v > 0) -1 else 1) else it }, event)
                        return true
                    }
                }
                MotionEvent.ACTION_BUTTON_PRESS -> if (event.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    selectWord(event)
                    selecting = false
                    showMenu()
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        // Мигание не держит экран: снятый с окна — без отложенных перерисовок.
        removeCallbacks(blinker)
        blinkPosted = false
        blinkOn = true
        super.onDetachedFromWindow()
    }

    // Фокус для программы (1004) — фокус окна и экрана вместе (терминал:A-24): уход приложения в фон программа видит.
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        tab?.session?.focus(gainFocus && hasWindowFocus())
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        tab?.session?.focus(hasWindowFocus && hasFocus())
        invalidate()
    }

    // ---- рисование ----

    /** Когда текст экрана для TalkBack обновлялся последний раз (терминал:A-12): не чаще раза в [SPEAK_EVERY_MS]. */
    private var spokenAt = 0L

    /**
     * Текст видимых строк — описанием экрана, пока включена доступность
     * (терминал:A-12): TalkBack читает экран терминала, как у Termux. Без
     * доступности — ничего не считается.
     */
    private fun speakable() {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return
        if (!am.isEnabled) return
        val now = Clock.SYSTEM.monotonicNanos() / 1_000_000
        if (now - spokenAt < SPEAK_EVERY_MS) return
        spokenAt = now
        val t = tab ?: return
        val text = t.session.read { emu ->
            val first = (emu.scrollbackSize - scrollBack).coerceAtLeast(0)
            emu.text(first, 0, (first + emu.rows - 1).coerceAtMost(emu.lineCount - 1), emu.cols - 1)
        }.lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }.joinToString("\n")
        contentDescription = "Терминал «${t.label}»: " + text.takeLast(SPEAK_MAX)
    }

    override fun onDraw(canvas: Canvas) {
        val t = tab
        if (t == null) {
            canvas.drawColor(0xff000000.toInt())
            return
        }
        // Под замком сессии — только снимок; рисование — без замка (терминал:A-10).
        t.session.read { emu -> capture(emu) }
        // Щипок — масштаб рисования до конца жеста (терминал:A-9).
        canvas.drawColor(frame.defBg)
        canvas.save()
        if (pinch != 1f) canvas.scale(pinch, pinch)
        canvas.translate(originX, 0f)
        paint(canvas)
        canvas.restore()
        place(canvas)
        speakable()
    }

    /**
     * Снимок кадра (терминал:A-10): под замком сессии — копия видимых строк
     * и курсора, а рисование идёт без замка, и поток вывода не ждёт конца
     * кадра. Строки снимка переиспользуются от кадра к кадру. Сверху и снизу —
     * до [LINK_SPAN] строк продолжения переноса: адрес, начатый за краем
     * экрана, подчёркнут и на экране.
     */
    private class Frame {
        var rows: Array<TerminalRow> = emptyArray()
        var count = 0
        var top = 0
        var visible = 0
        var first = 0
        var cols = 0
        var screenRows = 0
        var lineCount = 0
        var defFg = 0
        var defBg = 0
        var cursorX = 0
        var cursorY = 0
        var cursorOn = false
        var cursorShape = 0
        var cursorBlink = false

        fun row(i: Int): TerminalRow {
            if (i >= rows.size) {
                val old = rows
                rows = Array(maxOf(i + 1, old.size * 2)) { if (it < old.size) old[it] else TerminalRow(0) }
            }
            return rows[i]
        }
    }

    private val frame = Frame()
    private val frameRow: (Int) -> TerminalRow = { frame.rows[it] }

    private fun capture(emu: TerminalEmulator) {
        if (anchored) {
            val dropped = (emu.historyDropped - seenDropped).toInt()
            val added = (emu.historyAdded - seenAdded).toInt()
            if (scrollBack > 0) {
                scrollBack += added
                newLines += added
            }
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
        if (scrollBack == 0) newLines = 0
        // «↓ к концу» с числом новых строк (терминал:A-4) — экрану, когда меняется; не во время рисования.
        val away = if (scrollBack > 0) newLines else null
        if (away != awayShown) {
            awayShown = away
            post { onAway?.invoke(away) }
        }
        val f = frame
        f.defFg = 0xff000000.toInt() or (if (emu.reverseVideo) emu.backgroundRgb else emu.foregroundRgb)
        f.defBg = 0xff000000.toInt() or (if (emu.reverseVideo) emu.foregroundRgb else emu.backgroundRgb)
        f.first = emu.scrollbackSize - scrollBack
        f.cols = emu.cols
        f.screenRows = emu.rows
        f.lineCount = emu.lineCount
        val end = minOf(f.first + emu.rows, emu.lineCount)
        var from = f.first
        while (from > 0 && f.first - from < LINK_SPAN && emu.line(from - 1).wrapped) from--
        var to = end
        while (to < emu.lineCount && to - end < LINK_SPAN && emu.line(to - 1).wrapped) to++
        f.top = f.first - from
        f.visible = end - f.first
        f.count = to - from
        for (i in 0 until f.count) emu.line(from + i).copyInto(f.row(i))
        f.cursorX = emu.cursorX
        f.cursorY = emu.cursorY
        f.cursorOn = emu.cursorVisible
        f.cursorShape = emu.cursorShape
        f.cursorBlink = emu.cursorBlink
    }

    private fun paint(canvas: Canvas) {
        val f = frame
        val sel = selection?.let(::ordered)
        for (y in 0 until f.visible) {
            // Адреса и ссылки OSC 8 — подчёркнуты (терминал:A-19): видно, что касание их откроет.
            val links = TerminalEmulator.linkCells(f.count, frameRow, f.top + y)
            drawRow(canvas, f.rows[f.top + y], y, f.first + y, links, sel)
        }
        val cursor = scrollBack == 0 && f.cursorOn && f.cursorY < f.visible
        val blink = cursor && f.cursorBlink && hasFocus() && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (!blink) {
            blinkOn = true
            if (blinkPosted) {
                removeCallbacks(blinker)
                blinkPosted = false
            }
        } else if (!blinkPosted) {
            blinkPosted = true
            postDelayed(blinker, BLINK_MS)
        }
        if (cursor && blinkOn) drawCursor(canvas)
        if (sel != null) drawHandles(canvas, sel, f.first)
    }

    /** Указатель места в истории (терминал:A-4): тонкая полоса у правого края окна, пока экран не внизу. */
    private fun place(canvas: Canvas) {
        val f = frame
        if (scrollBack > 0 && f.lineCount > 0) {
            val total = f.lineCount.toFloat()
            val h = (height * f.screenRows / total).coerceAtLeast(dp(24f))
            val top = (height - h) * f.first / (total - f.screenRows).coerceAtLeast(1f)
            fill.color = HANDLE_COLOR
            canvas.drawRect(width - dp(3f), top, width.toFloat(), top + h, fill)
        }
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
            // Жирный — ярче для восьми основных цветов, как в xterm; выключается настройкой (терминал:A-18).
            if (fg && bold && i < 8 && boldBright) i += 8
            0xff000000.toInt() or TermColor.PALETTE[i]
        }
    }

    /** Стиль ячейки на экране: адрес или ссылка OSC 8 — с подчёркиванием (терминал:A-19). */
    private fun shown(row: TerminalRow, x: Int, links: BooleanArray): Style {
        val st = row.style(x)
        return if (x < links.size && links[x] && !st.has(Attr.UNDERLINE)) st.with(Attr.UNDERLINE, true) else st
    }

    /** Цвета ячейки — в [cellFg] и [cellBg], без объектов на каждую ячейку кадра. */
    private var cellFg = 0
    private var cellBg = 0

    private fun colors(style: Style, selected: Boolean) {
        val f = frame
        var fg = color(style.fg, true, style.has(Attr.BOLD), f.defFg, f.defBg)
        var bg = color(style.bg, false, false, f.defFg, f.defBg)
        if (style.has(Attr.INVERSE) != selected) {
            val k = fg
            fg = bg
            bg = k
        }
        if (style.has(Attr.DIM)) fg = (fg and 0x00ffffff) or 0x99000000.toInt()
        cellFg = fg
        cellBg = bg
    }

    private fun wideAt(row: TerminalRow, x: Int): Boolean = x + 1 < row.cols && row.codePoint(x + 1) == TerminalRow.WIDE_TAIL

    /** Буфер куска ASCII одного стиля: одна строка — один `drawText` (терминал:A-10). */
    private val run = CharArray(TerminalEmulator.MAX_COLS)

    /**
     * Строка экрана (терминал:A-10): сначала фон — подряд идущие ячейки
     * одного цвета одним прямоугольником, потом текст — подряд идущий ASCII
     * одного стиля одним `drawText` и без замера ширины (у моноширинного
     * шрифта она равна ячейке); прочие знаки — по ячейке.
     */
    private fun drawRow(canvas: Canvas, row: TerminalRow, y: Int, line: Int, links: BooleanArray, sel: IntArray?) {
        val n = minOf(frame.cols, row.cols)
        val top = y * cellH
        var start = 0
        var end = 0
        var bgRun = frame.defBg
        for (x in 0 until n) {
            if (row.codePoint(x) == TerminalRow.WIDE_TAIL) continue
            colors(shown(row, x, links), sel != null && inSelection(sel, line, x))
            val span = if (wideAt(row, x)) 2 else 1
            if (cellBg == bgRun && x == end) {
                end = x + span
                continue
            }
            if (bgRun != frame.defBg && end > start) {
                fill.color = bgRun
                canvas.drawRect(start * cellW, top, end * cellW, top + cellH, fill)
            }
            bgRun = cellBg
            start = x
            end = x + span
        }
        if (bgRun != frame.defBg && end > start) {
            fill.color = bgRun
            canvas.drawRect(start * cellW, top, end * cellW, top + cellH, fill)
        }
        var x = 0
        while (x < n) {
            val cp = row.codePoint(x)
            if (cp == TerminalRow.WIDE_TAIL || cp == TerminalRow.EMPTY) {
                x++
                continue
            }
            val style = shown(row, x, links)
            val selected = sel != null && inSelection(sel, line, x)
            colors(style, selected)
            if (style.has(Attr.INVISIBLE)) {
                x++
                continue
            }
            val left = x * cellW
            if (cp in ASCII && row.combiningAt(x) == null) {
                var k = 0
                var next = x
                while (next < n) {
                    val c = row.codePoint(next)
                    if (c !in ASCII || row.combiningAt(next) != null) break
                    if (next > x && (shown(row, next, links) != style || (sel != null && inSelection(sel, line, next)) != selected)) break
                    run[k++] = c.toChar()
                    next++
                }
                text(style)
                canvas.drawText(run, 0, k, left, top + ascent, paint)
                decorate(canvas, style, left, next * cellW, top)
                x = next
                continue
            }
            val right = left + cellW * (if (wideAt(row, x)) 2 else 1)
            if (!box(canvas, row, x, left, top, right - left, cellFg)) {
                text(style)
                val s = row.text(x)
                val base = top + ascent
                // Знак шрифта бывает шире своих ячеек (широкие, эмодзи): сжать, чтобы не налезал на соседа.
                val need = right - left
                val w = paint.measureText(s)
                if (w > need * 1.05f) {
                    canvas.save()
                    canvas.scale(need / w, 1f, left, base)
                    canvas.drawText(s, left, base, paint)
                    canvas.restore()
                } else {
                    canvas.drawText(s, left, base, paint)
                }
            }
            decorate(canvas, style, left, right, top)
            x++
        }
    }

    private fun text(style: Style) {
        paint.color = cellFg
        paint.isFakeBoldText = style.has(Attr.BOLD)
        paint.textSkewX = if (style.has(Attr.ITALIC)) -0.25f else 0f
    }

    /** Подчёркивание, зачёркивание и надчёркивание куска `left..right` цветом текста. */
    private fun decorate(canvas: Canvas, style: Style, left: Float, right: Float, top: Float) {
        if (style.has(Attr.UNDERLINE) || style.has(Attr.DOUBLE_UNDERLINE)) {
            fill.color = cellFg
            canvas.drawRect(left, top + cellH - 2f, right, top + cellH - 1f, fill)
            if (style.has(Attr.DOUBLE_UNDERLINE)) canvas.drawRect(left, top + cellH - 4f, right, top + cellH - 3f, fill)
        }
        if (style.has(Attr.STRIKE)) {
            fill.color = cellFg
            canvas.drawRect(left, top + cellH / 2, right, top + cellH / 2 + 1f, fill)
        }
        if (style.has(Attr.OVERLINE)) {
            fill.color = cellFg
            canvas.drawRect(left, top, right, top + 1f, fill)
        }
    }

    private fun drawCursor(canvas: Canvas) {
        val f = frame
        val x = f.cursorX
        val y = f.cursorY
        val left = x * cellW
        val top = y * cellH
        val row = f.rows[f.top + y]
        val right = left + cellW * (if (wideAt(row, x)) 2 else 1)
        fill.color = f.defFg
        if (!hasFocus()) {
            fill.style = Paint.Style.STROKE
            canvas.drawRect(left + 0.5f, top + 0.5f, right - 0.5f, top + cellH - 0.5f, fill)
            fill.style = Paint.Style.FILL
            return
        }
        when (f.cursorShape) {
            3, 4 -> canvas.drawRect(left, top + cellH - 3f, right, top + cellH, fill)
            5, 6 -> canvas.drawRect(left, top, left + 3f, top + cellH, fill)
            else -> {
                canvas.drawRect(left, top, right, top + cellH, fill)
                val cp = if (x < row.cols) row.codePoint(x) else TerminalRow.EMPTY
                if (cp != TerminalRow.EMPTY && cp != TerminalRow.WIDE_TAIL && !box(canvas, row, x, left, top, right - left, f.defBg)) {
                    paint.color = f.defBg
                    paint.isFakeBoldText = false
                    paint.textSkewX = 0f
                    canvas.drawText(row.text(x), left, top + ascent, paint)
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_SP = 13f

        /** Настройки экрана терминала (не секреты): размер шрифта, Shift+Enter. */
        private const val PREFS = "terminal"
        private const val FONT_PX = "font_px"
        private const val SHIFT_ENTER = "shift_enter_newline"
        private const val BOLD_BRIGHT = "bold_bright"
        private const val KEEP_ON = "keep_screen_on"

        /** Поле сетки слева и справа, dp (терминал:A-24). */
        private const val SIDE_DP = 4f

        /** «Не гасить экран», пока открыт терминал (терминал:A-24); по умолчанию — да. */
        fun keepScreenOn(context: Context): Boolean =
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEEP_ON, true) }.getOrDefault(true)

        fun setKeepScreenOn(context: Context, on: Boolean) {
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEEP_ON, on).apply() }
        }

        /** Печатный ASCII: его ширина — ячейка, кусок рисуется одним вызовом. */
        private val ASCII = 0x20..0x7E

        /** Строк продолжения переноса над и под экраном в снимке кадра — для подчёркивания адресов. */
        private const val LINK_SPAN = 8

        /** Полупериод мигания курсора, мс — как у xterm и VTE. */
        private const val BLINK_MS = 530L
        private const val FONT_STEP = 1.1f

        /** Строк за один щелчок колеса мыши. */
        private const val WHEEL_LINES = 3f

        /** Shift+Enter — новая строка (терминал:A-6); по умолчанию — да. */
        fun shiftEnterNewline(context: Context): Boolean =
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SHIFT_ENTER, true) }.getOrDefault(true)

        fun setShiftEnterNewline(context: Context, on: Boolean) {
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SHIFT_ENTER, on).apply() }
        }
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

        /** Текст экрана для TalkBack — не чаще раза в полсекунды и не длиннее 4000 знаков (терминал:A-12). */
        private const val SPEAK_EVERY_MS = 500L
        private const val SPEAK_MAX = 4_000

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

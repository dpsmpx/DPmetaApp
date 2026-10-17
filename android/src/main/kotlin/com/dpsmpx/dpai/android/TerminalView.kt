package com.dpsmpx.dpai.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.dpsmpx.dpai.terminal.Attr
import com.dpsmpx.dpai.terminal.Key
import com.dpsmpx.dpai.terminal.Mod
import com.dpsmpx.dpai.terminal.MouseButton
import com.dpsmpx.dpai.terminal.MouseMode
import com.dpsmpx.dpai.terminal.Style
import com.dpsmpx.dpai.terminal.TermColor
import com.dpsmpx.dpai.terminal.TerminalEmulator
import com.dpsmpx.dpai.terminal.TerminalRow
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Экран своего терминала (D-57): сетка эмулятора на Canvas, курсор,
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
    private var scrollAccum = 0f

    /** Выделение: сквозные строки и столбцы эмулятора (начало, конец). */
    private var selection: IntArray? = null
    private var selecting = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selection != null) {
                selection = null
                invalidate()
                return true
            }
            val t = tab ?: return true
            val mouse = t.session.read { it.mouseMode } != MouseMode.NONE
            if (mouse) {
                val (c, r) = cell(e)
                t.session.mouse(MouseButton.LEFT, c, r, pressed = true, motion = false)
                t.session.mouse(MouseButton.LEFT, c, r, pressed = false, motion = false)
            } else {
                showKeyboard()
            }
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
            val line = t.session.read { it.scrollbackSize - scrollBack + r }
            selection = intArrayOf(line, c, line, c)
            selecting = true
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
        if (t.session.read { it.cols != cols || it.rows != rows }) t.session.resize(cols, rows)
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

    private fun cell(e: MotionEvent): Pair<Int, Int> {
        val t = tab
        val cols = t?.session?.read { it.cols } ?: 1
        val rows = t?.session?.read { it.rows } ?: 1
        return (e.x / cellW).toInt().coerceIn(0, cols - 1) to (e.y / cellH).toInt().coerceIn(0, rows - 1)
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
        if (selecting && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val t = tab ?: return true
            val (c, r) = cell(event)
            val line = t.session.read { it.scrollbackSize - scrollBack + r }
            selection?.let { it[2] = line; it[3] = c }
            invalidate()
            return true
        }
        if (selecting && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            selecting = false
            copySelection()
            return true
        }
        if (scale.isInProgress) return true
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /** Начало и конец выделения по порядку. */
    private fun ordered(s: IntArray): IntArray =
        if (s[0] < s[2] || (s[0] == s[2] && s[1] <= s[3])) s.copyOf() else intArrayOf(s[2], s[3], s[0], s[1])

    private fun copySelection() {
        val t = tab ?: return
        val s = ordered(selection ?: return)
        val text = t.session.read { it.text(s[0], s[1], s[2], s[3]) }
        if (text.isEmpty()) return
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("терминал", text))
        Toast.makeText(context, "Скопировано: ${text.length} симв.", Toast.LENGTH_SHORT).show()
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) imm.hideSoftInputFromWindow(windowToken, 0) else showKeyboard()
    }

    // ---- ввод ----

    /** Текст с экранной клавиатуры или ряда клавиш. */
    fun input(text: String) {
        val t = tab ?: return
        val mods = stickyMods()
        scrollBack = 0
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
        scrollBack = 0
        t.session.key(key, extraMods or stickyMods())
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Без подсказок и автоисправлений: клавиатура шлёт символы, а не слова.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
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
        val cp = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (cp > 0) {
            scrollBack = 0
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

        /** Размер шрифта — общий для вкладок, пока жив процесс. */
        private var fontPx = 0f
    }
}

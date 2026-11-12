package com.dpsmpx.dpai.android

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import kotlin.math.roundToInt
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.CodeEditorBuffer
import com.dpsmpx.dpai.ui.EditorActions
import com.dpsmpx.dpai.ui.EditorSelection
import com.dpsmpx.dpai.ui.EditorSnapshot
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor as SoraEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class EditorCommand { UNDO, REDO, SAVE, FIND, REPLACE, GOTO, COMMENT, DUPLICATE, DELETE_LINE, MOVE_UP, MOVE_DOWN, INDENT, UNINDENT, NEXT_FILE, PREV_FILE, CLOSE_FILE, FILES }
internal data class EditorCallbacks(val save: () -> Unit = {}, val close: () -> Unit = {}, val browse: () -> Unit = {}, val next: (Boolean) -> Unit = {}, val busy: Boolean = false, val canSave: Boolean = false, val preview: () -> Unit = {}, val exit: () -> Unit = {}, val run: () -> Unit = {}, val configureRun: () -> Unit = {})

/** Тонкий адаптер: рендеринг/IME у Sora, снимки и история — CodeEditorBuffer (D-136). */
internal class StudioCodeField(context: Context) : SoraEditor(context) {
    var changed: () -> Unit = {}
    var command: (EditorCommand) -> Unit = {}
    var position: (Int, Int) -> Unit = { _, _ -> }
    var issue: (String) -> Unit = {}
    var viewport: (Int, Int) -> Unit = { _, _ -> }
    var zoom: (Int) -> Unit = {}
    private var model: CodeEditorBuffer? = null
    private var observed: EditorSnapshot? = null
    private var applying = false
    private var batch = false
    private var languageKey = ""
    private var lastSize = -1
    var nativeChanges = 0
        private set

    /** Подчёркивания языковой службы (D-137); отрезки — в координатах текущего текста. */
    private var marks: List<com.dpsmpx.dpai.lsp.Assist.Mark> = emptyList()
    private val markProvider = io.github.rosemoe.sora.lang.diagnostic.DiagnosticProvider { container ->
        val length = text.length
        container.addDiagnostics(marks.filter { it.until <= length }.mapIndexed { i, m ->
            val severity = when (m.severity) {
                com.dpsmpx.dpai.lsp.LspSeverity.ERROR -> io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_ERROR
                com.dpsmpx.dpai.lsp.LspSeverity.WARNING -> io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_WARNING
                else -> io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_TYPO
            }
            io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion(m.from, m.until, severity, i.toLong())
        })
    }

    fun mark(list: List<com.dpsmpx.dpai.lsp.Assist.Mark>) {
        if (list == marks) return
        marks = list
        invalidateDiagnostics()
    }

    init {
        typefaceText = Typeface.MONOSPACE
        isLineNumberEnabled = true
        setHighlightCurrentLine(true)
        setHighlightCurrentBlock(true)
        setScrollBarEnabled(true)
        setScaleTextSizes(10f, 32f)
        setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        props.clipboardTextLengthLimit = 256 * 1024
        getComponent(EditorAutoCompletion::class.java).isEnabled = false
        registerDiagnosticProvider(markProvider)
        subscribeEvent(ScrollEvent::class.java) { event, _ -> if (!applying) viewport(event.endX, event.endY) }
        subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (!applying) {
                val buffer = model ?: return@subscribeEvent
                val current = text.toString()
                val s = selection()
                if (!buffer.edit(current, s, typing = true, coalesce = batch)) {
                    issue("Текст превышает предел 2 млн позиций UTF-16; изменение отменено")
                    postInLifecycle { sync(force = true) }
                } else {
                    nativeChanges++
                    observed = buffer.state
                    if (!batch) { batch = true; postInLifecycle { batch = false } }
                    changed()
                }
            }
        }
        subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            position(cursor.left().line + 1, cursor.left().column + 1)
            if (!applying) model?.let { buffer ->
                val s = selection()
                if (s != buffer.state.selection) { buffer.select(s.start, s.end); observed = buffer.state; changed() }
            }
        }
        subscribeEvent(EditorKeyEvent::class.java) { event, _ ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val ctrl = event.isCtrlPressed
                val shift = event.isShiftPressed
                val action = when {
                    ctrl && event.keyCode == KeyEvent.KEYCODE_Z -> if (shift) EditorCommand.REDO else EditorCommand.UNDO
                    ctrl && event.keyCode == KeyEvent.KEYCODE_Y -> EditorCommand.REDO
                    ctrl && event.keyCode == KeyEvent.KEYCODE_S -> EditorCommand.SAVE
                    ctrl && event.keyCode == KeyEvent.KEYCODE_F -> EditorCommand.FIND
                    ctrl && event.keyCode == KeyEvent.KEYCODE_H -> EditorCommand.REPLACE
                    ctrl && event.keyCode == KeyEvent.KEYCODE_G -> EditorCommand.GOTO
                    ctrl && event.keyCode == KeyEvent.KEYCODE_SLASH -> EditorCommand.COMMENT
                    ctrl && event.keyCode == KeyEvent.KEYCODE_D -> EditorCommand.DUPLICATE
                    ctrl && shift && event.keyCode == KeyEvent.KEYCODE_K -> EditorCommand.DELETE_LINE
                    event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> EditorCommand.MOVE_UP
                    event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> EditorCommand.MOVE_DOWN
                    ctrl && event.keyCode == KeyEvent.KEYCODE_TAB -> if (shift) EditorCommand.PREV_FILE else EditorCommand.NEXT_FILE
                    event.keyCode == KeyEvent.KEYCODE_TAB -> if (shift) EditorCommand.UNINDENT else EditorCommand.INDENT
                    ctrl && event.keyCode == KeyEvent.KEYCODE_W -> EditorCommand.CLOSE_FILE
                    ctrl && event.keyCode == KeyEvent.KEYCODE_P -> EditorCommand.FILES
                    else -> null
                }
                if (action != null) { event.interceptAndSetResult(true); command(action) }
            }
        }
    }

    fun bind(buffer: CodeEditorBuffer, path: String, ready: Boolean, dark: Boolean, wrap: Boolean, size: Int, width: Int, tabs: Boolean, restored: Pair<Int, Int>?, highlighted: Boolean) {
        val initial = model !== buffer
        if (initial) { model = buffer; observed = null }
        isWordwrap = wrap
        tabWidth = width
        if (lastSize != size) { setTextSize(size.toFloat()); lastSize = size }
        val key = "$path:$ready:$dark:$width:$tabs:$highlighted"
        if (ready && languageKey != key) {
            colorScheme = EditorLanguages.scheme(dark, highlighted)
            setEditorLanguage(EditorLanguages.language(path, width, tabs))
            languageKey = key
        }
        sync()
        if (initial && restored != null) postInLifecycle {
            scroller.startScroll(restored.first.coerceIn(0, scrollMaxX), restored.second.coerceIn(0, scrollMaxY), 0, 0, 0)
            invalidate()
        }
    }

    override fun setTextSizePxDirect(size: Float) {
        super.setTextSizePxDirect(size)
        if (model != null) {
            val sp = (size / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)).roundToInt().coerceIn(10, 32)
            zoom(sp)
        }
    }

    fun sync(force: Boolean = false) {
        val buffer = model ?: return
        if (!force && observed === buffer.state) return
        applying = true
        try {
            val state = buffer.state
            if (force || observed?.text !== state.text) {
                setText(state.text)
                isUndoEnabled = false
                lineSeparator = LineSeparator.fromSeparatorString(EditorActions.separator(state.text))
            }
            val from = text.indexer.getCharPosition(state.selection.from.coerceIn(0, text.length))
            val until = text.indexer.getCharPosition(state.selection.until.coerceIn(0, text.length))
            if (from.index == until.index) setSelection(from.line, from.column)
            else {
                setSelectionRegion(from.line, from.column, until.line, until.column, state.selection.start < state.selection.end)
                selectionAnchor = if (state.selection.start < state.selection.end) cursor.left() else cursor.right()
            }
            observed = state
        } finally { applying = false }
    }
    private fun selection(): EditorSelection = if (selectionAnchor?.index == cursor.right && cursor.isSelected) EditorSelection(cursor.right, cursor.left) else EditorSelection(cursor.left, cursor.right)
    override fun undo() = command(EditorCommand.UNDO)
    override fun redo() = command(EditorCommand.REDO)

    /** Нативное меню и Ctrl+C используют тот же sensitive-путь, что остальное приложение. */
    override fun copyTextToClipboard(text: CharSequence, start: Int, end: Int) { copySafe(text, start, end) }
    private fun copySafe(source: CharSequence, start: Int, end: Int): Boolean {
        if (end - start > props.clipboardTextLengthLimit) { issue("Для копирования выделите не больше 256 тыс. символов"); return false }
        return try { Clipboard.copy(context, "Код", source.subSequence(start, end).toString(), sensitive = true); true }
        catch (_: RuntimeException) { Toast.makeText(context, "Не удалось скопировать текст", Toast.LENGTH_SHORT).show(); false }
    }
    override fun cutText() {
        if (!isEditable) return
        val from = if (cursor.isSelected) cursor.left else text.getCharIndex(cursor.left().line, 0)
        val until = if (cursor.isSelected) cursor.right else if (cursor.left().line + 1 < lineCount) text.getCharIndex(cursor.left().line + 1, 0) else text.length
        if (!copySafe(text, from, until)) return
        text.delete(from, until)
        val caret = text.indexer.getCharPosition(from.coerceAtMost(text.length))
        setSelection(caret.line, caret.column)
        notifyIMEExternalCursorChange()
    }
    override fun cutLine() = cutText()
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
    fun dispose() { model = null; changed = {}; command = {}; release() }
}

@Composable
internal fun NativeCodeField(buffer: CodeEditorBuffer, path: String, revision: Int, enabled: Boolean, wrap: Boolean, size: Int, width: Int, tabs: Boolean, marks: List<com.dpsmpx.dpai.lsp.Assist.Mark>, highlighted: Boolean, onActivate: (() -> Unit)?,
    onChange: () -> Unit, onCommand: (EditorCommand) -> Unit, onPosition: (Int, Int) -> Unit, onIssue: (String) -> Unit, onZoom: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var ready by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    val action by rememberUpdatedState(onCommand)
    val position by rememberUpdatedState(onPosition)
    val issue by rememberUpdatedState(onIssue)
    val zoom by rememberUpdatedState(onZoom)
    val activate by rememberUpdatedState(onActivate)
    var visited by rememberSaveable(path) { mutableStateOf(false) }
    var offsetX by rememberSaveable(path) { mutableStateOf(0) }
    var offsetY by rememberSaveable(path) { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { EditorLanguages.prepare(context) }
        ready = result is Outcome.Success
        if (result is Outcome.Failure) issue(result.errors.first().message)
    }
    // revision — сигнал команд модели; при обычном вводе observed уже совпадает со снимком.
    revision.let {
        AndroidView(factory = { StudioCodeField(it) }, modifier = modifier, onRelease = { it.dispose() }, update = { editor ->
            editor.changed = { change() }; editor.command = { action(it) }; editor.position = { line, column -> position(line, column) }; editor.issue = { issue(it) }
            val restored = if (visited) offsetX to offsetY else null
            editor.viewport = { x, y -> offsetX = x; offsetY = y; visited = true }
            editor.zoom = { zoom(it) }
            val entering = !editor.isEditable && enabled
            editor.setOnClickListener { if (!enabled) editor.postInLifecycle { activate?.invoke() } }
            editor.isEditable = enabled
            editor.bind(buffer, path, ready, dark, wrap, size, width, tabs, restored, highlighted)
            if (entering) editor.postInLifecycle {
                editor.requestFocus()
                (editor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            editor.mark(marks)
        })
    }
}

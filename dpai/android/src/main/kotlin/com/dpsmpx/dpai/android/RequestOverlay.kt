package com.dpsmpx.dpai.android

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.RequestCard
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Окно поверх приложений (D-44) — второй, необязательный канал запросов:
 * только если человек включил его в приложении ([enabled]) **и** дал
 * разрешение системы «поверх других приложений». Нет одного из двух —
 * окна нет, запрос приходит уведомлением; ничего не ломается.
 *
 * Окно компактное: текст запроса, чья задача, варианты. Пока открыто
 * само приложение, окна нет — запрос виден на вкладке «Запросы». Окно
 * перетаскивается за верхнюю строку и запоминает место: в любом
 * постоянном месте оно что-то закрывает — шапку, клавиатуру (проверка на
 * Redmi Note 13, 8 октября 2026). Касания вне окна идут приложению под ним. Опасный вариант — вторым
 * нажатием, а если приложение требует вход (D-104, `DPmetaApp:D-61`), — на
 * экране приложения после входа; вариант с текстом — с полем ввода. «Скрыть
 * окно» убирает окно, а запрос остаётся в уведомлении и на экране «Запросы».
 */
object RequestOverlay {
    private const val FLAG = "overlay-enabled"
    private const val POSITION = "overlay-y"

    /** Приложение на экране (`MainActivity` между `onResume` и `onPause`): окно не нужно. */
    @Volatile
    var appVisible: Boolean = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** Убранные «Скрыть окно» в этом процессе — окно им не возвращается. */
    private val later = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var shown: Pair<String, View>? = null

    /** Включил ли человек окно в приложении (флаг в каталоге файлов приложения). */
    fun enabled(context: Context): Boolean = File(context.filesDir, FLAG).isFile

    fun setEnabled(context: Context, on: Boolean) {
        val vfs = NioVfs(context.filesDir.toPath())
        runCatching { if (on) vfs.write(FLAG, "включено человеком\n".toByteArray()) else vfs.delete(FLAG) }
        if (!on) hide()
    }

    /** Разрешение системы «поверх других приложений». */
    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Экран настройки разрешения для этого приложения: даёт только человек. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun sync(context: Context, model: DemoModel, pending: Map<RequestId, RequestCard>) {
        if (!enabled(context) || !permitted(context) || appVisible) return hide()
        val current = shown?.first
        if (current != null && pending.keys.none { it.value == current }) hide()
        if (shown != null) return
        val next = pending.values.firstOrNull { it.id.value !in later } ?: return
        main.post {
            if (shown != null) return@post
            if (runCatching { show(context.applicationContext, next) }.isSuccess) worker.execute { model.delivered(next.id, "overlay") }
        }
    }

    /** Вход приложения нужен (D-104): спрашивается в момент нажатия — за время показа окна вход мог истечь. */
    private fun loginNeeded(context: Context): Boolean = (context.applicationContext as? DpaiApplication)?.loginNeeded() == true

    private fun position(context: Context): Int? =
        runCatching { NioVfs(context.filesDir.toPath()).read(POSITION)?.toString(Charsets.UTF_8)?.trim()?.toIntOrNull() }.getOrNull()

    private fun savePosition(context: Context, y: Int) {
        runCatching { NioVfs(context.filesDir.toPath()).write(POSITION, "$y\n".toByteArray()) }
    }

    /** Ответ принят и ждёт такта (D-94): окно этого запроса убирается и до итога не возвращается. */
    fun waiting(id: String) {
        later += id
        closed(id)
    }

    /** Подтверждение из `dpai` (D-91): окно снова доступно запросу, даже скрытому «Скрыть окно». */
    fun again(id: String) {
        later -= id
    }

    /** Запрос закрыт ответом: его окно убирается. */
    fun closed(id: String) {
        if (shown?.first == id) hide()
    }

    fun hide() {
        main.post {
            val s = shown ?: return@post
            shown = null
            runCatching { s.second.context.getSystemService(WindowManager::class.java).removeView(s.second) }
        }
    }

    private fun show(context: Context, card: RequestCard) {
        val wm = context.getSystemService(WindowManager::class.java)
        val dp = context.resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fg = if (night) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(if (night) 0xF0262626.toInt() else 0xF8FFFFFF.toInt())
                setStroke((1 * dp).toInt(), 0x33888888)
            }
            elevation = 8 * dp
        }
        fun text(s: String, size: Float, bold: Boolean = false) = TextView(context).apply {
            this.text = s
            textSize = size
            setTextColor(fg)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Касания вне окна — приложению под ним; клавиатуру окно получает только с полем ввода.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (card.buttons.none { it.needsText }) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = position(context) ?: (48 * dp).toInt()
            horizontalMargin = 0.03f
        }
        // Верхняя строка — ручка: перетаскивание по вертикали, место запоминается.
        root.addView(text("⠿ DP AI · ${card.kind} · ${card.origin}", 12f).apply {
            var startY = 0f
            var startParam = 0
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startY = e.rawY
                        startParam = params.y
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val max = context.resources.displayMetrics.heightPixels - root.height
                        params.y = (startParam + (e.rawY - startY).toInt()).coerceIn(0, maxOf(0, max))
                        runCatching { wm.updateViewLayout(root, params) }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        savePosition(context, params.y)
                        v.performClick()
                    }
                }
                true
            }
        })
        root.addView(text(card.title, 16f, bold = true))
        root.addView(text(card.body, 14f))
        val status = text("", 12f).apply { setTextColor(0xFFC62828.toInt()) }
        val input = if (card.buttons.any { it.needsText }) {
            EditText(context).apply {
                hint = "ответ"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setTextColor(fg)
                maxLines = 4
            }.also { root.addView(it) }
        } else {
            null
        }
        root.addView(status)
        // Кнопки — столбцом во всю ширину: у запроса бывает пять вариантов, в строку на ~393 dp они не влезают (аудит 1 ноября 2026, A-7).
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val wide = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        for (b in card.buttons) {
            var armed = false
            var armedLogin = false
            row.addView(Button(context).apply {
                text = b.label
                isAllCaps = false
                setOnClickListener {
                    val login = b.confirm && loginNeeded(context)
                    // Опасное — вторым нажатием: одно касание по ошибке ничего не делает (D-44). Вход стал нужен
                    // или не нужен между нажатиями — подтверждение заново: подсказка обещает то, что случится (D-104).
                    if (b.confirm && (!armed || login != armedLogin)) {
                        armed = true
                        armedLogin = login
                        text = "Точно: ${b.label}?"
                        status.text = if (login) "Опасное действие: нажмите ещё раз — вход по PIN и подтверждение в приложении" else "Опасное действие: нажмите ещё раз, чтобы подтвердить"
                        return@setOnClickListener
                    }
                    if (login) {
                        // Вход приложения нужен — опасное подтверждается на его экране после входа, а не здесь (D-104).
                        // Окно убирает сам экран (`MainActivity.onResume`): запуск из фона прошивка может молча не пустить
                        // (MIUI — разрешение на всплывающие окна в фоне), и тогда окно с подсказкой остаётся.
                        if (runCatching { context.startActivity(MainActivity.confirm(context, b.uri)) }.isSuccess) {
                            later += card.id.value
                            status.text = "Открываю приложение: вход по PIN. Не открылось — откройте его сами: запрос на экране «Запросы»"
                        } else {
                            status.text = "Нужен вход: откройте приложение — запрос на экране «Запросы»"
                        }
                        return@setOnClickListener
                    }
                    val typed = input?.text?.toString()?.takeIf { b.needsText }
                    if (b.needsText && typed.isNullOrBlank()) {
                        status.text = "Нужен текст ответа"
                        return@setOnClickListener
                    }
                    isEnabled = false
                    status.text = "Отправляю…"
                    worker.execute {
                        val r = Answers.apply(context, card.id.value, b.uri, typed, b.label, "overlay")
                        main.post {
                            when {
                                r is Outcome.Success || Answers.queued(r) -> hide()
                                r is Outcome.Failure -> {
                                    isEnabled = true
                                    status.text = "Не принято: ${r.diagnostics.first().message}"
                                }
                            }
                        }
                    }
                }
            }, wide)
        }
        // Только прячет окно — на запрос не отвечает (в отличие от варианта «Решу позже»).
        row.addView(Button(context).apply {
            text = "Скрыть окно"
            isAllCaps = false
            setOnClickListener {
                later += card.id.value
                hide()
            }
        }, wide)
        root.addView(row)
        wm.addView(root, params)
        shown = card.id.value to root
    }
}

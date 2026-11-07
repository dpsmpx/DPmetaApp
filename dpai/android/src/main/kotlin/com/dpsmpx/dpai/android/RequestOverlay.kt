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
import com.dpsmpx.dpai.core.clock.Clock
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
        // Цвета — роли схемы приложения (AI:A-8, DPmetaApp:D-74): контраст текста к фону — не ниже 4,5 : 1 (AppPaletteTest).
        val scheme = com.dpsmpx.dpai.ui.AppPalette.scheme(night)
        val fg = scheme.onSurface.toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(scheme.surfaceContainerHigh.toInt())
                setStroke((1 * dp).toInt(), scheme.outlineVariant.toInt())
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
        // Верхняя строка — ручка: перетаскивание по вертикали, место запоминается; «×» — скрыть окно (AI:A-8).
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("⠿ AI · ${card.kind} · ${card.origin}", 12f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
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
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Только прячет окно — на запрос не отвечает (в отличие от варианта «Решу позже»).
        head.addView(TextView(context).apply {
            text = "×"
            textSize = 22f
            setTextColor(fg)
            gravity = Gravity.CENTER
            contentDescription = "Скрыть окно"
            minWidth = (48 * dp).toInt()
            minHeight = (48 * dp).toInt()
            setOnClickListener {
                later += card.id.value
                hide()
            }
        })
        root.addView(head)
        root.addView(text(card.title, 16f, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // Окно — маленькое (AI:A-8): текст до четырёх строк, целиком — в приложении («Ещё…»).
        root.addView(text(card.body, 14f).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // Ошибка — смысловым цветом под тему окна: прежний красный на тёмном фоне давал 2,7 : 1 (AI:A-8, D-74).
        val status = text("", 12f).apply { setTextColor(com.dpsmpx.dpai.ui.AppPalette.semantic(com.dpsmpx.dpai.ui.AppPalette.Role.ERROR, night).toInt()) }
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
        // Варианты — по два в строке (AI:A-8); больше трёх — два первых и «Ещё…» в приложение на этом запросе.
        val shownButtons = if (card.buttons.size > 3) card.buttons.take(2) else card.buttons
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var line: LinearLayout? = null
        fun place(v: android.view.View) {
            val l = line?.takeIf { it.childCount < 2 } ?: LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }.also {
                grid.addView(it)
                line = it
            }
            l.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt()) })
        }
        // Вес по роли (AI:A-8): «принять» — заливная, остальные — контурные, опасный — цветом ошибки.
        fun styled(btn: Button, tone: com.dpsmpx.dpai.ui.Tone, danger: Boolean) = btn.apply {
            isAllCaps = false
            minHeight = (48 * dp).toInt()
            val accept = tone == com.dpsmpx.dpai.ui.Tone.ACTIVE && !danger
            val error = com.dpsmpx.dpai.ui.AppPalette.semantic(com.dpsmpx.dpai.ui.AppPalette.Role.ERROR, night).toInt()
            setTextColor(if (accept) scheme.onPrimary.toInt() else if (danger) error else scheme.primary.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                if (accept) setColor(scheme.primary.toInt()) else setStroke((1 * dp).toInt(), if (danger) error else scheme.outline.toInt())
            }
        }
        for (b in shownButtons) {
            var armed = false
            var armedAt = 0L
            var armedLogin = false
            place(styled(Button(context), b.tone, b.confirm || b.tone == com.dpsmpx.dpai.ui.Tone.ERROR).apply {
                text = b.label
                setOnClickListener {
                    val login = b.confirm && loginNeeded(context)
                    // Опасное — вторым нажатием: одно касание по ошибке ничего не делает (D-44). Вход стал нужен
                    // или не нужен между нажатиями — подтверждение заново: подсказка обещает то, что случится (D-104).
                    // Второе нажатие — не раньше ARM_PAUSE_MS (двойное касание и дрожь пальца — не подтверждение)
                    // и не позже ARM_TTL_MS: взвод гаснет сам (аудит UI/UX 5 ноября 2026, AI:A-4).
                    val now = Clock.SYSTEM.monotonicNanos() / 1_000_000
                    if (b.confirm && armed && now - armedAt < ARM_PAUSE_MS) return@setOnClickListener
                    val fresh = armed && now - armedAt <= ARM_TTL_MS
                    if (b.confirm && (!fresh || login != armedLogin)) {
                        armed = true
                        armedAt = now
                        armedLogin = login
                        text = "Точно: ${b.label}?"
                        postDelayed({
                            if (armed && Clock.SYSTEM.monotonicNanos() / 1_000_000 - armedAt >= ARM_TTL_MS) {
                                armed = false
                                text = b.label
                                status.text = ""
                            }
                        }, ARM_TTL_MS)
                        status.text = if (login) "Опасное действие: нажмите ещё раз — вход по PIN и подтверждение в приложении" else "Опасное действие: нажмите ещё раз, чтобы подтвердить"
                        return@setOnClickListener
                    }
                    // Подтверждено: взвод гаснет — повтор после отказа снова с двумя нажатиями, а сброс по времени
                    // не стирает «Отправляю…» и подсказку (ревью PR #170).
                    armed = false
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
            })
        }
        if (card.buttons.size > 3) {
            place(styled(Button(context), com.dpsmpx.dpai.ui.Tone.NEUTRAL, danger = false).apply {
                text = "Ещё…"
                contentDescription = "Все варианты — в приложении"
                setOnClickListener {
                    // Приложение на этом запросе — все варианты; окно больше не всплывает ему (как «×»).
                    val link = com.dpsmpx.dpai.notifications.DeepLink.of(com.dpsmpx.dpai.notifications.DeepLink.Kind.REQUEST, card.id).toUri()
                    runCatching { context.startActivity(MainActivity.internal(context).setAction(MainActivity.OPEN).putExtra(MainActivity.EXTRA_LINK, link).addFlags(MainActivity.FRONT)) }
                    later += card.id.value
                    hide()
                }
            })
        }
        root.addView(grid)
        wm.addView(root, params)
        shown = card.id.value to root
    }
}

/** Пауза между нажатиями опасного ответа: быстрее — двойное касание, а не решение (AI:A-4). */
private const val ARM_PAUSE_MS = 500L

/** Сколько живёт взвод опасного ответа: дольше — первое нажатие снова (AI:A-4). */
private const val ARM_TTL_MS = 5_000L

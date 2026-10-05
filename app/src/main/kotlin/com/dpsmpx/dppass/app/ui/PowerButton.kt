package com.dpsmpx.dppass.app.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.Button
import com.dpsmpx.dppass.R
import kotlin.math.min

/**
 * Главная кнопка «Подключение» (D-34): кольцо и значок своего рисунка.
 *
 * - нет туннеля — тонкое кольцо, значок цвета акцента;
 * - подключение — по кольцу бежит дуга (анимация, пока виден экран);
 * - подключено — круг залит акцентом, кольцо — мягкое свечение;
 * - отказ — кольцо цвета ошибки.
 *
 * Смена состояния — плавный переход цвета. Для TalkBack это кнопка с
 * подписью «Подключить» / «Отключить» и состоянием.
 */
class PowerButton(context: Context) : View(context) {
    enum class Mode { OFF, CONNECTING, CONNECTED, FAILED, DISABLED }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon: Drawable = context.getDrawable(R.drawable.ic_power)!!.mutate()
    private val bounds = RectF()

    private var discColor = context.getColor(R.color.surface)
    private var accent = context.getColor(R.color.primary)
    private var iconColor = context.getColor(R.color.primary)
    private var sweepStart = 0f
    private var press = 1f

    var mode: Mode = Mode.OFF
        private set

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1_200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepStart = it.animatedValue as Float
            invalidate()
        }
    }
    private var colorAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        ring.strokeWidth = Ui.dpF(context, 3f)
        arc.strokeWidth = Ui.dpF(context, 4f)
        apply(Mode.OFF, animate = false)
    }

    /** Новое состояние; [animate] — плавный переход цвета (при первом показе — сразу). */
    fun apply(next: Mode, animate: Boolean = true) {
        val (d, a, i) = colors(next)
        val from = Triple(discColor, accent, iconColor)
        mode = next
        colorAnimator?.cancel()
        if (!animate || !isAttachedToWindow) {
            discColor = d
            accent = a
            iconColor = i
            invalidate()
        } else {
            val eval = ArgbEvaluator()
            colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                addUpdateListener {
                    val f = it.animatedFraction
                    discColor = eval.evaluate(f, from.first, d) as Int
                    accent = eval.evaluate(f, from.second, a) as Int
                    iconColor = eval.evaluate(f, from.third, i) as Int
                    invalidate()
                }
                start()
            }
        }
        isEnabled = next != Mode.DISABLED
        alpha = if (next == Mode.DISABLED) 0.5f else 1f
        updateSpinner()
    }

    private fun colors(m: Mode): Triple<Int, Int, Int> {
        fun c(id: Int) = context.getColor(id)
        return when (m) {
            Mode.OFF, Mode.DISABLED -> Triple(c(R.color.surface), c(R.color.primary), c(R.color.primary))
            Mode.CONNECTING -> Triple(c(R.color.primary_container), c(R.color.primary), c(R.color.on_primary_container))
            Mode.CONNECTED -> Triple(c(R.color.primary), c(R.color.primary), c(R.color.on_primary))
            Mode.FAILED -> Triple(c(R.color.error_container), c(R.color.error), c(R.color.error))
        }
    }

    private fun updateSpinner() {
        val run = mode == Mode.CONNECTING && isAttachedToWindow && visibility == VISIBLE
        if (run && !spinner.isStarted) spinner.start()
        if (!run && spinner.isStarted) spinner.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpinner()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Экран скрыт — анимация не крутится зря.
        if (isVisible) updateSpinner() else spinner.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = Ui.dp(context, 176)
        val w = resolveSize(want, widthMeasureSpec)
        val h = resolveSize(want, heightMeasureSpec)
        val s = min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - arc.strokeWidth
        canvas.save()
        canvas.scale(press, press, cx, cy)

        if (mode == Mode.CONNECTED) {
            // Мягкое свечение вокруг залитого круга.
            glow.color = withAlpha(accent, 0x33)
            canvas.drawCircle(cx, cy, outer, glow)
        }
        val inner = outer - Ui.dpF(context, 12f)
        disc.color = discColor
        canvas.drawCircle(cx, cy, inner, disc)

        ring.color = if (mode == Mode.CONNECTED) withAlpha(accent, 0x66) else withAlpha(accent, 0x40)
        canvas.drawCircle(cx, cy, outer, ring)
        if (mode == Mode.CONNECTING) {
            bounds.set(cx - outer, cy - outer, cx + outer, cy + outer)
            arc.color = accent
            canvas.drawArc(bounds, sweepStart - 90f, 100f, false, arc)
        }

        val size = (inner * 0.75f).toInt()
        icon.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        icon.setTint(iconColor)
        icon.draw(canvas)
        canvas.restore()
    }

    /** Нажатие — лёгкое сжатие: состояние «нажато» ставит сам View, без своего onTouchEvent. */
    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was != pressed && isEnabled) animatePress(if (pressed) 0.95f else 1f)
    }

    private fun animatePress(to: Float) {
        ValueAnimator.ofFloat(press, to).apply {
            duration = 120
            addUpdateListener {
                press = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isClickable = isEnabled
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

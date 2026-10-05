package com.dpsmpx.dppass.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.diagnostics.FailureKind
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Главный поток как диспетчер корутин. `kotlinx-coroutines-android` ради
 * одного диспетчера не подключается: новая зависимость — по D-18, а
 * интерфейс — без библиотек (D-34).
 */
object MainThread : CoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}

/**
 * Строительные блоки экранов (D-34): размеры — шагом 4 dp, касаемая
 * область — не меньше 48 dp, цвета — токены `values*`/colors.xml, текст —
 * стили `Text.DPpass.*`.
 */
internal object Ui {
    const val TOUCH_DP: Int = 48

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun text(context: Context, style: Int, value: CharSequence? = null): TextView = TextView(context).apply {
        setTextAppearance(style)
        if (value != null) text = value
    }

    fun column(context: Context, gapDp: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            // Прозрачный разделитель — отступ между элементами без лишних View.
            dividerDrawable = GradientDrawable().apply { setSize(dp(context, gapDp), dp(context, gapDp)) }
        }
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Карточка: поверхность с обводкой и внутренним отступом 16 dp. */
    fun card(context: Context, gapDp: Int = 8): LinearLayout = column(context, gapDp).apply {
        setBackgroundResource(R.drawable.bg_card)
        val p = dp(context, 16)
        setPadding(p, p, p, p)
    }

    enum class Style { PRIMARY, TONAL, OUTLINE }

    fun button(context: Context, label: Int, style: Style, icon: Int? = null): Button = Button(context).apply {
        setText(label)
        isAllCaps = false
        stateListAnimator = null
        setTextAppearance(R.style.Text_DPpass_Label)
        val (bg, fg) = when (style) {
            Style.PRIMARY -> R.drawable.bg_button_primary to R.color.on_primary
            Style.TONAL -> R.drawable.bg_button_tonal to R.color.on_primary_container
            Style.OUTLINE -> R.drawable.bg_button_outline to R.color.primary
        }
        setBackgroundResource(bg)
        setTextColor(context.getColor(fg))
        minHeight = dp(context, TOUCH_DP)
        minimumHeight = dp(context, TOUCH_DP)
        setPadding(dp(context, 20), 0, dp(context, 20), 0)
        if (icon != null) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = dp(context, 8)
            compoundDrawableTintList = ColorStateList.valueOf(context.getColor(fg))
        }
    }

    fun icon(context: Context, res: Int, tint: Int, sizeDp: Int = 24): ImageView = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(context.getColor(tint))
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        // Значок рядом с текстом — украшение: TalkBack читает текст.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Плашка состояния: цвет фона и текста — пара токенов. */
    fun chip(context: Context, value: CharSequence, background: Int, foreground: Int): TextView =
        text(context, R.style.Text_DPpass_Caption, value).apply {
            setBackgroundResource(R.drawable.bg_pill)
            backgroundTintList = ColorStateList.valueOf(context.getColor(background))
            setTextColor(context.getColor(foreground))
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** Заголовок отказа по категории (D-22): подробность — в сообщении диагностики. */
    fun kind(kind: FailureKind): Int = when (kind) {
        FailureKind.NETWORK_UNREACHABLE -> R.string.kind_network_unreachable
        FailureKind.DNS_RESOLUTION_FAILED -> R.string.kind_dns_resolution_failed
        FailureKind.CONFIG_PARSE_FAILED -> R.string.kind_config_parse_failed
        FailureKind.CONFIG_FORMAT_UNSUPPORTED -> R.string.kind_config_format_unsupported
        FailureKind.CONFIG_VALIDATION_FAILED -> R.string.kind_config_validation_failed
        FailureKind.CAPABILITY_UNSUPPORTED -> R.string.kind_capability_unsupported
        FailureKind.ROUTE_COMPILATION_FAILED -> R.string.kind_route_compilation_failed
        FailureKind.BACKEND_VALIDATION_FAILED -> R.string.kind_backend_validation_failed
        FailureKind.BACKEND_START_FAILED -> R.string.kind_backend_start_failed
        FailureKind.TUN_START_FAILED -> R.string.kind_tun_start_failed
        FailureKind.ENDPOINT_UNREACHABLE -> R.string.kind_endpoint_unreachable
        FailureKind.TLS_HANDSHAKE_FAILED -> R.string.kind_tls_handshake_failed
        FailureKind.AUTHENTICATION_FAILED -> R.string.kind_authentication_failed
        FailureKind.HEALTH_CHECK_FAILED -> R.string.kind_health_check_failed
        FailureKind.SUBSCRIPTION_FETCH_FAILED -> R.string.kind_subscription_fetch_failed
        FailureKind.SUBSCRIPTION_INVALID -> R.string.kind_subscription_invalid
        FailureKind.STORAGE_FAILED -> R.string.kind_storage_failed
        FailureKind.INTERNAL -> R.string.kind_internal
    }
}

package com.dpsmpx.dppass.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.app.AppGraph
import com.dpsmpx.dppass.app.AppPrefs
import com.dpsmpx.dppass.app.TunnelStatus
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope

/** Что экранам нужно от `MainActivity`: граф, область корутин и переходы. */
interface Host {
    val activity: Activity
    val scope: CoroutineScope

    /** Граф готов (собирается в фоне при запуске). */
    val graph: AppGraph.Graph?

    val prefs: AppPrefs

    fun connect()

    fun disconnect()

    fun showTab(tab: Int)

    fun toast(text: CharSequence)

    /** Отказ с подробностями: заголовок и причина целиком, с цепочкой `causes` (D-22). */
    fun problem(title: CharSequence, reason: Diagnostic)

    /** Конфигурация или подписки изменились: перерисовать все экраны. */
    fun changed()
}

/** Экран — представление и перерисовка по данным (D-34). */
abstract class Screen(protected val host: Host) {
    protected val context: Activity get() = host.activity

    abstract val view: View

    abstract fun render(status: TunnelStatus)

    protected fun scroll(content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        val side = Ui.dp(context, 16)
        content.setPadding(side, Ui.dp(context, 24), side, Ui.dp(context, 24))
        addView(content)
    }

    protected fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0]).format(Date(millis))

    protected fun time(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, context.resources.configuration.locales[0]).format(Date(millis))
}

/**
 * «Подключение»: состояние туннеля, одна кнопка, выбранный сервер и
 * причина последнего отказа (D-34).
 */
class ConnectScreen(host: Host) : Screen(host) {
    private val source = Ui.text(context, R.style.Text_DPpass_Secondary).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val powerIcon = Ui.icon(context, R.drawable.ic_power, R.color.primary, 64)
    private val power = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.bg_pill)
        foreground = context.getDrawable(R.drawable.fg_ripple_pill)
        isClickable = true
        isFocusable = true
        addView(powerIcon, FrameLayout.LayoutParams(Ui.dp(context, 64), Ui.dp(context, 64), Gravity.CENTER))
    }
    private val title = Ui.text(context, R.style.Text_DPpass_Headline).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val detail = Ui.text(context, R.style.Text_DPpass_Secondary).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private val serverValue = Ui.text(context, R.style.Text_DPpass_Title)
    private val serverHint = Ui.text(context, R.style.Text_DPpass_Caption)
    private val serverCard = Ui.card(context, 0).apply {
        setBackgroundResource(R.drawable.bg_card)
        foreground = context.getDrawable(R.drawable.bg_row)
        isClickable = true
        isFocusable = true
        setOnClickListener { host.showTab(TAB_SERVERS) }
        val row = Ui.row(context)
        val texts = Ui.column(context, 2)
        texts.addView(Ui.text(context, R.style.Text_DPpass_Caption, context.getString(R.string.server_label)))
        texts.addView(serverValue)
        texts.addView(serverHint)
        row.addView(texts, Ui.weight())
        row.addView(Ui.icon(context, R.drawable.ic_chevron, R.color.text_secondary))
        addView(row)
    }

    private val failureTitle = Ui.text(context, R.style.Text_DPpass_Title).apply { setTextColor(context.getColor(R.color.error)) }
    private val failureText = Ui.text(context, R.style.Text_DPpass_Secondary).apply { setTextIsSelectable(true) }
    private val failureCard = Ui.card(context).apply {
        addView(failureTitle)
        addView(failureText)
        addView(Ui.button(context, R.string.open_log, Ui.Style.OUTLINE, R.drawable.ic_log).apply { setOnClickListener { host.showTab(TAB_LOG) } })
    }

    private val emptyCard = Ui.card(context, 12).apply {
        addView(Ui.text(context, R.style.Text_DPpass_Body, context.getString(R.string.empty_title)))
        addView(
            Ui.button(context, R.string.empty_action, Ui.Style.PRIMARY, R.drawable.ic_add).apply {
                setOnClickListener { host.showTab(TAB_SUBSCRIPTIONS) }
            },
        )
    }

    override val view: View = scroll(
        Ui.column(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(source, Ui.matchWidth())
            val size = Ui.dp(context, 168)
            addView(power, LinearLayout.LayoutParams(size, size).apply { topMargin = Ui.dp(context, 32) })
            addView(title, Ui.matchWidth().apply { topMargin = Ui.dp(context, 24) })
            addView(detail, Ui.matchWidth().apply { topMargin = Ui.dp(context, 4) })
            addView(serverCard, Ui.matchWidth().apply { topMargin = Ui.dp(context, 32) })
            addView(failureCard, Ui.matchWidth().apply { topMargin = Ui.dp(context, 16) })
            addView(emptyCard, Ui.matchWidth().apply { topMargin = Ui.dp(context, 16) })
        },
    )

    override fun render(status: TunnelStatus) {
        val graph = host.graph
        val core = graph?.core
        val ir = core?.activation?.state?.activeConfig
        val hasServers = ir != null && ir.profiles.isNotEmpty()

        source.text = when (val s = ConfigSource.of(core?.activation?.state?.active?.source)) {
            ConfigSource.None -> context.getString(R.string.source_none)
            ConfigSource.Own -> context.getString(R.string.source_own)
            is ConfigSource.FromSubscription -> {
                val name = (core?.subscriptions?.state() as? Outcome.Success)?.value?.find(s.id)?.let { it.metadata.title ?: it.name } ?: s.id.value
                context.getString(R.string.source_subscription, name)
            }
        }

        val (bg, fg) = when (status) {
            is TunnelStatus.Connected -> R.color.primary to R.color.on_primary
            is TunnelStatus.Failed -> R.color.error_container to R.color.error
            TunnelStatus.Stopping -> R.color.surface_variant to R.color.text_secondary
            else -> R.color.primary_container to R.color.on_primary_container
        }
        power.backgroundTintList = ColorStateList.valueOf(context.getColor(bg))
        powerIcon.imageTintList = ColorStateList.valueOf(context.getColor(fg))
        power.isEnabled = status != TunnelStatus.Stopping && (status.busy || hasServers)
        power.alpha = if (power.isEnabled) 1f else DISABLED_ALPHA
        power.contentDescription = context.getString(if (status.busy) R.string.action_disconnect else R.string.action_connect)
        power.setOnClickListener { if (status.busy) host.disconnect() else host.connect() }

        title.setText(
            when (status) {
                TunnelStatus.Idle -> R.string.status_off
                is TunnelStatus.Connecting -> R.string.status_connecting
                is TunnelStatus.Connected -> R.string.status_connected
                TunnelStatus.Stopping -> R.string.status_stopping
                is TunnelStatus.Failed -> R.string.status_failed
            },
        )
        detail.text = when (status) {
            is TunnelStatus.Connecting -> status.profileName?.let { context.getString(R.string.status_checking, it) }.orEmpty()
            is TunnelStatus.Connected -> context.getString(R.string.connected_since, status.profileName, time(status.sinceMillis))
            else -> ""
        }
        detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE

        val chosen = ir?.let { c -> ServerList.effectiveSelection(c.profiles, host.prefs.selectedServer)?.let { id -> c.profiles.first { it.id == id } } }
        serverValue.text = chosen?.name ?: context.getString(R.string.server_auto)
        serverHint.text = chosen?.let(ServerList::summary) ?: context.getString(R.string.server_auto_hint)
        serverCard.visibility = if (hasServers) View.VISIBLE else View.GONE

        if (status is TunnelStatus.Failed) {
            failureTitle.setText(Ui.kind(status.reason.kind))
            failureText.text = status.reason.message
            failureCard.visibility = View.VISIBLE
        } else {
            failureCard.visibility = View.GONE
        }
        emptyCard.visibility = if (graph != null && !hasServers) View.VISIBLE else View.GONE
    }

    companion object {
        const val TAB_CONNECT: Int = 0
        const val TAB_SERVERS: Int = 1
        const val TAB_SUBSCRIPTIONS: Int = 2
        const val TAB_LOG: Int = 3
        const val DISABLED_ALPHA: Float = 0.5f
    }
}

package com.dpsmpx.dppass.app.ui

import android.content.ClipboardManager
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.app.DPpassVpnService
import com.dpsmpx.dppass.app.TunnelState
import com.dpsmpx.dppass.app.TunnelStatus
import com.dpsmpx.dppass.connection.ActivationResult
import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Серверы»: серверы активной конфигурации в её порядке, выбор
 * пользователя (D-34) и здоровье каждого с запуска процесса (D-15).
 * Здесь же — своя конфигурация документом DPpass из буфера обмена.
 */
class ServersScreen(host: Host) : Screen(host) {
    private val count = Ui.text(context, R.style.Text_DPpass_Secondary)
    private val list = Ui.card(context, 0).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4)) }
    private val empty = Ui.text(context, R.style.Text_DPpass_Secondary, context.getString(R.string.servers_empty))

    override val view: View = scroll(
        Ui.column(context, 16).apply {
            addView(
                Ui.column(context, 4).apply {
                    addView(Ui.text(context, R.style.Text_DPpass_Headline, context.getString(R.string.tab_servers)))
                    addView(count)
                },
            )
            addView(list, Ui.matchWidth())
            addView(empty)
            addView(
                Ui.button(context, R.string.import_clipboard, Ui.Style.OUTLINE, R.drawable.ic_paste).apply { setOnClickListener { importClipboard() } },
                Ui.matchWidth(),
            )
        },
    )

    override fun render(status: TunnelStatus) {
        val core = host.graph?.core
        val profiles = core?.activation?.state?.activeConfig?.profiles.orEmpty()
        list.removeAllViews()
        empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        count.text = context.resources.getQuantityString(R.plurals.servers_count, profiles.size, profiles.size)
        if (core == null || profiles.isEmpty()) return
        val connected = (status as? TunnelStatus.Connected)?.profileId
        val rows = ServerList.rows(profiles, core.health::of, host.prefs.selectedServer, connected, Clock.SYSTEM.nowMillis())
        list.addView(row(null, context.getString(R.string.server_auto), context.getString(R.string.server_auto_hint), null, rows.none { it.selected }, false, status))
        for (r in rows) {
            val state = when (r.health) {
                RowHealth.UNKNOWN -> null
                RowHealth.HEALTHY -> Triple(context.getString(R.string.server_state_ok), R.color.success_container, R.color.success)
                RowHealth.FAILED -> Triple(
                    context.getString(R.string.server_state_failed, r.lastFailure?.let { context.getString(Ui.kind(it)) }.orEmpty()),
                    R.color.error_container,
                    R.color.error,
                )
                RowHealth.PAUSED -> Triple(
                    context.getString(R.string.server_state_paused, r.pauseSeconds.toInt(), r.lastFailure?.let { context.getString(Ui.kind(it)) }.orEmpty()),
                    R.color.warning_container,
                    R.color.warning,
                )
            }
            list.addView(row(r.id, r.name, r.summary, state, r.selected, r.connected, status))
        }
    }

    private fun row(
        id: ProfileId?,
        name: String,
        summary: String,
        state: Triple<String, Int, Int>?,
        selected: Boolean,
        connected: Boolean,
        status: TunnelStatus,
    ): View = Ui.row(context).apply {
        setBackgroundResource(R.drawable.bg_row)
        val p = Ui.dp(context, 12)
        setPadding(p, p, p, p)
        minimumHeight = Ui.dp(context, Ui.TOUCH_DP)
        isClickable = true
        isFocusable = true
        isSelected = selected
        addView(Ui.icon(context, if (selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off, if (selected) R.color.primary else R.color.text_secondary))
        val texts = Ui.column(context, 2)
        texts.addView(Ui.text(context, R.style.Text_DPpass_Label, name).apply { setTextColor(context.getColor(R.color.text)) })
        texts.addView(Ui.text(context, R.style.Text_DPpass_Caption, summary))
        if (state != null) {
            texts.addView(
                Ui.chip(context, state.first, state.second, state.third),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 4) },
            )
        }
        addView(texts, Ui.weight().apply { marginStart = Ui.dp(context, 12) })
        if (connected) addView(Ui.chip(context, context.getString(R.string.server_connected), R.color.primary_container, R.color.on_primary_container))
        if (selected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stateDescription = context.getString(R.string.a11y_selected)
        setOnClickListener { choose(id, status) }
    }

    /** Выбор сохраняется; если туннель есть или поднимается — переподключение к выбранному. */
    private fun choose(id: ProfileId?, status: TunnelStatus) {
        if (id == host.prefs.selectedServer) return
        host.prefs.selectedServer = id
        if (status.busy) {
            DPpassVpnService.reconnect(context)
            host.toast(context.getString(R.string.reconnecting))
        }
        host.changed()
    }

    private fun importClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        val graph = host.graph
        if (text.isNullOrBlank() || graph == null) {
            host.toast(context.getString(R.string.clipboard_empty))
            return
        }
        host.scope.launch {
            val r = withContext(Dispatchers.IO) { graph.core.importAndActivate(text) }
            val failure: Diagnostic? = when (r) {
                is Outcome.Failure -> r.errors.first()
                is Outcome.Success -> (r.value as? ActivationResult.Rejected)?.diagnostics?.firstOrNull { it.isError }
            }
            if (failure == null) {
                host.toast(context.getString(R.string.import_done))
                if (TunnelState.status.value.busy) DPpassVpnService.reconnect(context)
            } else {
                host.problem(context.getString(R.string.import_failed, context.getString(Ui.kind(failure.kind))), failure)
            }
            host.changed()
        }
    }
}

package com.dpsmpx.dppass.app.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.app.DeviceLog
import com.dpsmpx.dppass.app.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Журнал» (D-26): журнал на телефоне — включить, выключить,
 * скопировать; локальный отчёт DPpass. Наружу ничего не уходит, кроме
 * буфера обмена по действию пользователя (D-12).
 */
class LogScreen(host: Host) : Screen(host) {
    private val toggle = Switch(context).apply {
        setText(R.string.log_switch)
        setTextAppearance(R.style.Text_DPpass_Body)
        minHeight = Ui.dp(context, Ui.TOUCH_DP)
        setOnCheckedChangeListener { _, on -> if (on != DeviceLog.enabled(context)) DeviceLog.setEnabled(context, on) }
    }
    private val report = Ui.text(context, R.style.Text_DPpass_Mono).apply { setTextIsSelectable(true) }

    override val view: View = scroll(
        Ui.column(context, 16).apply {
            addView(Ui.text(context, R.style.Text_DPpass_Headline, context.getString(R.string.tab_log)))
            addView(
                Ui.card(context).apply {
                    addView(toggle, Ui.matchWidth())
                    addView(Ui.text(context, R.style.Text_DPpass_Caption, context.getString(R.string.log_note)))
                    addView(
                        Ui.button(context, R.string.copy_log, Ui.Style.TONAL, R.drawable.ic_copy).apply { setOnClickListener { copy() } },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                    )
                },
                Ui.matchWidth(),
            )
            addView(
                Ui.row(context).apply {
                    addView(Ui.text(context, R.style.Text_DPpass_Title, context.getString(R.string.report_title)), Ui.weight())
                    addView(Ui.button(context, R.string.report_refresh, Ui.Style.OUTLINE, R.drawable.ic_refresh).apply { setOnClickListener { refreshReport() } })
                },
            )
            addView(Ui.card(context).apply { addView(report, Ui.matchWidth()) }, Ui.matchWidth())
        },
    )

    override fun render(status: TunnelStatus) {
        toggle.isChecked = DeviceLog.enabled(context)
    }

    /** Отчёт собирается по запросу: он длинный и читает журнал целиком. */
    fun refreshReport() {
        val core = host.graph?.core ?: return
        host.scope.launch {
            report.text = withContext(Dispatchers.Default) { core.report().render() }
        }
    }

    private fun copy() {
        DeviceLog.copyToClipboard(context, "button") { chars ->
            context.runOnUiThread {
                host.toast(if (chars != null) context.resources.getQuantityString(R.plurals.log_copied, chars, chars) else context.getString(R.string.log_copy_failed))
            }
        }
    }
}

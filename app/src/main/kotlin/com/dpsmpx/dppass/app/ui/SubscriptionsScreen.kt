package com.dpsmpx.dppass.app.ui

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.app.DPpassVpnService
import com.dpsmpx.dppass.app.TunnelState
import com.dpsmpx.dppass.app.TunnelStatus
import com.dpsmpx.dppass.connection.ActivationResult
import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.subscriptions.UpdateResult
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Подписки» (D-36): список, добавление, обновление с применением,
 * идентификатор устройства только по согласию (D-33), удаление.
 * Обновление — вручную; применённая подписка, пока туннель есть,
 * переподключает его с новыми серверами.
 */
class SubscriptionsScreen(host: Host) : Screen(host) {
    private val list = Ui.column(context, 16)
    private val empty = Ui.text(context, R.style.Text_DPpass_Secondary, context.getString(R.string.subs_empty))

    /** Подписки, которые сейчас загружаются: кнопки их карточек выключены. */
    private val running = HashSet<SubscriptionId>()

    override val view: View = scroll(
        Ui.column(context, 16).apply {
            addView(
                Ui.row(context).apply {
                    addView(Ui.text(context, R.style.Text_DPpass_Headline, context.getString(R.string.tab_subscriptions)), Ui.weight())
                    addView(Ui.button(context, R.string.subs_add, Ui.Style.TONAL, R.drawable.ic_add).apply { setOnClickListener { add() } })
                },
            )
            addView(empty)
            addView(list, Ui.matchWidth())
        },
    )

    override fun render(status: TunnelStatus) {
        val core = host.graph?.core ?: return
        val state = when (val s = core.subscriptions.state()) {
            is Outcome.Success -> s.value
            is Outcome.Failure -> {
                list.removeAllViews()
                empty.text = context.getString(Ui.kind(s.errors.first().kind))
                empty.visibility = View.VISIBLE
                return
            }
        }
        val rows = SubscriptionList.rows(state, ConfigSource.of(core.activation.state.active?.source), Clock.SYSTEM.nowMillis())
        empty.setText(R.string.subs_empty)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        rows.forEach { list.addView(card(it), Ui.matchWidth()) }
    }

    private fun card(r: SubscriptionRow): View = Ui.card(context).apply {
        val head = Ui.row(context)
        head.addView(Ui.text(context, R.style.Text_DPpass_Title, r.name), Ui.weight())
        if (r.active) head.addView(Ui.chip(context, context.getString(R.string.subs_active), R.color.success_container, R.color.success))
        addView(head)

        val updated = r.updatedAtMillis?.let { at ->
            val servers = r.profileCount?.let { n -> context.resources.getQuantityString(R.plurals.servers_count, n, n) }
            listOfNotNull(context.getString(R.string.subs_updated, dateTime(at)), servers).joinToString(ServerList.SEPARATOR)
        } ?: context.getString(R.string.subs_never)
        addView(Ui.text(context, R.style.Text_DPpass_Secondary, updated))
        r.usedBytes?.let { used ->
            val text = r.totalBytes?.let { total -> context.getString(R.string.subs_traffic, bytes(used), bytes(total)) }
                ?: context.getString(R.string.subs_traffic_unlimited, bytes(used))
            addView(Ui.text(context, R.style.Text_DPpass_Secondary, text))
        }
        r.expireMillis?.let { at ->
            val text = context.getString(if (r.expired) R.string.subs_expired else R.string.subs_expires, date(at))
            addView(Ui.text(context, R.style.Text_DPpass_Secondary, text).apply { if (r.expired) setTextColor(context.getColor(R.color.error)) })
        }
        r.failure?.let { f ->
            addView(
                Ui.text(context, R.style.Text_DPpass_Secondary, context.getString(R.string.subs_failed, context.getString(Ui.kind(f.kind)))).apply {
                    setTextColor(context.getColor(R.color.error))
                },
            )
            addView(Ui.text(context, R.style.Text_DPpass_Caption, f.message).apply { setTextIsSelectable(true) })
            if (f.code == DiagCode.SUBSCRIPTION_DEVICE_ID_REQUIRED && !r.deviceIdEnabled) {
                addView(Ui.text(context, R.style.Text_DPpass_Secondary, context.getString(R.string.subs_device_id_needed)))
            }
        }
        r.announce?.let { addView(Ui.text(context, R.style.Text_DPpass_Caption, context.getString(R.string.subs_announce, it))) }

        // Идентификатор устройства — только по согласию (D-33).
        addView(
            Switch(context).apply {
                setText(R.string.subs_device_id)
                setTextAppearance(R.style.Text_DPpass_Body)
                minHeight = Ui.dp(context, Ui.TOUCH_DP)
                isChecked = r.deviceIdEnabled
                setOnCheckedChangeListener { _, on -> edit { host.graph?.core?.subscriptions?.setDeviceId(r.id, on) } }
            },
            Ui.matchWidth(),
        )
        addView(Ui.text(context, R.style.Text_DPpass_Caption, context.getString(R.string.subs_device_id_note)))
        if (r.deviceIdEnabled) {
            addView(
                Ui.button(context, R.string.subs_device_id_reset, Ui.Style.OUTLINE).apply {
                    setOnClickListener {
                        confirm(context.getString(R.string.subs_device_id_reset_confirm)) {
                            edit { host.graph?.core?.subscriptions?.resetDeviceId(r.id) }
                        }
                    }
                },
            )
        }

        val busy = r.id in running
        val actions = Ui.row(context)
        actions.addView(
            Ui.button(context, if (busy) R.string.subs_refreshing else R.string.subs_refresh, Ui.Style.PRIMARY, R.drawable.ic_refresh).apply {
                isEnabled = !busy
                setOnClickListener { refresh(r.id, r.name) }
            },
            Ui.weight(),
        )
        actions.addView(
            Ui.button(context, R.string.subs_remove, Ui.Style.OUTLINE, R.drawable.ic_delete).apply {
                isEnabled = !busy
                setOnClickListener {
                    confirm(context.getString(R.string.subs_remove_confirm, r.name)) { edit { host.graph?.core?.subscriptions?.remove(r.id) } }
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(context, 8)
            },
        )
        addView(actions, Ui.matchWidth().apply { topMargin = Ui.dp(context, 8) })
    }

    private fun bytes(n: Long): String =
        Formats.bytes(n, context.resources.getStringArray(R.array.units_bytes).toList(), context.resources.configuration.locales[0])

    private fun date(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, context.resources.configuration.locales[0]).format(Date(millis))

    private fun add() {
        val url = EditText(context).apply {
            setHint(R.string.subs_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val name = EditText(context).apply {
            setHint(R.string.subs_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val form = Ui.column(context, 8).apply {
            val p = Ui.dp(context, 24)
            setPadding(p, Ui.dp(context, 8), p, 0)
            addView(url, Ui.matchWidth())
            addView(name, Ui.matchWidth())
            addView(Ui.text(context, R.style.Text_DPpass_Caption, context.getString(R.string.subs_dialog_note)))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.subs_dialog_title)
            .setView(form)
            .setPositiveButton(R.string.subs_add) { _, _ ->
                val core = host.graph?.core ?: return@setPositiveButton
                when (val r = core.subscriptions.add(url.text.toString(), name.text.toString())) {
                    is Outcome.Failure -> host.problem(context.getString(Ui.kind(r.errors.first().kind)), r.errors.first())
                    // Добавленная подписка сразу загружается и применяется: пустой подписке не из чего выбирать.
                    is Outcome.Success -> refresh(r.value.id, r.value.name)
                }
                host.changed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh(id: SubscriptionId, name: String) {
        val graph = host.graph ?: return
        if (!running.add(id)) return
        host.changed()
        host.scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { graph.core.subscriptions.refresh(id, graph.fetcher, graph.appVersion) }
            } finally {
                running.remove(id)
            }
            when (r) {
                is Outcome.Failure -> host.problem(context.getString(Ui.kind(r.errors.first().kind)), r.errors.first())
                is Outcome.Success -> {
                    val run = r.value
                    val activation = run.activation
                    when {
                        run.applied -> {
                            host.toast(context.getString(R.string.subs_applied, name))
                            if (TunnelState.status.value.busy) DPpassVpnService.reconnect(context)
                        }
                        activation is ActivationResult.Rejected -> activation.diagnostics.firstOrNull { it.isError }?.let {
                            host.problem(context.getString(R.string.subs_not_applied, context.getString(Ui.kind(it.kind))), it)
                        }
                        run.update is UpdateResult.Failed -> run.update.diagnostics.firstOrNull { it.isError }?.let {
                            host.toast(context.getString(R.string.subs_not_applied, context.getString(Ui.kind(it.kind))))
                        }
                        else -> host.toast(context.getString(R.string.subs_unchanged))
                    }
                }
            }
            host.changed()
        }
    }

    /** Правка, которая сохраняется сразу (D-36); отказ хранилища — с причиной. */
    private fun edit(change: () -> Outcome<*>?) {
        when (val r = change()) {
            is Outcome.Failure -> host.problem(context.getString(Ui.kind(r.errors.first().kind)), r.errors.first())
            else -> Unit
        }
        host.changed()
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

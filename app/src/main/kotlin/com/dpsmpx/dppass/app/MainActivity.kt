package com.dpsmpx.dppass.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.connection.ActivationResult
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.routing.RouteQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Оболочка разработки: показывает, что конвейер DPpass работает на
 * Android — разбор примера, активация на ядре Xray (проверка без запуска), TunnelSpec,
 * решение маршрутизации, локальный отчёт. Интерфейс продукта — этап 4
 * `ROADMAP` (фреймворк UI — отложенное решение).
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Экран может показать конфигурацию — снимки экрана запрещены (docs/SECURITY_MODEL.md, T9).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        output = TextView(this).apply { setTextIsSelectable(true) }
        val start = Button(this).apply {
            setText(R.string.start_vpn)
            setOnClickListener { requestVpn() }
        }
        val title = TextView(this).apply { setText(R.string.shell_title) }
        val note = TextView(this).apply { setText(R.string.shell_note) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(note)
            addView(start)
            addView(output)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        scope.launch { runShell() }
    }

    private suspend fun runShell() {
        val core = AppGraph.core(this)
        val restored = core.restore()
        val sample = assets.open("sample/config.json").bufferedReader().use { it.readText() }
        val lines = mutableListOf<String>()
        lines += "restore: ${if (restored.isEmpty()) "ok" else restored.joinToString { it.code.name }}"
        when (val r = core.importAndActivate(sample)) {
            is Outcome.Failure -> lines += "sample rejected: ${r.errors.joinToString { it.code.name }}"
            is Outcome.Success -> lines += when (val a = r.value) {
                is ActivationResult.Activated -> "activated revision ${a.record.revision}, profile ${a.record.profileId}"
                is ActivationResult.Rejected -> "rejected at ${a.record.failedStep}"
            }
        }
        core.tunnelSpec()?.valueOrNull()?.let { lines += "tunnel spec: ${it.routes.size} routes, ${it.excludedRoutes.size} excluded, mtu ${it.mtu}" }
        core.explain(RouteQuery(domain = "ads.example.com"))?.let { lines += "route ads.example.com → ${it.action} (${it.reason})" }
        lines += ""
        lines += core.report().render()
        runOnUiThread { output.text = lines.joinToString("\n") }
    }

    private fun requestVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION") // без AndroidX нет Activity Result API; замена — вместе с выбором UI (этап 4)
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            startService(Intent(this, DPpassVpnService::class.java))
        }
    }

    @Deprecated("Activity Result API — вместе с выбором UI (этап 4)")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) startService(Intent(this, DPpassVpnService::class.java))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_VPN = 1
    }
}

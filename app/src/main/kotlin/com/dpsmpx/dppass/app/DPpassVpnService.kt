package com.dpsmpx.dppass.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Safe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Сервис туннеля (D-17). Жизненный цикл уже настоящий: foreground с
 * типом `specialUse`, уведомление, `onRevoke`, остановка. Туннель не
 * поднимается, пока ядро фиктивное: TUN без ядра «съел» бы весь трафик
 * устройства. Сервис это проверяет и честно останавливается с
 * BACKEND_NOT_AVAILABLE — граница готова для этапа 2 (Xray).
 */
class DPpassVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val core = AppGraph.core(this)
        val spec = core.tunnelSpec()?.valueOrNull()
        if (spec == null) {
            core.log.diagnostic(Diagnostic.error(DiagCode.PROFILES_EMPTY, "нет активной конфигурации — туннель не поднимается"))
            stopSelf()
            return
        }
        if (core.engine.capabilities.backendId.startsWith("mock")) {
            core.log.diagnostic(
                Diagnostic.error(DiagCode.BACKEND_NOT_AVAILABLE, "реального ядра ещё нет (этап 2): TUN без ядра отрезал бы устройство от сети"),
            )
            core.log.info("tunnel_spec_ready", "routes" to spec.routes.size, "apps" to Safe(spec.apps::class.simpleName ?: "?"))
            stopSelf()
            return
        }
        // Этап 2 (ROADMAP): AndroidTunnelProvider(this).establish(spec) → TunInput(fd, mtu) →
        // core.connect(...). До интеграции ядра этот путь недостижим.
        core.log.diagnostic(Diagnostic.error(DiagCode.BACKEND_NOT_AVAILABLE, "интеграция ядра ещё не сделана"))
        stopSelf()
    }

    override fun onRevoke() {
        // Пользователь отозвал разрешение или включил другой VPN.
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private companion object {
        const val CHANNEL = "tunnel"
        const val NOTIFICATION_ID = 1
    }
}

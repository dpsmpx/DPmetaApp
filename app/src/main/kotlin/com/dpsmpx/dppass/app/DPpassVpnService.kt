package com.dpsmpx.dppass.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.connection.ConnectionReport
import com.dpsmpx.dppass.connection.HealthChecker
import com.dpsmpx.dppass.connection.toTunInput
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Safe
import com.dpsmpx.dppass.engine.EngineSession
import com.dpsmpx.dppass.tunnel.TunnelHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Сервис туннеля в процессе приложения (D-17, D-30): foreground с типом
 * `specialUse`, TUN по `TunnelSpec`, ядро Xray на его fd.
 *
 * Запуск: активная конфигурация → `AndroidTunnelProvider.establish` →
 * `toTunInput()` → `DPpassCore.connect`. Кандидаты перебираются по плану
 * (D-15), сервер, выбранный пользователем, — первым (D-34); каждый
 * проверяется по полезной нагрузке — запросом DNS через сервер профиля к
 * DNS-серверу конфигурации (D-32). Ни один не прошёл — TUN закрывается и
 * сервис останавливается с причиной: TUN без ядра «съел» бы весь трафик
 * устройства.
 *
 * Состояние для интерфейса — [TunnelState]; уведомление показывает его и
 * кнопку «Отключить».
 *
 * Остановка — сначала ядро, потом fd TUN (fd принадлежит платформе, D-25):
 * действием [ACTION_STOP], при `onRevoke` (пользователь отозвал VPN или
 * включил другой) и в `onDestroy`. [ACTION_RECONNECT] — остановка и новый
 * запуск: другой выбранный сервер, новая конфигурация.
 */
class DPpassVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Состояние туннеля меняется только под этим замком: запуск и остановка не пересекаются. */
    private val state = Mutex()
    private var connecting: Job? = null
    private var session: EngineSession? = null
    private var tunnel: TunnelHandle? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DeviceLog.log.info("service_stop_requested")
                TunnelState.set(TunnelStatus.Stopping)
                scope.launch {
                    shutdown()
                    TunnelState.set(TunnelStatus.Idle)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                DeviceLog.log.info("service_reconnect")
                startInForeground(TunnelStatus.Connecting(null))
                scope.launch {
                    shutdown()
                    start()
                }
                return START_NOT_STICKY
            }
        }
        DeviceLog.log.info("service_start")
        startInForeground(TunnelStatus.Connecting(null))
        scope.launch { start() }
        return START_NOT_STICKY
    }

    private suspend fun start() {
        state.withLock {
            if (connecting?.isActive == true || session != null) {
                DeviceLog.log.info("service_already_running")
                return
            }
            TunnelState.set(TunnelStatus.Connecting(null))
            connecting = scope.launch { connect() }
        }
    }

    private suspend fun connect() {
        val graph = AppGraph.graph(this)
        val core = graph.core
        if (core.activation.state.activeConfig == null) core.restore()
        val ir = core.activation.state.activeConfig
        val spec = core.tunnelSpec()?.valueOrNull()
        if (ir == null || spec == null) {
            fail(Diagnostic.error(DiagCode.PROFILES_EMPTY, "нет активной конфигурации — туннель не поднимается"))
            return
        }
        val handle = when (val t = AndroidTunnelProvider(this).establish(spec)) {
            is Outcome.Failure -> {
                t.errors.drop(1).forEach(core.log::diagnostic)
                fail(t.errors.first())
                return
            }
            is Outcome.Success -> t.value
        }
        core.log.info("tunnel_up", "routes" to spec.routes.size, "apps" to Safe(spec.apps::class.simpleName ?: "?"))
        // Проба здоровья (D-32) блокирует поток — не дольше своего срока, меньше срока плана.
        val probeTimeout = (core.policy.healthTimeoutMillis - PROBE_MARGIN_MILLIS).toInt()
        val checker = HealthChecker { _, profile ->
            progress(TunnelStatus.Connecting(profile.name))
            withContext(Dispatchers.IO) { graph.engine.probeHealth(ir.dns, probeTimeout) }
        }
        val preferred = AppPrefs(this).selectedServer
        val job = currentCoroutineContext().job
        val report = try {
            core.connect(null, checker, handle.toTunInput(), preferred) { delay(it) }
        } catch (e: CancellationException) {
            // Остановка во время паузы между попытками: сеансов нет, остаётся TUN.
            handle.close()
            throw e
        }
        when (report) {
            is ConnectionReport.Connected -> withContext(NonCancellable) {
                // Вызовы ядра не прерываются отменой: остановка могла прийти, пока
                // шёл запуск. Тогда сеанс не принимается, а сразу останавливается.
                val accepted = state.withLock {
                    if (job.isCancelled) {
                        false
                    } else {
                        session = report.session
                        tunnel = handle
                        true
                    }
                }
                if (accepted) {
                    core.log.info("connected", "profile" to Safe(report.profileId.value), "attempts" to report.attempts.size)
                    val name = ir.profiles.firstOrNull { it.id == report.profileId }?.name ?: report.profileId.value
                    progress(TunnelStatus.Connected(report.profileId, name, core.clock.nowMillis()))
                } else {
                    report.session.stop()
                    handle.close()
                }
            }
            is ConnectionReport.Failed -> {
                handle.close()
                fail(report.reason)
            }
            null -> {
                handle.close()
                fail(Diagnostic.error(DiagCode.PROFILES_EMPTY, "нет активной конфигурации — подключаться не к чему"))
            }
        }
    }

    /** Отказ запуска: причина — в журнал и на экран, сервис уходит. */
    private fun fail(reason: Diagnostic) {
        AppGraph.core(this).log.diagnostic(reason)
        TunnelState.set(TunnelStatus.Failed(reason))
        stopSelf()
    }

    /** Новое состояние подключения — и в уведомление: его видно и без экрана. */
    private fun progress(status: TunnelStatus) {
        TunnelState.set(status)
        startInForeground(status)
    }

    /** Ядро — потом TUN; повторный вызов ничего не делает. */
    private suspend fun shutdown() {
        state.withLock {
            connecting?.cancel()
            connecting = null
            session?.stop()
            session = null
            tunnel?.close()
            tunnel = null
        }
    }

    override fun onRevoke() {
        // Пользователь отозвал разрешение или включил другой VPN.
        DeviceLog.log.info("service_revoked")
        scope.launch {
            shutdown()
            TunnelState.set(TunnelStatus.Idle)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Сервис уходит: уборка — в области процесса, она переживёт этот объект.
        AppGraph.scope.launch { shutdown() }
        // Отказ остаётся на экране; всё остальное без сервиса — «не подключено».
        if (TunnelState.status.value !is TunnelStatus.Failed) TunnelState.set(TunnelStatus.Idle)
        // Сервис остановлен — работа закончена: журнал в буфер обмена (D-26).
        DeviceLog.onWorkFinished(this, "service_stop")
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(status: TunnelStatus) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val text = when (status) {
            is TunnelStatus.Connected -> getString(R.string.notification_connected, status.profileName)
            is TunnelStatus.Connecting -> status.profileName?.let { getString(R.string.notification_checking, it) }
                ?: getString(R.string.notification_connecting)
            else -> getString(R.string.notification_connecting)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DPpassVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_power), getString(R.string.action_disconnect), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP: String = "com.dpsmpx.dppass.action.STOP"
        const val ACTION_RECONNECT: String = "com.dpsmpx.dppass.action.RECONNECT"
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION_ID = 1
        private const val PROBE_MARGIN_MILLIS = 2_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DPpassVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DPpassVpnService::class.java).setAction(ACTION_STOP))
        }

        fun reconnect(context: Context) {
            context.startForegroundService(Intent(context, DPpassVpnService::class.java).setAction(ACTION_RECONNECT))
        }
    }
}

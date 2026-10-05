package com.dpsmpx.dppass.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.connection.ConnectionReport
import com.dpsmpx.dppass.connection.StabilityContext
import com.dpsmpx.dppass.connection.StabilitySample
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
import kotlinx.coroutines.isActive
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
 * (D-15); каждый проверяется по полезной нагрузке — запросом DNS через
 * сервер профиля к DNS-серверу конфигурации (D-32). Ни один не прошёл —
 * TUN закрывается и сервис останавливается с причиной в журнале: TUN без
 * ядра «съел» бы весь трафик устройства.
 *
 * Остановка — сначала ядро, потом fd TUN (fd принадлежит платформе, D-25):
 * действием [ACTION_STOP], при `onRevoke` (пользователь отозвал VPN или
 * включил другой) и в `onDestroy`.
 */
class DPpassVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Состояние туннеля меняется только под этим замком: запуск и остановка не пересекаются. */
    private val state = Mutex()
    private var connecting: Job? = null
    private var session: EngineSession? = null
    private var tunnel: TunnelHandle? = null
    private var supervisor: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DeviceLog.log.info("service_stop_requested")
            scope.launch {
                shutdown()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        DeviceLog.log.info("service_start")
        startInForeground(getString(R.string.notification_connecting))
        scope.launch {
            state.withLock {
                if (connecting?.isActive == true || session != null) {
                    DeviceLog.log.info("service_already_running")
                    return@launch
                }
                connecting = scope.launch { connect() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun connect() {
        val graph = AppGraph.graph(this)
        val core = graph.core
        if (core.activation.state.activeConfig == null) core.restore()
        val ir = core.activation.state.activeConfig
        val spec = core.tunnelSpec()?.valueOrNull()
        if (ir == null || spec == null) {
            core.log.diagnostic(Diagnostic.error(DiagCode.PROFILES_EMPTY, "нет активной конфигурации — туннель не поднимается"))
            stopSelf()
            return
        }
        val handle = when (val t = AndroidTunnelProvider(this).establish(spec)) {
            is Outcome.Failure -> {
                t.errors.forEach(core.log::diagnostic)
                stopSelf()
                return
            }
            is Outcome.Success -> t.value
        }
        core.log.info("tunnel_up", "routes" to spec.routes.size, "apps" to Safe(spec.apps::class.simpleName ?: "?"))
        // Проба здоровья (D-32) блокирует поток — не дольше своего срока, меньше срока плана.
        val probeTimeout = (core.policy.healthTimeoutMillis - PROBE_MARGIN_MILLIS).toInt()
        val checker = HealthChecker { _, _ -> withContext(Dispatchers.IO) { graph.engine.probeHealth(ir.dns, probeTimeout) } }
        val job = currentCoroutineContext().job
        val report = try {
            core.connect(null, checker, handle.toTunInput()) { delay(it) }
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
                    startInForeground(getString(R.string.notification_connected))
                    supervisor?.cancel()
                    supervisor = scope.launch { superviseSession(report.session, handle, report.profileId, ir.dns) }
                } else {
                    report.session.stop()
                    handle.close()
                }
            }
            is ConnectionReport.Failed -> {
                core.log.diagnostic(report.reason)
                handle.close()
                stopSelf()
            }
            null -> {
                core.log.diagnostic(Diagnostic.error(DiagCode.PROFILES_EMPTY, "нет активной конфигурации — подключаться не к чему"))
                handle.close()
                stopSelf()
            }
        }
    }

    /** Ядро — потом TUN; повторный вызов ничего не делает. */
    private suspend fun shutdown() {
        supervisor?.cancel()
        supervisor = null
        state.withLock {
            connecting?.cancel()
            connecting = null
            session?.stop()
            session = null
            tunnel?.close()
            tunnel = null
        }
        AppGraph.graph(this).core.stability.persist()
    }

    /**
     * Long-lived stability loop (0.5.0): measure useful-path RTT, derive jitter
     * from consecutive samples, learn the profile, and reconnect only after
     * hysteresis confirms real degradation. No packet payload or destination is stored.
     */
    private suspend fun superviseSession(
        expectedSession: EngineSession,
        expectedTunnel: TunnelHandle,
        profileId: com.dpsmpx.dppass.core.ProfileId,
        dns: com.dpsmpx.dppass.routing.DnsPolicy,
    ) {
        val graph = AppGraph.graph(this)
        var samples = 0
        while (currentCoroutineContext().isActive) {
            delay(SUPERVISION_INTERVAL_MILLIS)
            val stillCurrent = state.withLock { session === expectedSession && tunnel === expectedTunnel }
            if (!stillCurrent) return
            val started = SystemClock.elapsedRealtime()
            val result = graph.engine.probeHealth(dns, SUPERVISION_TIMEOUT_MILLIS.toInt())
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceIn(1L, SUPERVISION_TIMEOUT_MILLIS + 2_000L)
            val failed = result is Outcome.Failure
            graph.core.stability.observe(
                profileId,
                StabilitySample(
                    success = !failed,
                    rttMillis = elapsed,
                    stalled = failed && result.errors.any { it.code == DiagCode.PAYLOAD_STALLED },
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
            samples++
            if (samples % PERSIST_EVERY_SAMPLES == 0) graph.core.stability.persist()
            if (failed) {
                graph.core.log.warning("adaptive_probe_failed", "profile" to Safe(profileId.value), "elapsed_ms" to elapsed)
            }
            if (graph.core.stability.shouldRecover(profileId, currentNetworkContext())) {
                graph.core.log.warning("adaptive_recovery", "profile" to Safe(profileId.value))
                val removed = state.withLock {
                    if (session === expectedSession && tunnel === expectedTunnel) {
                        session = null
                        tunnel = null
                        true
                    } else false
                }
                if (!removed) return
                expectedSession.stop()
                expectedTunnel.close()
                graph.core.stability.persist()
                startConnection()
                return
            }
        }
    }

    /**
     * Obtain a coarse non-VPN transport context. Android exposes the VPN itself
     * as a network, so the learner deliberately ignores TRANSPORT_VPN and uses
     * the underlying transport when available. No SSID, BSSID, address or
     * destination is persisted.
     */
    private fun currentNetworkContext(): StabilityContext {
        val cm = getSystemService(ConnectivityManager::class.java)
        val networks = runCatching { cm.allNetworks.asList() }.getOrDefault(emptyList())
        val candidate = networks.asSequence()
            .mapNotNull { network ->
                cm.getNetworkCapabilities(network)?.takeIf { caps ->
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }?.let { network to it }
            }
            .firstOrNull { (_, caps) ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        val caps = candidate?.second
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
            else -> "UNKNOWN"
        }
        return StabilityContext(
            network = type,
            restriction = com.dpsmpx.dppass.network.RestrictionClass.UNKNOWN,
            tls = com.dpsmpx.dppass.network.Reachability.UNKNOWN,
            udp = com.dpsmpx.dppass.network.Reachability.UNKNOWN,
            bulk = com.dpsmpx.dppass.network.Reachability.UNKNOWN,
        )
    }

    /** Starts one new connection attempt only when no session/attempt is active. */
    private fun startConnection() {
        scope.launch {
            state.withLock {
                if (connecting?.isActive == true || session != null) return@withLock
                connecting = scope.launch { connect() }
            }
        }
    }

    override fun onRevoke() {
        // Пользователь отозвал разрешение или включил другой VPN.
        DeviceLog.log.info("service_revoked")
        scope.launch {
            shutdown()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Сервис уходит: уборка — в области процесса, она переживёт этот объект.
        AppGraph.scope.launch { shutdown() }
        // Сервис остановлен — фиксируем завершение локально; внешней передачи журнала нет.
        DeviceLog.onWorkFinished(this, "service_stop")
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP: String = "com.dpsmpx.dppass.action.STOP"
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION_ID = 1
        private const val PROBE_MARGIN_MILLIS = 2_000L
        private const val SUPERVISION_INTERVAL_MILLIS = 15_000L
        private const val SUPERVISION_TIMEOUT_MILLIS = 5_000L
        private const val PERSIST_EVERY_SAMPLES = 4

        fun stop(context: Context) {
            context.startService(Intent(context, DPpassVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

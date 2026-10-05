package com.dpsmpx.dppass.app

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.service.quicksettings.TileService
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.app.ui.Formats
import com.dpsmpx.dppass.connection.ConnectionReport
import com.dpsmpx.dppass.connection.HealthChecker
import com.dpsmpx.dppass.connection.toTunInput
import com.dpsmpx.dppass.core.Clock
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
import kotlinx.coroutines.isActive
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
 * Жизненный цикл (D-40): пока туннель нужен — `START_STICKY`, и после
 * гибели процесса система запускает сервис заново без интента; always-on
 * VPN запускает его действием `android.net.VpnService`. В обоих случаях
 * поднимается последняя активная конфигурация (D-07). Остановка
 * пользователем — `START_NOT_STICKY`.
 *
 * Пока туннель поднят: сеть под ним — `setUnderlyingNetworks` по сети по
 * умолчанию (система и приложения видят её тип и тарификацию); счётчики
 * ядра раз в секунду → [TunnelState.traffic], уведомление — раз в
 * [NOTIFY_EVERY] секунды и только при включённом экране.
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
    private var ticker: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DeviceLog.log.info("service_stop_requested")
                TunnelState.set(TunnelStatus.Stopping)
                announce()
                scope.launch {
                    shutdown()
                    TunnelState.set(TunnelStatus.Idle)
                    announce()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                DeviceLog.log.info("service_reconnect")
                if (!startInForeground(TunnelStatus.Connecting(null))) return START_NOT_STICKY
                scope.launch {
                    shutdown()
                    start()
                }
                return START_STICKY
            }
        }
        // Свой запуск, always-on (действие VpnService) или перезапуск после гибели процесса (интента нет).
        DeviceLog.log.info("service_start", "by" to Safe(if (intent == null) "restart" else intent.action ?: "app"))
        if (!startInForeground(TunnelStatus.Connecting(null))) return START_NOT_STICKY
        scope.launch { start() }
        return START_STICKY
    }

    private suspend fun start() {
        state.withLock {
            if (connecting?.isActive == true || session != null) {
                DeviceLog.log.info("service_already_running")
                return
            }
            TunnelState.set(TunnelStatus.Connecting(null))
            announce()
            connecting = scope.launch { connect() }
        }
    }

    private suspend fun connect() {
        val graph = AppGraph.graph(this)
        val core = graph.core
        if (core.activation.state.activeConfig == null) core.restore()
        val ir = core.activation.state.activeConfig
        val spec = core.tunnelSpec()
        if (ir == null || spec == null) {
            fail(Diagnostic.error(DiagCode.PROFILES_EMPTY, "нет активной конфигурации — туннель не поднимается"))
            return
        }
        val tunSpec = when (spec) {
            is Outcome.Failure -> {
                fail(spec.errors.first())
                return
            }
            is Outcome.Success -> spec.value
        }
        val handle = when (val t = AndroidTunnelProvider(this).establish(tunSpec)) {
            is Outcome.Failure -> {
                t.errors.drop(1).forEach(core.log::diagnostic)
                fail(t.errors.first())
                return
            }
            is Outcome.Success -> t.value
        }
        core.log.info("tunnel_up", "routes" to tunSpec.routes.size, "apps" to Safe(tunSpec.apps::class.simpleName ?: "?"))
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
                    progress(TunnelStatus.Connected(report.profileId, name, core.clock.nowMillis(), Clock.MONOTONIC.nowMillis()))
                    watchNetwork()
                    startTicker(report.session)
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

    /**
     * Счётчики ядра → скорость и итоги (D-40). Раз в секунду, пока экран
     * включён; уведомление — реже: каждое обновление стоит батареи.
     */
    private fun startTicker(session: EngineSession) {
        ticker?.cancel()
        ticker = scope.launch {
            val meter = TrafficMeter()
            val power = getSystemService(PowerManager::class.java)
            var tick = 0
            while (isActive) {
                if (power.isInteractive) {
                    val traffic = meter.sample(session.stats(), Clock.MONOTONIC.nowMillis())
                    TunnelState.traffic(traffic)
                    if (tick % NOTIFY_EVERY == 0) (TunnelState.status.value as? TunnelStatus.Connected)?.let { startInForeground(it, traffic) }
                    tick++
                } else {
                    // Экран выключен: никто не смотрит — отсчёт скорости заново после включения.
                    meter.reset()
                }
                delay(1_000)
            }
        }
    }

    /** Сеть под туннелем — сеть по умолчанию (D-40): её тип и тарификацию видят система и приложения. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
                DeviceLog.log.info("underlying_network", "state" to Safe("available"))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
                DeviceLog.log.info("underlying_network", "state" to Safe("lost"))
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // Слишком много колбэков у процесса или нет разрешения — туннель работает и без этого.
            DeviceLog.log.diagnostic(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "сеть под туннелем не отслеживается", e))
        }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // Уже снят.
        }
    }

    /** Отказ запуска: причина — в журнал и на экран, сервис уходит. */
    private fun fail(reason: Diagnostic) {
        AppGraph.core(this).log.diagnostic(reason)
        TunnelState.set(TunnelStatus.Failed(reason))
        announce()
        stopSelf()
    }

    /** Новое состояние подключения — и в уведомление: его видно и без экрана. */
    private fun progress(status: TunnelStatus) {
        TunnelState.set(status)
        announce()
        startInForeground(status)
    }

    /** Ядро — потом TUN; повторный вызов ничего не делает. */
    private suspend fun shutdown() {
        state.withLock {
            ticker?.cancel()
            ticker = null
            unwatchNetwork()
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
            announce()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Сервис уходит: уборка — в области процесса, она переживёт этот объект.
        AppGraph.scope.launch { shutdown() }
        // Отказ остаётся на экране; всё остальное без сервиса — «не подключено».
        if (TunnelState.status.value !is TunnelStatus.Failed) TunnelState.set(TunnelStatus.Idle)
        announce()
        // Сервис остановлен — работа закончена: журнал в буфер обмена (D-26).
        DeviceLog.onWorkFinished(this, "service_stop")
        scope.cancel()
        super.onDestroy()
    }

    /** Плитка быстрых настроек показывает состояние, только пока её слушают: попросить обновиться. */
    private fun announce() {
        try {
            TileService.requestListeningState(this, ComponentName(this, DPpassTileService::class.java))
        } catch (e: RuntimeException) {
            // Плитки нет в шторке — некому показывать.
        }
    }

    /**
     * Уведомление foreground-сервиса. Ложь — система не дала стать
     * foreground (Android 12+ из фона); тогда сервис уходит с причиной.
     */
    private fun startInForeground(status: TunnelStatus, traffic: Traffic? = null): Boolean {
        val n = notification(status, traffic)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                DeviceLog.log.diagnostic(Diagnostic.fromThrowable(DiagCode.TUN_ESTABLISH_FAILED, "система не разрешила сервису туннеля работать из фона", e))
                TunnelState.set(TunnelStatus.Failed(Diagnostic.error(DiagCode.TUN_ESTABLISH_FAILED, "система не разрешила запуск туннеля из фона")))
                stopSelf()
                return false
            }
            throw e
        }
        return true
    }

    private fun notification(status: TunnelStatus, traffic: Traffic?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val units = resources.getStringArray(R.array.units_bytes).toList()
        val locale = resources.configuration.locales[0]
        val text = when (status) {
            is TunnelStatus.Connected -> if (traffic != null && (traffic.downPerSec > 0 || traffic.upPerSec > 0)) {
                getString(
                    R.string.notification_connected_traffic,
                    status.profileName,
                    Formats.speed(traffic.downPerSec, units, locale, getString(R.string.per_second)),
                    Formats.speed(traffic.upPerSec, units, locale, getString(R.string.per_second)),
                )
            } else {
                getString(R.string.notification_connected, status.profileName)
            }
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
        // На заблокированном экране — только «DPpass подключён», без имени сервера (T9):
        // имя часто называет страну и поставщика.
        val public = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (status is TunnelStatus.Connected) R.string.notification_public_connected else R.string.notification_connecting))
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .build()
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(if (status is TunnelStatus.Connected) R.string.notification_title_connected else R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_power), getString(R.string.action_disconnect), stop).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (status is TunnelStatus.Connected) {
            // Время с подключения считает сама шторка — без обновлений уведомления.
            b.setWhen(status.sinceMillis).setShowWhen(true).setUsesChronometer(true)
        } else {
            b.setShowWhen(false)
        }
        return b.build()
    }

    companion object {
        const val ACTION_STOP: String = "com.dpsmpx.dppass.action.STOP"
        const val ACTION_RECONNECT: String = "com.dpsmpx.dppass.action.RECONNECT"
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION_ID = 1
        private const val PROBE_MARGIN_MILLIS = 2_000L

        /** Уведомление со скоростью — раз в столько секунд (только при включённом экране). */
        private const val NOTIFY_EVERY = 2

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

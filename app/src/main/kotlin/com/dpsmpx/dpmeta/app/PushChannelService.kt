package com.dpsmpx.dpmeta.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.DisplayZone
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.data.PultAlerts
import com.dpsmpx.dpmeta.app.data.PushPrefs
import com.dpsmpx.dpmeta.net.AllowList
import com.dpsmpx.dpmeta.net.HttpRequest
import com.dpsmpx.dpmeta.net.PushSignature
import com.dpsmpx.dpmeta.net.UrlTransport
import com.dpsmpx.dpmeta.notify.PushChannel
import com.dpsmpx.dpmeta.notify.PushEvents
import com.dpsmpx.dpmeta.notify.SeenIds
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Мгновенный канал (D-152; F-55): служба переднего плана держит соединение с темой
 * ntfy, пока канал включён (по умолчанию выключен — заряд). Событие проходит
 * подпись, срок и повтор ([PushEvents.verify]) и идёт в те же правила и уведомления,
 * что и события синхронизации. Тип службы — `specialUse`: стандартные не подходят
 * (`dataSync` Android 15 ограничивает шестью часами в сутки и не даёт запускать при
 * загрузке).
 */
class PushChannelService : Service() {
    private var loop: Loop? = null
    private var network: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // «Выключить» в уведомлении — обычный запуск, не переднего плана: startForeground не нужен.
        if (intent?.action == STOP) {
            PushPrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            // Сначала — передний план: служба, поднятая startForegroundService и ушедшая без него, роняет приложение.
            startForeground(ID, notification(this, status.value), type)
        } catch (_: IllegalStateException) {
            // Запуск из фона запрещён (Android 12+) — канал поднимется при открытии приложения.
            setStatus(this, "ждёт открытия приложения: система не дала запустить в фоне")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!PushPrefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        val current = loop
        if (current == null || intent?.action == RESTART) {
            current?.stop()
            loop = Loop(applicationContext).also { it.start() }
        }
        if (network == null) {
            network = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { loop?.kick() }
            }.also { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(cb) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        loop?.stop()
        loop = null
        network?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        network = null
        if (!PushPrefs.enabled(this)) setStatus(this, OFF)
        super.onDestroy()
    }

    /**
     * Соединение и переподключение — в своём потоке: обрыв — пауза по [PushChannel.backoffSeconds],
     * сеть вернулась — сразу. Ожидание строки обрывает [stop] (закрывает соединение).
     */
    private class Loop(private val app: Context) {
        @Volatile private var stopped = false
        @Volatile private var connected = false
        @Volatile private var close: (() -> Unit)? = null
        private var failures = 0
        private var since: String? = null
        private var pending: ScheduledFuture<*>? = null
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dpmeta-push").apply { isDaemon = true } }

        fun start() = schedule(0)

        fun stop() {
            stopped = true
            close?.invoke()
            executor.shutdownNow()
        }

        /** Сеть появилась: ждущее переподключение — сейчас. */
        fun kick() {
            if (stopped || connected) return
            synchronized(this) { failures = 0 }
            schedule(0)
        }

        @Synchronized
        private fun schedule(seconds: Long) {
            if (stopped) return
            pending?.cancel(false)
            pending = runCatching { executor.schedule(::connect, seconds, TimeUnit.SECONDS) }.getOrNull()
        }

        private fun connect() {
            if (stopped || connected) return
            val key = when (val k = PushPrefs.key(app)) {
                is Outcome.Failure -> return setStatus(app, "ключ не прочитан: ${k.diagnostics.first().message}")
                is Outcome.Success -> k.value ?: return setStatus(app, "нет ключа — создайте его в настройках канала")
            }
            val server = PushPrefs.server(app)
            val request = HttpRequest("GET", PushChannel.streamUrl(server, PushPrefs.topic(app), since))
            val allowed = AllowList.push(PushChannel.host(server)).check(request)
            if (allowed is Outcome.Failure) return setStatus(app, "адрес не разрешён: ${allowed.diagnostics.first().message}")
            setStatus(app, "подключение…")
            val r = UrlTransport().lines(request, PushChannel.READ_TIMEOUT_MS, onConnected = { close = it }) { line ->
                if (stopped) return@lines false
                if (!connected) {
                    connected = true
                    synchronized(this) { failures = 0 }
                    setStatus(app, "подключён с ${time()}")
                }
                handle(line, key)
                true
            }
            connected = false
            close = null
            if (stopped) return
            val n = synchronized(this) { ++failures }
            val delay = PushChannel.backoffSeconds(n)
            val why = (r as? Outcome.Failure)?.diagnostics?.first()?.message ?: "сервер закрыл соединение"
            setStatus(app, "нет связи (${time()}): $why; снова через $delay с")
            schedule(delay)
        }

        private fun handle(line: String, key: Secret) {
            val frame = PushChannel.frame(line) ?: return
            frame.id?.let { since = it }
            val now = Clock.SYSTEM.now()
            when (val v = PushEvents.verify(frame.text, now, seen.snapshot()) { PushSignature.hex(key, it) }) {
                is Outcome.Failure -> lastDropped.value = "${time()}: ${v.diagnostics.first().message}"
                is Outcome.Success -> {
                    seen.add(v.value.id)
                    runCatching { PultAlerts.postEvents(app, listOf(PushEvents.event(v.value)), now) }
                    lastEvent.value = "${time()}: ${PushEvents.event(v.value).text}"
                }
            }
        }
    }

    companion object {
        private const val CHANNEL = "dpmeta.push.service"
        private const val ID = 4202
        private const val STOP = "com.dpsmpx.dpmeta.app.PushChannelService.STOP"
        private const val RESTART = "com.dpsmpx.dpmeta.app.PushChannelService.RESTART"
        const val OFF = "выключен"

        /** Принятые id — на весь процесс: перезапуск службы не пропускает повтор. */
        private val seen = SeenIds()

        private val status = MutableStateFlow(OFF)
        private val lastEvent = MutableStateFlow<String?>(null)
        private val lastDropped = MutableStateFlow<String?>(null)

        /** Состояние для настроек: соединение, последнее событие, последнее отброшенное. */
        val state: StateFlow<String> get() = status
        val event: StateFlow<String?> get() = lastEvent
        val dropped: StateFlow<String?> get() = lastDropped

        private fun time(): String = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(DisplayZone.current)
            .format(java.time.Instant.ofEpochMilli(Clock.SYSTEM.now().epochMillis))

        private fun setStatus(context: Context, text: String) {
            status.value = text
            if (text != OFF) runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID, notification(context, text)) }
        }

        /**
         * Поднять канал, если он включён (экран на виду, загрузка телефона, смена настроек).
         * [restart] — переподключиться с новыми сервером, темой или ключом. Отказ системы — `false`.
         */
        fun start(context: Context, restart: Boolean = false): Boolean {
            if (!PushPrefs.enabled(context)) return false
            return try {
                context.startForegroundService(Intent(context, PushChannelService::class.java).apply { if (restart) action = RESTART })
                true
            } catch (_: IllegalStateException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

        @Volatile
        private var running = false

        /** Окно на виду (оболочка зовёт при каждом показе): включённый, но не поднятый канал — поднять. */
        fun ensure(context: Context) {
            if (!running) start(context.applicationContext)
        }

        /** Выключить: служба уходит, соединение закрывается. */
        fun stop(context: Context) {
            PushPrefs.setEnabled(context, false)
            context.stopService(Intent(context, PushChannelService::class.java))
            status.value = OFF
        }

        private fun notification(context: Context, text: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Мгновенный канал", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Соединение с сервером ntfy, пока канал включён; сами события — в каналах пульта"
                        setShowBadge(false)
                    },
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val off = PendingIntent.getService(context, 3, Intent(context, PushChannelService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_pult_summary)
                .setContentTitle("Мгновенный канал")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Выключить", off).build())
                .apply { open?.let(::setContentIntent) }
                .build()
        }
    }
}

/**
 * Загрузка телефона и обновление приложения (D-152): включённый канал поднимается сам.
 * Только системные рассылки; снаружи не виден.
 */
class PushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PushChannelService.start(context)
        }
    }
}

package com.dpsmpx.dpai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Контроллер в фоне (D-36): пока в приложении есть target с каталогом
 * Claude, такт контура идёт в службе переднего плана — с постоянным
 * уведомлением «контроллер работает», — и уведомления о задачах приходят
 * при закрытом экране. Экран в это время только рисует снимок. Тип службы
 * — `specialUse` (Android 14+): ни один стандартный тип не описывает
 * «опрос агентов по SSH»; приложение не из каталога, отдельного
 * обоснования ему не нужно.
 *
 * Процессор держит частичный wakelock (D-39): без него при выключенном
 * экране устройство засыпает, такт встаёт, и уведомления приходят только
 * после включения экрана. Wakelock продлевается каждым тактом на
 * [WAKE_MINUTES]: если такты остановились, он истекает сам.
 *
 * «Остановить» в уведомлении останавливает контроллер до явного запуска
 * на экране ([resume]): отметка — файл [STOPPED] в каталоге приложения,
 * экран без неё службу не поднимает.
 */
class ControllerService : Service() {
    private var timer: ScheduledExecutorService? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            runCatching { NioVfs(filesDir.toPath()).write(STOPPED, "остановлен из уведомления\n".toByteArray()) }
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Контроллер работает", NotificationManager.IMPORTANCE_MIN))
        val open = Notifier.openApp(this)
        val stop = PendingIntent.getService(this, 1, Intent(this, ControllerService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DP AI: контроллер работает")
            .setContentText("опрос targets и задач")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .setOngoing(true)
            .build()
        // Перезапуск по START_STICKY после смерти процесса идёт из фона: система может
        // отказать службе переднего плана — тогда служба уходит, а не падает по кругу.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (timer == null) {
            val app = applicationContext
            val controller = Controller.get(app.filesDir, app.packageManager.getPackageInfo(app.packageName, 0).versionName)
            val model = DemoModel.get(app.cacheDir, controller)
            val notifier = Notifier(app)
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dpai:controller")
                .apply { setReferenceCounted(false) }
            wake = lock
            running = true
            timer = Executors.newSingleThreadScheduledExecutor().also { t ->
                t.scheduleWithFixedDelay({
                    lock.acquire(TimeUnit.MINUTES.toMillis(WAKE_MINUTES))
                    runCatching { model.step(null).notifications.forEach(notifier::show) }
                }, 0, STEP_SECONDS, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        timer?.shutdownNow()
        timer = null
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dpai-controller"
        private const val ID = 1001
        private const val STOP = "com.dpsmpx.dpai.STOP_CONTROLLER"
        private const val STEP_SECONDS = 3L

        /** Срок wakelock от последнего такта: такты встали — wakelock истёк (D-39). */
        private const val WAKE_MINUTES = 10L

        /** Отметка «остановлен человеком» в каталоге файлов приложения. */
        private const val STOPPED = "controller-stopped"

        /** Служба шагает сама: экран не делает такт, только рисует ([DemoModel.view]). */
        @Volatile
        var running: Boolean = false
            private set

        /** Остановлен ли контроллер кнопкой «Остановить» — до явного [resume]. */
        fun stoppedByUser(context: Context): Boolean = File(context.filesDir, STOPPED).isFile

        /**
         * Запуск службы экраном, если человек её не останавливал. Из фона
         * (Android 12+) запуск службы переднего плана запрещён — это не
         * падение приложения, а пропуск: следующий такт экрана попробует снова.
         */
        fun start(context: Context): Boolean {
            if (stoppedByUser(context)) return false
            return try {
                context.startForegroundService(Intent(context, ControllerService::class.java))
                true
            } catch (_: IllegalStateException) {
                false // ForegroundServiceStartNotAllowedException (API 31+) — её подкласс
            }
        }

        /** Явный запуск человеком после «Остановить». */
        fun resume(context: Context): Boolean {
            runCatching { NioVfs(context.filesDir.toPath()).delete(STOPPED) }
            return start(context)
        }
    }
}

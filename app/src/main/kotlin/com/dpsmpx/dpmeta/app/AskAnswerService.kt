package com.dpsmpx.dpmeta.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpmeta.app.data.AskSession
import com.dpsmpx.dpmeta.app.data.PultAlerts
import com.dpsmpx.dpmeta.model.Event
import java.util.concurrent.Executors

/**
 * Ответ «Спросить Claude» в фоне (F-58; D-161): пока идёт ответ — служба переднего плана
 * (`dataSync`, как у клонирования, D-78) с «Claude отвечает…» и «Прервать», чтобы Android
 * не закрыл процесс, когда автор ушёл из приложения. Ответ дописан, а приложение не на
 * экране — событие `chat.answer.done` в правила пульта: уведомление только «Ответ готов»,
 * без текста ответа (ответ автора 15 ноября 2026). Работа — в потоке [AskSession]; служба
 * только держит процесс.
 */
class AskAnswerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // «Прервать» в уведомлении — обычный запуск: ответ обрывается, служба уйдёт по [Watcher.finished].
        if (intent?.action == STOP) {
            AskSession.stop()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Чат: идёт ответ", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Пока Claude отвечает в чате «Спросить Claude»; «Прервать» — оборвать ответ"
            },
        )
        // Отказ системы (окно уже в фоне, предел Android 15 на dataSync) — не падение: ответ идёт без службы.
        try {
            startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Ответ кончился раньше, чем поднялась служба, — сразу уйти.
        if (AskSession.running == null) stopSelf()
        return START_NOT_STICKY
    }

    /** Предел `dataSync` (Android 15): уходит служба, а не ответ. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    /**
     * Связь чата со службой: ставится один раз на процесс ([install]). Ответ начат — служба;
     * кончился — служба уходит, а дописанный в фоне ответ — событие для правил.
     */
    private class Watcher(private val app: Context) : AskSession.Watcher {
        override fun started() {
            try {
                app.startForegroundService(Intent(app, AskAnswerService::class.java))
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+): окно уже в фоне — ответ без службы.
            }
        }

        override fun finished(ok: Boolean, profile: String?) {
            app.stopService(Intent(app, AskAnswerService::class.java))
            if (!ok || visible()) return
            val event = Event(PultAlerts.CHAT_DONE, Clock.SYSTEM.now(), null, "Ответ готов", listOfNotNull(profile?.let { "profile" to it }).toMap())
            worker.execute { runCatching { PultAlerts.postEvents(app, listOf(event), Clock.SYSTEM.now()) } }
        }

        /** Приложение на экране: важность процесса «передний план», а не «служба переднего плана». */
        private fun visible(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    companion object {
        private const val CHANNEL = "pult-ask"
        private const val ID = 4203
        private const val STOP = "com.dpsmpx.dpmeta.app.AskAnswerService.STOP"

        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dpmeta-ask-done").apply { isDaemon = true } }

        /** Подключить службу к чату — из активности пульта, один раз на процесс. */
        fun install(context: Context) {
            AskSession.watch(Watcher(context.applicationContext))
        }

        private fun notification(context: Context): Notification {
            // Касание — в приложение через его вход (экран входа, D-59), сразу в чат.
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 2, Intent(it).putExtra(PultActivity.EXTRA_ASK, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val stop = PendingIntent.getService(
                context, 3, Intent(context, AskAnswerService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_pult_summary)
                .setContentTitle("Claude отвечает…")
                .setContentText("Чат «Спросить Claude»: ответ дописывается")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "Прервать", stop).build())
            if (android.os.Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            open?.let { b.setContentIntent(it) }
            return b.build()
        }
    }
}

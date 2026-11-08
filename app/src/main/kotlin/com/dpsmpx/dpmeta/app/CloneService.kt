package com.dpsmpx.dpmeta.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.dpsmpx.dpmeta.app.data.PultRepository

/**
 * Служба переднего плана пульта на время клонирования (D-78): синхронизация,
 * в которой есть отмеченный клон, которого ещё нет на телефоне, идёт под ней
 * — прошивка не выгружает процесс на середине клона. Работа — в пуле
 * процесса ([com.dpsmpx.dpmeta.app.data.PultPool]); служба только держит
 * процесс и показывает ход («Клоны: N из M») с «Отменить» — той же отменой
 * между репозиториями, что на экране (UX-10). Синхронизация кончилась —
 * экран снимает службу ([stop]).
 */
class CloneService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            PultRepository.cancelRunningClones()
            notify(this, "Отменяю: начатый клон доходит до конца…", cancellable = false)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Пульт: клонирование", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ход первого клонирования репозиториев; «Отменить» — между репозиториями"
            },
        )
        val text = intent?.getStringExtra(TEXT) ?: STARTING
        // Отказ системы (окно уже в фоне, предел Android 15 на dataSync) — не падение:
        // служба уходит, клонирование идёт в пуле, как до D-78.
        try {
            startForeground(ID, notification(this, text, cancellable = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_NOT_STICKY
    }

    /** Предел `dataSync` (Android 15, 6 часов в сутки): уходит служба, а не работа (D-78). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "pult-clones"
        private const val ID = 4201
        private const val CANCEL = "com.dpsmpx.dpmeta.app.CloneService.CANCEL"
        private const val TEXT = "text"
        private const val STARTING = "Клоны: готовлю…"

        @Volatile
        private var running = false

        /**
         * Поднять службу (экран на виду). Из фона Android 12+ запуск службы
         * переднего плана запрещён — это пропуск, а не падение: `false`.
         */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, CloneService::class.java))
            true
        } catch (_: IllegalStateException) {
            false // ForegroundServiceStartNotAllowedException (API 31+) — её подкласс
        }

        /** Ход в уведомлении: только пока служба идёт (иначе уведомления нет и не будет). */
        fun progress(context: Context, text: String) {
            if (running) notify(context, text, cancellable = true)
        }

        /** Синхронизация кончилась: служба уходит, уведомление снимается. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CloneService::class.java))
        }

        private fun notify(context: Context, text: String, cancellable: Boolean) {
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, notification(context, text, cancellable)) }
        }

        private fun notification(context: Context, text: String, cancellable: Boolean): Notification {
            // Касание — в приложение через его вход (экран входа, D-59), а не прямо в пульт.
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Пульт: клонирую репозитории")
                .setContentText(text)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            // Ход видно сразу, а не через 10 с задержки Android 12+ для служб переднего плана.
            if (android.os.Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            open?.let { b.setContentIntent(it) }
            if (cancellable) {
                val cancel = PendingIntent.getService(
                    context, 1, Intent(context, CloneService::class.java).setAction(CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(Notification.Action.Builder(null, "Отменить", cancel).build())
            }
            return b.build()
        }
    }
}

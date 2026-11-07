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

/**
 * Служба переднего плана своего терминала (аудит UI/UX 5 ноября 2026,
 * терминал:A-1): пока открыта хоть одна вкладка, процесс приложения держится
 * — SSH-сессия и `claude`, ждущий кода входа, не обрываются, пока человек в
 * браузере. Уведомление — «Терминал: N вкладок», без текста экрана (T8):
 * касание открывает терминал, «Закрыть все» — подтверждение на его экране.
 * Последняя вкладка закрыта — служба уходит. Вкладки по-прежнему только в
 * памяти (D-27): после смерти процесса восстанавливать нечего.
 */
class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = TerminalHub.tabs.size
        if (n == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Терминал", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // «Закрыть все» — через подтверждение на экране терминала: одно касание в шторке обрывало бы все сеансы.
        val closeAll = PendingIntent.getActivity(
            this, 1,
            Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_CLOSE_ALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Терминал: ${tabsWord(n)}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Закрыть все", closeAll).build())
            .build()
        // Повторный запуск из фона система может не пустить: тогда служба уходит, а не падает по кругу.
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, notification)
        } catch (_: IllegalStateException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "dpai.terminal"
        private const val ID = 4207

        /** «1 вкладка», «2 вкладки», «5 вкладок». */
        fun tabsWord(n: Int): String {
            val m10 = n % 10
            val m100 = n % 100
            val w = when {
                m10 == 1 && m100 != 11 -> "вкладка"
                m10 in 2..4 && m100 !in 12..14 -> "вкладки"
                else -> "вкладок"
            }
            return "$n $w"
        }

        /**
         * Служба — по числу вкладок: есть — поднять или обновить уведомление,
         * нет — снять. Зовётся при открытии и закрытии вкладки; из фона
         * Android 12+ запуск не пустит — это пропуск, а не падение.
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            if (TerminalHub.tabs.isEmpty()) {
                runCatching { app.stopService(Intent(app, TerminalService::class.java)) }
            } else {
                try {
                    app.startForegroundService(Intent(app, TerminalService::class.java))
                } catch (_: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException (API 31+) — её подкласс
                }
            }
        }
    }
}

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
        val notification = notification(this, n)
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

        /**
         * Уведомление службы: «Терминал: N вкладок», а если фоновая вкладка
         * подала сигнал — «Вкладка «…» ждёт» (терминал:A-8), без текста
         * программы (T8).
         */
        private fun notification(context: Context, n: Int): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Терминал", NotificationManager.IMPORTANCE_LOW))
            return build(context, n)
        }

        /** Сигнал фоновой вкладки — строкой уведомления, не чаще раза в [ATTENTION_EVERY_MS] (терминал:A-8). */
        fun attention(context: Context) {
            val now = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() / 1_000_000
            if (now - lastAttention < ATTENTION_EVERY_MS || TerminalHub.tabs.isEmpty()) return
            lastAttention = now
            refresh(context)
        }

        /** Перерисовать уведомление (вкладку увидели — строка «ждёт» уходит). */
        fun refresh(context: Context) {
            if (TerminalHub.tabs.isEmpty()) return
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, notification(context.applicationContext, TerminalHub.tabs.size)) }
        }

        @Volatile
        private var lastAttention = 0L
        private const val ATTENTION_EVERY_MS = 5_000L

        private fun build(context: Context, n: Int): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // «Закрыть все» — через подтверждение на экране терминала: одно касание в шторке обрывало бы все сеансы.
        val closeAll = PendingIntent.getActivity(
            context, 1,
            Intent(context, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_CLOSE_ALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val waiting = TerminalHub.tabs.filter { it.attention }.map { it.label }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Терминал: ${tabsWord(n)}")
            .apply { if (waiting.isNotEmpty()) setContentText(waiting.joinToString(", ") { "Вкладка «$it» ждёт" }) }
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Закрыть все", closeAll).build())
            .build()
        }

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

package com.dpsmpx.dpai.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationCategory
import java.util.concurrent.Executors
import android.app.Notification as AndroidNotification

/**
 * Уведомления контроллера в Android (D-36): решение «показать, обновить,
 * придержать» принимает `NotificationEngine` ядра; здесь — только канал
 * по категории и показ. Одно уведомление на ключ предмета: обновление
 * заменяет прежнее, а не добавляет новое (ТЗ, §36).
 */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        for (c in NotificationCategory.entries) {
            manager.createNotificationChannel(NotificationChannel(channel(c), title(c), importance(c)))
        }
    }

    fun show(n: Notification) {
        // Запрос человеку показывает RequestPresenter — по ждущим запросам, а не по потоку (D-44).
        if (n.request != null) return
        val builder = AndroidNotification.Builder(context, channel(n.category))
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(n.title + if (n.count > 1) " (×${n.count})" else "")
            .setContentText(n.body)
            .setStyle(AndroidNotification.BigTextStyle().bigText(n.body))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // Нажатие открывает приложение — то же окно, а не второе поверх (ревью 8 октября 2026);
            // ссылка уведомления — к задаче или сессии (ТЗ, §76).
            .setContentIntent(n.link?.let { openLink(context, it.toUri()) } ?: openApp(context))
        // Кнопок у сведений нет: решения человека — запросы (D-44, D-49), их показывает RequestPresenter.
        manager.notify(n.key.hashCode(), builder.build())
    }

    fun cancel(key: String) = manager.cancel(key.hashCode())

    companion object {
        /** Открыть приложение: существующее окно выходит вперёд, нового не создаётся. */
        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Открыть приложение по ссылке `dpai://вид/id` (ТЗ, §76): явный
         * компонент, неизменяемый `PendingIntent`; ссылку разбирает строгий
         * `DeepLink.parse` на экране.
         */
        fun openLink(context: Context, link: String): PendingIntent = PendingIntent.getActivity(
            context, link.hashCode(),
            MainActivity.internal(context).setAction(MainActivity.OPEN).putExtra(MainActivity.EXTRA_LINK, link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun channelOf(c: NotificationCategory) = channel(c)

        private fun channel(c: NotificationCategory) = "dpai-${c.name.lowercase()}"

        private fun title(c: NotificationCategory) = when (c) {
            NotificationCategory.INFO -> "Сведения"
            NotificationCategory.PROGRESS -> "Ход работы"
            NotificationCategory.SUCCESS -> "Готово"
            NotificationCategory.SYSTEM -> "Система и сводки"
            NotificationCategory.WARNING -> "Предупреждения"
            NotificationCategory.BLOCKED -> "Нужно вмешательство"
            NotificationCategory.FAILURE -> "Сбои"
            NotificationCategory.APPROVAL -> "Одобрения"
            NotificationCategory.SECURITY -> "Безопасность"
        }

        /** Важность канала — от категории: шумят только то, что ждёт человека. */
        private fun importance(c: NotificationCategory) = when {
            c.rank >= NotificationCategory.BLOCKED.rank -> NotificationManager.IMPORTANCE_HIGH
            c.rank >= NotificationCategory.WARNING.rank -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_LOW
        }
    }
}

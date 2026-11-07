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
        NotifyGroups.ensure(manager)
        for (c in NotificationCategory.entries) {
            manager.createNotificationChannel(
                NotificationChannel(channel(c), title(c), importance(c)).apply {
                    group = NotifyGroups.of(c)
                    description = NotifyGroups.description(c)
                },
            )
        }
    }

    fun show(n: Notification) {
        // Запрос человеку показывает RequestPresenter — по ждущим запросам, а не по потоку (D-44).
        if (n.request != null) return
        val builder = AndroidNotification.Builder(context, channel(n.category))
            .setSmallIcon(R.drawable.ic_notify_ai)
            .setContentTitle(n.title + if (n.count > 1) " (×${n.count})" else "")
            .setContentText(n.body)
            .setStyle(AndroidNotification.BigTextStyle().bigText(n.body))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // Названия задач и текст — не для экрана блокировки (аудит безопасности, A-12).
            .setVisibility(AndroidNotification.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen(context, channel(n.category), title(n.category)))
            // Срок жизни (аудит стабильности, A-4): у приложения не больше 50 уведомлений, лишние система
            // молча отбрасывает — и с ними запросы человеку. Сведения устаревают сами; решения — запросы.
            .apply { timeout(n.category)?.let { setTimeoutAfter(it) } }
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
            Intent(context, MainActivity::class.java).addFlags(MainActivity.FRONT),
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
                .addFlags(MainActivity.FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun channelOf(c: NotificationCategory) = channel(c)

        /** Сколько живёт уведомление категории; `null` — пока его не смахнут (сбои, безопасность). */
        private fun timeout(c: NotificationCategory): Long? = when (c) {
            NotificationCategory.INFO, NotificationCategory.PROGRESS -> 2 * HOUR_MS
            NotificationCategory.SUCCESS, NotificationCategory.SYSTEM, NotificationCategory.WARNING -> 24 * HOUR_MS
            NotificationCategory.BLOCKED, NotificationCategory.APPROVAL -> 72 * HOUR_MS
            NotificationCategory.FAILURE, NotificationCategory.SECURITY -> null
        }

        private const val HOUR_MS = 3_600_000L

        /** Версия для экрана блокировки: только что есть уведомление, без текста (A-12). */
        fun lockScreen(context: Context, channel: String, title: String): AndroidNotification =
            AndroidNotification.Builder(context, channel).setSmallIcon(R.drawable.ic_notify_ai).setContentTitle(title).build()

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

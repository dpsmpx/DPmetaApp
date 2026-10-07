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
        val builder = AndroidNotification.Builder(context, channel(n.category))
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(n.title + if (n.count > 1) " (×${n.count})" else "")
            .setContentText(n.body)
            .setStyle(AndroidNotification.BigTextStyle().bigText(n.body))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        for ((i, a) in n.actions.withIndex()) {
            // Кнопка — только через свой неэкспортируемый приёмник (A-7, T10):
            // чужое приложение не пришлёт ссылку одобрения.
            val intent = Intent(context, ApprovalReceiver::class.java).setAction(ApprovalReceiver.ACTION).putExtra(ApprovalReceiver.URI, a.uri).putExtra(ApprovalReceiver.KEY, n.key)
            val pending = PendingIntent.getBroadcast(context, (n.key.hashCode() * 31) + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(AndroidNotification.Action.Builder(null as Icon?, a.label, pending).build())
        }
        manager.notify(n.key.hashCode(), builder.build())
    }

    fun cancel(key: String) = manager.cancel(key.hashCode())

    companion object {
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

/**
 * Нажатая кнопка уведомления (D-10, A-7): приёмник не экспортируется,
 * ссылку разбирает и проверяет хеш предложения оркестратор. Исход —
 * новым уведомлением в том же ключе: принято или почему нет.
 */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val uri = intent.getStringExtra(URI) ?: return
        val key = intent.getStringExtra(KEY) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                val controller = Controller.get(app.filesDir, app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                val result = DemoModel.get(app.cacheDir, controller).respond(uri)
                val notifier = Notifier(app)
                notifier.cancel(key)
                val text = when (result) {
                    is Outcome.Success -> if (result.value) "Принято." else "Запрос уже решён или заменён."
                    is Outcome.Failure -> "Не принято: " + result.diagnostics.first().message
                }
                notifier.show(
                    Notification("$key:answer", NotificationCategory.INFO, "Ответ на запрос", text, null, 1, nowStamp(), nowStamp()),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.dpsmpx.dpai.APPROVAL"
        const val URI = "uri"
        const val KEY = "key"
        private val worker = Executors.newSingleThreadExecutor()

        private fun nowStamp() = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now()
    }
}

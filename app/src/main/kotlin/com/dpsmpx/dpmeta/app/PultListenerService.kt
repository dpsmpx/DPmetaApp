package com.dpsmpx.dpmeta.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpmeta.app.data.ListenerPrefs
import com.dpsmpx.dpmeta.app.data.PultAlerts
import com.dpsmpx.dpmeta.notify.AppNotifications
import java.util.concurrent.Executors

/**
 * Слушатель уведомлений (F-57; D-155): доступ даёт человек в настройках Android; пока
 * слушатель выключен в приложении или пакет не в белом списке — уведомление отбрасывается
 * сразу, ничего не сохраняя и не записывая в журнал (ТЗ, T-16). Из списка — событие
 * `app.notification` в те же правила, что у пульта; свой поток — не главный.
 */
class PultListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext
        if (!ListenerPrefs.enabled(app)) return
        val allowed = ListenerPrefs.packages(app).toSet()
        if (sbn.packageName !in allowed) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val event = AppNotifications.event(
            sbn.packageName,
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
            sbn.postTime.takeIf { it > 0 } ?: Clock.SYSTEM.now().epochMillis,
            sbn.key,
            allowed,
            app.packageName,
            ongoing = sbn.isOngoing,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        ) ?: return
        worker.execute { runCatching { PultAlerts.postEvents(app, listOf(event), Clock.SYSTEM.now()) } }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dpmeta-listener").apply { isDaemon = true } }

        /** Дал ли человек доступ к уведомлениям в настройках Android. */
        fun granted(context: Context): Boolean {
            val own = ComponentName(context, PultListenerService::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return list.split(':').any { it == own }
        }
    }
}

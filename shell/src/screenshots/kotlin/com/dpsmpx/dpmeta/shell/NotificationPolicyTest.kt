package com.dpsmpx.dpmeta.shell

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.dpsmpx.dpai.android.DpaiApplication
import com.dpsmpx.dpai.android.Notifier
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.DisplayZone
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.ui.NoticePresentation
import com.dpsmpx.dpmeta.app.data.PultAlerts
import com.dpsmpx.dpmeta.notify.QuietHours
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import android.app.Notification as AndroidNotification

/** Настоящий Android Notifier → callback оболочки → правила/каналы, без службы и сети. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationPolicyTest {
    private class PolicyApp(base: Context) : DpaiApplication() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        var calls = 0
        var beforeRender: (() -> Unit)? = null
        override fun presentNotice(notice: Notification, render: (NoticePresentation) -> Boolean) {
            calls++
            PultAlerts.presentAi(this, notice) { style -> beforeRender?.invoke(); render(style) }
        }
    }

    private lateinit var app: PolicyApp
    private lateinit var manager: NotificationManager

    @Before
    fun setup() {
        val base = RuntimeEnvironment.getApplication()
        shadowOf(base).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app = PolicyApp(base)
        manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        assertIs<Outcome.Success<*>>(PultAlerts.resetRules(app))
        PultAlerts.setQuiet(app, null)
        PultAlerts.setPrivacy(app, true)
    }

    private fun notice(category: NotificationCategory = NotificationCategory.INFO): Notification {
        val now = Clock.SYSTEM.now()
        return Notification("policy-${category.name}", category, "Задача", "Ответ ИИ", null, 1, now, now)
    }

    private fun rules(action: String = "notify") {
        assertIs<Outcome.Success<*>>(PultAlerts.saveRules(app, """
            schema = 1
            [[rule]]
            id = "test.ai"
            title = "Обычные сообщения ИИ"
            on = "ai.*"
            action = "$action"
            importance = "high"
        """.trimIndent()))
    }

    @Test
    fun silentRuleHidesInformationButCannotHideMandatoryMessages() {
        rules("silent")
        val notifier = Notifier(app)
        notifier.show(notice())
        assertEquals(1, app.calls)
        assertTrue(manager.activeNotifications.isEmpty())
        notifier.show(notice(NotificationCategory.SECURITY))
        notifier.show(notice(NotificationCategory.APPROVAL))
        assertEquals(1, app.calls, "обязательные категории обходят пользовательский callback")
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.all { it.notification.visibility == AndroidNotification.VISIBILITY_PRIVATE })
    }

    @Test
    fun nativeNoticeUsesQuietPolicyAndEnablingPrivacyRemovesPublicCard() {
        rules()
        PultAlerts.setPrivacy(app, false)
        val at = Instant.ofEpochMilli(Clock.SYSTEM.now().epochMillis).atZone(DisplayZone.current)
        val minute = at.hour * 60 + at.minute
        PultAlerts.setQuiet(app, QuietHours((minute + 1410) % 1440, (minute + 30) % 1440))
        val notifier = Notifier(app)
        notifier.show(notice())
        val shown = manager.activeNotifications.single().notification
        assertTrue(shown.channelId.startsWith("dpmeta.alert."))
        assertEquals(AndroidNotification.VISIBILITY_PUBLIC, shown.visibility)
        assertEquals(AndroidNotification.GROUP_ALERT_SUMMARY, shown.groupAlertBehavior)
        PultAlerts.setPrivacy(app, true)
        assertTrue(manager.activeNotifications.isEmpty())
        notifier.show(notice())
        assertEquals(AndroidNotification.VISIBILITY_PRIVATE, manager.activeNotifications.single().notification.visibility)
    }

    @Test
    fun lateRenderCannotPutPublicCardBackAfterPrivacyChange() {
        rules()
        PultAlerts.setPrivacy(app, false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        app.beforeRender = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val notifier = Notifier(app)
        val posting = thread {
            try { notifier.show(notice()) } catch (error: Throwable) { failure.set(error) }
        }
        var changing: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            changing = thread {
                try { attempted.countDown(); PultAlerts.setPrivacy(app, true); changed.countDown() } catch (error: Throwable) { failure.set(error) }
            }
            assertTrue(attempted.await(1, TimeUnit.SECONDS))
            assertFalse(changed.await(100, TimeUnit.MILLISECONDS), "смена приватности и показ должны иметь общий guard")
        } finally {
            release.countDown()
            posting.join(5000)
            changing?.join(5000)
        }
        assertFalse(posting.isAlive)
        assertFalse(changing.isAlive)
        assertNull(failure.get())
        assertTrue(PultAlerts.settings(app).privacy)
        assertTrue(manager.activeNotifications.none { it.notification.visibility == AndroidNotification.VISIBILITY_PUBLIC })
    }
}

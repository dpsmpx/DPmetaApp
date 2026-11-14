package com.dpsmpx.dpmeta.shell

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.dpsmpx.dpai.android.AiSummary
import com.dpsmpx.dpai.android.MainActivity
import com.dpsmpx.dpai.android.HealthAuto
import com.dpsmpx.dpmeta.app.screens.WidgetText
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpai.core.measure.Measure
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36], application = Application::class)
class StudioWidgetTest {
    @Test
    fun noHealthCheckIsUnknownAndFirstSuccessfulCheckRecordsZero() {
        val app = RuntimeEnvironment.getApplication()
        assertNull(HealthAuto.lastCount(app))
        HealthAuto.remember(app, 0)
        assertEquals(0, HealthAuto.lastCount(app))
        assertEquals("Проверка: 0 проблем", WidgetText.of(null, null, HealthAuto.lastCount(app)).health)
    }

    @Test
    fun remoteViewsContainOnlyStatusAndActionsOpenTheNormalInternalEntry() {
        val app = RuntimeEnvironment.getApplication()
        val text = WidgetText.of(null, Screens.AiCounts(true, Measure.unknown("остановлен"), Measure.unknown("остановлен"), Measure.unknown("остановлен")))
        val remote = StudioWidget.render(app, text, AiSummary.open(app, null), "Прочитано: сейчас")
        val view = remote.apply(app, FrameLayout(app))
        val width = app.resources.getDimensionPixelSize(R.dimen.widget_min_width)
        val height = app.resources.getDimensionPixelSize(R.dimen.widget_min_height)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val last = view.findViewById<View>(R.id.widget_check)
        var bottom = last.bottom
        var parent = last.parent
        while (parent is View && parent !== view) { bottom += parent.top; parent = parent.parent }
        assertTrue(bottom <= height - view.paddingBottom, "кнопки должны помещаться при минимальном размере: низ $bottom, доступно ${height - view.paddingBottom}")
        assertEquals(text.projects, view.findViewById<TextView>(R.id.widget_projects).text.toString())
        assertEquals("Нет среза проектов", view.findViewById<TextView>(R.id.widget_project_date).text.toString())
        assertFalse(view.findViewById<TextView>(R.id.widget_ai_counts).text.contains("0"))
        for ((id, request, expected) in listOf(
            Triple(R.id.widget_ide, 1, MainActivity.ide(app)),
            Triple(R.id.widget_ai, 2, AiSummary.open(app, null)),
            Triple(R.id.widget_check, 3, MainActivity.check(app)),
            Triple(R.id.widget_projects, 4, Intent(app, DpmetaActivity::class.java)),
        )) {
            val pending = PendingIntent.getActivity(app, request, expected, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            assertNotNull(pending)
            assertTrue(shadowOf(pending).isImmutable)
            assertTrue(view.findViewById<View>(id).performClick())
            val launched = shadowOf(app).nextStartedActivity
            assertEquals(expected.component, launched.component)
            assertEquals(expected.action, launched.action)
            assertEquals(expected.extras?.keySet().orEmpty(), launched.extras?.keySet().orEmpty())
            for (key in expected.extras?.keySet().orEmpty()) assertEquals(expected.getStringExtra(key), launched.getStringExtra(key))
        }
    }
}

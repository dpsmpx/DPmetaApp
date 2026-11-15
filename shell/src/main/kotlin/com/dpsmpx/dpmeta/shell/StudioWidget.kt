package com.dpsmpx.dpmeta.shell

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dpsmpx.dpai.android.AiSummary
import com.dpsmpx.dpai.android.AppLogs
import com.dpsmpx.dpai.android.HealthAuto
import com.dpsmpx.dpai.android.MainActivity
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.data.WidgetCache
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.WidgetText
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Локальные счётчики и переходы через обычную блокировку приложения (D-144). */
class StudioWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == REFRESH) onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf())
        else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        if (!updating.compareAndSet(false, true)) { pending?.finish(); return }
        val app = context.applicationContext
        thread(name = "dpmeta-widget") {
            try {
                val projects = WidgetCache.projects(app)
                val ai = runCatching { AiSummary.read(app) }.getOrNull()
                val counts = ai?.let { Screens.AiCounts(it.configured, it.waiting, it.running, it.nearLimit, it.problems) }
                val text = WidgetText.of((projects as? Outcome.Success)?.value, counts, HealthAuto.lastCount(app), ai?.manual)
                val views = render(app, text, AiSummary.open(app, ai?.request), "Прочитано: ${Screens.time(Clock.SYSTEM.now())}")
                if (projects is Outcome.Failure) views.setTextViewText(R.id.widget_project_date, "Кэш проектов не прочитан; откройте приложение")
                // Обновляются все текущие экземпляры, в том числе добавленные во время чтения.
                manager.updateAppWidget(ComponentName(app, StudioWidget::class.java), views)
            } catch (error: Exception) {
                AppLogs.info(app, "виджет", "Не обновлён: ${error.javaClass.simpleName}")
                manager.updateAppWidget(ComponentName(app, StudioWidget::class.java),
                    render(app, WidgetText.of(null, null), AiSummary.open(app, null), "Не прочитано; откройте приложение"))
            } finally {
                updating.set(false)
                pending?.finish()
            }
        }
    }

    companion object {
        private const val REFRESH = "com.dpsmpx.dpmeta.WIDGET_REFRESH"
        private val updating = AtomicBoolean()

        /**
         * Перечитать по такту (D-153): синхронизация пульта, конец ручного запуска, новые числа
         * «AI» или проверки. Виджета на экране нет — ничего не читается.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val ids = runCatching { AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, StudioWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            app.sendBroadcast(Intent(app, StudioWidget::class.java).setAction(REFRESH))
        }

        /** Отдельно от чтения: проверяется настоящий RemoteViews и маршруты, без запуска контура. */
        internal fun render(context: Context, text: WidgetText, ai: Intent, readAt: String): RemoteViews =
            RemoteViews(context.packageName, R.layout.studio_widget).apply {
                setTextViewText(R.id.widget_projects, text.projects)
                setTextViewText(R.id.widget_project_date, text.projectDate)
                setTextViewText(R.id.widget_ai_counts, text.ai)
                setTextViewText(R.id.widget_health, text.health)
                setTextViewText(R.id.widget_runs, text.runs)
                setTextViewText(R.id.widget_read_at, readAt)
                setOnClickPendingIntent(R.id.widget_ide, open(context, 1, MainActivity.ide(context)))
                setOnClickPendingIntent(R.id.widget_ai, open(context, 2, ai))
                setOnClickPendingIntent(R.id.widget_check, open(context, 3, MainActivity.check(context)))
                setOnClickPendingIntent(R.id.widget_projects, open(context, 4, Intent(context, DpmetaActivity::class.java)))
                setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 5,
                    Intent(context, StudioWidget::class.java).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }

        private fun open(context: Context, request: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(context, request,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

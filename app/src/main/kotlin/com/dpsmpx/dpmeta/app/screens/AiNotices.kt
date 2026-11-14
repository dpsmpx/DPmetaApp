package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpmeta.app.data.RecordedNotice
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.model.Event

/** Назначение источника неизвестному проекту не угадывается из текста задачи (D-142). */
object AiNotices {
    const val FEED_KIND = "ai.notice"

    fun event(notice: Notification): Event? {
        if (notice.request != null || notice.category == NotificationCategory.APPROVAL || notice.category == NotificationCategory.SECURITY) return null
        val category = notice.category.name.lowercase(java.util.Locale.ROOT)
        val title = Redactor.STRICT.redact(notice.title)
        return Event("ai.$category", notice.lastAt, null, title + "\n" + Redactor.STRICT.redact(notice.body),
            mapOf("title" to title, "category" to category), mapOf("count" to notice.count.toDouble()), "${notice.key}:${notice.firstAt.epochMillis}")
    }

    fun feed(records: List<RecordedNotice>): List<FeedEvent> = records.filter { it.inFeed }.map {
        // Пустой проект — событие подсистемы, а не запись неизвестного репозитория.
        FeedEvent(it.event.at, "", FEED_KIND, it.event.text)
    }
}

package com.dpsmpx.dpai.android

import android.app.NotificationChannelGroup
import android.app.NotificationManager
import com.dpsmpx.dpai.notifications.NotificationCategory

/**
 * Группы каналов уведомлений «AI» (AI:A-18): в настройках уведомлений
 * DPmetaApp строки «Сведения», «Готово»… подписаны частью приложения, и
 * каналы пульта, когда появятся, от них отличимы. Три группы: решения
 * человека, ход работы, служба.
 */
internal object NotifyGroups {
    const val DECISIONS = "dpai-group-decisions"
    const val PROGRESS = "dpai-group-progress"
    const val SERVICE = "dpai-group-service"

    /** Создать группы (повтор безопасен: та же группа обновляется). */
    fun ensure(manager: NotificationManager) {
        manager.createNotificationChannelGroups(
            listOf(
                NotificationChannelGroup(DECISIONS, "AI: решения").apply { description = "Запросы, нужное вмешательство, сбои и безопасность" },
                NotificationChannelGroup(PROGRESS, "AI: ход работы").apply { description = "Сведения, ход и итоги задач" },
                NotificationChannelGroup(SERVICE, "AI: служба").apply { description = "Контроллер, терминал и диск приложения" },
            ),
        )
    }

    /** Группа канала категории: ждущее человека — в «решения», остальное — в «ход работы». */
    fun of(c: NotificationCategory): String = when (c) {
        NotificationCategory.BLOCKED, NotificationCategory.FAILURE, NotificationCategory.SECURITY, NotificationCategory.APPROVAL -> DECISIONS
        else -> PROGRESS
    }

    /** Описание канала категории — строкой в настройках системы. */
    fun description(c: NotificationCategory): String = when (c) {
        NotificationCategory.INFO -> "Что произошло в задачах и сессиях — без действия"
        NotificationCategory.PROGRESS -> "Ход задач: начаты, идут, ждут"
        NotificationCategory.SUCCESS -> "Задачи и проверки, которые закончились успешно"
        NotificationCategory.SYSTEM -> "Сводки контура и состояние системы"
        NotificationCategory.WARNING -> "Что стоит посмотреть: лимиты, предупреждения"
        NotificationCategory.BLOCKED -> "Задача стоит и ждёт человека"
        NotificationCategory.FAILURE -> "Сбои задач, targets и контура"
        NotificationCategory.APPROVAL -> "Подтверждения: исключения и одобрения"
        NotificationCategory.SECURITY -> "Ключи хостов, вход и другое о безопасности"
    }
}

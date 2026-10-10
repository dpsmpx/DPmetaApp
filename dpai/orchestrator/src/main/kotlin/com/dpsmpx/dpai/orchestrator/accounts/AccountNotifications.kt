package com.dpsmpx.dpai.orchestrator.accounts

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.LimitLevel
import com.dpsmpx.dpai.accounts.LimitWindow
import com.dpsmpx.dpai.accounts.LoginStatus
import com.dpsmpx.dpai.accounts.OverlapKind
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.notifications.DeepLink
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.notifications.NotificationRequest

/**
 * Уведомления по событиям аккаунтов (`docs/ACCOUNT_MODEL.md`, §7; ТЗ,
 * §37): что случилось, с какой подпиской или слотом, что будет дальше и
 * что сделать. Одинаковы при одной и нескольких подписках (D-30).
 *
 * [before] и [after] — состояние до и после события: из [after] берутся
 * метки и время сброса, по [before] видно, что повторное наблюдение
 * предела (проба раз в `unknownResetProbeInterval`) ничего не изменило —
 * такое уведомлением не становится. Ключ уведомления — предмет; повторы
 * по нему агрегирует `NotificationEngine`, снятый предел закрывает
 * [resolvedKeys].
 */
object AccountNotifications {
    fun requests(event: AccountEvent, before: AccountsState, after: AccountsState): List<NotificationRequest> {
        val at = event.at
        return when (event) {
            is AccountEvent.LimitObserved -> {
                val was = before.subscriptions[event.subscription]?.window(event.window)
                if (was != null && was.level == event.level && was.resetAt == event.resetAt) return emptyList()
                val sub = after.subscriptions.getValue(event.subscription)
                val what = windowName(event.window)
                if (event.level == LimitLevel.REACHED) {
                    listOf(
                        NotificationRequest(
                            limitKey(event.subscription, event.window), NotificationCategory.BLOCKED,
                            "«${sub.label}»: достигнут $what",
                            "Новые сессии на этой подписке ждут; ${Accounts().resetPhrase(sub, at)}. " +
                                "Начатые задачи сохранят handoff и продолжатся после сброса.",
                            DeepLink.of(DeepLink.Kind.SUBSCRIPTION, event.subscription), at,
                        ),
                    )
                } else {
                    listOf(
                        NotificationRequest(
                            limitKey(event.subscription, event.window), NotificationCategory.WARNING,
                            "«${sub.label}»: близко к пределу — $what",
                            "Новые задачи отложены, начатые продолжаются" +
                                (event.resetAt?.let { "; сброс через ${Accounts.formatDuration(it - at)}" } ?: "") + ".",
                            DeepLink.of(DeepLink.Kind.SUBSCRIPTION, event.subscription), at,
                        ),
                    )
                }
            }
            is AccountEvent.LimitReset -> {
                val sub = after.subscriptions.getValue(event.subscription)
                listOf(
                    NotificationRequest(
                        "subscription:${event.subscription}:reset:${event.window}", NotificationCategory.SUCCESS,
                        "«${sub.label}»: ${windowName(event.window)} снят",
                        "Подписка снова принимает задачи.",
                        DeepLink.of(DeepLink.Kind.SUBSCRIPTION, event.subscription), at,
                    ),
                )
            }
            is AccountEvent.SlotStateChanged -> {
                val slot = after.slots.getValue(event.slot)
                if (event.to == AccountState.AUTH_REQUIRED && slot.login == LoginStatus.REQUIRED) {
                    listOf(
                        NotificationRequest(
                            "slot:${event.slot}:auth", NotificationCategory.BLOCKED,
                            "Нужен вход формой Claude",
                            "На ${slot.targetId} выполните «${SlotEnvironment.CONFIG_DIR}=${slot.configDir} claude» и войдите " +
                                "через форму Claude (/login). Ключи API не используются (D-29).",
                            DeepLink.of(DeepLink.Kind.ACCOUNT, event.slot), at,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            is AccountEvent.LoginObserved -> {
                val slot = after.slots.getValue(event.slot)
                if (event.login == LoginStatus.FORBIDDEN_METHOD) {
                    listOf(
                        NotificationRequest(
                            "slot:${event.slot}:method", NotificationCategory.SECURITY,
                            "Слот остановлен: вход не формой Claude",
                            "Claude Code сообщает вход «${event.authMethod}». Разрешён только вход формой Claude: уберите ключ " +
                                "или помощник ключа из настроек каталога ${slot.configDir} на ${slot.targetId} и войдите заново.",
                            DeepLink.of(DeepLink.Kind.ACCOUNT, event.slot), at,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            is AccountEvent.OverlapDetected -> {
                val slot = after.slots.getValue(event.slot)
                val sub = after.subscriptions.getValue(slot.subscriptionId)
                val body = when (event.kind) {
                    OverlapKind.IDENTITY_MISMATCH ->
                        "В каталоге ${slot.configDir} на ${slot.targetId} вход другим аккаунтом, чем у подписки «${sub.label}». " +
                            "Слот остановлен, чтобы пределы и работа подписок не смешались."
                    OverlapKind.DUPLICATE_SUBSCRIPTION ->
                        "Этот аккаунт Claude уже зарегистрирован как подписка «${label(after, event.other)}». " +
                            "Слот подписки «${sub.label}» остановлен: одна подписка не должна считаться дважды."
                }
                listOf(
                    NotificationRequest(
                        "slot:${event.slot}:overlap", NotificationCategory.SECURITY, "Пересечение аккаунтов", body,
                        DeepLink.of(DeepLink.Kind.ACCOUNT, event.slot), at,
                    ),
                )
            }
            is AccountEvent.LoginExpiring -> {
                val slot = after.slots.getValue(event.slot)
                listOf(
                    NotificationRequest(
                        "slot:${event.slot}:expiring", NotificationCategory.WARNING,
                        "Вход скоро истечёт",
                        (event.expiresAt?.let { "Вход истекает через ${Accounts.formatDuration(it - at)}. " } ?: "Вход скоро истекает. ") +
                            "Обновите: «${SlotEnvironment.CONFIG_DIR}=${slot.configDir} claude», затем /login на ${slot.targetId}.",
                        DeepLink.of(DeepLink.Kind.ACCOUNT, event.slot), at,
                    ),
                )
            }
            is AccountEvent.ErrorRecorded -> {
                val until = event.cooldownUntil ?: return emptyList()
                listOf(
                    NotificationRequest(
                        "slot:${event.slot}:cooldown", NotificationCategory.WARNING,
                        "Слот на паузе после серии ошибок",
                        "Последняя ошибка: ${event.code}. Пауза ${Accounts.formatDuration(until - at)}; задачи пойдут на другие слоты или подождут.",
                        DeepLink.of(DeepLink.Kind.ACCOUNT, event.slot), at,
                    ),
                )
            }
            is AccountEvent.SubscriptionSwitched -> listOf(
                NotificationRequest(
                    "task:${event.task}:switched", NotificationCategory.INFO,
                    "Задача продолжена на другой подписке",
                    "«${label(after, event.from)}» → «${label(after, event.to)}»: на прежней подписке предел или нет свободного слота.",
                    DeepLink.of(DeepLink.Kind.TASK, event.task), at,
                ),
            )
            else -> emptyList()
        }
    }

    /** Ключи, которые событие закрывает: снятый предел больше не висит в уведомлениях. */
    fun resolvedKeys(event: AccountEvent): List<String> = when (event) {
        is AccountEvent.LimitReset -> listOf(limitKey(event.subscription, event.window))
        is AccountEvent.LoginObserved -> if (event.login == LoginStatus.OK) {
            listOf("slot:${event.slot}:auth", "slot:${event.slot}:method", "slot:${event.slot}:overlap", "slot:${event.slot}:expiring")
        } else {
            emptyList()
        }
        is AccountEvent.ErrorsCleared -> listOf("slot:${event.slot}:cooldown")
        else -> emptyList()
    }

    private fun limitKey(sub: SubscriptionId, window: LimitWindow) = "subscription:$sub:limit:$window"

    private fun windowName(w: LimitWindow) = when (w) {
        LimitWindow.SESSION -> "предел пятичасового окна"
        LimitWindow.WEEKLY -> "недельный предел"
    }

    private fun label(state: AccountsState, id: SubscriptionId?): String =
        id?.let { state.subscriptions[it]?.label } ?: "неизвестная"
}

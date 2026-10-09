package com.dpsmpx.dpai.orchestrator.commands

import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Команда оркестратора от человека или оболочки (D-42, D-06): экран,
 * уведомление, окно поверх приложений, `dpai`. Исполняет её только поток
 * такта ([CommandInbox]); итог — `OperationResult`. [key] — ключ
 * идемпотентности (ТЗ, §5.4): повтор с тем же ключом возвращает
 * записанный итог, а не делает второго действия.
 */
sealed interface Command {
    val operation: String
    val key: String?

    /** Ответ на запрос человеку (D-44); идемпотентен сам — по запросу и варианту. */
    data class AnswerRequest(val uri: String, val text: String?, val by: String, val channel: String?) : Command {
        override val operation: String get() = "request.answer"
        override val key: String? get() = null
    }

    /** Канал показал запрос (D-44). */
    data class Delivered(val request: RequestId, val channel: String) : Command {
        override val operation: String get() = "request.delivered"
        override val key: String? get() = null
    }

    /** Проверочный запрос (D-44); [ttl] `null` — срок контура по умолчанию. */
    data class Probe(val kind: RequestKind, val ttl: Duration?, override val key: String? = null) : Command {
        override val operation: String get() = "request.probe"
    }

    /** Новая задача (ТЗ, §21; D-42, п. 1). */
    data class AddTask(
        val title: String,
        val objective: String,
        val priority: Priority = Priority.NORMAL,
        val policy: TaskPolicy = TaskPolicy(),
        override val key: String? = null,
    ) : Command {
        override val operation: String get() = "task.add"
    }

    /** Приоритет задачи (ТЗ, §21; D-42). */
    data class SetPriority(override val task: TaskId, val priority: Priority, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.priority"
    }

    /**
     * Класс, модель, усилие и предел стоимости хода (D-40, D-42) — до
     * запуска; `null` у модели и усилия — по маршруту класса, у бюджета —
     * без предела.
     */
    data class SetPolicy(
        override val task: TaskId,
        val taskClass: TaskClass,
        val model: String?,
        val effort: String?,
        val maxBudgetUsdCents: Long?,
        override val key: String? = null,
    ) : Command, TaskCommand {
        override val operation: String get() = "task.policy"
    }

    /** Приостановить задачу: идущий ход останавливается с handoff, задача не запускается до [Resume]. */
    data class Hold(override val task: TaskId, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.hold"
    }

    /** Продолжить удержанную задачу: снова в план; продолжение — той же сессией провайдера. */
    data class Resume(override val task: TaskId, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.resume"
    }

    /** Отменить задачу: ход останавливается с уборкой процессов (опасно — подтверждает оболочка, ТЗ, §68). */
    data class Cancel(override val task: TaskId, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.cancel"
    }

    /**
     * Повторить упавшую, отменённую или заблокированную задачу со сбросом
     * счётчиков; [text] — ответ человека к следующему ходу (у заблокированной).
     */
    data class Retry(override val task: TaskId, val text: String? = null, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.retry"
    }

    /** Проверить вручную задачу без проверки (D-42): проверка в её дереве заново, без хода Claude. */
    data class Verify(override val task: TaskId, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.verify"
    }

    /** Принять непроверенное завершение с исключением политики (D-23): кто — оболочка, почему — [reason]. */
    data class Waive(override val task: TaskId, val reason: String, val by: String, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.waive"
    }

    /** Остановить ход сессии (D-42): ход остановлен, handoff записан, задача приостановлена. */
    data class StopSession(val session: com.dpsmpx.dpai.core.ids.SessionId, override val key: String? = null) : Command {
        override val operation: String get() = "session.stop"
    }

    /**
     * Запрос в сессию (D-42; ТЗ, §12): новый ход той же сессии провайдера
     * с [text], моделью и усилием; [fresh] — свежая сессия с handoff.
     */
    data class SendToSession(
        val session: com.dpsmpx.dpai.core.ids.SessionId,
        val text: String?,
        val model: String? = null,
        val effort: String? = null,
        val fresh: Boolean = false,
        override val key: String? = null,
    ) : Command {
        override val operation: String get() = if (fresh) "session.fresh" else "session.send"
    }

    /** Порог «у предела» подписки в процентах загрузки окна (D-45): на нём — откладывание несрочного и экономия D-40. */
    data class SetNearPercent(val subscription: com.dpsmpx.dpai.core.ids.SubscriptionId, val percent: Int, override val key: String? = null) : Command {
        override val operation: String get() = "subscription.near-percent"
    }

    /** Target принимает новые сессии или нет (D-42, п. 5); идущие на нём ходы доигрывают. */
    data class SetTargetEnabled(val target: com.dpsmpx.dpai.core.ids.ExecutionTargetId, val enabled: Boolean, override val key: String? = null) : Command {
        override val operation: String get() = "target.enabled"
    }

    /** Слот аккаунта включён или выключен (D-42, п. 5); после включения нужна проба входа. */
    data class SetSlotEnabled(val slot: com.dpsmpx.dpai.core.ids.AccountId, val enabled: Boolean, override val key: String? = null) : Command {
        override val operation: String get() = "slot.enabled"
    }

    /** Общий предел одновременных сессий контура (D-42, п. 6); `null` — без общего предела. */
    data class SetSessionLimit(val limit: Int?, override val key: String? = null) : Command {
        override val operation: String get() = "control.session-limit"
    }

    /** Спрятать завершённую задачу в архив или вернуть (D-42). */
    data class Archive(override val task: TaskId, val archived: Boolean, override val key: String? = null) : Command, TaskCommand {
        override val operation: String get() = "task.archive"
    }

    /** Все завершённые задачи — в архив (D-42); итог — сколько спрятано. */
    data class ArchiveFinished(override val key: String? = null) : Command {
        override val operation: String get() = "tasks.archive-done"
    }

    companion object {
        /** Ключ идемпотентности — короткая латиница: его пишет оболочка (UUID нажатия), а не человек. */
        val KEY = Regex("[A-Za-z0-9_.:-]{8,80}")
    }
}

/**
 * Очередь команд (D-42, п. 1): один писатель. Любой поток кладёт
 * команду ([submit]) и ждёт её итог; поток такта разбирает очередь в
 * безопасных точках — в начале такта и между вызовами target, когда
 * память совпадает с журналом (пачек нет). Так ответ человека не ждёт
 * весь такт с вызовами по SSH и не берёт замок на весь такт.
 */
class CommandInbox {
    private class Pending(val command: Command, val result: CompletableFuture<OperationResult<*>>)

    private val queue = LinkedBlockingDeque<Pending>()

    fun submit(command: Command): CompletableFuture<OperationResult<*>> =
        CompletableFuture<OperationResult<*>>().also { queue.put(Pending(command, it)) }

    val size: Int get() = queue.size

    /** Исполнить всё, что пришло к этому моменту; исключение исполнителя — итог-исключение у ждущего, очередь идёт дальше. */
    fun drain(execute: (Command) -> OperationResult<*>): Int {
        var n = 0
        while (true) {
            val p = queue.poll() ?: return n
            try {
                p.result.complete(execute(p.command))
            } catch (e: Exception) {
                p.result.completeExceptionally(e)
            }
            n++
        }
    }

    /**
     * Ждать команду до [timeoutMs]: поток такта спит между тактами, но
     * просыпается сразу, как пришла команда. `true` — команда есть.
     */
    fun await(timeoutMs: Long): Boolean {
        if (queue.isNotEmpty()) return true
        val p = queue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS) ?: return false
        // Взятое — обратно в голову: порядок команд сохраняется.
        queue.putFirst(p)
        return true
    }
}

/** Команда о задаче: её id — в происхождении итога (ТЗ, §5.4). */
interface TaskCommand {
    val task: TaskId
}

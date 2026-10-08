package com.dpsmpx.dpai.orchestrator.commands

import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.tasks.Priority
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

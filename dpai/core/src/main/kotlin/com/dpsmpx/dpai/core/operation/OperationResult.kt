package com.dpsmpx.dpai.core.operation

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ContextId
import com.dpsmpx.dpai.core.ids.CorrelationId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.OperationId
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.RepositoryId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome

/** Кто инициировал действие (ТЗ, §5.4). */
enum class ActorKind { USER, ORCHESTRATOR, AGENT, SYSTEM }

data class Actor(val kind: ActorKind, val label: String)

/**
 * Происхождение действия (ТЗ, §5.4, §153): кто запустил, какая задача,
 * аккаунт, сессия, контекст, проект, коммит, execution target. Поле, которого
 * у действия нет, — `null`, а не выдуманное значение (D-13).
 */
data class Provenance(
    val initiator: Actor,
    val taskId: TaskId? = null,
    val accountId: AccountId? = null,
    val sessionId: SessionId? = null,
    val contextId: ContextId? = null,
    val contextVersion: Int? = null,
    val projectId: ProjectId? = null,
    val repositoryId: RepositoryId? = null,
    val commit: String? = null,
    val executionTargetId: ExecutionTargetId? = null,
    val correlationId: CorrelationId? = null,
)

enum class OperationStatus { SUCCEEDED, SUCCEEDED_WITH_WARNINGS, FAILED }

/**
 * Результат команды оркестратора (D-06; ТЗ, §5.1): статус, значение,
 * диагностика, предупреждения, причины, `operationId`, время, происхождение.
 * Статус выводится из [outcome] и не задаётся отдельно — «успех с ошибкой»
 * невыразим.
 */
class OperationResult<out T> private constructor(
    val operationId: OperationId,
    val operation: String,
    val timestamp: Timestamp,
    val provenance: Provenance,
    val outcome: Outcome<T>,
) {
    val status: OperationStatus
        get() = when {
            outcome is Outcome.Failure -> OperationStatus.FAILED
            outcome.warnings.isNotEmpty() -> OperationStatus.SUCCEEDED_WITH_WARNINGS
            else -> OperationStatus.SUCCEEDED
        }

    val value: T? get() = outcome.valueOrNull()

    val diagnostics: List<Diagnostic> get() = outcome.diagnostics

    val warnings: List<Diagnostic> get() = outcome.warnings

    /** Исходные причины отказа — листья причинных цепочек ошибок; у успеха — пусто. */
    val causes: List<Diagnostic> get() = outcome.errors.flatMap { it.rootCauses() }

    companion object {
        fun <T> record(
            operation: String,
            provenance: Provenance,
            ids: IdGenerator,
            clock: Clock,
            outcome: Outcome<T>,
        ): OperationResult<T> = OperationResult(ids.next(IdKinds.OPERATION), operation, clock.now(), provenance, outcome)
    }
}

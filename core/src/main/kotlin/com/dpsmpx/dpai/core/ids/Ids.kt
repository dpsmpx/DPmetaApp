package com.dpsmpx.dpai.core.ids

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Типизированный идентификатор (D-08): `<префикс>_<26 знаков Crockford base32>`.
 * Тело — 48 бит времени создания (мс эпохи) и 80 бит случайности, поэтому
 * идентификаторы одного вида сортируются по времени создания.
 */
sealed interface TypedId {
    val value: String
}

/**
 * Вид идентификатора: префикс, имя типа, создание значения. Разбор проверяет
 * алфавит, длину и префикс: идентификатор задачи нельзя прочитать как
 * идентификатор сессии (`ID_WRONG_KIND`).
 */
class IdKind<T : TypedId> internal constructor(
    val prefix: String,
    val typeName: String,
    private val wrap: (String) -> T,
) {
    fun parse(text: String): Outcome<T> {
        val sep = text.indexOf('_')
        if (sep <= 0) return Outcome.failure(DiagCode.ID_INVALID, "не идентификатор $typeName: нет префикса")
        val prefix = text.substring(0, sep)
        val body = text.substring(sep + 1)
        if (prefix != this.prefix) {
            val other = IdKinds.ALL.firstOrNull { it.prefix == prefix }
            return if (other != null) {
                Outcome.failure(DiagCode.ID_WRONG_KIND, "ожидался $typeName, а это ${other.typeName}")
            } else {
                Outcome.failure(DiagCode.ID_INVALID, "не идентификатор $typeName: неизвестный префикс")
            }
        }
        if (!Crockford.isValidBody(body)) {
            return Outcome.failure(DiagCode.ID_INVALID, "не идентификатор $typeName: тело — не 26 знаков Crockford base32")
        }
        return Outcome.success(wrap(text))
    }

    /** Для значений, которые заведомо верны (константы тестов); неверное — ошибка программиста. */
    fun trusted(text: String): T = requireNotNull(parse(text).valueOrNull()) { "неверный $typeName: $text" }

    internal fun fromBody(body: String): T = wrap(prefix + "_" + body)

    /** Время создания, закодированное в идентификаторе. */
    fun createdAt(id: T): Timestamp = Timestamp(Crockford.decodeTime(id.value.substring(prefix.length + 1)))

    override fun toString(): String = "IdKind($typeName)"
}

@JvmInline value class TaskId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class SessionId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class AccountId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class ExecutionTargetId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class ProjectId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class RepositoryId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class WorktreeId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class ContextId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class HandoffId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class EventId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class OperationId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class CorrelationId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class ApprovalId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

@JvmInline value class SubscriptionId internal constructor(override val value: String) : TypedId {
    override fun toString(): String = value
}

/** Все виды идентификаторов; префиксы — `docs/DATA_MODEL.md`, §1. */
object IdKinds {
    val TASK = IdKind("tsk", "TaskId", ::TaskId)
    val SESSION = IdKind("ses", "SessionId", ::SessionId)
    val ACCOUNT = IdKind("acc", "AccountId", ::AccountId)
    val TARGET = IdKind("tgt", "ExecutionTargetId", ::ExecutionTargetId)
    val PROJECT = IdKind("prj", "ProjectId", ::ProjectId)
    val REPOSITORY = IdKind("rep", "RepositoryId", ::RepositoryId)
    val WORKTREE = IdKind("wtr", "WorktreeId", ::WorktreeId)
    val CONTEXT = IdKind("ctx", "ContextId", ::ContextId)
    val HANDOFF = IdKind("hnd", "HandoffId", ::HandoffId)
    val EVENT = IdKind("evt", "EventId", ::EventId)
    val OPERATION = IdKind("op", "OperationId", ::OperationId)
    val CORRELATION = IdKind("cor", "CorrelationId", ::CorrelationId)
    val APPROVAL = IdKind("apr", "ApprovalId", ::ApprovalId)
    val SUBSCRIPTION = IdKind("sub", "SubscriptionId", ::SubscriptionId)

    val ALL: List<IdKind<*>> = listOf(
        TASK, SESSION, ACCOUNT, TARGET, PROJECT, REPOSITORY, WORKTREE, CONTEXT, HANDOFF, EVENT, OPERATION,
        CORRELATION, APPROVAL, SUBSCRIPTION,
    )

    init {
        check(ALL.map { it.prefix }.toSet().size == ALL.size) { "префиксы идентификаторов повторяются" }
    }
}

/**
 * Идентификатор сессии у провайдера (ТЗ, §28): непрозрачная строка, у Claude
 * Code — UUID (`docs/research/CLAUDE_CODE_CLI.md`). Отдельный тип: его нельзя
 * перепутать с [SessionId] оркестратора.
 */
@JvmInline
value class ProviderSessionId internal constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val SAFE = Regex("[A-Za-z0-9._:-]{1,128}")

        /**
         * Новый UUID версии 4 из [random] (D-07): оркестратор сам назначает id
         * сессии Claude Code (`--session-id`) и пишет его в журнал до запуска
         * (`docs/research/CLAUDE_CODE_CLI.md`, §3).
         */
        fun newUuid(random: RandomSource): ProviderSessionId {
            val b = random.nextBytes(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return ProviderSessionId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
        }

        fun parse(text: String): Outcome<ProviderSessionId> =
            if (SAFE.matches(text)) {
                Outcome.success(ProviderSessionId(text))
            } else {
                Outcome.failure(DiagCode.ID_INVALID, "не идентификатор сессии провайдера: 1–128 знаков [A-Za-z0-9._:-]")
            }
    }
}

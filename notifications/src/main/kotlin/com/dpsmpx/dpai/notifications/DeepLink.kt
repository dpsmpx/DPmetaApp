package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Ссылка `dpai://<вид>/<id>` (ТЗ, §76): из уведомления — прямо к сущности.
 * Разбор строгий: вид из списка, идентификатор своего вида.
 */
data class DeepLink(val kind: Kind, val id: String) {
    enum class Kind(val path: String, internal val idKind: IdKind<*>) {
        TASK("task", IdKinds.TASK),
        SESSION("session", IdKinds.SESSION),
        PROJECT("project", IdKinds.PROJECT),
        ACCOUNT("account", IdKinds.ACCOUNT),
        SUBSCRIPTION("subscription", IdKinds.SUBSCRIPTION),
        EVENT("event", IdKinds.EVENT),
    }

    fun toUri(): String = "$SCHEME://${kind.path}/$id"

    override fun toString(): String = toUri()

    companion object {
        const val SCHEME = "dpai"

        fun of(kind: Kind, id: com.dpsmpx.dpai.core.ids.TypedId): DeepLink = DeepLink(kind, id.value)

        fun parse(uri: String): Outcome<DeepLink> {
            val m = Regex("""dpai://([a-z]+)/([A-Za-z0-9_]+)""").matchEntire(uri)
                ?: return Outcome.failure(DiagCode.VALUE_INVALID, "не ссылка dpai://<вид>/<id>")
            val kind = Kind.entries.firstOrNull { it.path == m.groupValues[1] }
                ?: return Outcome.failure(DiagCode.VALUE_INVALID, "неизвестный вид ссылки")
            return when (val id = kind.idKind.parse(m.groupValues[2])) {
                is Outcome.Success -> Outcome.success(DeepLink(kind, m.groupValues[2]))
                is Outcome.Failure -> Outcome.Failure(id.diagnostics)
            }
        }
    }
}

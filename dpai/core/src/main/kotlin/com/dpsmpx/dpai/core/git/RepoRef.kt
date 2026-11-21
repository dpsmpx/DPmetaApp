package com.dpsmpx.dpai.core.git

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Репозиторий GitHub `владелец/имя` (DPmetaApp:D-179). Строгая проверка: имя не
 * подставит в запрос путь или параметр.
 */
@ConsistentCopyVisibility
data class RepoRef private constructor(val owner: String, val name: String) {
    override fun toString(): String = "$owner/$name"

    companion object {
        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val NAME = Regex("[A-Za-z0-9._-]{1,100}")
        private val PULL = Regex("""https://github\.com/([^/]+)/([^/]+)/pull/[0-9]+""")

        /** Только каноническая ссылка GitHub PR; владелец и имя проверяются тем же правилом. */
        fun fromPullUrl(url: String): Outcome<RepoRef> {
            val found = PULL.matchEntire(url) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "не ссылка GitHub PR")
            return of(found.groupValues[1], found.groupValues[2])
        }

        fun of(owner: String, name: String): Outcome<RepoRef> = when {
            !OWNER.matches(owner) -> Outcome.failure(DiagCode.VALUE_INVALID, "владелец репозитория недопустим", owner)
            !NAME.matches(name) || name == "." || name == ".." || name.endsWith(".git") ->
                Outcome.failure(DiagCode.VALUE_INVALID, "имя репозитория недопустимо", name)
            else -> Outcome.success(RepoRef(owner, name))
        }

        /** Из записи `владелец/имя`. */
        fun parse(text: String): Outcome<RepoRef> {
            val parts = text.trim().split('/')
            if (parts.size != 2) return Outcome.failure(DiagCode.VALUE_INVALID, "ожидалось «владелец/имя»", text)
            return of(parts[0], parts[1])
        }
    }
}


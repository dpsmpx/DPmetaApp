package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.state.AccountState.*

/** Состояние слота аккаунта (`docs/ACCOUNT_MODEL.md`, §2; ТЗ, §9). */
enum class AccountState { AUTH_REQUIRED, AVAILABLE, BUSY, COOLDOWN, RATE_LIMITED, ERROR, DISABLED }

object AccountLifecycle {
    val table: TransitionTable<AccountState> =
        TransitionTable(
            name = "аккаунт",
            states = AccountState.entries,
            initial = AUTH_REQUIRED,
            terminal = emptySet(),
            ordinary = mapOf(
                AUTH_REQUIRED to setOf(AVAILABLE, ERROR, DISABLED),
                AVAILABLE to setOf(BUSY, COOLDOWN, RATE_LIMITED, AUTH_REQUIRED, ERROR, DISABLED),
                BUSY to setOf(AVAILABLE, COOLDOWN, RATE_LIMITED, AUTH_REQUIRED, ERROR, DISABLED),
                COOLDOWN to setOf(AVAILABLE, AUTH_REQUIRED, ERROR, DISABLED),
                RATE_LIMITED to setOf(COOLDOWN, AVAILABLE, AUTH_REQUIRED, ERROR, DISABLED),
                ERROR to setOf(AVAILABLE, COOLDOWN, AUTH_REQUIRED, DISABLED),
            ),
            explicit = mapOf(DISABLED to setOf(AUTH_REQUIRED)),
        )
}

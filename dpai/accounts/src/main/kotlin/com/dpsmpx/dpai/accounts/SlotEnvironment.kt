package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Окружение процесса `claude` для слота (D-27, D-29; `docs/ACCOUNT_MODEL.md`, §4).
 *
 * Переменные, которые перекрыли бы вход слота формой Claude или увели бы
 * запросы с его токеном на другой адрес, удаляются; `CLAUDE_CONFIG_DIR` —
 * каталог слота. Предупреждение называет только имена удалённых
 * переменных, не значения (D-14).
 */
object SlotEnvironment {
    const val CONFIG_DIR = "CLAUDE_CONFIG_DIR"

    /** Перекрывают вход по подписке (`docs/research/CLAUDE_CODE_CLI.md`, §4) или меняют адрес запросов. */
    val OVERRIDING: List<String> = listOf(
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_VERTEX",
        "CLAUDE_CODE_USE_FOUNDRY",
        "ANTHROPIC_PROFILE",
        "ANTHROPIC_FEDERATION_RULE_ID",
        "ANTHROPIC_ORGANIZATION_ID",
        "ANTHROPIC_IDENTITY_TOKEN_FILE",
        "ANTHROPIC_BASE_URL",
    )

    fun forSlot(base: Map<String, String>, slot: SlotState): Outcome<Map<String, String>> {
        val removed = OVERRIDING.filter { it in base }
        val env = base.filterKeys { it !in OVERRIDING }.toMutableMap()
        val replacedDir = base[CONFIG_DIR]?.takeIf { it != slot.configDir }
        env[CONFIG_DIR] = slot.configDir
        val notes = buildList {
            if (removed.isNotEmpty()) {
                add(
                    Diagnostic.warning(
                        DiagCode.ACCOUNT_ENVIRONMENT_SANITIZED,
                        "из окружения удалены переменные, перекрывающие вход формой Claude: ${removed.joinToString()}",
                        "slot ${slot.id}",
                    ),
                )
            }
            if (replacedDir != null) {
                add(Diagnostic.warning(DiagCode.ACCOUNT_ENVIRONMENT_SANITIZED, "$CONFIG_DIR окружения заменён каталогом слота", "slot ${slot.id}"))
            }
        }
        return Outcome.success(env.toSortedMap(), notes)
    }
}

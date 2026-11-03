package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.Executor
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

    /** Каталог настроек и входа слота по исполнителю (D-100). */
    fun configVar(executor: Executor): String = when (executor) {
        Executor.CLAUDE -> CONFIG_DIR
        Executor.CODEX -> CODEX_HOME
        Executor.GEMINI -> GEMINI_HOME
    }

    /** Переменные, которые перекрыли бы вход слота аккаунтом или увели запросы (D-29, D-100). */
    fun overriding(executor: Executor): List<String> = when (executor) {
        Executor.CLAUDE -> OVERRIDING
        Executor.CODEX -> CODEX_OVERRIDING
        Executor.GEMINI -> GEMINI_OVERRIDING
    }

    const val CODEX_HOME = "CODEX_HOME"
    const val GEMINI_HOME = "GEMINI_CLI_HOME"

    /** Ключи OpenAI и Codex, свой адрес API (`docs/research/CODEX_CLI.md`, §2). */
    val CODEX_OVERRIDING: List<String> = listOf("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL")

    /** Ключи Gemini и Google, Vertex и облачный проект (`docs/research/GEMINI_CLI.md`, §2). */
    val GEMINI_OVERRIDING: List<String> = listOf(
        "GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GENAI_USE_VERTEXAI", "GOOGLE_GENAI_USE_GCA",
        "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_CLOUD_PROJECT",
    )

    fun forSlot(base: Map<String, String>, slot: SlotState): Outcome<Map<String, String>> {
        val overriding = overriding(slot.executor)
        val dirVar = configVar(slot.executor)
        val removed = overriding.filter { it in base }
        val env = base.filterKeys { it !in overriding }.toMutableMap()
        val replacedDir = base[dirVar]?.takeIf { it != slot.configDir }
        env[dirVar] = slot.configDir
        val notes = buildList {
            if (removed.isNotEmpty()) {
                add(
                    Diagnostic.warning(
                        DiagCode.ACCOUNT_ENVIRONMENT_SANITIZED,
                        "из окружения удалены переменные, перекрывающие ${AuthStatus.allowedPhrase(slot.executor)}: ${removed.joinToString()}",
                        "slot ${slot.id}",
                    ),
                )
            }
            if (replacedDir != null) {
                add(Diagnostic.warning(DiagCode.ACCOUNT_ENVIRONMENT_SANITIZED, "$dirVar окружения заменён каталогом слота", "slot ${slot.id}"))
            }
        }
        return Outcome.success(env.toSortedMap(), notes)
    }
}

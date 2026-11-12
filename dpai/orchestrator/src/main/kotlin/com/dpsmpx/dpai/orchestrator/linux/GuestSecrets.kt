package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.diagnostics.Secret

/**
 * Секреты для ИИ в Linux приложения (`DPmetaApp:D-114`; D-144): переменные
 * окружения процессов гостя — ходов «в приложении» и терминала. Значения — только
 * в окружении процесса: в документ хода (`RunSpec`) и в файлы они не попадают.
 *
 * Переменная, которая у слота перекрыла бы вход по подписке (D-29, D-100), у этого
 * слота удаляется, как прежде; копию ключа он видит под именем [PREFIX]`<имя>`.
 */
object GuestSecrets {
    const val PREFIX = "DPMETA_"

    /** Имя переменной: латиница в верхнем регистре, цифры, `_`; не с цифры. */
    val NAME = Regex("^[A-Z_][A-Z0-9_]{0,63}$")

    /** Переменные, которые перекрывают вход хотя бы у одного вида слота. */
    val SLOT_OVERRIDING: Set<String> =
        (SlotEnvironment.OVERRIDING + SlotEnvironment.CODEX_OVERRIDING + SlotEnvironment.GEMINI_OVERRIDING).toSet()

    /** Имя копии для слотов, у которых [variable] удаляется; не перекрывает вход — `null`. */
    fun alias(variable: String): String? = if (variable in SLOT_OVERRIDING) PREFIX + variable else null

    /**
     * Переменная ключа API-профиля по провайдеру и адресу: у OpenAI-совместимых —
     * по известному хосту, иначе — `OPENAI_API_KEY` (так их читают SDK).
     */
    fun variableFor(kind: ProviderKind, host: String): String = when (kind) {
        ProviderKind.ANTHROPIC -> "ANTHROPIC_API_KEY"
        ProviderKind.GEMINI -> "GEMINI_API_KEY"
        ProviderKind.OPENAI_COMPATIBLE -> when (host.lowercase()) {
            "openrouter.ai" -> "OPENROUTER_API_KEY"
            "api.deepseek.com" -> "DEEPSEEK_API_KEY"
            "api.mistral.ai" -> "MISTRAL_API_KEY"
            else -> "OPENAI_API_KEY"
        }
    }

    /** Выданный секрет: переменная и значение. */
    data class Issued(val variable: String, val value: Secret)

    /**
     * Окружение гостя из выданных секретов: стандартное имя и, если оно перекрывает
     * вход слота, копия [PREFIX]`<имя>`. Пустое значение и неверное имя — пропуск;
     * у одной переменной — первый.
     */
    fun environment(issued: List<Issued>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (i in issued) {
            if (!NAME.matches(i.variable) || i.value.isEmpty || i.variable in out) continue
            val value = i.value.reveal()
            out[i.variable] = value
            alias(i.variable)?.let { out[it] = value }
        }
        return out
    }

    /** Имена переменных окружения — для журнала и карты, без значений. */
    fun names(issued: List<Issued>): List<String> =
        issued.filter { NAME.matches(it.variable) && !it.value.isEmpty }.map { it.variable }.distinct().flatMap { listOfNotNull(it, alias(it)) }
}

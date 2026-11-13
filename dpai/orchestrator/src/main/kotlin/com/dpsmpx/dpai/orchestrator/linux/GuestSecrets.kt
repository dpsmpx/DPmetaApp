package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
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

    /** Переменные, которыми управляет приложение: свой секрет их именем не называется. */
    val MANAGED: Set<String> = setOf("GH_TOKEN", "GITHUB_TOKEN", "HF_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY", "DEEPSEEK_API_KEY", "MISTRAL_API_KEY")

    /** Переменные среды, подмена которых меняет поведение оболочки и загрузчика, а не передаёт секрет. */
    private val SYSTEM: Set<String> = setOf("PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD", "TERM", "LANG", "TMPDIR", "IFS", "ENV", "BASH_ENV", "PS1", "PS4", "HOSTNAME", "EDITOR", "PAGER")
    private val SYSTEM_PREFIXES: List<String> = listOf("LD_", "LC_", "PROOT_", "DPAI_", "CLAUDE_", "CODEX_", "GEMINI_CLI_", "GIT_", "SSH_", "NODE_", "NPM_CONFIG_", "PYTHON", "JAVA_", "_")

    /**
     * Имя своего секрета автора (`DPmetaApp:D-113`, `DPmetaApp:D-117`) — оно же имя
     * переменной окружения: латиница в верхнем регистре, цифры и `_`, с буквы, до 64
     * знаков. Не принимаются имена, которыми управляет приложение, имена с
     * [PREFIX], переменные слотов и переменные самой среды (`PATH`, `LD_…`, `GIT_…`):
     * секрет под таким именем менял бы поведение программ, а не только был бы им виден.
     */
    fun customName(raw: String): Outcome<String> {
        val name = raw.trim()
        fun no(why: String) = Outcome.failure(DiagCode.VALUE_INVALID, why, name.take(64))
        return when {
            name.isEmpty() -> Outcome.failure(DiagCode.FIELD_MISSING, "назовите секрет: например, MY_SERVICE_TOKEN")
            !NAME.matches(name) || name.startsWith("_") -> no("имя — латиница в верхнем регистре, цифры и «_», с буквы, до 64 знаков")
            name.startsWith(PREFIX) -> no("имена с $PREFIX приложение даёт слотам само")
            name in MANAGED || name in SLOT_OVERRIDING -> no("этим именем управляет приложение: выдайте секрет из списка выше")
            name in SYSTEM || SYSTEM_PREFIXES.any { name.startsWith(it) } -> no("это переменная самой среды Linux: под таким именем секрет менял бы работу программ")
            else -> Outcome.success(name)
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

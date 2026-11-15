package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Свой параметр ручного профиля (D-183): имя для подстановки `${ИМЯ}`, подпись для
 * человека и варианты; по умолчанию — первый вариант.
 */
data class ManualParam(val name: String, val title: String, val choices: List<String>) {
    val valid: Boolean
        get() = NAME.matches(name) && name !in ManualVars.BUILT_IN &&
            title.isNotBlank() && title.length <= MAX_TITLE && title.none { it.isISOControl() } &&
            choices.size in 1..MAX_CHOICES && choices.distinct().size == choices.size &&
            choices.all { it.isNotEmpty() && it.length <= MAX_VALUE && it.none { c -> c.isISOControl() } }

    companion object {
        val NAME = Regex("[A-Z][A-Z0-9_]{0,31}")
        const val MAX = 10
        const val MAX_TITLE = 60
        const val MAX_CHOICES = 20
        const val MAX_VALUE = 200
    }
}

/**
 * Подстановки ручных профилей (D-183): встроенные переменные, свои параметры и
 * окружение шагов. Только текст — без Android и процессов.
 */
object ManualVars {
    /** Встроенные: имя папки проекта, корень проекта, дерево запуска, ветка, имя target. */
    val BUILT_IN: Set<String> = setOf("PROJECT", "ROOT", "TREE", "BRANCH", "TARGET")
    const val MAX_ENV = 32

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    private val SECRET_LIKE = Regex("TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|PRIVATE_?KEY|CREDENTIAL", RegexOption.IGNORE_CASE)
    private val REF = Regex("\\$\\$\\{|\\$\\{([A-Za-z0-9_]*)\\}")

    /** Значения встроенных для запуска [project] на [target] в дереве [location]. */
    fun builtIns(project: ProjectEntry, target: TargetEntry, location: WorkspaceLocation): Map<String, String> = mapOf(
        "PROJECT" to project.root.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'),
        "ROOT" to project.root,
        "TREE" to location.path,
        "BRANCH" to location.branch,
        "TARGET" to target.label,
    )

    /** Имена, на которые ссылается [text] (`$${` — не ссылка). */
    fun names(text: String): Set<String> = REF.findAll(text).mapNotNull { m -> m.groups[1]?.value }.toSet()

    /** Подставить `${ИМЯ}` из [values]; `$${` — знак `${`; неизвестное имя — отказ. */
    fun substitute(text: String, values: Map<String, String>): Outcome<String> {
        val out = StringBuilder()
        var last = 0
        for (m in REF.findAll(text)) {
            out.append(text, last, m.range.first)
            val name = m.groups[1]?.value
            if (name == null) {
                out.append("\${")
            } else {
                out.append(values[name] ?: return Outcome.failure(DiagCode.VALUE_INVALID, "нет параметра \${$name}: встроенные — ${BUILT_IN.joinToString()}, свои — в профиле"))
            }
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return Outcome.success(out.toString())
    }

    /** Почему переменную окружения нельзя хранить в профиле; `null` — можно. */
    fun envProblem(name: String, value: String): String? = when {
        !ENV_NAME.matches(name) -> "имя переменной «$name» — латиница, цифры и «_», не с цифры"
        SECRET_LIKE.containsMatchIn(name) -> "«$name» похоже на секрет — секреты даются только по разрешению, не в профиле"
        value.length > ManualParam.MAX_VALUE * 5 || value.any { it == '\u0000' || it == '\n' } -> "значение «$name» — одна строка до ${ManualParam.MAX_VALUE * 5} знаков"
        else -> null
    }

    /** Окружение из поля: по строке `ИМЯ=значение`; пустые строки пропускаются. */
    fun parseEnv(text: String): Outcome<List<Pair<String, String>>> {
        val out = ArrayList<Pair<String, String>>()
        for ((i, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return Outcome.failure(DiagCode.VALUE_INVALID, "окружение, строка ${i + 1}: нужно ИМЯ=значение")
            val name = line.substring(0, eq).trim()
            val value = line.substring(eq + 1)
            envProblem(name, value)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "окружение, строка ${i + 1}: $it") }
            if (out.any { it.first == name }) return Outcome.failure(DiagCode.VALUE_INVALID, "окружение: «$name» дважды")
            out += name to value
        }
        if (out.size > MAX_ENV) return Outcome.failure(DiagCode.VALUE_INVALID, "окружение — не больше $MAX_ENV переменных")
        return Outcome.success(out)
    }

    /** Параметры из поля: по строке `ИМЯ | подпись | вариант, вариант`. */
    fun parseParams(text: String): Outcome<List<ManualParam>> {
        val out = ArrayList<ManualParam>()
        for ((i, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split('|').map { it.trim() }
            val p = if (parts.size == 3) ManualParam(parts[0], parts[1], parts[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }) else null
            if (p == null || !p.valid) {
                return Outcome.failure(DiagCode.VALUE_INVALID,
                    "параметр, строка ${i + 1}: нужно ИМЯ | подпись | вариант, вариант — имя заглавными латинскими (не ${BUILT_IN.joinToString()}), 1…${ManualParam.MAX_CHOICES} разных вариантов")
            }
            if (out.any { it.name == p.name }) return Outcome.failure(DiagCode.VALUE_INVALID, "параметр ${p.name} — дважды")
            out += p
        }
        if (out.size > ManualParam.MAX) return Outcome.failure(DiagCode.VALUE_INVALID, "параметров — не больше ${ManualParam.MAX}")
        return Outcome.success(out)
    }

    /** Обратно в текст полей экрана. */
    fun paramsText(params: List<ManualParam>): String = params.joinToString("\n") { "${it.name} | ${it.title} | ${it.choices.joinToString(", ")}" }

    fun envText(env: List<Pair<String, String>>): String = env.joinToString("\n") { "${it.first}=${it.second}" }
}

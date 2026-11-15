package com.dpsmpx.dpai.ui

/**
 * Варианты после неудачной ручной сборки или проверки (D-188; `DPmetaApp:D-168`, «меньше ИИ»,
 * шаг 2): правила раскладывают известные ошибки на варианты без вызова ИИ, выбирает человек.
 * «Поручить ИИ» — всегда последним и только по выбору: это расход токенов. Текст вывода не
 * становится командой: варианты только называют, что сделать, и ведут на экран (D-141).
 */
object FailureOptions {
    enum class Kind { OPEN_PROBLEM, INSTALL_SDK, FREE_SPACE, NETWORK_RETRY, RAISE_TIMEOUT, MISSING_TOOL, MEMORY, ASK_AI }

    /** Вариант: что сделать и почему; [path]/[line] — место ошибки в дереве (для OPEN_PROBLEM). */
    data class Option(val kind: Kind, val title: String, val detail: String, val path: String? = null, val line: Int? = null)

    private val SDK = Regex("SDK location not found|ANDROID_HOME|ANDROID_SDK_ROOT|sdk\\.dir|Failed to find target with hash string|Failed to find Build Tools", RegexOption.IGNORE_CASE)
    private val SPACE = Regex("No space left on device|ENOSPC|not enough space", RegexOption.IGNORE_CASE)
    private val NETWORK = Regex(
        "Could not resolve host|Temporary failure in name resolution|UnknownHostException|Network is unreachable|Connection (?:refused|timed out)|" +
            "Could not GET|Could not HEAD|Read timed out|CERTIFICATE_VERIFY_FAILED|PKIX path building failed",
        RegexOption.IGNORE_CASE,
    )
    private val MEMORY = Regex("OutOfMemoryError|Java heap space|GC overhead limit exceeded|Gradle build daemon disappeared|daemon .* disappeared unexpectedly|Killed\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val NOT_FOUND = Regex("(?:command not found|not found: [^\\s]+|No such file or directory)", RegexOption.IGNORE_CASE)

    /**
     * [steps] — шаги профиля (имя и argv), [results] — итоги шагов, [output] — хвост вывода,
     * [problems] — места из вывода, [relative] — путь находки от корня дерева или `null`, если вне.
     */
    fun of(steps: List<ManualStep>, results: List<ManualStepResult>, output: String, problems: BuildProblemList, relative: (String) -> String?): List<Option> {
        val failed = results.lastOrNull { !it.ok } ?: return emptyList()
        val text = output.takeLast(MAX_OUTPUT)
        val step = steps.getOrNull(failed.step - 1)
        val out = mutableListOf<Option>()
        problems.items.firstOrNull { it.severity == ProblemSeverity.ERROR }?.let { p ->
            relative(p.path)?.let { rel -> out += Option(Kind.OPEN_PROBLEM, "Открыть ошибку: $rel:${p.line}", p.message.take(200), rel, p.line) }
        }
        if (failed.reason.contains("таймаут")) {
            out += Option(Kind.RAISE_TIMEOUT, "Увеличить таймаут шага ${failed.step}",
                "Шаг «${step?.name ?: failed.step}» не успел за ${step?.timeoutSeconds ?: "?"} с — поднимите таймаут в профиле или соберите на ПК-target.")
        }
        if (SDK.containsMatchIn(text)) {
            out += Option(Kind.INSTALL_SDK, "Поставить Android SDK", "Сборке не хватает Android SDK: «Настройки AI» → «Linux в приложении» → «Android SDK…», в профиле — «Нужно: Android SDK».")
        }
        if (SPACE.containsMatchIn(text)) out += Option(Kind.FREE_SPACE, "Освободить место", "На target кончилось место: удалите старые сборки и кеши («Хранилище») и повторите.")
        if (NETWORK.containsMatchIn(text)) out += Option(Kind.NETWORK_RETRY, "Повторить при сети", "Не удалось скачать зависимости или подключиться: проверьте сеть/VPN и запустите снова — код менять не нужно.")
        if (MEMORY.containsMatchIn(text)) {
            out += Option(Kind.MEMORY, "Меньше памяти сборке", "Не хватило памяти: уменьшите -Xmx в org.gradle.jvmargs (gradle.properties), закройте другие сборки или соберите на ПК-target.")
        }
        val tool = step?.command?.firstOrNull()
        if (tool != null && (failed.exitCode == 127 || NOT_FOUND.containsMatchIn(text) && text.contains(tool.substringAfterLast('/')))) {
            out += Option(Kind.MISSING_TOOL, "Нет программы: ${tool.take(80)}", "Программы нет на target или путь неверен: поставьте её («Linux в приложении») или поправьте команду шага ${failed.step}.")
        }
        out += Option(Kind.ASK_AI, "Поручить ИИ", "Новая задача ИИ с выжимкой ошибки — расходует токены; выберите, если варианты выше не подходят.")
        return out
    }

    /** Задача ИИ по ошибке: заголовок и цель — только выжимка (до 40 строк хвоста и 5 мест), без всего вывода. */
    fun aiTask(kind: ManualProfileKind, steps: List<ManualStep>, results: List<ManualStepResult>, output: String, problems: BuildProblemList, branch: String): Pair<String, String> {
        val failed = results.lastOrNull { !it.ok }
        val step = failed?.let { steps.getOrNull(it.step - 1) }
        val what = if (kind == ManualProfileKind.BUILD) "сборка" else "проверка"
        val title = "Исправить: $what не прошла" + (step?.let { " на шаге «${it.name.take(60)}»" } ?: "")
        val objective = buildString {
            appendLine("Ручная $what ветки $branch не прошла" + (failed?.let { " на шаге ${it.step}: ${it.reason}" } ?: "") + ".")
            step?.let { appendLine("Команда шага: ${ManualProfile.quote(it.command).take(400)}") }
            val places = problems.items.filter { it.severity == ProblemSeverity.ERROR }.take(5)
            if (places.isNotEmpty()) {
                appendLine("Ошибки:")
                for (p in places) appendLine("- ${p.path}:${p.line} — ${p.message.take(200)}")
            }
            appendLine("Хвост вывода:")
            append(output.lines().takeLast(40).joinToString("\n").takeLast(4000))
            appendLine()
            append("Найди причину, исправь код и запусти ту же ручную $what снова.")
        }
        return title to objective
    }

    private const val MAX_OUTPUT = 64 * 1024
}

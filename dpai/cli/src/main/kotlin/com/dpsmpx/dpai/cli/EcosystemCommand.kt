package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.ecosystem.ManifestEcosystem
import com.dpsmpx.dpai.orchestrator.ecosystem.EcosystemPlan
import com.dpsmpx.dpai.orchestrator.ecosystem.PlannedSuggestion
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Глобальный прогон экосистемы из командной строки (ТЗ, §43–§46; D-97,
 * часть 1). Манифест `dpai.ecosystem` — файлом: его строит
 * `DPmeta:tools/manifest.py` (`DPmeta:C-13`), код DPmeta сюда не переносится
 * (D-16), чтение — то же строгое, что у контура (`ManifestEcosystem`, D-09).
 *
 * - `show` — проекты, состояние и предложения с решением прогона;
 * - `run` — задачи из разрешённых предложений одного проекта ставятся
 *   контуру приложения через канал (D-47), каждая — с подтверждением
 *   человека (`docs/direction/V1_SCOPE.md`, §3); `--yes` подтверждает все.
 *   Ключ команды — проект и предложение: повторный прогон задачу не
 *   дублирует (D-42).
 */
class EcosystemCommand(
    private val out: (String) -> Unit,
    private val err: (String) -> Unit,
    /** Строка ответа человека на вопрос; конец ввода — `null`, то есть «нет». */
    private val input: () -> String?,
    /** Постановка задачи в контур — тем же каналом, что `dpai task add`; итог — код выхода. */
    private val submit: (PlannedSuggestion) -> Int,
    private val read: (String) -> String? = ::readFile,
) {
    fun run(args: List<String>): Int {
        unknownFlag(args)?.let { return usage("неизвестный ключ «$it»") }
        val action = args.getOrNull(0) ?: return usage()
        val file = args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: return usage()
        val projects = when (val m = ManifestEcosystem { read(file) }.projects()) {
            is Outcome.Failure -> {
                err("манифест $file не читается:")
                m.errors.forEach { err("  ${Words.reason(it.code.name)}: ${it.message}${it.subject?.let { s -> " ($s)" }.orEmpty()}") }
                return ControlCommand.FAILED
            }
            is Outcome.Success -> m.value
        }
        val operations = opt(args, "--operations")?.let(::csv)?.toSet() ?: setOf(DEFAULT_OPERATION)
        return when (action) {
            "show" -> show(projects, opt(args, "--project"), operations)
            "run" -> runProject(projects, opt(args, "--project") ?: return usage("нужен --project <ключ проекта из манифеста>"), operations, args)
            else -> usage()
        }
    }

    private fun show(projects: List<EcosystemProject>, project: String?, operations: Set<String>): Int {
        if (projects.isEmpty()) {
            out("в манифесте нет проектов")
            return ControlCommand.OK
        }
        // Без --project все проекты считаются подключёнными: видно, что разрешила бы политика.
        val connected = project?.let(::setOf) ?: projects.map { it.key }.toSet()
        val plan = EcosystemPlan.of(projects, connected, null, operations).groupBy { it.project.key }
        for (p in projects) {
            val state = p.state.toSortedMap().entries.joinToString(", ") { "${it.key} ${it.value}" }
            out("${p.key} — ${p.name}${if (state.isNotEmpty()) " ($state)" else ""}")
            if (p.documents.isNotEmpty()) out("  документы: ${p.documents.joinToString(", ")}")
            for (s in plan[p.key].orEmpty()) {
                val verdict = s.refusal ?: "можно поставить"
                out("  ${s.suggestion.key}  ${s.suggestion.priority}  ${s.suggestion.operation}  ${s.suggestion.title} — $verdict")
            }
        }
        return ControlCommand.OK
    }

    private fun runProject(projects: List<EcosystemProject>, project: String, operations: Set<String>, args: List<String>): Int {
        if (projects.none { it.key == project }) return ControlCommand.FAILED.also { err("в манифесте нет проекта «$project»: ${projects.joinToString(", ") { it.key }}") }
        val max = opt(args, "--max")?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return usage("--max — число задач, от 1") } ?: DEFAULT_MAX
        val yes = "--yes" in args
        val plan = EcosystemPlan.of(projects.filter { it.key == project }, setOf(project), setOf(project), operations)
        plan.filter { !it.allowed }.forEach { out("пропущено ${it.suggestion.key}: ${it.refusal}") }
        val allowed = plan.filter { it.allowed }
        if (allowed.isEmpty()) {
            out("ставить нечего: разрешённых предложений у «$project» нет")
            return ControlCommand.OK
        }
        var failed = false
        var placed = 0
        for (s in allowed) {
            if (placed >= max) {
                out("предел --max $max: остальные предложения — следующим прогоном")
                break
            }
            if (!yes && !confirm(s)) {
                out("не поставлено ${s.suggestion.key}: нет подтверждения")
                continue
            }
            if (submit(s) == ControlCommand.OK) placed++ else failed = true
        }
        return if (failed) ControlCommand.FAILED else ControlCommand.OK
    }

    private fun confirm(s: PlannedSuggestion): Boolean {
        out("Поставить задачу «${s.suggestion.title}» (${s.project.key}, ${s.suggestion.priority})? да/нет")
        val a = input()?.trim()?.lowercase() ?: return false
        return a in YES
    }

    private fun usage(message: String = USAGE): Int = ControlCommand.USAGE_ERROR.also { err(message) }

    companion object {
        /** Вид работы, который выгружает DPmeta (`DPmeta:C-13`): без --operations прогон берёт только его. */
        const val DEFAULT_OPERATION = "docs"

        /** Предел задач за один прогон — как у `RunPolicy` (ТЗ, §46). */
        const val DEFAULT_MAX = 10

        private val YES = setOf("да", "д", "yes", "y")
        private val VALUE_FLAGS = setOf("--project", "--operations", "--max")
        private val PLAIN_FLAGS = setOf("--yes")

        val USAGE = """
            |  dpai ecosystem show <манифест> [--project ключ] [--operations docs,…]
            |                            проекты экосистемы и предложения с решением прогона
            |  dpai ecosystem run <манифест> --project ключ [--operations docs,…] [--max N] [--yes]
            |                            задачи из предложений проекта — контуру приложения, каждая с подтверждением
            |                            (манифест строит DPmeta: python3 dpmeta/tools/manifest.py --out файл)
        """.trimMargin()

        private fun unknownFlag(args: List<String>): String? {
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a in VALUE_FLAGS -> i++
                    a.startsWith("--") && a !in PLAIN_FLAGS -> return a
                }
                i++
            }
            return null
        }

        private fun opt(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

        private fun csv(v: String): List<String> = v.split(',').map(String::trim).filter(String::isNotEmpty)

        private fun readFile(path: String): String? = try {
            Files.readString(Paths.get(path))
        } catch (_: IOException) {
            null
        }
    }
}

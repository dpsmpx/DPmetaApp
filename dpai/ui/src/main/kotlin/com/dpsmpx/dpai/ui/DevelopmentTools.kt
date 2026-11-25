package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCategory
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationParameter
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.git.GitStatus

/** Universal Development Tools, D-223: единые чтения для UI/API/CLI/сценариев, без shell. */
object DevelopmentTools {
    enum class Phase { SCAN, READ, ANALYZE }
    data class GitEvidence(val status: GitStatus, val patch: String = "", val truncated: Boolean = false)
    private fun field(name: String, title: String, required: Boolean = false, size: Int = 1000) =
        AutomationParameter(name, title, emptyList(), text = true, required = required, maxLength = size)
    private val defs = listOf(
        Triple("structure", "Карта проекта", emptyList()),
        Triple("find_files", "Подобрать файлы", listOf(field("query", "Задача или имена файлов", true, 200))),
        Triple("impact", "Влияние изменений и кандидаты тестов", emptyList()),
        Triple("diff", "Изменения Git", emptyList()),
        Triple("history", "История проверок и сборок", emptyList()),
        Triple("diagnostics", "Разобрать ошибку сборки или проверки", listOf(
            field("path", "Путь журнала; пусто — история профиля"),
            AutomationParameter("kind", "Вид профиля", listOf("BUILD", "CHECK"), "BUILD"),
            field("run", "Id запуска; пусто — последний", size = 100), field("baseline", "Id базы; пусто — предыдущий", size = 100))),
        Triple("tests", "Отчёт тестов JUnit", listOf(field("path", "Путь отчёта XML", true), field("baseline", "Путь базового XML"))),
        Triple("quality", "Отчёт анализатора SARIF", listOf(field("path", "Путь отчёта SARIF", true))),
        Triple("measurements", "Сравнить замеры", listOf(field("path", "Текущий JSON замеров", true), field("baseline", "Базовый JSON замеров", true))),
        Triple("artifact", "Проверить целостность артефакта", listOf(field("path", "Путь артефакта", true), field("sha256", "Ожидаемый SHA-256", size = 64))),
        Triple("duplicates", "Найти одинаковые файлы", emptyList()),
        Triple("symbol", "Исследовать символ через LSP", listOf(field("path", "Сохранённый файл", true), field("sha256", "SHA-256 прочитанного файла", true, 64),
            AutomationParameter("operation", "Операция LSP", listOf("symbols", "definition", "references", "impact", "incoming", "outgoing"), "symbols"),
            field("line", "Строка с 1", size = 8), field("column", "Столбец с 1 (UTF-16)", size = 8))),
    )

    val ids: Set<String> = defs.map { "development." + it.first }.toSet()
    fun describe(root: String, platform: TargetPlatform, inApp: Boolean): List<AutomationDefinition> = defs.map { (id, title, parameters) ->
        val semantic = id == "symbol"
        AutomationDefinition("development.$id", title, purpose(id), AutomationSource.DEVELOPMENT,
            if (id in setOf("diagnostics", "tests", "quality", "measurements", "artifact")) AutomationCategory.CHECK else AutomationCategory.REPOSITORY,
            Hashing.sha256("DevelopmentTools:1:$id").hex, ActionLevel.READ, listOf(root), AutomationInvocation.DIRECT,
            !semantic || inApp, reason = if (semantic && !inApp) "семантическая служба редактора доступна в Linux приложения; на SSH используйте настроенный профиль анализатора" else null,
            parameters = parameters, platforms = setOf(platform), timeoutSeconds = 60, remote = !semantic,
            deterministic = !semantic, idempotent = true, cancellable = true,
            verification = "Фактические входы и хеши; неполнота, неизвестная причина и границы анализа указаны явно", triggers = setOf("MANUAL", "AI"))
    }

    private fun purpose(id: String): String = when (id) {
        "structure" -> "project_structure_summary: файлы, языки и папки build-файлов, без исполнения конфигурации. Полнота и пределы явны."
        "find_files" -> "find_relevant_files: ранжирование путей по словам, не семантический поиск. Для смысла символа используйте development.symbol."
        "impact" -> "Потенциально затронутые модули и тесты по Git и буквальным project-зависимостям. Полный профиль проверки остаётся обязателен."
        "diff" -> "Настоящий Git diff от HEAD, включая staged; секретные пути и внешние diff/textconv исключены. Новые файлы указаны отдельно."
        "diagnostics" -> "Первичные кандидаты, дедупликация, гипотезы и сравнение сохранённых запусков; хвост лога не доказывает первопричину."
        "tests" -> "Фактические testcases JUnit, ошибки/пропуски, seed и изменение результатов. Не запускает тесты и не доказывает свежесть."
        "quality" -> "Нормализованный SARIF 2.1.0 от настроенных анализаторов безопасности, качества, лицензий и совместимости; не имитирует их запуск."
        "measurements" -> "Сравнить dpai.measurements v1 только при одинаковых методике, среде и единицах; commit и входные хеши обязательны."
        "artifact" -> "SHA-256 и размер артефакта до 64 МиБ, сравнение с ожидаемой суммой. Не подтверждает подпись и установку."
        "duplicates" -> "Точные повторы целых текстовых файлов; не доказывает семантическую эквивалентность и ненужность кода."
        "symbol" -> "find_symbol_impact: symbols/definition/references/call hierarchy действующей LSP для точного сохранённого текста. Нет службы — явный отказ."
        else -> "История существующих CHECK/BUILD с commit, временем, исходом и хешем сохранённого вывода."
    }

    fun run(id: String, values: Map<String, String>, tree: FileTree, root: String, clock: Clock,
        cancelled: () -> Boolean = { false }, history: () -> Outcome<List<ManualHistoryEntry>> = { unsupported() },
        git: (Boolean) -> Outcome<GitEvidence> = { unsupported() },
        semantic: (String, ByteArray, Map<String, String>) -> Outcome<JsonObject> = { _, _, _ -> unsupported() },
        checks: List<AutomationDefinition> = emptyList(), progress: (Phase, Int) -> Unit = { _, _ -> }): Outcome<JsonObject> {
        val definition = defs.firstOrNull { "development." + it.first == id } ?: return unsupported()
        if (values.keys.any { key -> definition.third.none { it.name == key } } || definition.third.any { p ->
                values[p.name]?.let { !p.accepts(it) } ?: (p.required && p.defaultValue == null) }) return invalid()
        val started = clock.monotonicNanos()
        fun stop() = cancelled() || clock.monotonicNanos() - started >= 60_000_000_000L
        if (stop()) return interrupted()
        progress(Phase.ANALYZE, 0)
        val input = definition.third.mapNotNull { p -> (values[p.name] ?: p.defaultValue)?.takeIf { it.isNotEmpty() }?.let { p.name to it } }.toMap()
        fun read(path: String, bytes: Long = 2L * 1024 * 1024): Outcome<ByteArray> {
            if (!reportPath(path)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "секретный, служебный или внешний путь недоступен")
            if (stop()) return interrupted()
            progress(Phase.READ, 0)
            return tree.read(path, bytes)
        }
        fun text(path: String): Outcome<String> = read(path).let { r -> when (r) { is Outcome.Failure -> r; is Outcome.Success -> TextFile.decode(r.value) } }
        val result: Outcome<JsonObject> = when (definition.first) {
            "structure", "find_files", "impact", "duplicates" -> {
                val survey = ProjectSurvey(tree, ::stop) { progress(Phase.SCAN, it) }
                when (val s = survey.scan()) {
                    is Outcome.Failure -> s
                    is Outcome.Success -> when (definition.first) {
                        "structure" -> Outcome.success(survey.structure(s.value))
                        "find_files" -> Outcome.success(survey.relevant(s.value, input["query"]!!))
                        "duplicates" -> survey.duplicates(s.value)
                        else -> when (val g = git(false)) { is Outcome.Failure -> g; is Outcome.Success -> survey.impact(s.value,
                            (g.value.status.changed + g.value.status.untracked + g.value.status.conflicted).filter(::safePath)).map {
                                JsonObject(it.fields + ("head" to (g.value.status.head?.let(::json) ?: JsonNull))) } }
                    }
                }
            }
            "diff" -> git(true).map { g ->
                JsonObject.of("head" to (g.status.head?.let(::json) ?: JsonNull), "basis" to json("GIT_DIFF_HEAD"),
                    "changed" to ProjectSurvey.strings(g.status.changed.filter(::safePath).take(80)),
                    "untracked" to ProjectSurvey.strings(g.status.untracked.filter(::safePath).take(80)),
                    "conflicted" to ProjectSurvey.strings(g.status.conflicted.filter(::safePath).take(80)),
                    "patch" to json(com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(g.patch).take(16_000)),
                    "patchHash" to json(Hashing.sha256(g.patch).hex), "truncated" to json(g.truncated || g.patch.length > 16_000),
                    "complete" to json(false), "note" to json("Git diff от HEAD: новые файлы не входят в patch; показаны разрешённые пути, снимок не атомарен."))
            }
            "diagnostics" -> if (input["path"] != null) {
                if (input["run"] != null || input["baseline"] != null) return invalid()
                when (val raw = text(input["path"]!!)) { is Outcome.Failure -> raw; is Outcome.Success ->
                    Outcome.success(JsonObject(DevelopmentDiagnostics.log(raw.value, root).fields + ("path" to json(input["path"]!!)))) }
            } else historyReport(input, root, history)
            "history" -> when (val h = history()) {
                is Outcome.Failure -> h
                is Outcome.Success -> Outcome.success(DevelopmentDiagnostics.history(h.value))
            }
            "tests", "quality", "measurements" -> {
                val current = when (val r = text(input["path"]!!)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
                val previous = input["baseline"]?.let { when (val r = text(it)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value } }
                when (definition.first) {
                    "quality" -> DevelopmentReports.sarif(current, ::stop)
                    "measurements" -> DevelopmentReports.measurements(current, previous ?: return invalid())
                    else -> {
                        val now = when (val r = DevelopmentReports.junit(current, ::stop)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
                        val before = previous?.let { when (val r = DevelopmentReports.junit(it, ::stop)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value } }
                        Outcome.success(DevelopmentReports.tests(now, before))
                    }
                }.map { JsonObject(it.fields + mapOf("path" to json(input["path"]!!), "baselinePath" to (input["baseline"]?.let(::json) ?: JsonNull))) }
            }
            "artifact" -> {
                val expected = input["sha256"]
                if (expected != null && !expected.matches(Regex("[a-f0-9]{64}"))) return invalid()
                when (val r = read(input["path"]!!, 64L * 1024 * 1024)) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> {
                        val hash = Hashing.sha256(r.value).hex
                        if (expected != null && hash != expected) Outcome.failure(DiagCode.STATE_CONFLICT, "SHA-256 артефакта не совпадает с ожидаемым")
                        else Outcome.success(JsonObject.of("path" to json(input["path"]!!), "bytes" to json(r.value.size), "sha256" to json(hash),
                            "checksumVerified" to json(expected != null), "signatureVerified" to json(false),
                            "note" to json("Хеш фактически прочитанных байтов; подпись, запуск и установка проверяются отдельным инструментом.")))
                    }
                }
            }
            "symbol" -> {
                val path = input["path"]!!
                if (!safePath(path) || !input["sha256"].orEmpty().matches(Regex("[a-f0-9]{64}"))) return invalid()
                when (val r = tree.read(path, TextFile.MAX_EDIT_BYTES)) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> {
                        if (Hashing.sha256(r.value).hex != input["sha256"]) return Outcome.failure(DiagCode.STATE_CONFLICT, "файл символа изменился")
                        val answer = semantic(path, r.value, input)
                        when (val reread = tree.read(path, TextFile.MAX_EDIT_BYTES)) {
                            is Outcome.Failure -> reread
                            is Outcome.Success -> if (Hashing.sha256(reread.value).hex != input["sha256"]) Outcome.failure(DiagCode.STATE_CONFLICT, "файл изменился во время запроса")
                            else if (input["operation"] == "impact" && answer is Outcome.Success) {
                                val paths = ((answer.value["items"] as? JsonArray)?.items.orEmpty().mapNotNull { ((it as? JsonObject)?.get("path") as? JsonString)?.value } + path).distinct()
                                val survey = ProjectSurvey(tree, ::stop)
                                when (val snapshot = survey.scan()) { is Outcome.Failure -> snapshot; is Outcome.Success -> survey.impact(snapshot.value, paths).map {
                                    JsonObject(answer.value.fields + ("potentialImpact" to it)) } }
                            } else answer
                        }
                    }
                }
            }
            else -> unsupported()
        }
        if (stop()) return interrupted()
        val verification = if (definition.first == "impact" || input["operation"] == "impact") mapOf("verificationProfiles" to JsonArray(checks
            .filter { it.id == "manual.check" || it.id.startsWith("verification.") }.map { d -> JsonObject.of("id" to json(d.id), "revision" to json(d.revision),
                "available" to json(d.available), "reason" to (d.reason?.let { json(it) } ?: JsonNull), "fullMilestoneRequired" to json(d.id == "verification.full")) })) else emptyMap()
        return result.map { bounded(JsonObject(it.fields + verification + mapOf("format" to json("dpai.development-report"), "version" to json(1),
            "tool" to json(id), "effect" to json("READ_ONLY")))) }
    }

    private fun historyReport(input: Map<String, String>, root: String, history: () -> Outcome<List<ManualHistoryEntry>>): Outcome<JsonObject> {
        val entries = when (val h = history()) { is Outcome.Failure -> return h; is Outcome.Success -> h.value.filter { it.kind.name == (input["kind"] ?: "BUILD") } }
        val current = if (input["run"] == null) entries.lastOrNull() else entries.firstOrNull { it.run.value == input["run"] }
        val baseline = if (input["baseline"] == null) current?.let { entries.takeWhile { e -> e.run != it.run }.lastOrNull() }
            else entries.firstOrNull { it.run.value == input["baseline"] }
        return if (current == null || input["baseline"] != null && baseline == null) Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "запуска этого вида нет в истории")
        else Outcome.success(DevelopmentDiagnostics.report(current, baseline, root, entries))
    }

    /** Ограничение сохраняет JSON и хеши; усечённый отчёт не выдаётся за полный. */
    private fun bounded(value: JsonObject): JsonObject {
        var result = value
        var changed = false
        repeat(64) {
            if (CanonicalJson.write(result).length <= 24_000) return if (changed) JsonObject(result.fields + mapOf("truncated" to json(true), "complete" to json(false))) else result
            val key = result.fields.entries.filter { it.value is JsonArray && (it.value as JsonArray).items.isNotEmpty() }
                .maxByOrNull { CanonicalJson.write(it.value).length }?.key ?: return JsonObject.of("format" to json("dpai.development-report"),
                    "version" to json(1), "tool" to value["tool"]!!, "truncated" to json(true), "complete" to json(false),
                    "payloadHash" to json(Hashing.sha256(value).hex), "note" to json("Отчёт превышает предел; уточните запрос."))
            val items = (result[key] as JsonArray).items
            result = JsonObject(result.fields + (key to JsonArray(items.take(items.size / 2))))
            changed = true
        }
        return JsonObject.of("format" to json("dpai.development-report"), "version" to json(1), "tool" to value["tool"]!!,
            "truncated" to json(true), "complete" to json(false), "payloadHash" to json(Hashing.sha256(value).hex),
            "note" to json("Отчёт превышает предел; уточните запрос."))
    }

    fun safePath(path: String): Boolean = path.length <= 1000 && FileNames.pathValid(path) && !ApiProjectFiles.hidden(path)
    private fun reportPath(path: String): Boolean = path.length <= 1000 && FileNames.pathValid(path) &&
        !ApiProjectFiles.hidden(path.split('/').joinToString("/") { if (it.equals("build", true)) "report-output" else it })
    private fun <T> unsupported(): Outcome<T> = Outcome.failure(DiagCode.UNSUPPORTED, "источник инструмента не подключён")
    private fun <T> invalid(): Outcome<T> = Outcome.failure(DiagCode.VALUE_INVALID, "неверные параметры инструмента разработки")
    private fun <T> interrupted(): Outcome<T> = Outcome.failure(DiagCode.CANCELLED_BY_USER, "анализ отменён или исчерпал 60 секунд")
}

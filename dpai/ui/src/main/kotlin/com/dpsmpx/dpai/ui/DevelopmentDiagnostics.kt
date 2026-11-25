package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json

/** Нормализует фактическую историю; первое сообщение не объявляется доказанной первопричиной. */
object DevelopmentDiagnostics {
    private fun fingerprint(p: BuildProblem, root: String): String = Hashing.sha256(JsonObject.of(
        "path" to json(BuildProblems.relative(p.path, root).orEmpty()), "severity" to json(p.severity.name),
        "message" to json(Redactor.STANDARD.redact(p.message)))).hex

    fun log(output: String, root: String): JsonObject {
        val parsed = BuildProblems.parse(output, 100)
        val visible = parsed.items.filter { BuildProblems.relative(it.path, root)?.let(DevelopmentTools::safePath) == true }
        return JsonObject.of("basis" to json("EXPLICIT_LOG_FILE"), "complete" to json(false),
            "errors" to json(parsed.errors), "warnings" to json(parsed.warnings), "sha256" to json(Hashing.sha256(output).hex),
            "items" to JsonArray(visible.take(25).map { item(it, root) }), "truncated" to json(parsed.limited || visible.size > 25),
            "omittedExternalOrSensitive" to json(parsed.items.size - visible.size), "rootCauseProven" to json(false),
            "note" to json("Сообщения поддерживаемых форматов; отсутствие распознанных сообщений не означает успешную проверку или исправление."))
    }

    private fun item(p: BuildProblem, root: String): JsonObject = JsonObject.of("id" to json(fingerprint(p, root)),
        "path" to json(BuildProblems.relative(p.path, root)!!), "line" to json(p.line),
        "column" to (p.column?.let { json(it) } ?: JsonNull), "severity" to json(p.severity.name), "category" to json("TOOL_MESSAGE"),
        "tool" to JsonNull, "toolVersion" to JsonNull, "code" to JsonNull,
        "message" to json(Redactor.STANDARD.redact(p.message)), "reportedByTool" to json(true), "rootCauseProven" to json(false))

    fun report(current: ManualHistoryEntry, baseline: ManualHistoryEntry?, root: String, history: List<ManualHistoryEntry> = emptyList()): JsonObject {
        val problems = BuildProblems.parse(current.output, 100)
        val previous = baseline?.let { BuildProblems.parse(it.output, 100) }
        val before = previous?.items?.map { fingerprint(it, root) }?.toSet().orEmpty()
        val now = problems.items.map { fingerprint(it, root) }.toSet()
        val similar = history.takeLast(20).filter { it.run != current.run && it.finishedAt <= current.finishedAt }.mapNotNull { candidate ->
            val matched = BuildProblems.parse(candidate.output, 100).items.map { fingerprint(it, root) }.toSet().intersect(now)
            if (matched.isEmpty()) null else JsonObject.of("run" to json(candidate.run.value), "matchingDiagnostics" to json(matched.size),
                "subsequentSuccessfulRun" to (history.firstOrNull { it.finishedAt > candidate.finishedAt && it.finishedAt <= current.finishedAt && it.outcome == ManualOutcome.OK }?.run?.value?.let(::json) ?: JsonNull),
                "resolutionProven" to json(false))
        }
        val items = problems.items.filter { BuildProblems.relative(it.path, root)?.let(DevelopmentTools::safePath) == true }
        val suggestions = FailureOptions.of(current.steps.map { ManualStep(it.first, listOf("unknown"), 1) },
            current.steps.mapNotNull { it.second }, current.output, problems) { BuildProblems.relative(it, root) }
            .filter { it.kind in setOf(FailureOptions.Kind.INSTALL_SDK, FailureOptions.Kind.FREE_SPACE, FailureOptions.Kind.NETWORK_RETRY, FailureOptions.Kind.MEMORY) }
        fun proof(run: ManualHistoryEntry): JsonObject = JsonObject.of("run" to json(run.run.value), "kind" to json(run.kind.name),
            "head" to (run.head?.let { json(it) } ?: JsonNull), "target" to json(run.target), "finishedAt" to json(run.finishedAt.epochMillis),
            "outcome" to json(run.outcome.name), "storedOutputHash" to json(Hashing.sha256(run.output).hex), "scope" to json("STORED_OUTPUT_TAIL"))
        return JsonObject.of("basis" to json("STORED_PROFILE_HISTORY"), "complete" to json(false), "run" to proof(current),
            "baseline" to (baseline?.let(::proof) ?: JsonNull),
            "errors" to json(problems.errors), "warnings" to json(problems.warnings),
            "items" to JsonArray(items.take(25).mapIndexed { index, p -> JsonObject(item(p, root).fields +
                ("primaryCandidate" to json(index == items.indexOfFirst { it.severity == ProblemSeverity.ERROR }))) }),
            "similarHistory" to JsonArray(similar.take(5)),
            "newDiagnosticIds" to ProjectSurvey.strings((now - before).sorted().take(30)),
            "absentDiagnosticIds" to ProjectSurvey.strings((before - now).sorted().take(30)),
            "hypotheses" to JsonArray(suggestions.take(6).map { option -> JsonObject.of("kind" to json(option.kind.name),
                "certainty" to json("HYPOTHESIS"), "evidence" to json("matched stored output"), "suggestion" to json(Redactor.STANDARD.redact(option.detail).take(500))) }),
            "omittedExternalOrSensitive" to json(problems.items.size - items.size), "truncated" to json(problems.limited || items.size > 25),
            "freshnessVerified" to json(false), "fullLogReference" to json("manual-history:" + current.run.value),
            "note" to json("Факты — сообщения инструмента. Первое сообщение и совпадение известного шаблона не доказывают причину; отсутствие ошибки в сохранённом хвосте не доказывает исправление."))
    }

    fun history(entries: List<ManualHistoryEntry>): JsonObject = JsonObject.of("basis" to json("STORED_PROFILE_HISTORY"),
        "runs" to JsonArray(entries.takeLast(20).asReversed().map { e -> JsonObject.of("run" to json(e.run.value), "kind" to json(e.kind.name),
            "head" to (e.head?.let { json(it) } ?: JsonNull), "finishedAt" to json(e.finishedAt.epochMillis), "outcome" to json(e.outcome.name),
            "durationMillis" to (e.duration?.inWholeMilliseconds?.let { json(it) } ?: JsonNull), "outputHash" to json(Hashing.sha256(e.output).hex)) }),
        "truncated" to json(entries.size > 20), "note" to json("История существующих запусков; новый процесс не запускается."))
}

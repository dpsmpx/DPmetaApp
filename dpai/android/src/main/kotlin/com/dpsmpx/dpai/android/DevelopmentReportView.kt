package com.dpsmpx.dpai.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/** Краткий результат в прежнем окне; доказательства раскрываются и копируются целиком. */
@Composable
internal fun DevelopmentReportView(report: JsonObject) {
    var details by rememberSaveable(CanonicalJson.write(report)) { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.development_result), style = MaterialTheme.typography.titleSmall)
        for ((key, label) in listOf("files" to R.string.development_count_files, "errors" to R.string.development_count_errors,
            "warnings" to R.string.development_count_warnings, "passed" to R.string.development_count_passed,
            "failed" to R.string.development_count_failed, "skipped" to R.string.development_count_skipped,
            "flakyReported" to R.string.development_count_flaky,
            "findings" to R.string.development_count_findings, "bytes" to R.string.development_count_bytes)) {
            (report[key] as? JsonNumber)?.let { Text(stringResource(label, it.literal)) }
        }
        val items = (report["items"] as? JsonArray)?.items.orEmpty().filterIsInstance<JsonObject>().take(8)
        for (item in items) {
            val path = (item["path"] as? JsonString)?.value
            val line = (item["line"] as? JsonNumber)?.literal
            listOfNotNull(path?.let { it + (line?.let { n -> ":$n" } ?: "") }, (item["name"] as? JsonString)?.value,
                (item["message"] as? JsonString)?.value).takeIf { it.isNotEmpty() }?.let { DiagnosticText(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
        }
        Text(stringResource(R.string.development_scope), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.development_hide_details else R.string.development_details)) }
        if (details) DiagnosticText(CanonicalJson.write(report), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun developmentTitle(id: String): Int = when (id.removePrefix("development.")) {
    "structure" -> R.string.development_structure
    "find_files" -> R.string.development_find_files
    "impact" -> R.string.development_impact
    "diff" -> R.string.development_diff
    "history" -> R.string.development_history
    "diagnostics" -> R.string.development_diagnostics
    "tests" -> R.string.development_tests
    "quality" -> R.string.development_quality
    "measurements" -> R.string.development_measurements
    "artifact" -> R.string.development_artifact
    "duplicates" -> R.string.development_duplicates
    else -> R.string.development_symbol
}

internal fun developmentParameter(name: String): Int = when (name) {
    "query" -> R.string.development_query
    "path" -> R.string.development_path
    "baseline" -> R.string.development_baseline
    "run" -> R.string.development_run
    "kind" -> R.string.development_kind
    "sha256" -> R.string.development_sha256
    "operation" -> R.string.development_operation
    "line" -> R.string.development_line
    else -> R.string.development_column
}

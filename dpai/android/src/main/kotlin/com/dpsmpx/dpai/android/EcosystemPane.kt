package com.dpsmpx.dpai.android

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.ecosystem.ManifestEcosystem
import com.dpsmpx.dpai.orchestrator.ecosystem.EcosystemPlan
import com.dpsmpx.dpai.orchestrator.ecosystem.PlannedSuggestion
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Глобальный прогон из приложения (ТЗ, §43–§46; D-97, часть 1). Манифест
 * `dpai.ecosystem` — файлом, выбранным системным окном: его строит
 * `DPmeta:tools/manifest.py` (`DPmeta:C-13`), код DPmeta сюда не переносится
 * (D-16). Проект этого контура выбирает человек (выбор запоминается);
 * задача из предложения ставится после «Поставить» в окне подтверждения
 * (`docs/direction/V1_SCOPE.md`, §3) командой с ключом
 * `ecosystem:<проект>:<предложение>` — повтор задачу не дублирует (D-42).
 * Отбор — тот же `EcosystemPlan`, что у `dpai ecosystem`. Без
 * подтверждения — только по политике прогона, которую человек включает
 * здесь же (D-106, `AutoPolicySection`).
 */
@Composable
fun EcosystemDialog(model: DemoModel, onDone: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<EcosystemProject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var project by remember { mutableStateOf(EcosystemChoice.project(context)) }
    var allOperations by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<PlannedSuggestion?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var kept by remember { mutableStateOf(false) }
    // Выбранный прежде манифест — сразу: его же читает проход политики (D-106).
    LaunchedEffect(Unit) {
        val uri = withContext(Dispatchers.IO) { EcosystemChoice.manifest(context) } ?: return@LaunchedEffect
        kept = true
        when (val r = withContext(Dispatchers.IO) { readManifest(context, uri) }) {
            is Outcome.Success -> projects = r.value
            is Outcome.Failure -> error = "Сохранённый манифест не читается: ${r.errors.joinToString("; ") { it.message }}"
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { readManifest(context, uri) }) {
                    is Outcome.Success -> {
                        projects = r.value
                        error = null
                        val before = withContext(Dispatchers.IO) { EcosystemChoice.manifest(context) }
                        kept = withContext(Dispatchers.IO) { EcosystemChoice.keepManifest(context, uri) }
                        // Другой файл — другие предложения: политика, включённая над прежним, выключается (D-106, T19).
                        if (before != null && before != uri) {
                            val off = withContext(Dispatchers.IO) {
                                val auto = Controller.get(context.filesDir).ecosystemAuto
                                if (auto.policy().valueOrNull() != null) {
                                    auto.disable()
                                    AppLogs.get(context).info("экосистема", "политика прогона выключена: выбран другой манифест")
                                    true
                                } else {
                                    false
                                }
                            }
                            if (off) status = "Выбран другой манифест — политика прогона выключена: включите её заново."
                        }
                    }
                    is Outcome.Failure -> error = "Манифест не читается: ${r.errors.joinToString("; ") { it.message }}"
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Экосистема") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Манифест строит DPmeta: python3 dpmeta/tools/manifest.py --out ecosystem.json. " +
                        "Задачи ставятся этому контуру — по одной, после подтверждения.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { pick.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text(if (projects == null) "Выбрать манифест…" else "Другой манифест…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                projects?.let { ps ->
                    if (ps.isEmpty()) Text("В манифесте нет проектов.", style = MaterialTheme.typography.bodySmall)
                    if (ps.isNotEmpty()) Text("Проект этого контура:", style = MaterialTheme.typography.labelMedium)
                    for (p in ps) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                project = p.key
                                EcosystemChoice.remember(context, p.key)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = project == p.key, onClick = null)
                            Text("${p.name} (${p.key})", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { allOperations = !allOperations }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allOperations, onCheckedChange = null)
                        Text("Все виды работ, не только docs", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    val chosen = ps.firstOrNull { it.key == project }
                    if (chosen == null && ps.isNotEmpty()) Text("Выберите проект, чтобы увидеть его предложения.", style = MaterialTheme.typography.bodySmall)
                    chosen?.let { p ->
                        val plan = EcosystemPlan.of(listOf(p), setOf(p.key), setOf(p.key), if (allOperations) null else setOf(DOCS))
                        if (plan.isEmpty()) Text("У проекта нет предложений.", style = MaterialTheme.typography.bodySmall)
                        for (s in plan) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(s.suggestion.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${s.suggestion.priority} · ${s.suggestion.operation}" + (s.refusal?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (s.allowed) TextButton(onClick = { confirm = s }) { Text("Поставить задачу…") }
                            }
                        }
                    }
                    if (ps.isNotEmpty()) AutoPolicySection(model, chosen, kept)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Закрыть") } },
    )
    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Поставить задачу?") },
            text = { Text("«${s.suggestion.title}»\n\n${s.suggestion.objective}", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            s.command()?.let(model::addTask) ?: Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "предложение не разрешено")
                        }
                        status = when {
                            r is Outcome.Success -> "Поставлена: ${s.suggestion.key}"
                            r is Outcome.Failure && r.diagnostics.all { it.code == DiagCode.COMMAND_QUEUED } -> "В очереди: ${s.suggestion.key} поставится следующим тактом"
                            r is Outcome.Failure -> "Не поставлена: ${r.diagnostics.first().message}"
                            else -> null
                        }
                    }
                }) { Text("Поставить") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

/** Вид работы, который выгружает DPmeta (`DPmeta:C-13`): по умолчанию — только он. */
internal const val DOCS = "docs"

/** Манифест больше этого — не манифест: файл, выбранный по ошибке, не читается в память целиком. */
private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

internal fun readManifest(context: Context, uri: Uri): Outcome<List<EcosystemProject>> {
    val text = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_MANIFEST_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${MAX_MANIFEST_BYTES / 1024 / 1024} МиБ — это не манифест")
            }
            buf.toString(Charsets.UTF_8.name())
        }
    } catch (e: IOException) {
        return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "файл не открылся: ${e.message}")
    } catch (e: SecurityException) {
        return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "нет доступа к файлу: ${e.message}")
    }
    return ManifestEcosystem { text }.projects()
}

/**
 * Какой проект манифеста — этот контур, и какой файл — манифест: выбор
 * человека, в настройках приложения. Разрешение на чтение файла
 * сохраняется (D-106): его читает проход политики без окна; прежнее
 * разрешение при выборе нового файла отпускается.
 */
internal object EcosystemChoice {
    private const val PREFS = "dpai.ecosystem"
    private const val PROJECT = "project"
    private const val MANIFEST = "manifest"

    fun project(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROJECT, null)

    fun remember(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PROJECT, key).apply()
    }

    fun manifest(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MANIFEST, null)?.let(Uri::parse)

    /** Сохранить выбранный манифест; файл без постоянного разрешения — `false`: проход политики его не прочтёт. */
    fun keepManifest(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val kept = try {
            resolver.takePersistableUriPermission(uri, flags)
            true
        } catch (_: SecurityException) {
            false
        }
        val previous = manifest(context)
        if (previous != null && previous != uri) runCatching { resolver.releasePersistableUriPermission(previous, flags) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (kept) putString(MANIFEST, uri.toString()) else remove(MANIFEST) }.apply()
        return kept
    }
}

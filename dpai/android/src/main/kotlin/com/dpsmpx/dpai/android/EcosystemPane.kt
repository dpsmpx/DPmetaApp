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
import androidx.core.content.edit
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
    // Источник (D-114): реестр пульта — по умолчанию в DPmetaApp, файл с ПК — запасной.
    val registry = remember { EcosystemChoice.registryAvailable(context) }
    var source by remember { mutableStateOf(EcosystemChoice.source(context)) }
    var loading by remember { mutableStateOf(false) }
    // Манифест выбранного источника — сразу: его же читает проход политики (D-106).
    LaunchedEffect(source) {
        projects = null
        error = null
        val file = withContext(Dispatchers.IO) { EcosystemChoice.manifest(context) }
        if (source == EcosystemChoice.Source.FILE && file == null) return@LaunchedEffect
        loading = true
        kept = source == EcosystemChoice.Source.REGISTRY || file != null
        when (val r = withContext(Dispatchers.IO) { EcosystemChoice.load(context) }) {
            is Outcome.Success -> projects = r.value
            is Outcome.Failure -> error = (if (source == EcosystemChoice.Source.REGISTRY) "Реестр не читается: " else "Сохранённый манифест не читается: ") + r.errors.joinToString("; ") { it.message }
        }
        loading = false
    }
    fun choose(to: EcosystemChoice.Source) {
        scope.launch {
            val (ok, note) = withContext(Dispatchers.IO) { EcosystemChoice.setSource(context, to) }
            if (ok) source = to
            note?.let { status = it }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { readManifest(context, uri) }) {
                    is Outcome.Success -> {
                        projects = r.value
                        error = null
                        // Другой файл — другие предложения: политика, включённая над прежним, выключается до того,
                        // как проход такта прочтёт новый файл (D-106, T19).
                        // Итог: можно ли сохранить новый файл и что сказать человеку.
                        val (keep, note) = withContext(Dispatchers.IO) {
                            val before = EcosystemChoice.manifest(context)
                            val auto = Controller.get(context.filesDir).ecosystemAuto
                            if (before == null || before == uri || auto.policy().valueOrNull() == null) {
                                true to null
                            } else {
                                when (val d = auto.disable()) {
                                    is Outcome.Success -> true to "Выбран другой манифест — политика прогона выключена: включите её заново."
                                        .also { AppLogs.get(context).info("экосистема", "политика прогона выключена: выбран другой манифест") }
                                    is Outcome.Failure -> false to "Политика не выключилась (${d.errors.firstOrNull()?.message}) — новый манифест не сохранён"
                                }
                            }
                        }
                        if (keep) kept = withContext(Dispatchers.IO) { EcosystemChoice.keepManifest(context, uri) }
                        note?.let { status = it }
                    }
                    is Outcome.Failure -> error = "Манифест не читается: ${r.errors.joinToString("; ") { it.message }}"
                }
            }
        }
    }
    // Экран, а не окно в окне (AI:A-23): вход — «Экосистема…» на «Задачах» и в «Новой задаче».
    FullScreenDialog(onDismissRequest = onDone, title = "Экосистема") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Задачи из предложений ставятся этому контуру — по одной, после подтверждения.", style = MaterialTheme.typography.bodySmall)
            Text("Откуда проекты и предложения:", style = MaterialTheme.typography.labelMedium)
            if (registry) {
                Row(Modifier.fillMaxWidth().clickable { choose(EcosystemChoice.Source.REGISTRY) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = source == EcosystemChoice.Source.REGISTRY, onClick = null)
                    Text("Реестр пульта — без ПК", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { choose(EcosystemChoice.Source.FILE) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = source == EcosystemChoice.Source.FILE, onClick = null)
                Text("Файл манифеста с ПК", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (source == EcosystemChoice.Source.FILE) {
                Text("Файл строит DPmeta на ПК: python3 dpmeta/tools/manifest.py --out ecosystem.json", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pick.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text(if (projects == null) "Выбрать файл…" else "Другой файл…")
                }
            }
            if (loading) Text("Читаю…", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            projects?.let { ps ->
                if (ps.isEmpty()) Text("Проектов нет.", style = MaterialTheme.typography.bodySmall)
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
                        Text(p.name, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { allOperations = !allOperations }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allOperations, onCheckedChange = null)
                    Text("Все виды работ, не только документы", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
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
                                "${com.dpsmpx.dpai.core.text.Words.priority(s.suggestion.priority.uppercase())} · ${s.suggestion.operation}" + (s.refusal?.let { " · $it" } ?: ""),
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
    }
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
                            // Итог — по названию, а не ключу предложения (AI:A-23).
                            r is Outcome.Success -> "Поставлена: «${s.suggestion.title}»"
                            r is Outcome.Failure && r.diagnostics.all { it.code == DiagCode.COMMAND_QUEUED } -> "В очереди: «${s.suggestion.title}» поставится следующим тактом"
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
    private const val SOURCE = "source"

    /** Откуда манифест (D-114): реестр приложения (по умолчанию, если он есть) или файл с ПК. */
    enum class Source { REGISTRY, FILE }

    /** Есть ли у приложения свой реестр (сборка DPmetaApp): без него — только файл. */
    fun registryAvailable(context: Context): Boolean = (context.applicationContext as? DpaiApplication)?.ecosystemRegistry == true

    fun source(context: Context): Source {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(SOURCE, null)?.let { s -> Source.entries.firstOrNull { it.name == s } }
        return defaultSource(registryAvailable(context), saved, prefs.getString(MANIFEST, null) != null)
    }

    /**
     * Источник без выбора человека (D-114): нет реестра — файл; выбран — он;
     * не выбран, а файл сохранён (выбор до D-114, возможно с политикой) —
     * файл: обновление приложения источник молча не меняет, политика читает
     * то, над чем её включили; иначе — реестр.
     */
    internal fun defaultSource(registry: Boolean, saved: Source?, fileKept: Boolean): Source = when {
        !registry -> Source.FILE
        saved != null -> saved
        fileKept -> Source.FILE
        else -> Source.REGISTRY
    }

    /**
     * Смена источника (D-114): тот же — ничего; политика включена — сначала
     * выключить её ([disable]); не выключилась — источник не меняется. Итог —
     * можно ли сменить и что сказать человеку.
     */
    internal fun switch(from: Source, to: Source, policyOn: Boolean, disable: () -> Outcome<*>): Pair<Boolean, String?> = when {
        from == to -> true to null
        !policyOn -> true to null
        else -> when (val d = disable()) {
            is Outcome.Success -> true to "Источник манифеста сменён — политика прогона выключена: включите её заново."
            is Outcome.Failure -> false to "Политика не выключилась (${d.diagnostics.firstOrNull()?.message}) — источник не сменён"
        }
    }

    /**
     * Сменить источник: другой источник — другие предложения, поэтому
     * включённая политика выключается (D-114, как при смене файла, D-106);
     * не выключилась — источник не меняется. Итог — строка для человека.
     */
    fun setSource(context: Context, to: Source): Pair<Boolean, String?> {
        val auto = Controller.get(context.filesDir).ecosystemAuto
        val r = switch(source(context), to, auto.policy().valueOrNull() != null) { auto.disable() }
        if (r.second != null && r.first) AppLogs.get(context).info("экосистема", "политика прогона выключена: сменён источник манифеста")
        if (r.first) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(SOURCE, to.name) }
        return r
    }

    /** Проекты манифеста из выбранного источника — для экрана и прохода политики. Блокирующий вызов. */
    fun load(context: Context): Outcome<List<EcosystemProject>> = when (source(context)) {
        // Сбой оболочки — отказ словами, а не падение окна или такта.
        Source.REGISTRY -> when (val m = runCatching { (context.applicationContext as? DpaiApplication)?.ecosystemManifest() }.getOrElse { Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "манифест из реестра не собран: ${it.javaClass.simpleName}") }) {
            null -> Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "у этой сборки нет реестра — выберите файл манифеста")
            is Outcome.Failure -> m
            is Outcome.Success -> ManifestEcosystem { m.value }.projects()
        }
        Source.FILE -> manifest(context)?.let { readManifest(context, it) } ?: Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "файл манифеста не выбран")
    }

    fun project(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROJECT, null)

    fun remember(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(PROJECT, key) }
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { if (kept) putString(MANIFEST, uri.toString()) else remove(MANIFEST) }
        return kept
    }
}

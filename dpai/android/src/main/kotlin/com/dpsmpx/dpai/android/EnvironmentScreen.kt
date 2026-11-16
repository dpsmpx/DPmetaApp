package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.AlpineView
import com.dpsmpx.dpai.ui.AppComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Экран «Окружение» (D-191…D-193; `DPmetaApp:D-171`, шаги 1–3): пакеты Alpine гостя — что стоит,
 * версия, размер, сумма, для кого нужен, совместимость между собой; обновить, убрать,
 * починить без ИИ. База читается с хоста без процесса гостя; команды `apk` — та же
 * долгая работа `LINUX_APK`, что и на экране «Linux в приложении», по одной (AI:A-14).
 */
@Composable
fun EnvironmentScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val jobs = remember { LongJobs.get(context) }
    val state by jobs.state.collectAsState()
    val work = state[LongJobs.LINUX_APK]
    val running = LongJobs.GUEST.any { state[it]?.running == true }
    var inv by remember { mutableStateOf<AlpinePackages.Inventory?>(null) }
    var problems by remember { mutableStateOf<List<AlpinePackages.Problem>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AlpineView.Filter.ALL) }
    var sort by rememberSaveable { mutableStateOf(AlpineView.Sort.NAME) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<AlpinePackages.Removal?>(null) }
    var allOutput by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(EnvSection.ALPINE) }
    var components by remember { mutableStateOf<List<AppComponents.Component>>(emptyList()) }
    var removingComponent by remember { mutableStateOf<AppComponents.Component?>(null) }

    // Список перечитывается, когда команда гостя закончилась: база — после неё.
    LaunchedEffect(running) {
        if (running) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val env = LinuxSetup.environment(context)
            when (val r = AlpinePackages.read(NioVfs(context.filesDir.toPath()), env.rootfs)) {
                is Outcome.Success -> {
                    inv = r.value
                    problems = r.value?.let(AlpinePackages::problems).orEmpty()
                    note = if (r.value == null) "Linux в приложении не установлен или apk ещё ничего не ставил." else null
                }
                is Outcome.Failure -> note = r.errors.first().message
            }
            val arch = inv?.byName?.get("musl")?.arch
            components = runCatching { AppComponents.scan(EnvironmentComponents.rootfs(context).toPath(), arch) }.getOrDefault(emptyList())
        }
        loaded = true
    }

    fun apk(action: LinuxEnvironment.Apk, names: List<String>, label: String) {
        allOutput = false
        jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
            when (val r = LinuxSetup.apk(context, action, names)) {
                is Outcome.Success -> LongJobs.Done(
                    if (r.value.ok) "$label: готово" else "$label: apk завершился с кодом ${r.value.exitCode} — причина в выводе ниже",
                    (r.value.stdout + r.value.stderr).takeLast(MAX_OUTPUT),
                    failed = !r.value.ok,
                )
                is Outcome.Failure -> LongJobs.Done("$label: ${r.errors.first().message}", failed = true)
            }
        }
    }

    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Убрать «${r.name}»?") },
            text = { Text(AlpineView.removalText(r)) },
            confirmButton = {
                if (r.allowed) {
                    TextButton(onClick = {
                        removing = null
                        apk(LinuxEnvironment.Apk.DEL, listOf(r.name), "Убрать ${r.name}")
                    }) { Text("Убрать") }
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(if (r.allowed) "Отмена" else "Закрыть") } },
        )
    }

    removingComponent?.let { c ->
        RemoveComponentDialog(c, onCancel = { removingComponent = null }) {
            removingComponent = null
            allOutput = false
            EnvironmentComponents.remove(context, jobs, c)
        }
    }

    val current = inv
    val rows = remember(current, problems, query, filter, sort) {
        current?.let { AlpineView.rows(it, problems, query, filter, sort) }.orEmpty()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onBack) { Text("← Назад") }
                Text("Окружение", style = MaterialTheme.typography.titleMedium)
                Text("Linux в приложении: пакеты Alpine и компоненты DP_IDE — что стоит, сколько занимает, откуда и с какой суммой.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sec in EnvSection.entries) FilterChip(selected = section == sec, onClick = { section = sec; open = null }, label = { Text(sec.title) })
                }
                if (!loaded) Text("Читаю базу пакетов…", style = MaterialTheme.typography.bodySmall)
                note?.takeIf { section == EnvSection.ALPINE }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (section == EnvSection.COMPONENTS && loaded) Text(AppComponents.summary(components), style = MaterialTheme.typography.bodyMedium)
                current?.takeIf { section == EnvSection.ALPINE }?.let { c ->
                    Text(AlpineView.summary(c, problems), style = MaterialTheme.typography.bodyMedium)
                    if (c.repositories.isNotEmpty()) {
                        Text("Репозитории: " + c.repositories.joinToString { it.removePrefix("https://dl-cdn.alpinelinux.org/alpine/") }, style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (fix in AlpineView.repairs(problems)) {
                            TextButton(enabled = !running, onClick = { apk(fix.action, fix.packages, fix.title) }) { Text(fix.title) }
                        }
                        TextButton(enabled = !running, onClick = { apk(LinuxEnvironment.Apk.UPGRADE, emptyList(), "Обновить всё") }) { Text("Обновить всё") }
                    }
                    if (problems.any { it.kind == AlpinePackages.ProblemKind.CONFLICT }) {
                        Text("Конфликт пакетов сам не чинится: уберите один из несовместимых.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                for (j in listOfNotNull(work)) {
                    if (j.running) {
                        Text("${j.label}…", style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        j.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                val output = work?.output.orEmpty()
                if (output.isNotEmpty()) {
                    Text((if (allOutput) output else output.takeLast(1)).joinToString("\n"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    if (output.size > 1) TextButton(onClick = { allOutput = !allOutput }) { Text(if (allOutput) "Свернуть вывод" else "Показать весь вывод (${output.size} строк)") }
                }
                if (current != null && section == EnvSection.ALPINE) {
                    OutlinedTextField(query, { query = it.take(100) }, label = { Text("Поиск: имя или описание") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in AlpineView.Filter.entries) FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
                        for (s in AlpineView.Sort.entries) FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.title) })
                    }
                    Text("Показано: ${rows.size}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (section == EnvSection.SETTINGS) {
            item {
                EnvironmentSettingsSection(current?.release, current?.repositories.orEmpty(), running) { text, label ->
                    allOutput = false
                    applyRepositories(context, jobs, text, label)
                }
            }
        }
        if (section == EnvSection.COMPONENTS) {
            items(components, key = { it.id }) { c ->
                val install = if (EnvironmentComponents.ideInstall(c) != null || EnvironmentComponents.reinstall(c) != null) {
                    { allOutput = false; EnvironmentComponents.install(context, jobs, c) }
                } else null
                ComponentRow(c, open == c.id, running, onToggle = { open = if (open == c.id) null else c.id }, onRemove = { removingComponent = c }, onInstall = install)
            }
        }
        if (section == EnvSection.ALPINE) items(rows, key = { it.name }) { row ->
            Column(Modifier.fillMaxWidth().clickable { open = if (open == row.name) null else row.name }.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(row.version, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    Text(row.size, style = MaterialTheme.typography.labelSmall)
                }
                val tags = listOfNotNull("ваш".takeIf { row.mine }, "система".takeIf { row.system }, row.requiredBy.takeIf { it.isNotEmpty() }?.let { "нужен для ${it.size}" })
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                for (p in row.problems) Text(p, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                val pkg = current?.byName?.get(row.name)
                if (open == row.name && pkg != null) {
                    for ((k, v) in AlpineView.details(pkg, current)) Text("$k: $v", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !running, onClick = { apk(LinuxEnvironment.Apk.UPGRADE_SOME, listOf(row.name), "Обновить ${row.name}") }) { Text("Обновить") }
                        if (row.mine && !row.system) {
                            TextButton(enabled = !running, onClick = { removing = AlpinePackages.removal(current, row.name) }) { Text("Убрать…") }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Разделы экрана. */
private enum class EnvSection(val title: String) { ALPINE("Пакеты Alpine"), COMPONENTS("Компоненты"), SETTINGS("Настройки") }

/** Строк вывода `apk` на экране: хвост, где итог и ошибка. */
private const val MAX_OUTPUT = 20

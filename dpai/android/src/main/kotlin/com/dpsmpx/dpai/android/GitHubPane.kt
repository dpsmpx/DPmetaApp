package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.connectors.GhCheck
import com.dpsmpx.dpai.connectors.GhPr
import com.dpsmpx.dpai.connectors.GhPrDetail
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PR проекта на GitHub (D-108): открытые PR, проверки, отзывы и комментарии — командами `gh`
 * на target проекта, его входом; токена GitHub у приложения нет (D-14). Уровень 0 — только
 * чтение. Текст PR — данные со стороны (T3): очищен при разборе, ссылки — по D-75.
 */
@Composable
fun GitHubPane(model: DemoModel, onBack: () -> Unit) {
    val desk = remember { model.github() }
    val scope = rememberCoroutineScope()
    val link = rememberLinkTap()
    var prs by remember { mutableStateOf<List<GhPr>?>(null) }
    var detail by remember { mutableStateOf<GhPrDetail?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        val d = desk ?: return
        loading = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { d.list() }) {
                is Outcome.Success -> { prs = r.value; failure = null }
                is Outcome.Failure -> failure = r.errors.first().message
            }
            loading = false
        }
    }

    fun open(n: Int) {
        val d = desk ?: return
        loading = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { d.view(n) }) {
                is Outcome.Success -> { detail = r.value; failure = null }
                is Outcome.Failure -> failure = r.errors.first().message
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    detail?.let { v ->
        androidx.activity.compose.BackHandler { detail = null }
        PrCard(v, desk, loading, onBack = { detail = null }, onLink = link, onChanged = { open(v.pr.number) })
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("PR на GitHub", style = MaterialTheme.typography.titleMedium)
        if (desk == null) {
            Text(
                "Нет проекта с репозиторием: Targets → у target ⋮ → «Проект…». PR читаются командой gh на его target — " +
                    "в Linux приложения: пакет github-cli и в терминале gh auth login.",
                style = MaterialTheme.typography.bodySmall,
            )
            return
        }
        Text("Репозиторий ${desk.repository} · открытые PR · gh на target проекта", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !loading, onClick = { load() }) { Text(if (loading) "Загружаю…" else "Обновить") }
        }
        failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val list = prs ?: return
        if (list.isEmpty()) Text("Открытых PR нет.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.number }) { pr ->
                Card(Modifier.fillMaxWidth().clickable(enabled = !loading) { open(pr.number) }) {
                    Column(Modifier.padding(10.dp)) {
                        Text("#${pr.number} ${pr.title}", style = MaterialTheme.typography.titleSmall)
                        Text("${pr.head} → ${pr.base} · ${pr.author}" + if (pr.draft) " · черновик" else "", style = MaterialTheme.typography.labelSmall)
                        Text(listOfNotNull(checksLine(pr.checks), reviewWord(pr.review)).joinToString(" · ").ifEmpty { "проверок нет" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrCard(
    v: GhPrDetail,
    desk: com.dpsmpx.dpai.orchestrator.github.GitHubDesk?,
    loading: Boolean,
    onBack: () -> Unit,
    onLink: (String) -> Unit,
    onChanged: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { TextButton(onClick = onBack) { Text("← Список PR") } }
        item {
            Text("#${v.pr.number} ${v.pr.title}", style = MaterialTheme.typography.titleMedium)
            Text("${v.pr.head} → ${v.pr.base} · ${v.pr.author}" + if (v.pr.draft) " · черновик" else "", style = MaterialTheme.typography.bodySmall)
            Text("Слияние: ${mergeableWord(v.mergeable)}" + (reviewWord(v.pr.review)?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onLink(v.pr.url) }) { Text(v.pr.url) }
            if (loading) Text("Загружаю…", style = MaterialTheme.typography.labelSmall)
        }
        if (v.pr.checks.isNotEmpty()) {
            item { HorizontalDivider(); Text("Проверки", style = MaterialTheme.typography.titleSmall) }
            items(v.pr.checks) { c -> Text("${checkMark(c)} ${c.name} — ${c.state.lowercase()}", style = MaterialTheme.typography.bodySmall) }
        }
        item {
            HorizontalDivider()
            Text("Описание", style = MaterialTheme.typography.titleSmall)
            Text(v.body.ifBlank { "без описания" }, style = MaterialTheme.typography.bodySmall)
        }
        if (v.notes.isNotEmpty()) {
            item { HorizontalDivider(); Text("Отзывы и комментарии", style = MaterialTheme.typography.titleSmall) }
            items(v.notes) { n ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${n.author}${n.review?.let { " · ${reviewWord(it) ?: it.lowercase()}" } ?: ""} · ${n.at.take(16).replace('T', ' ')}", style = MaterialTheme.typography.labelSmall)
                    if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // Комментарий — уровень 2, «Слить» — уровень 3 (DPmetaApp:D-80).
        if (desk != null) item { PrActions(v, desk, onChanged) }
    }
}

/**
 * Отправка ветки задачи на GitHub с PR (D-108; уровень 1 по `DPmetaApp:D-36`): до нажатия
 * окно показывает, что уйдёт, — ветку, удалённый, базу и заголовок; итог — адрес PR.
 */
@Composable
internal fun GitHubSendSection(model: DemoModel, task: TaskId, title: String, objective: String = "", reviews: List<String> = emptyList()) {
    val desk = remember { model.github() } ?: return
    val scope = rememberCoroutineScope()
    val link = rememberLinkTap()
    var asking by remember(task) { mutableStateOf(false) }
    var busy by remember(task) { mutableStateOf(false) }
    var result by remember(task) { mutableStateOf<String?>(null) }
    var failure by remember(task) { mutableStateOf<String?>(null) }
    val plan = remember(task) { desk.plan(task) }
    val prTitle = title.lineSequence().firstOrNull().orEmpty().take(120).ifBlank { "Задача ${task.value}" }
    // Описание по шаблону из фактов задачи — без ИИ (D-189; DPmetaApp:D-168); правится перед отправкой.
    var body by remember(task) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(asking) {
        if (asking && body == null) {
            val diff = withContext(Dispatchers.IO) { model.taskDiff(task).valueOrNull() }
            body = com.dpsmpx.dpai.ui.PrTemplate.body(task.value, objective, plan.branch, diff, reviews)
        }
    }
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("GitHub", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(enabled = !busy, onClick = { asking = true }) { Text(if (busy) "Отправляю…" else "Отправить ветку и открыть PR…") }
    }
    result?.let { url -> TextButton(onClick = { link(url) }) { Text("PR: $url") } }
    failure?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Отправить на GitHub?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Ветка ${plan.branch} уйдёт в ${plan.remote} (репозиторий ${plan.repository}), и откроется PR в ${plan.base} " +
                            "с заголовком «$prTitle». Это увидят на GitHub; PR можно закрыть, ветку — удалить. Запись — в журнал действий.",
                    )
                    // Шаблон без ИИ: факты задачи; что не известно — сказано, правьте здесь.
                    androidx.compose.material3.OutlinedTextField(
                        value = body ?: "Собираю описание…", onValueChange = { body = it.take(com.dpsmpx.dpai.ui.PrTemplate.MAX_BODY) },
                        enabled = body != null, label = { Text("Описание PR (шаблон, без ИИ)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    busy = true
                    scope.launch {
                        val text = body ?: "Задача ${task.value} контура «AI», ветка ${plan.branch}."
                        when (val r = withContext(Dispatchers.IO) { desk.send(task, prTitle, text) }) {
                            is Outcome.Success -> { result = r.value; failure = null }
                            is Outcome.Failure -> failure = r.errors.first().message
                        }
                        busy = false
                    }
                }) { Text("Отправить") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Отмена") } },
        )
    }
}

private fun checksLine(checks: List<GhCheck>): String? {
    if (checks.isEmpty()) return null
    val ok = checks.count { it.passed }
    val bad = checks.count { it.failed }
    val going = checks.size - ok - bad
    return listOfNotNull("✓ $ok".takeIf { ok > 0 }, "✗ $bad".takeIf { bad > 0 }, "… $going".takeIf { going > 0 }).joinToString(" ")
}

private fun checkMark(c: GhCheck) = when {
    c.passed -> "✓"
    c.failed -> "✗"
    else -> "…"
}

private fun reviewWord(r: String?): String? = when (r) {
    null -> null
    "APPROVED" -> "одобрен"
    "CHANGES_REQUESTED" -> "просят правок"
    "REVIEW_REQUIRED" -> "ждёт отзыва"
    "COMMENTED" -> "комментарий"
    "DISMISSED" -> "отзыв снят"
    else -> r.lowercase()
}

private fun mergeableWord(m: String) = when (m) {
    "MERGEABLE" -> "можно слить"
    "CONFLICTING" -> "конфликт"
    else -> "GitHub ещё считает"
}

/**
 * Действия с PR (`DPmetaApp:D-80`): комментарий — уровень 2 (окно «что уйдёт», затем PIN
 * устройства); «Слить» — уровень 3 (красное окно, ввод `владелец/имя`, без биометрии). Итог —
 * строкой; карточка перечитывается.
 */
@Composable
private fun PrActions(v: GhPrDetail, desk: com.dpsmpx.dpai.orchestrator.github.GitHubDesk, onChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var text by androidx.compose.runtime.saveable.rememberSaveable(v.pr.number) { mutableStateOf("") }
    var askComment by remember { mutableStateOf(false) }
    var askMerge by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val repo = com.dpsmpx.dpai.connectors.GitHubCommands.repoOf(v.pr.url)

    fun run(label: String, after: () -> Unit = {}, action: () -> Outcome<Unit>) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { action() }
            busy = false
            when (r) {
                is Outcome.Success -> {
                    note = "$label: готово"
                    after()
                    onChanged()
                }
                is Outcome.Failure -> note = "$label: ${r.errors.first().message}"
            }
        }
    }

    HorizontalDivider()
    Text("Комментарий", style = MaterialTheme.typography.titleSmall)
    androidx.compose.material3.OutlinedTextField(
        text, { if (it.length <= com.dpsmpx.dpai.connectors.GitHubCommands.MAX_COMMENT) text = it },
        label = { Text("Текст комментария") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    TextButton(enabled = !busy && text.isNotBlank(), onClick = { askComment = true }) { Text("Отправить комментарий…") }
    HorizontalDivider()
    androidx.compose.material3.Button(
        enabled = !busy && repo != null && v.pr.state == "OPEN",
        onClick = { typed = ""; askMerge = true },
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) { Text("Слить…") }
    if (busy) Text("Выполняю…", style = MaterialTheme.typography.labelSmall)
    note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }

    if (askComment) {
        AlertDialog(
            onDismissRequest = { askComment = false },
            title = { Text("Отправить комментарий?") },
            text = { Text("В PR #${v.pr.number} (${repo ?: v.pr.url}) от вашего входа gh уйдёт:\n\n$text\n\nДальше — PIN устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    askComment = false
                    val body = text
                    DeviceConfirm.ask(context, "Комментарий к PR #${v.pr.number}", onOk = {
                        run("Комментарий", after = { text = "" }) { desk.comment(v.pr.number, body) }
                    }, onDenied = { why -> note = "Комментарий: $why" })
                }) { Text("Дальше — PIN") }
            },
            dismissButton = { TextButton(onClick = { askComment = false }) { Text("Отмена") } },
        )
    }
    if (askMerge && repo != null) {
        AlertDialog(
            onDismissRequest = { askMerge = false },
            containerColor = MaterialTheme.colorScheme.errorContainer,
            titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
            textContentColor = MaterialTheme.colorScheme.onErrorContainer,
            title = { Text("Слить PR #${v.pr.number}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Ветка ${v.pr.head} войдёт в ${v.pr.base} репозитория $repo на GitHub merge-коммитом — только коммит " +
                            "${v.headSha.take(7)}, показанный здесь. Вернуть можно только новым коммитом (revert). " +
                            "Слияние: ${mergeableWord(v.mergeable)}" + (checksLine(v.pr.checks)?.let { "; проверки: $it" } ?: "") + ".",
                    )
                    Text("Чтобы подтвердить, введите имя репозитория: $repo")
                    androidx.compose.material3.OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(repo) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = typed.trim() == repo, onClick = {
                    askMerge = false
                    val name = typed
                    run("Слияние") { desk.merge(v, name) }
                }) { Text("Слить") }
            },
            dismissButton = { TextButton(onClick = { askMerge = false }) { Text("Отмена") } },
        )
    }
}

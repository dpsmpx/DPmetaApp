package com.dpsmpx.dpai.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.NewProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Мастер нового проекта (`DPmetaApp:D-150`; D-181): шаблон, имя и место, проверка
 * инструментов и создание. GitHub не нужен: проект — локальный Git; опубликовать
 * его можно позже. [onLinux] — установить Linux приложения; [onOpen] — открыть
 * созданный проект в IDE.
 */
@Composable
internal fun NewProjectWizard(onLinux: () -> Unit, onOpen: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { DemoModel.get(context) }
    val jobs = remember { LongJobs.get(context) }
    val jobState by jobs.state.collectAsState()
    val installing = jobState[LongJobs.LINUX_APK]?.running == true

    var page by rememberSaveable { mutableIntStateOf(0) }
    var language by rememberSaveable { mutableStateOf(NewProject.Language.CPP.name) }
    var templateName by rememberSaveable { mutableStateOf(NewProject.Template.CPP_CMAKE.name) }
    var name by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf("") }
    var pkgEdited by rememberSaveable { mutableStateOf(false) }
    var placeKey by rememberSaveable { mutableStateOf(NewProjectRun.PHONE) }
    var parent by rememberSaveable { mutableStateOf(NewProject.DEFAULT_PARENT) }
    var gitName by rememberSaveable { mutableStateOf("") }
    var gitEmail by rememberSaveable { mutableStateOf("") }
    var missing by remember { mutableStateOf<List<String>?>(null) }
    var probeNote by remember { mutableStateOf<String?>(null) }
    var probeRound by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var written by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<NewProjectRun.Created?>(null) }
    var installNote by remember { mutableStateOf<String?>(null) }

    val template = NewProject.Template.valueOf(templateName)
    val options = rememberRead(Unit, initial = emptyList()) { NewProjectRun.places(context, Controller.get(context.filesDir).targets()) }
    val option = options.firstOrNull { it.place.key == placeKey }
    val place = option?.place
    val placeProblem = option?.problem ?: place?.let { NewProjectRun.unavailable(template, it) }
    val form = NewProject.form(template, name, if (template.packageName) pkg else "")
    val spec = (form as? Outcome.Success)?.value
    val parentProblem = NewProject.parentProblem(parent)

    // Проверка инструментов — при входе на последний шаг и после установки.
    LaunchedEffect(page, placeKey, templateName, probeRound, installing) {
        if (page != 2 || installing || place == null || placeProblem != null) return@LaunchedEffect
        missing = null
        probeNote = "Проверяю инструменты на «${place.title}»…"
        val r = withContext(Dispatchers.IO) { NewProjectRun.probe(context, model, place, template) }
        when (r) {
            is Outcome.Success -> { missing = r.value; probeNote = null }
            is Outcome.Failure -> probeNote = "Не проверено: ${r.errors.first().message}"
        }
    }

    Dialog(onDismissRequest = { if (busy == null) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
                Text("Новый проект", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (created != null) "Готово" else "Шаг ${page + 1} из 3 · " + listOf("язык и шаблон", "имя и место", "проверка и создание")[page],
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(4.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val done = created
                    when {
                        done != null -> {
                            Text("Проект «${done.entry.root.substringAfterLast('/')}» создан: ${done.root}.")
                            Text("Git — локальный, ветка ${NewProject.BRANCH} с первым коммитом; GitHub не нужен — опубликовать проект можно позже: «На GitHub…» в карточке «На устройстве».", style = MaterialTheme.typography.bodySmall)
                            Text("Сборка, проверки и запуск — вкладки рабочего проекта в IDE; ИИ получает задачи этого проекта.", style = MaterialTheme.typography.bodySmall)
                        }
                        page == 0 -> {
                            Text("Язык", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (l in NewProject.Language.entries) FilterChip(selected = language == l.name, onClick = {
                                    language = l.name
                                    templateName = NewProject.Template.entries.first { it.language == l }.name
                                }, label = { Text(l.title) })
                            }
                            Text("Шаблон", style = MaterialTheme.typography.titleSmall)
                            for (t in NewProject.Template.entries.filter { it.language.name == language }) {
                                Row(
                                    Modifier.fillMaxWidth().selectable(selected = template == t, role = Role.RadioButton, onClick = { templateName = t.name }).padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    RadioButton(selected = template == t, onClick = null)
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(t.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(t.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        page == 1 -> {
                            OutlinedTextField(name, { v ->
                                name = v.take(NewProject.MAX_NAME + 10)
                                if (!pkgEdited) pkg = NewProject.defaultPackage(name)
                            }, label = { Text("Имя проекта") }, supportingText = {
                                Text((form as? Outcome.Failure)?.takeIf { name.isNotEmpty() && it.errors.first().subject == "name" }?.errors?.first()?.message
                                    ?: "строчная латиница и цифры, слова через «-»: например, my-game")
                            }, isError = name.isNotEmpty() && (form as? Outcome.Failure)?.errors?.first()?.subject == "name", singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (template.packageName) {
                                OutlinedTextField(pkg, { pkg = it.take(110); pkgEdited = true }, label = { Text("Пакет") }, supportingText = {
                                    Text((form as? Outcome.Failure)?.takeIf { it.errors.first().subject == "package" }?.errors?.first()?.message ?: "например, local.my_game")
                                }, isError = (form as? Outcome.Failure)?.errors?.first()?.subject == "package", singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            Text("Где", style = MaterialTheme.typography.titleSmall)
                            for (o in options) {
                                val problem = o.problem ?: NewProjectRun.unavailable(template, o.place)
                                Row(
                                    Modifier.fillMaxWidth().selectable(selected = placeKey == o.place.key, enabled = problem == null || o.place is NewProjectRun.Place.Phone, role = Role.RadioButton,
                                        onClick = { placeKey = o.place.key }).padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    RadioButton(selected = placeKey == o.place.key, onClick = null, enabled = problem == null)
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(o.place.title, style = MaterialTheme.typography.bodyLarge)
                                        problem?.let { DiagnosticText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                            if (option?.place is NewProjectRun.Place.Phone && option.problem != null && NewProjectRun.unavailable(template, option.place) == null) {
                                OutlinedButton(onClick = onLinux) { Text("Linux приложения…") }
                            }
                            OutlinedTextField(parent, { parent = it.take(210) }, label = { Text("Папка от домашней") }, supportingText = {
                                Text(parentProblem ?: "~/${NewProject.normalizeParent(parent)}/${name.ifBlank { "<имя>" }}" + (place?.let { " · ${it.title}" } ?: ""))
                            }, isError = parentProblem != null, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        else -> {
                            Text("${template.title} · ${spec?.name} · ${place?.title}", style = MaterialTheme.typography.titleSmall)
                            probeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            val m = missing
                            when {
                                m == null -> Unit
                                m.isEmpty() -> Text("Инструменты на месте ✓", style = MaterialTheme.typography.bodySmall)
                                else -> {
                                    Text("Нет: ${m.joinToString()}.", color = MaterialTheme.colorScheme.error)
                                    if (place is NewProjectRun.Place.Phone) {
                                        Text("Поставить в Linux приложения: ${NewProject.alpinePackages(template).joinToString()}" +
                                            (if (NewProject.needsKotlinc(template)) " и kotlinc ${com.dpsmpx.dpai.ui.KotlinRuntime.VERSION}" else "") + " — нужна сеть.", style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(enabled = !installing && busy == null, onClick = {
                                            installNote = null
                                            jobs.start(LongJobs.LINUX_APK, "Инструменты шаблона", LongJobs.GUEST, onFinished = { d ->
                                                installNote = d.note.takeIf { d.failed }
                                                probeRound++
                                            }) {
                                                NewProjectRun.install(context, template)
                                            }
                                        }) { Text(if (installing) "Ставлю…" else "Поставить недостающее") }
                                        installNote?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                    } else {
                                        Text("Установите их на «${place?.title}». Проект можно создать и сейчас: соберётся, когда инструменты появятся." +
                                            (if ("git" in m) " Без Git проект не создать." else ""), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text("Автор коммитов", style = MaterialTheme.typography.titleSmall)
                            Text("Нужен, только если Git на target ещё не знает автора; запишется в настройки этого репозитория.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(gitName, { gitName = it.take(110) }, label = { Text("Имя (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(gitEmail, { gitEmail = it.take(210) }, label = { Text("Почта (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("Будет: папка ~/${NewProject.normalizeParent(parent)}/${spec?.name}, Git с веткой ${NewProject.BRANCH} и первым коммитом, рабочий проект с профилями «Сборка», «Проверки»" +
                                (if (template != NewProject.Template.KOTLIN_ANDROID) " и «Запуск»" else "") + ". GitHub не нужен.", style = MaterialTheme.typography.bodySmall)
                            busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp)); Text(it) } }
                        }
                    }
                    error?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (created != null) {
                        TextButton(onClick = onClose) { Text("Закрыть") }
                        Button(onClick = onOpen) { Text("Открыть в IDE") }
                    } else {
                        TextButton(enabled = busy == null, onClick = onClose) { Text("Отмена") }
                        if (page > 0) TextButton(enabled = busy == null && !written, onClick = { error = null; page-- }) { Text("Назад") }
                        when (page) {
                            0 -> Button(onClick = { page = 1 }) { Text("Далее") }
                            1 -> Button(enabled = spec != null && place != null && placeProblem == null && parentProblem == null, onClick = { error = null; page = 2 }) { Text("Далее") }
                            else -> Button(enabled = busy == null && !installing && spec != null && place != null && missing?.contains("git") != true, onClick = {
                                val s = spec ?: return@Button
                                val p = place ?: return@Button
                                val identity = when (val i = NewProject.identity(gitName, gitEmail)) {
                                    is Outcome.Failure -> { error = i.errors.first().message; return@Button }
                                    is Outcome.Success -> i.value
                                }
                                error = null
                                busy = "Создаю…"
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        NewProjectRun.create(context, model, p, s, parent, identity, resume = written, step = { busy = it }, written = { written = true })
                                    }
                                    busy = null
                                    when (r) {
                                        is Outcome.Success -> created = r.value
                                        is Outcome.Failure -> error = r.errors.first().message + if (written) " Файлы уже записаны — «Повторить» доделает Git и проект." else ""
                                    }
                                }
                            }) { Text(if (written) "Повторить" else "Создать") }
                        }
                    }
                }
            }
        }
    }
}

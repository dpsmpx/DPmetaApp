package com.dpsmpx.dpmeta.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.Links
import com.dpsmpx.dpmeta.app.screens.Panels
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.Stats
import com.dpsmpx.dpmeta.formats.Panel
import com.dpsmpx.dpmeta.github.GitHub
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.metrics.MetricPoint

/**
 * Токены оформления пульта (D-15; ТЗ, §9): свои цвета, светлая и тёмная
 * схемы по системе. Цвета — здесь и только здесь.
 */
object Tokens {
    val light: ColorScheme = lightColorScheme(primary = Color(0xFF2F5D8A), secondary = Color(0xFF5B6B7A), error = Color(0xFFB3261E))
    val dark: ColorScheme = darkColorScheme(primary = Color(0xFF9CC3EA), secondary = Color(0xFFB7C4D1), error = Color(0xFFF2B8B5))
    val gap = 12.dp
}

@Composable
fun PultTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Tokens.dark else Tokens.light, content = content)

/** Состояние экрана, которое держит активность: срез, лента, строка о синхронизации, есть ли токен. */
data class PultView(
    val state: EcosystemState?,
    val status: String,
    val hasToken: Boolean,
    val busy: Boolean,
    val feed: List<FeedEvent> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    /** История чисел (D-34) — для статистики и плиток сводки (F-34). */
    val history: List<MetricPoint> = emptyList(),
    /** Окно выбора клонов (D-70); `null` — закрыто. */
    val clonePrompt: ClonePrompt? = null,
    /** Сколько репозиториев отмечено для клонов — строка настроек. */
    val clones: String = "",
)

/**
 * Окно выбора клонов (D-70): все репозитории аккаунта, реестр — сверху;
 * [checked] — отмеченные заранее (при первом входе — реестр); [first] —
 * первый вход: «Позже» спросит снова.
 */
data class ClonePrompt(val options: List<com.dpsmpx.dpmeta.app.data.PultRepository.CloneOption>, val checked: Set<String>, val first: Boolean)

/** Загрузка по запросу (F-09): каталог и файл проекта; итог — в главном потоке. */
interface Loader {
    fun directory(project: String, dir: String, done: (Outcome<List<GitHub.Entry>>) -> Unit)

    fun document(project: String, path: String, done: (Outcome<String>) -> Unit)

    /** Панель проекта (F-10) — `config/projects/<Проект>.toml` в APK; нет файла — `null`. */
    fun panel(project: String): Outcome<Panel>? = null
}

private enum class Tab(val title: String) { SUMMARY("Сводка"), PROJECTS("Проекты"), FEED("Лента"), QUESTIONS("Вопросы"), SETTINGS("Настройки") }

/** Куда перешли внутри вкладки: карточка → каталог → документ; «назад» снимает верх. */
private sealed interface Nav {
    data class Card(val project: String) : Nav

    data class Dir(val project: String, val path: String) : Nav

    data class Doc(val project: String, val path: String, val anchor: String?) : Nav

    /** Статистика (F-34): [project] — из карточки, `null` — сравнение всех проектов. */
    data class Stats(val project: String?) : Nav
}

/** [onAi] — вкладка «AI» перед «Настройками» (D-53): открывает оболочку подсистемы; `null` — вкладки нет. */
@Composable
fun PultApp(
    view: PultView,
    loader: Loader,
    onSync: () -> Unit,
    onSaveToken: (String) -> Unit,
    onForgetToken: () -> Unit,
    onAi: (() -> Unit)? = null,
    onExport: ((json: Boolean, period: Stats.Period, share: Boolean) -> Unit)? = null,
    /** «Клоны…» в настройках (D-70). */
    onClones: () -> Unit = {},
    /** Итог окна клонов: отмеченные или `null` — «Позже» / «Отмена». */
    onClonesChosen: (Set<String>?) -> Unit = {},
) {
    var tab by remember { mutableStateOf(if (view.hasToken) Tab.SUMMARY else Tab.SETTINGS) }
    val stack = remember { mutableStateListOf<Nav>() }
    val open: (Nav) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    if (t == Tab.SETTINGS && onAi != null) NavigationBarItem(selected = false, onClick = onAi, icon = {}, label = { Text("AI") })
                    NavigationBarItem(selected = tab == t && stack.isEmpty(), onClick = { tab = t; stack.clear() }, icon = {}, label = { Text(t.title) })
                }
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).padding(Tokens.gap).fillMaxSize()) {
            val s = view.state
            val top = stack.lastOrNull()
            when {
                top is Nav.Card && s != null -> Screens.card(s, top.project)?.let { CardScreen(it, loader, back, open) }
                top is Nav.Dir -> DirScreen(top, loader, back, open)
                top is Nav.Doc -> DocScreen(top, view.names, loader, back, open)
                top is Nav.Stats && s != null -> StatsScreen(view.history, s.projects.map { it.name }, top.project, s.asOf, back, view.status, onExport)
                tab == Tab.SETTINGS -> Settings(view, onSync, onSaveToken, onForgetToken, onClones)
                s == null -> Text(if (view.hasToken) "Среза ещё нет: «Обновить» в настройках." else "Нужен токен GitHub: вкладка «Настройки».")
                tab == Tab.SUMMARY -> Summary(Screens.summary(s), view, onSync) { open(Nav.Stats(null)) }
                tab == Tab.PROJECTS -> Projects(Screens.projects(s)) { open(Nav.Card(it)) }
                tab == Tab.FEED -> FeedScreen(view.feed, s.projects.map { it.name })
                tab == Tab.QUESTIONS -> Questions(Screens.questions(s))
            }
        }
    }
    view.clonePrompt?.let { ClonesDialog(it, onClonesChosen) }
}

@Composable
private fun Line(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label)
    Text(value, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Summary(s: Screens.Summary, view: PultView, onSync: () -> Unit, onStats: () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    Text("Срез ${s.asOf}", style = MaterialTheme.typography.titleMedium)
    Line("Работают", s.active)
    Line("Вопросов автору", s.questions)
    Line("Открытых PR", s.pullsWaiting)
    Line("Проектов", s.projects.toString())
    Line("Ошибок источников", s.sourceErrors.toString())
    Line("Шлюз готов", s.gatesReady)
    Line("Синхронизация", s.syncHealth)
    view.state?.let { st ->
        // Плитки — один раз на историю и срез, не на каждую перерисовку сводки.
        val tiles = remember(view.history, st.asOf) { Stats.tiles(view.history, st.asOf) }
        Tiles(tiles)
    }
    TextButton(onClick = onStats) { Text("Статистика →") }
    Button(onClick = onSync, enabled = !view.busy) { Text(if (view.busy) "Синхронизация…" else "Обновить") }
    if (view.status.isNotEmpty()) Text(view.status, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Projects(groups: List<Screens.Group>, onOpen: (String) -> Unit) = LazyColumn(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    for (g in groups) {
        item { Text(g.title, style = MaterialTheme.typography.titleMedium) }
        items(g.rows) { r ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(r.name) }) {
                Column(Modifier.padding(Tokens.gap)) {
                    Text(r.name, fontWeight = FontWeight.SemiBold)
                    Text("${r.working}; решений ${r.decisions}, TODO ${r.todo}, ROADMAP ${r.roadmap}, PR ${r.pulls}" + if (r.errors > 0) "; ошибок ${r.errors}" else "")
                }
            }
        }
    }
}

/** Пункт панели (F-10): читается при показе карточки; документ — переход в просмотр (F-09). */
@Composable
private fun PanelRow(project: String, item: Panel.Item, loader: Loader, open: (Nav) -> Unit) {
    if (item.kind == Panel.Kind.DOCUMENT) {
        TextButton(onClick = { open(Nav.Doc(project, item.path, null)) }) { Text(item.title) }
        return
    }
    var lines by remember(project, item) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(project, item) {
        if (item.kind == Panel.Kind.COUNT) {
            loader.directory(project, item.path) { r -> lines = if (r is Outcome.Success) Panels.fromDirectory(item, r.value) else Panels.failed(r as Outcome.Failure) }
        } else {
            loader.document(project, item.path) { r -> lines = if (r is Outcome.Success) Panels.fromText(item, r.value) else Panels.failed(r as Outcome.Failure) }
        }
    }
    val shown = lines
    Column {
        Text("${item.title}: ${shown?.firstOrNull() ?: "…"}")
        shown?.drop(1)?.forEach { Text("  $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun checked(c: Screens.Checked?): String =
    c?.let { "#${it.number} — ${it.verdict}, ${it.age}" + (it.line?.let { l -> "; «$l»" } ?: "") } ?: Screens.UNKNOWN

@Composable
private fun CardScreen(c: Screens.Card, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) {
    // Панель (F-10) — один раз на карточку: файл из APK и его разбор не повторяются при перерисовке.
    val panel = remember(c.name) { loader.panel(c.name) }
    CardList(c, panel, loader, onBack, open)
}

@Composable
private fun CardList(c: Screens.Card, panel: Outcome<Panel>?, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) = LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    item { TextButton(onClick = onBack) { Text("← Назад") } }
    item { Text("${c.name} — ${c.place}, ${c.working}", style = MaterialTheme.typography.titleMedium) }
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { open(Nav.Dir(c.name, "")) }) { Text("Документы") }
            for ((title, path) in c.files) TextButton(onClick = { open(Nav.Doc(c.name, path, null)) }) { Text(title) }
        }
    }
    item { Text("Решений: ${c.decisions}", fontWeight = FontWeight.SemiBold) }
    items(c.lastDecisions) { Text("  $it") }
    item { Text("TODO: ${c.todo}, за автором ${c.forAuthor}", fontWeight = FontWeight.SemiBold) }
    item { TextButton(onClick = { open(Nav.Stats(c.name)) }) { Text("Графики проекта →") } }
    item { Text("Готовность: ${c.ready.stage}; ${c.ready.verdict}", fontWeight = FontWeight.SemiBold) }
    items(c.ready.criteria) { Text("  $it") }
    item { Text("Ход PR: ${c.ready.pulls}") }
    item { Text("ROADMAP: ${c.roadmap}", fontWeight = FontWeight.SemiBold) }
    items(c.stages) { s -> Text("  [${s.flag}] ${s.title}" + if (s.steps.isEmpty()) "" else " — ${s.steps}") }
    item { Text("PR: ${c.pulls.size}", fontWeight = FontWeight.SemiBold) }
    items(c.pulls) { p -> Text("  #${p.number} ${p.title}" + (if (p.draft) " (черновик)" else "") + " — ${p.head}, ${p.updated}") }
    item { Text("Проверки последнего PR: ${checked(c.lastPull)}") }
    item { Text("Проверки последнего влитого: ${checked(c.lastMerged)}") }
    item { Text("Ветки: ${c.branches.size}", fontWeight = FontWeight.SemiBold) }
    items(c.branches) { b -> Text("  ${if (b.recent) "●" else "○"} ${b.name} — ${b.updated}") }
    for (p in c.panel) {
        item { Text("О себе (${p.name})", fontWeight = FontWeight.SemiBold) }
        item { Text(p.text, style = MaterialTheme.typography.bodySmall) }
    }
    when (panel) {
        null -> Unit
        is Outcome.Failure -> item { Text("Панель: ${Panels.failed(panel).first()}", color = MaterialTheme.colorScheme.error) }
        is Outcome.Success -> {
            item { Text("Панель", fontWeight = FontWeight.SemiBold) }
            items(panel.value.items) { PanelRow(c.name, it, loader, open) }
        }
    }
    if (c.errors.isNotEmpty()) {
        item { Text("Ошибки источников", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        items(c.errors) { Text("  $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun FeedScreen(events: List<FeedEvent>, projects: List<String>) {
    var project by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<String?>(null) }
    val rows = Screens.feed(events, project, kind)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("все виды") })
            for ((code, title) in Feed.KINDS) FilterChip(selected = kind == code, onClick = { kind = if (kind == code) null else code }, label = { Text(title) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = project == null, onClick = { project = null }, label = { Text("все проекты") })
            for (p in projects) FilterChip(selected = project == p, onClick = { project = if (project == p) null else p }, label = { Text(p) })
        }
        if (rows.isEmpty()) Text("Событий нет: они появляются со второй синхронизации.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows) { r ->
                Column {
                    Text("${r.time} · ${r.project} · ${r.kind}", style = MaterialTheme.typography.bodySmall)
                    Text(r.text)
                }
            }
        }
    }
}

@Composable
private fun DirScreen(nav: Nav.Dir, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) {
    var result by remember(nav) { mutableStateOf<Outcome<List<GitHub.Entry>>?>(null) }
    LaunchedEffect(nav) { loader.directory(nav.project, nav.path) { result = it } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { TextButton(onClick = onBack) { Text("← Назад") } }
        item { Text("${nav.project}: /${nav.path}", style = MaterialTheme.typography.titleMedium) }
        when (val r = result) {
            null -> item { Text("Загрузка…") }
            is Outcome.Failure -> item { Text("Не загрузилось: ${r.diagnostics.first().code.name} — ${r.diagnostics.first().message}", color = MaterialTheme.colorScheme.error) }
            is Outcome.Success -> items(r.value) { e ->
                Text(
                    (if (e.dir) "📁 " else "") + e.name,
                    Modifier.fillMaxWidth().clickable { open(if (e.dir) Nav.Dir(nav.project, e.path) else Nav.Doc(nav.project, e.path, null)) }.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DocScreen(nav: Nav.Doc, names: Map<String, String>, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) {
    var result by remember(nav) { mutableStateOf<Outcome<String>?>(null) }
    var anchor by remember(nav) { mutableStateOf(nav.anchor) }
    LaunchedEffect(nav) { loader.document(nav.project, nav.path) { result = it } }
    val scroll = rememberScrollState()
    var layout by remember(nav) { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.verticalScroll(scroll)) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("${nav.project}: ${nav.path}", style = MaterialTheme.typography.titleMedium)
        when (val r = result) {
            null -> Text("Загрузка…")
            is Outcome.Failure -> Text("Не загрузилось: ${r.diagnostics.first().code.name} — ${r.diagnostics.first().message}", color = MaterialTheme.colorScheme.error)
            is Outcome.Success -> {
                val markdown = nav.path.endsWith(".md", ignoreCase = true)
                val doc = remember(r.value) { if (markdown) DocText.build(r.value) else null }
                if (doc == null) {
                    Text(r.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                } else {
                    val text = remember(doc, names) {
                        DocText.annotated(doc, names, nav.project, nav.path) { target ->
                            when (target) {
                                is Links.Target.Anchor -> anchor = target.anchor
                                is Links.Target.File -> open(Nav.Doc(target.project, target.path, target.anchor))
                                is Links.Target.External -> Unit
                            }
                        }
                    }
                    Text(text, onTextLayout = { layout = it })
                    LaunchedEffect(anchor, layout) {
                        val a = anchor ?: return@LaunchedEffect
                        val l = layout ?: return@LaunchedEffect
                        doc.headings.firstOrNull { Links.slug(it.second) == a }?.let { (offset, _) -> scroll.animateScrollTo(l.getLineTop(l.getLineForOffset(offset)).toInt()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Questions(q: List<Screens.Question>) = LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    item { Text("Вопросов автору: ${q.size}", style = MaterialTheme.typography.titleMedium) }
    items(q) { i -> Text("${i.project}: " + (i.label?.let { "[$it] " } ?: "") + i.title) }
}

@Composable
private fun Settings(view: PultView, onSync: () -> Unit, onSaveToken: (String) -> Unit, onForget: () -> Unit, onClones: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    var token by remember { mutableStateOf("") }
    Text("GitHub", style = MaterialTheme.typography.titleMedium)
    Text(
        if (view.hasToken) {
            "Токен сохранён под ключом Keystore."
        } else {
            "Fine-grained PAT только для чтения: Contents, Pull requests, Metadata (D-17)."
        },
    )
    OutlinedTextField(value = token, onValueChange = { token = it.trim() }, label = { Text("Токен") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
        Button(onClick = { onSaveToken(token); token = "" }, enabled = token.isNotEmpty()) { Text("Сохранить") }
        if (view.hasToken) TextButton(onClick = onForget) { Text("Забыть токен") }
    }
    Spacer(Modifier.height(Tokens.gap))
    Button(onClick = onSync, enabled = view.hasToken && !view.busy) { Text(if (view.busy) "Синхронизация…" else "Обновить") }
    if (view.status.isNotEmpty()) Text(view.status, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(Tokens.gap))
    Text("Клоны репозиториев", style = MaterialTheme.typography.titleMedium)
    Text(
        "Отмеченные репозитории клонируются в приложение: «AI» работает в них (Linux-среда, /root/repos), пульт берёт из них файлы. " +
            view.clones,
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = onClones, enabled = view.hasToken && !view.busy) { Text("Клоны…") }
}

/**
 * Выбор клонов (D-70): репозиторий — строкой с отметкой; реестр — сверху.
 * Снятая отметка клон не удаляет: в нём может быть работа ИИ — он просто не
 * обновляется и не читается.
 */
@Composable
fun ClonesDialog(prompt: ClonePrompt, onDone: (Set<String>?) -> Unit) {
    var checked by remember(prompt) { mutableStateOf(prompt.checked) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Какие репозитории клонировать?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                Text(
                    "Клон — полная копия в приложении: «AI» работает в нём, пульт берёт из него файлы без запросов. " +
                        "Первое клонирование — долгое, лучше по Wi-Fi. Неотмеченные пульт читает с GitHub. Выбор меняется в «Настройках».",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Список — не выше половины экрана: кнопки окна остаются видны.
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(prompt.options, key = { it.slug }) { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { checked = if (o.slug in checked) checked - o.slug else checked + o.slug },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = o.slug in checked, onCheckedChange = null)
                            Column(Modifier.padding(start = 6.dp)) {
                                Text(o.slug, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull("реестр".takeIf { o.registry }, "закрытый".takeIf { o.private }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(checked) }) { Text("Клонировать отмеченные (${checked.size})") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(if (prompt.first) "Позже" else "Отмена") } },
    )
}

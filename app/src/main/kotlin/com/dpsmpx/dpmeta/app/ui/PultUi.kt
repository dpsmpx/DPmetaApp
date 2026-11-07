package com.dpsmpx.dpmeta.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.R
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

/** Отступы пульта (ТЗ, §9). Цвета — не здесь: тема приложения одна (D-74), смысловые — `AppPalette`. */
object Tokens {
    val gap = 12.dp
}

/**
 * Тема пульта — та же, что у «AI» (D-74; UX-5): цвета обоев телефона
 * (Android 12+), на Android 11 — запасная схема `AppPalette` всеми ролями.
 */
@Composable
fun PultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
    } else {
        fallbackScheme(dark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Запасная схема Android 11 — роли `AppPalette` (как у «AI», `fallbackScheme` её оболочки). */
private fun fallbackScheme(dark: Boolean): ColorScheme {
    val p = com.dpsmpx.dpai.ui.AppPalette.scheme(dark)
    fun c(v: Long) = Color(v)
    return if (dark) {
        darkColorScheme(
            primary = c(p.primary), onPrimary = c(p.onPrimary), primaryContainer = c(p.primaryContainer), onPrimaryContainer = c(p.onPrimaryContainer),
            secondary = c(p.secondary), onSecondary = c(p.onSecondary), secondaryContainer = c(p.secondaryContainer), onSecondaryContainer = c(p.onSecondaryContainer),
            tertiary = c(p.tertiary), onTertiary = c(p.onTertiary), tertiaryContainer = c(p.tertiaryContainer), onTertiaryContainer = c(p.onTertiaryContainer),
            background = c(p.background), onBackground = c(p.onBackground), surface = c(p.surface), onSurface = c(p.onSurface),
            surfaceVariant = c(p.surfaceVariant), onSurfaceVariant = c(p.onSurfaceVariant), outline = c(p.outline), outlineVariant = c(p.outlineVariant),
            error = c(p.error), onError = c(p.onError), errorContainer = c(p.errorContainer), onErrorContainer = c(p.onErrorContainer),
            surfaceContainerLowest = c(p.surfaceContainerLowest), surfaceContainerLow = c(p.surfaceContainerLow), surfaceContainer = c(p.surfaceContainer),
            surfaceContainerHigh = c(p.surfaceContainerHigh), surfaceContainerHighest = c(p.surfaceContainerHighest),
            inverseSurface = c(p.inverseSurface), inverseOnSurface = c(p.inverseOnSurface), inversePrimary = c(p.inversePrimary),
        )
    } else {
        lightColorScheme(
            primary = c(p.primary), onPrimary = c(p.onPrimary), primaryContainer = c(p.primaryContainer), onPrimaryContainer = c(p.onPrimaryContainer),
            secondary = c(p.secondary), onSecondary = c(p.onSecondary), secondaryContainer = c(p.secondaryContainer), onSecondaryContainer = c(p.onSecondaryContainer),
            tertiary = c(p.tertiary), onTertiary = c(p.onTertiary), tertiaryContainer = c(p.tertiaryContainer), onTertiaryContainer = c(p.onTertiaryContainer),
            background = c(p.background), onBackground = c(p.onBackground), surface = c(p.surface), onSurface = c(p.onSurface),
            surfaceVariant = c(p.surfaceVariant), onSurfaceVariant = c(p.onSurfaceVariant), outline = c(p.outline), outlineVariant = c(p.outlineVariant),
            error = c(p.error), onError = c(p.onError), errorContainer = c(p.errorContainer), onErrorContainer = c(p.onErrorContainer),
            surfaceContainerLowest = c(p.surfaceContainerLowest), surfaceContainerLow = c(p.surfaceContainerLow), surfaceContainer = c(p.surfaceContainer),
            surfaceContainerHigh = c(p.surfaceContainerHigh), surfaceContainerHighest = c(p.surfaceContainerHighest),
            inverseSurface = c(p.inverseSurface), inverseOnSurface = c(p.inverseOnSurface), inversePrimary = c(p.inversePrimary),
        )
    }
}

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
    /** Код и сообщение отказа к [status] — под «Подробнее» (UX-13); пусто — нет. */
    val statusDetails: String = "",
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

/**
 * Пункты панели пульта (D-73; ТЗ, §9): пять со значками и подписями — «AI»
 * между «Статистикой» и «Ещё» открывает подсистему (D-53) и пунктом этого
 * списка не считается. С «Соцсетями» пунктов станет шесть.
 */
private enum class Section(val title: String, val icon: Int) {
    SUMMARY("Сводка", R.drawable.ic_pult_summary),
    DEV("Разработка", R.drawable.ic_pult_dev),
    STATS("Статистика", R.drawable.ic_pult_stats),
    MORE("Ещё", R.drawable.ic_pult_more),
}

/** Вкладки сверху «Разработки» (D-73): проекты и вопросы автору; позже — PR (F-11). */
private enum class DevTab(val title: String) { PROJECTS("Проекты"), QUESTIONS("Вопросы") }

/** Куда перешли внутри вкладки: карточка → каталог → документ; «назад» снимает верх. */
private sealed interface Nav {
    data class Card(val project: String) : Nav

    data class Dir(val project: String, val path: String) : Nav

    data class Doc(val project: String, val path: String, val anchor: String?) : Nav

    /** Статистика (F-34): [project] — из карточки, `null` — сравнение всех проектов. */
    data class Stats(val project: String?) : Nav

    /** Вся лента событий (F-05) — со сводки (D-73). */
    data object Feed : Nav

    companion object {
        private const val SEP = "\u0001"

        /** Строкой — для сохранения места при смерти процесса (UX-3); поля разделены непечатным знаком. */
        fun encode(n: Nav): String = when (n) {
            is Card -> listOf("card", n.project)
            is Dir -> listOf("dir", n.project, n.path)
            is Doc -> listOf("doc", n.project, n.path, n.anchor.orEmpty())
            is Stats -> listOf("stats", n.project.orEmpty())
            Feed -> listOf("feed")
        }.joinToString(SEP)

        fun decode(s: String): Nav? {
            val p = s.split(SEP)
            return when (p.firstOrNull()) {
                "card" -> p.getOrNull(1)?.let(::Card)
                "dir" -> if (p.size >= 3) Dir(p[1], p[2]) else null
                "doc" -> if (p.size >= 4) Doc(p[1], p[2], p[3].ifEmpty { null }) else null
                "stats" -> Stats(p.getOrNull(1)?.ifEmpty { null })
                "feed" -> Feed
                else -> null
            }
        }
    }
}

/**
 * Стеки вкладок (UX-2, UX-3): у каждой вкладки — свой, и они переживают
 * поворот и смерть процесса. Сохраняются строками «вкладка, переход».
 */
private val StacksSaver = listSaver<SnapshotStateMap<Section, List<Nav>>, String>(
    save = { m -> m.flatMap { (t, l) -> l.map { "${t.name}\u0002${Nav.encode(it)}" } } },
    restore = { list ->
        mutableStateMapOf<Section, List<Nav>>().apply {
            for (e in list) {
                val (t, n) = e.split("\u0002", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val tab = Section.entries.firstOrNull { it.name == t } ?: continue
                val nav = Nav.decode(n) ?: continue
                this[tab] = this[tab].orEmpty() + nav
            }
        }
    },
)

/** [onAi] — пункт «AI» перед «Ещё» (D-53, D-73): открывает оболочку подсистемы; `null` — пункта нет. */
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
    // Пункт и стеки — rememberSaveable: поворот, тема и шрифт больше не сбрасывают место (UX-3).
    var tab by rememberSaveable { mutableStateOf(if (view.hasToken) Section.SUMMARY else Section.MORE) }
    var devTab by rememberSaveable { mutableStateOf(DevTab.PROJECTS) }
    val stacks = rememberSaveable(saver = StacksSaver) { mutableStateMapOf() }
    val stack = stacks[tab].orEmpty()
    val open: (Nav) -> Unit = { stacks[tab] = stacks[tab].orEmpty() + it }
    val back: () -> Unit = { stacks[tab]?.takeIf { it.isNotEmpty() }?.let { stacks[tab] = it.dropLast(1) } }
    // Системный «назад» — шаг вверх по стеку пункта, а не выход из приложения (UX-2).
    BackHandler(enabled = stack.isNotEmpty(), onBack = back)
    Scaffold(
        topBar = { Header(tab.title, view) },
        bottomBar = {
            NavigationBar {
                for (t in Section.entries) {
                    if (t == Section.MORE && onAi != null) {
                        NavigationBarItem(selected = false, onClick = onAi, icon = { Icon(painterResource(R.drawable.ic_pult_ai), contentDescription = null) }, label = { Text("AI") })
                    }
                    // Пункт выделен и во вложенном экране; касание открытого — к его началу, другого — туда, где оставили.
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { if (tab == t) stacks[t] = emptyList() else tab = t },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.title, maxLines = 1) },
                    )
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
                top is Nav.Feed && s != null -> FeedScreen(view.feed, s.projects.map { it.name }, back)
                tab == Section.MORE -> Settings(view, onSync, onSaveToken, onForgetToken, onClones)
                s == null -> Text(if (view.hasToken) "Среза ещё нет: «Обновить» в пункте «Ещё»." else "Нужен токен GitHub: пункт «Ещё».")
                tab == Section.SUMMARY -> Summary(Screens.summary(s), view, onSync, onStats = { tab = Section.STATS }) { open(Nav.Feed) }
                tab == Section.STATS -> StatsScreen(view.history, s.projects.map { it.name }, null, s.asOf, null, view.status, onExport)
                tab == Section.DEV -> Column(Modifier.fillMaxSize()) {
                    DevTabs(devTab) { devTab = it }
                    when (devTab) {
                        DevTab.PROJECTS -> Projects(Screens.projects(s)) { open(Nav.Card(it)) }
                        DevTab.QUESTIONS -> Questions(Screens.questions(s))
                    }
                }
            }
        }
    }
    view.clonePrompt?.let { ClonesDialog(it, onClonesChosen) }
}

/**
 * Заголовок каждого экрана пульта (D-73; ТЗ, §9): пункт, время среза или
 * «обновляется»; во время синхронизации — тонкая полоса хода под ним.
 */
@Composable
private fun Header(title: String, view: PultView) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Column(Modifier.padding(horizontal = Tokens.gap, vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val asOf = view.state?.let { "срез ${Screens.time(it.asOf)}" }
                Text(
                    when {
                        view.busy -> "Обновляется…" + (asOf?.let { " · $it" } ?: "")
                        asOf != null -> asOf.replaceFirstChar { it.uppercase() }
                        else -> "Среза ещё нет"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Полоса — того же места и без синхронизации: заголовок не прыгает.
            if (view.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevTabs(selected: DevTab, onSelect: (DevTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        for (t in DevTab.entries) Tab(selected = t == selected, onClick = { onSelect(t) }, text = { Text(t.title) })
    }
    Spacer(Modifier.height(Tokens.gap))
}

@Composable
private fun Line(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label)
    Text(value, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Summary(s: Screens.Summary, view: PultView, onSync: () -> Unit, onStats: () -> Unit, onFeed: () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    // Время среза — в заголовке (D-73).
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
    Row {
        TextButton(onClick = onStats) { Text("Статистика →") }
        TextButton(onClick = onFeed) { Text("Лента →") }
    }
    Button(onClick = onSync, enabled = !view.busy) { Text(if (view.busy) "Синхронизация…" else "Обновить") }
    if (view.status.isNotEmpty()) StatusText(view)
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
private fun FeedScreen(events: List<FeedEvent>, projects: List<String>, onBack: () -> Unit) {
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = Screens.feed(events, project, kind)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("Лента", style = MaterialTheme.typography.titleMedium)
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
            is Outcome.Failure -> item { FailureText("Не загрузилось", Screens.failure(r.diagnostics.first())) }
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
            is Outcome.Failure -> FailureText("Не загрузилось", Screens.failure(r.diagnostics.first()))
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
    // Пока токен сохранён — одна строка, поле — по «Заменить» (UX-12): пустое поле при сохранённом токене путало.
    var replacing by rememberSaveable { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    Text("GitHub", style = MaterialTheme.typography.titleMedium)
    if (view.hasToken && !replacing) {
        Text("Токен GitHub сохранён под ключом Keystore.")
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
            TextButton(onClick = { replacing = true }) { Text("Заменить") }
            TextButton(onClick = { forgetting = true }) { Text("Забыть токен…", color = MaterialTheme.colorScheme.error) }
        }
    } else {
        Text("Fine-grained PAT только для чтения: Contents, Pull requests, Metadata.")
        // Тип «пароль» для клавиатуры: без подсказок и запоминания набранного, а не только точки на экране (UX-12).
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() }, label = { Text("Токен") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
            Button(onClick = { onSaveToken(token); token = ""; replacing = false }, enabled = token.isNotEmpty()) { Text("Сохранить") }
            if (replacing) TextButton(onClick = { replacing = false; token = "" }) { Text("Отмена") }
        }
    }
    // Необратимое — окном (UX-12, AI:A-4): fine-grained PAT GitHub показывает один раз.
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text("Забыть токен GitHub?") },
            text = {
                Text(
                    "Токен удалится с телефона, и пульт перестанет обновляться. На GitHub он останется действующим — " +
                        "отзовите его там. Показать его ещё раз GitHub не сможет: для пульта нужен будет новый.",
                )
            },
            confirmButton = { TextButton(onClick = { forgetting = false; onForget() }) { Text("Забыть", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Отмена") } },
        )
    }
    Spacer(Modifier.height(Tokens.gap))
    Button(onClick = onSync, enabled = view.hasToken && !view.busy) { Text(if (view.busy) "Синхронизация…" else "Обновить") }
    if (view.status.isNotEmpty()) StatusText(view)
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
                        "Первое клонирование — долгое, лучше по Wi-Fi. Неотмеченные пульт читает с GitHub. Выбор меняется в пункте «Ещё».",
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
                                    if (o.gone) "нет в списке аккаунта — снимите отметку, если не нужен" else listOfNotNull("реестр".takeIf { o.registry }, "закрытый".takeIf { o.private }).joinToString(" · "),
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

/** Отказ: причина словом, код и сообщение — по «Подробнее» (ТЗ, §6.9; UX-13). */
@Composable
private fun FailureText(what: String, f: Screens.Failure) = Column {
    Text("$what: ${f.text}", color = MaterialTheme.colorScheme.error)
    Details(f.details)
}

/** Строка итога синхронизации и действий; у отказа — «Подробнее». */
@Composable
private fun StatusText(view: PultView) = Column {
    Text(view.status, style = MaterialTheme.typography.bodySmall)
    if (view.statusDetails.isNotEmpty()) Details(view.statusDetails)
}

@Composable
private fun Details(details: String) {
    var shown by rememberSaveable(details) { mutableStateOf(false) }
    TextButton(onClick = { shown = !shown }) { Text(if (shown) "Скрыть" else "Подробнее") }
    if (shown) Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

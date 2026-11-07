package com.dpsmpx.dpmeta.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.dpsmpx.dpai.ui.LinkTaps
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.produceState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
import com.dpsmpx.dpmeta.app.screens.Screens.DevTab
import com.dpsmpx.dpmeta.app.screens.Screens.Section
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

/**
 * Запасная схема Android 11 — роли `AppPalette`. Копия `fallbackScheme` оболочки «AI»
 * (`dpai/android`, `MainActivity.kt`): пульт от неё не зависит (D-53); новая роль — в обе.
 */
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
    /** Числа «AI» от оболочки `:shell` (D-76); `null` — строки «AI» нет. */
    val ai: Screens.AiCounts? = null,
    /** Номер законченной (или не начатой) синхронизации — итог снэкбаром один раз (UX-6); 0 — ещё не было. */
    val syncEnded: Int = 0,
    /** Чем она кончилась — текст и переход снэкбара. */
    val syncEnd: SyncEnd = SyncEnd.DONE,
    /** Ход синхронизации (UX-10): «Клоны: 3 из 12», «Источники…»; `null` — не идёт или ход неизвестен. */
    val progress: String? = null,
)

/**
 * Окно выбора клонов (D-70): все репозитории аккаунта, реестр — сверху;
 * [checked] — отмеченные заранее (при первом входе — реестр); [first] —
 * первый вход: «Позже» спросит снова.
 */
data class ClonePrompt(val options: List<com.dpsmpx.dpmeta.app.data.PultRepository.CloneOption>, val checked: Set<String>, val first: Boolean)

/** Итог «Обновить» для снэкбара (UX-6): обновлено, не вышло, не начато (нет токена, уже идёт). */
enum class SyncEnd { DONE, FAILED, REFUSED }

/** Загрузка по запросу (F-09): каталог и файл проекта; итог — в главном потоке. */
interface Loader {
    fun directory(project: String, dir: String, done: (Outcome<List<GitHub.Entry>>) -> Unit)

    fun document(project: String, path: String, done: (Outcome<String>) -> Unit)

    /** Панель проекта (F-10) — `config/projects/<Проект>.toml` в APK; нет файла — `null`. */
    fun panel(project: String): Outcome<Panel>? = null
}

/** Значки пунктов панели (D-73): свои векторные рисунки. */
private val Section.icon: Int get() = when (this) {
    Section.SUMMARY -> R.drawable.ic_pult_summary
    Section.DEV -> R.drawable.ic_pult_dev
    Section.STATS -> R.drawable.ic_pult_stats
    Section.MORE -> R.drawable.ic_pult_more
}

/** Высота панели пульта — та же, что у панели «AI» (D-73, `DP_AI_Orcestrator:D-112`: 48 dp); крупный шрифт её растит. */
private val PANEL_HEIGHT = 48.dp

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

/**
 * [onAi] — пункт «AI» перед «Ещё» (D-53, D-73): открывает оболочку подсистемы
 * (`true` — на «Запросах», D-76); `null` — пункта нет.
 */
@Composable
fun PultApp(
    view: PultView,
    loader: Loader,
    onSync: () -> Unit,
    onSaveToken: (String) -> Unit,
    onForgetToken: () -> Unit,
    onAi: ((requests: Boolean) -> Unit)? = null,
    onExport: ((json: Boolean, period: Stats.Period, share: Boolean) -> Unit)? = null,
    /** «Клоны…» в настройках (D-70). */
    onClones: () -> Unit = {},
    /** «Отменить» клоны идущей синхронизации (UX-10). */
    onCancelSync: () -> Unit = {},
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
    // Итог синхронизации — снэкбаром один раз (UX-6): «Обновлено», ошибки и отказ — с переходом к причине.
    val snack = remember { SnackbarHostState() }
    // Номер итога живёт в окне, а не в сохранённом состоянии: новое окно (смерть процесса) начинает с 0 и
    // не показывает «Обновлено» без синхронизации (проверка PR #172).
    var shownSync by remember { mutableIntStateOf(view.syncEnded) }
    LaunchedEffect(view.syncEnded) {
        if (view.syncEnded <= shownSync) return@LaunchedEffect
        shownSync = view.syncEnded
        val errors = view.state?.projects?.sumOf { it.errors.size } ?: 0
        val text = when {
            view.syncEnd == SyncEnd.FAILED -> "Не обновилось — причина в «Ещё»"
            view.syncEnd == SyncEnd.REFUSED -> view.status.ifEmpty { "Не обновилось" }
            errors > 0 -> "Обновлено · ошибок источников $errors"
            else -> "Обновлено"
        }
        val more = view.syncEnd != SyncEnd.DONE || errors > 0
        val r = snack.showSnackbar(text, actionLabel = if (more) "Подробнее" else null, withDismissAction = true, duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) tab = if (view.syncEnd != SyncEnd.DONE) Section.MORE else Section.DEV.also { devTab = DevTab.PROJECTS }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { Header(tab.title, view, onSync, onCancelSync) },
        bottomBar = {
            // Панель — своя, как у «AI» (D-73): значки и подписи, 48 dp, роль вкладки и «выбрано» для TalkBack.
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).heightIn(min = PANEL_HEIGHT).selectableGroup(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    // Порядок — Screens.panelItems: тот же, что сверяет тест панели.
                    for (t in Screens.panelItems(ai = onAi != null)) {
                        if (t == null) {
                            PanelItem(Screens.AI_TITLE, R.drawable.ic_pult_ai, selected = null) { onAi?.invoke(false) }
                        } else {
                            // Пункт выделен и во вложенном экране; касание открытого — к его началу, другого — туда, где оставили.
                            PanelItem(t.title, t.icon, selected = tab == t) { if (tab == t) stacks[t] = emptyList() else tab = t }
                        }
                    }
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
                tab == Section.SUMMARY -> Summary(
                    Screens.summary(s), view, onSync, onAi,
                    onTile = { g ->
                        when (g) {
                            Screens.Goes.PROJECTS -> { tab = Section.DEV; devTab = DevTab.PROJECTS }
                            Screens.Goes.QUESTIONS -> { tab = Section.DEV; devTab = DevTab.QUESTIONS }
                            Screens.Goes.SYNC -> tab = Section.MORE
                        }
                    },
                ) { open(Nav.Feed) }
                tab == Section.STATS -> StatsScreen(view.history, s.projects.map { it.name }, null, s.asOf, null, view.status, onExport)
                tab == Section.DEV -> Column(Modifier.fillMaxSize()) {
                    DevTabs(devTab) { devTab = it }
                    when (devTab) {
                        DevTab.PROJECTS -> Projects(Screens.projects(s)) { open(Nav.Card(it)) }
                        DevTab.QUESTIONS -> Questions(Screens.questions(s), open)
                    }
                }
            }
        }
    }
    view.clonePrompt?.let { ClonesDialog(it, onClonesChosen) }
}

/** Пункт панели: значок над подписью; [selected] `null` — не вкладка, а вход («AI»). */
@Composable
private fun RowScope.PanelItem(label: String, icon: Int, selected: Boolean?, onClick: () -> Unit) {
    val color = if (selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val action = if (selected == null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
    }
    Column(
        Modifier.weight(1f).heightIn(min = PANEL_HEIGHT).then(action).padding(vertical = 2.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Заголовок каждого экрана пульта (D-73; ТЗ, §9): пункт, время среза или
 * «обновляется»; во время синхронизации — тонкая полоса хода под ним.
 */
@Composable
private fun Header(title: String, view: PultView, onSync: () -> Unit, onCancel: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = Tokens.gap, vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val asOf = view.state?.let { "срез ${Screens.time(it.asOf)}" }
                Text(
                    when {
                        view.busy -> "Обновляется…" + (view.progress?.let { " · $it" } ?: asOf?.let { " · $it" } ?: "")
                        asOf != null -> asOf.replaceFirstChar { it.uppercase() }
                        else -> "Среза ещё нет"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Клоны идут — «Отменить» между репозиториями (UX-10); иначе — «Обновить», одно, значком (UX-6).
            if (view.busy && view.progress?.startsWith("Клоны") == true) {
                TextButton(onClick = onCancel) { Text("Отменить") }
            } else {
                IconButton(onClick = onSync, enabled = view.hasToken && !view.busy) {
                    Icon(painterResource(R.drawable.ic_pult_refresh), contentDescription = "Обновить")
                }
            }
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

/**
 * Сводка (UX-6; ТЗ, F-04, §9): одна сетка плиток — у каждой свой экран,
 * строка «AI» (D-76), последние события и вся лента. «Обновить» — в
 * заголовке и жестом «потянуть»; итог — снэкбаром.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Summary(
    s: Screens.Summary,
    view: PultView,
    onSync: () -> Unit,
    onAi: ((requests: Boolean) -> Unit)?,
    onTile: (Screens.Goes) -> Unit,
    onFeed: () -> Unit,
) = PullToRefreshBox(isRefreshing = view.busy, onRefresh = { if (view.hasToken) onSync() }, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
        val history = remember(view.history, view.state?.asOf) { view.state?.let { Stats.tiles(view.history, it.asOf) }.orEmpty().associateBy { it.metric.id } }
        SummaryTiles(Screens.tiles(s), history, onTile)
        view.ai?.let { c ->
            Card(Modifier.fillMaxWidth().clickable(enabled = onAi != null) { onAi?.invoke(Screens.aiToRequests(c)) }) {
                Text(Screens.aiLine(c) + " ▸", Modifier.padding(Tokens.gap))
            }
        }
        val recent = remember(view.feed) { Screens.feed(view.feed).take(SUMMARY_EVENTS) }
        Text("Последние события", style = MaterialTheme.typography.titleSmall)
        if (recent.isEmpty()) Text("Событий нет: они появляются со второй синхронизации.", style = MaterialTheme.typography.bodySmall)
        for (r in recent) {
            Column {
                Text("${r.time} · ${r.project} · ${r.kind}", style = MaterialTheme.typography.bodySmall)
                Text(r.text, maxLines = 2)
            }
        }
        TextButton(onClick = onFeed) { Text("Вся лента ▸") }
    }
}

/** Сколько последних событий на сводке; остальные — «Вся лента ▸». */
private const val SUMMARY_EVENTS = 3

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

@Composable
private fun CardScreen(c: Screens.Card, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) {
    // Панель (F-10) — один раз на карточку: файл из APK и его разбор не повторяются при перерисовке.
    val panel = remember(c.name) { loader.panel(c.name) }
    CardList(c, panel, loader, onBack, open)
}

/**
 * Карточка проекта (UX-8; F-06, F-07): сверху — «Внимание», если есть что
 * сказать; документы — чипами; разделы — свёрнутыми строками с итогом;
 * решение — касанием открывает журнал на своём якоре.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CardList(c: Screens.Card, panel: Outcome<Panel>?, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) {
    val view = remember(c) { Screens.cardView(c) }
    val opened = rememberSaveable(c.name, saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val decisionsPath = c.files.firstOrNull { it.first == "Решения" }?.second
    val todoPath = c.files.firstOrNull { it.first == "TODO" }?.second
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Назад") }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { open(Nav.Stats(c.name)) }) { Text("Графики ▸") }
            }
        }
        item { Text("${c.name} — ${c.place}, ${c.working}", style = MaterialTheme.typography.titleMedium) }
        if (view.attention.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Tokens.gap), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Внимание", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        for (a in view.attention) Text(a, color = MaterialTheme.colorScheme.error)
                        // Ошибки источников — здесь, а не в конце карточки: конфликт клона виден сразу (D-65).
                        for (e in c.errors) Text("  $e", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((title, path) in c.files) AssistChip(onClick = { open(Nav.Doc(c.name, path, null)) }, label = { Text(title) })
                AssistChip(onClick = { open(Nav.Dir(c.name, "")) }, label = { Text("Все файлы") })
            }
        }
        for (sec in view.sections) {
            val key = sec.kind.name
            val expanded = key in opened
            val expandable = sec.lines.isNotEmpty() || (sec.kind == Screens.CardSection.Kind.ABOUT && panel != null)
            item(key = key) {
                Text(
                    sec.title + when {
                        sec.kind == Screens.CardSection.Kind.TODO && todoPath != null -> " ▸"
                        !expandable -> ""
                        expanded -> " ▾"
                        else -> " ▸"
                    },
                    Modifier.fillMaxWidth().clickable(enabled = expandable || (sec.kind == Screens.CardSection.Kind.TODO && todoPath != null)) {
                        if (sec.kind == Screens.CardSection.Kind.TODO && todoPath != null) {
                            open(Nav.Doc(c.name, todoPath, null))
                        } else if (expanded) {
                            opened.remove(key)
                        } else {
                            opened.add(key)
                        }
                    }.padding(vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (expanded) {
                if (sec.kind == Screens.CardSection.Kind.DECISIONS) {
                    // Решение — касанием к журналу на его якоре (F-07); без id — просто строка.
                    items(c.lastDecisions) { d ->
                        val anchor = d.id?.let(Links::slug)
                        Text(
                            "  ${d.text}",
                            Modifier.fillMaxWidth().clickable(enabled = anchor != null && decisionsPath != null) { open(Nav.Doc(c.name, decisionsPath!!, anchor)) }.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    items(sec.lines) { Text("  $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (sec.kind == Screens.CardSection.Kind.ABOUT) {
                    when (panel) {
                        null -> Unit
                        is Outcome.Failure -> item { Text("Панель: ${Panels.failed(panel).first()}", color = MaterialTheme.colorScheme.error) }
                        is Outcome.Success -> items(panel.value.items) { PanelRow(c.name, it, loader, open) }
                    }
                }
            }
        }
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

/**
 * Документ (F-09, UX-9, UX-21): разбор и стили — не в главном потоке,
 * абзацы — списком (якорь — номер абзаца), поиск по тексту, выделение и
 * копирование; внешняя ссылка — по D-75 ([external]).
 */
@Composable
private fun DocScreen(nav: Nav.Doc, names: Map<String, String>, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var result by remember(nav) { mutableStateOf<Outcome<String>?>(null) }
    var anchor by remember(nav) { mutableStateOf(nav.anchor) }
    LaunchedEffect(nav) { loader.document(nav.project, nav.path) { result = it } }
    val taps = remember { LinkTaps() }
    val linkColor = MaterialTheme.colorScheme.primary
    val markdown = nav.path.endsWith(".md", ignoreCase = true)
    val blocks by produceState<DocText.Blocks?>(null, result, names, linkColor) {
        val r = result as? Outcome.Success ?: run {
            value = null
            return@produceState
        }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (markdown) {
                val doc = DocText.build(r.value)
                val text = DocText.annotated(doc, names, nav.project, nav.path, linkColor) { target ->
                    when (target) {
                        is Links.Target.Anchor -> anchor = target.anchor
                        is Links.Target.File -> open(Nav.Doc(target.project, target.path, target.anchor))
                        is Links.Target.External -> external(context, taps, target.url)
                    }
                }
                DocText.blocks(text, doc.headings, markdown = true)
            } else {
                DocText.blocks(androidx.compose.ui.text.AnnotatedString(r.value), emptyList(), markdown = false)
            }
        }
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var searching by rememberSaveable(nav) { mutableStateOf(false) }
    var query by rememberSaveable(nav) { mutableStateOf("") }
    var hit by remember(nav) { mutableStateOf<Int?>(null) }
    LaunchedEffect(anchor, blocks) {
        val a = anchor ?: return@LaunchedEffect
        blocks?.anchorIndex(a)?.let { list.animateScrollToItem(it) }
    }
    LaunchedEffect(hit) { hit?.let { list.animateScrollToItem(it) } }
    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(onClick = { searching = !searching }) { Text(if (searching) "Скрыть поиск" else "Найти") }
        }
        Text("${nav.project}: ${nav.path}", style = MaterialTheme.typography.titleMedium)
        if (searching) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it; hit = blocks?.find(it) }, label = { Text("Найти в документе") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = query.isNotBlank(), onClick = { hit = blocks?.find(query, hit ?: -1) }) { Text("Далее") }
            }
            if (query.isNotBlank() && hit == null && blocks != null) Text("Не найдено", style = MaterialTheme.typography.bodySmall)
        }
        when (val r = result) {
            null -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            is Outcome.Failure -> FailureText("Не загрузилось", Screens.failure(r.diagnostics.first()))
            is Outcome.Success -> {
                val b = blocks
                if (b == null) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                } else {
                    // Выделение и копирование (UX-21): документ — текст, а не картинка.
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(b.items) { _, t ->
                                if (markdown) {
                                    Text(t)
                                } else {
                                    Text(t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Внешняя ссылка (D-75): первое касание копирует и показывает адрес целиком,
 * второе той же — открывает в браузере телефона, только `https`. Адрес — не
 * секрет: копия без «чувствительного» флага.
 */
private fun external(context: android.content.Context, taps: LinkTaps, url: String) {
    fun say(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
    when (val act = taps.tap(url, com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() / 1_000_000)) {
        is LinkTaps.Act.Copy -> {
            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("ссылка", url))
            say(act.text)
        }
        is LinkTaps.Act.Open -> try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(act.url)))
        } catch (_: android.content.ActivityNotFoundException) {
            say("Нет приложения для ссылки — адрес скопирован")
        }
        is LinkTaps.Act.Refuse -> say(act.text)
    }
}

/**
 * Вопросы автору (F-24, UX-17): по проектам, метка — чипом, касание —
 * `TODO.md` проекта на разделе вопроса.
 */
@Composable
private fun Questions(q: List<Screens.Question>, open: (Nav) -> Unit) = LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    item { Text("Вопросов автору: ${q.size}", style = MaterialTheme.typography.titleMedium) }
    for ((project, items) in Screens.questionGroups(q)) {
        item(key = "p:$project") { Text(project, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
        items(items) { i ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = i.path != null) { open(Nav.Doc(i.project, i.path!!, i.section?.let(Links::slug))) }.padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                i.label?.let { AssistChip(onClick = {}, enabled = false, label = { Text(it) }, modifier = Modifier.padding(end = 6.dp)) }
                Text(i.title, Modifier.weight(1f))
                if (i.path != null) Text("▸")
            }
        }
    }
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
    // Полный итог синхронизации — здесь (UX-6): на сводке только снэкбар.
    Text("Синхронизация", style = MaterialTheme.typography.titleMedium)
    // Постоянная строка последней синхронизации (UX-10); временные итоги — снэкбаром.
    view.state?.let { st -> Text("Последняя: ${Screens.time(st.asOf)} · ${Screens.summary(st).syncHealth}", style = MaterialTheme.typography.bodySmall) }
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

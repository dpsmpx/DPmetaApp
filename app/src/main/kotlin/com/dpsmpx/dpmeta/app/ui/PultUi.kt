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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.R
import com.dpsmpx.dpmeta.actions.ActionEntry
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionResult
import com.dpsmpx.dpmeta.actions.ActionStatus
import com.dpsmpx.dpmeta.app.screens.PullActions
import com.dpsmpx.dpmeta.github.PullDetail
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
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
    /** «Отменить» нажато — начатый клон доходит до конца (UX-10). */
    val cancelling: Boolean = false,
    /** Срез и лента ещё читаются с диска (UX-20): не «среза нет», а «читаю». */
    val loading: Boolean = false,
    /** Пояс показа, минуты от UTC (UX-22): ключ перерисовки строк со временем. */
    val zone: Int = 180,
    /** «О программе» (UX-23): версии DPmetaApp и «AI». */
    val about: String = "",
)

/**
 * Ключи списка без своих id (UX-20): содержимое и номер повтора — сдвиг
 * или обрезка начала не перерисовывает весь список.
 */
private fun <T> contentKeys(list: List<T>, of: (T) -> Any): List<String> {
    val seen = HashMap<Any, Int>()
    return list.map { item ->
        val k = of(item)
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        "$k#$n"
    }
}

/**
 * Окно выбора клонов (D-70): все репозитории аккаунта, реестр — сверху;
 * [checked] — отмеченные заранее (при первом входе — реестр); [first] —
 * первый вход: «Позже» спросит снова.
 */
data class ClonePrompt(val options: List<com.dpsmpx.dpmeta.app.data.PultRepository.CloneOption>, val checked: Set<String>, val first: Boolean, val note: String? = null)

/** Итог «Обновить» для снэкбара (UX-6): обновлено, не вышло, не начато (нет токена, уже идёт). */
enum class SyncEnd { DONE, FAILED, REFUSED }

/** Загрузка по запросу (F-09): каталог и файл проекта; итог — в главном потоке. */
interface Loader {
    fun directory(project: String, dir: String, done: (Outcome<List<GitHub.Entry>>) -> Unit)

    fun document(project: String, path: String, done: (Outcome<String>) -> Unit)

    /** Панель проекта (F-10) — `config/projects/<Проект>.toml` в APK; нет файла — `null`. */
    fun panel(project: String): Outcome<Panel>? = null

    /** PR перед действием (F-12): репозиторий проекта и свежий PR с GitHub. */
    fun pull(project: String, number: Int, done: (Outcome<Pair<com.dpsmpx.dpmeta.model.RepoRef, PullDetail>>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "действий с PR здесь нет", project))

    /** Действие (F-12): подтверждение способом [PullActions.Request.way] создаёт активность — после PIN, если нужен. */
    fun act(request: PullActions.Request, done: (Outcome<ActionResult>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "действий здесь нет", request.command.target.display))

    /** Журнал действий (F-44). */
    fun journal(done: (Outcome<List<ActionEntry>>) -> Unit) = done(Outcome.success(emptyList()))

    /** Поделиться текстом (экспорт журнала, F-44). */
    fun share(subject: String, text: String) {}
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

/** Широкий экран (UX-24): от этой ширины — две панели «проекты | карточка». */
private val WIDE = 600.dp

/** Предел ширины содержимого: строки на планшете и в альбомной ориентации не растягиваются во всю ширину. */
private val CONTENT_MAX = 640.dp

/** Предел ширины двух панелей. */
private val TWO_PANE_MAX = 1100.dp

/** Куда перешли внутри вкладки: карточка → каталог → документ; «назад» снимает верх. */
private sealed interface Nav {
    data class Card(val project: String) : Nav

    data class Dir(val project: String, val path: String) : Nav

    data class Doc(val project: String, val path: String, val anchor: String?) : Nav

    /** Статистика (F-34): [project] — из карточки, `null` — сравнение всех проектов. */
    data class Stats(val project: String?) : Nav

    /** Вся лента событий (F-05) — со сводки (D-73). */
    data object Feed : Nav

    /** PR проекта с действиями (F-12) — из карточки. */
    data class Pull(val project: String, val number: Int) : Nav

    /** Журнал действий (F-44) — из «Ещё». */
    data object Journal : Nav

    companion object {
        private const val SEP = "\u0001"

        /** Строкой — для сохранения места при смерти процесса (UX-3); поля разделены непечатным знаком. */
        fun encode(n: Nav): String = when (n) {
            is Card -> listOf("card", n.project)
            is Dir -> listOf("dir", n.project, n.path)
            is Doc -> listOf("doc", n.project, n.path, n.anchor.orEmpty())
            is Stats -> listOf("stats", n.project.orEmpty())
            Feed -> listOf("feed")
            is Pull -> listOf("pull", n.project, n.number.toString())
            Journal -> listOf("journal")
        }.joinToString(SEP)

        fun decode(s: String): Nav? {
            val p = s.split(SEP)
            return when (p.firstOrNull()) {
                "card" -> p.getOrNull(1)?.let(::Card)
                "dir" -> if (p.size >= 3) Dir(p[1], p[2]) else null
                "doc" -> if (p.size >= 4) Doc(p[1], p[2], p[3].ifEmpty { null }) else null
                "stats" -> Stats(p.getOrNull(1)?.ifEmpty { null })
                "feed" -> Feed
                "pull" -> p.getOrNull(2)?.toIntOrNull()?.let { Pull(p[1], it) }
                "journal" -> Journal
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
    /** Удалить не отмеченный клон (UX-11) — после вопроса. */
    onDeleteClone: (String) -> Unit = {},
    /** Пояс показа выбран (UX-22): минуты от UTC. */
    onZone: (Int) -> Unit = {},
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
        // Широкий экран (UX-24): содержимое — не шире CONTENT_MAX по центру, вырез сбоку — отступом;
        // от WIDE — список проектов и карточка двумя панелями.
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.padding(inner).fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        val s = view.state
        val top = stack.lastOrNull()
        val twoPane = maxWidth >= WIDE && tab == Section.DEV && devTab == DevTab.PROJECTS && s != null && (top == null || top is Nav.Card)
        Column(Modifier.widthIn(max = if (twoPane) TWO_PANE_MAX else CONTENT_MAX).fillMaxSize().padding(Tokens.gap)) {
            when {
                twoPane -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                    val chosen = top?.project
                    Column(Modifier.weight(2f).fillMaxSize()) {
                        DevTabs(devTab) { devTab = it }
                        // Другой проект — вместо открытой карточки, а не поверх неё: «Назад» — к списку.
                        Projects(remember(s) { Screens.projects(s) }, chosen) { stacks[tab] = listOf(Nav.Card(it)) }
                    }
                    Column(Modifier.weight(3f).fillMaxSize()) {
                        val card = chosen?.let { remember(s, it, view.zone) { Screens.card(s, it) } }
                        if (card != null) CardScreen(card, loader, back, open) else Text("Выберите проект слева.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Модели экранов — один раз на срез, а не на каждую перекомпозицию (UX-20).
                top is Nav.Card && s != null -> remember(s, top.project, view.zone) { Screens.card(s, top.project) }?.let { CardScreen(it, loader, back, open) }
                top is Nav.Dir -> DirScreen(top, loader, back, open)
                top is Nav.Doc -> DocScreen(top, view.names, loader, back, open)
                top is Nav.Stats && s != null -> StatsScreen(view.history, s.projects.map { it.name }, top.project, s.asOf, back, view.status, onExport)
                top is Nav.Feed && s != null -> FeedScreen(view.feed, s.projects.map { it.name }, back) { open(Nav.Card(it)) }
                top is Nav.Pull -> {
                    val checks = remember(s, top) { s?.let { Screens.card(it, top.project) }?.pulls?.firstOrNull { it.number == top.number.toLong() }?.checks }
                    PullScreen(top.project, top.number, checks, loader, back)
                }
                top is Nav.Journal -> JournalScreen(loader, back)
                tab == Section.MORE -> Settings(view, onSync, onSaveToken, onForgetToken, onClones, onZone, onJournal = { open(Nav.Journal) })
                s == null && view.loading -> Text("Читаю срез…", style = MaterialTheme.typography.bodySmall)
                // Пустое состояние ведёт дальше (UX-11): кнопкой, а не словами «где-то в настройках».
                s == null -> Column(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                    Text(if (view.hasToken) "Среза ещё нет." else "Нужен токен GitHub — только для чтения.")
                    if (view.hasToken) {
                        Button(onClick = onSync, enabled = !view.busy) { Text("Обновить") }
                    } else {
                        Button(onClick = { tab = Section.MORE }) { Text("Ввести токен") }
                    }
                }
                tab == Section.SUMMARY -> Summary(
                    remember(s, view.zone) { Screens.summary(s) }, view, onSync, onAi,
                    onTile = { g ->
                        when (g) {
                            Screens.Goes.PROJECTS -> { tab = Section.DEV; devTab = DevTab.PROJECTS }
                            Screens.Goes.QUESTIONS -> { tab = Section.DEV; devTab = DevTab.QUESTIONS }
                            Screens.Goes.SYNC -> tab = Section.MORE
                        }
                    },
                    onProject = { open(Nav.Card(it)) },
                ) { open(Nav.Feed) }
                tab == Section.STATS -> StatsScreen(view.history, s.projects.map { it.name }, null, s.asOf, null, view.status, onExport)
                tab == Section.DEV -> Column(Modifier.fillMaxSize()) {
                    DevTabs(devTab) { devTab = it }
                    when (devTab) {
                        DevTab.PROJECTS -> Projects(remember(s) { Screens.projects(s) }) { open(Nav.Card(it)) }
                        DevTab.QUESTIONS -> Questions(remember(s) { Screens.questions(s) }, open)
                    }
                }
            }
        }
        }
    }
    view.clonePrompt?.let { ClonesDialog(it, onClonesChosen, onDeleteClone, busy = view.busy) }
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
        // Сверху — строка состояния, сбоку — вырез в альбомной ориентации (UX-24).
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
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
            if (view.busy && (view.cancelling || view.progress?.startsWith("Клоны") == true)) {
                TextButton(onClick = onCancel, enabled = !view.cancelling) { Text(if (view.cancelling) "Отменяю…" else "Отменить") }
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
    onProject: (String) -> Unit,
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
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "карточка проекта") { onProject(r.project) }.padding(vertical = 4.dp)) {
                Text("${r.time} · ${r.project} · ${r.kind}", style = MaterialTheme.typography.bodySmall)
                Text(r.text, maxLines = 2)
            }
        }
        TextButton(onClick = onFeed) { Text("Вся лента ▸") }
    }
}

/** Сколько последних событий на сводке; остальные — «Вся лента ▸». */
private const val SUMMARY_EVENTS = 3

/**
 * Список проектов (UX-18): место — заголовком, строка — имя и состояние
 * словом и цветом, ниже — ROADMAP, PR, TODO без «неизвестно» трижды;
 * ошибки источников — «⚠ N» цветом ошибки; решения — в карточке.
 */
@Composable
private fun Projects(groups: List<Screens.Group>, selected: String? = null, onOpen: (String) -> Unit) = LazyColumn(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
    for (g in groups) {
        item { Text(g.title, style = MaterialTheme.typography.titleMedium) }
        items(g.rows, key = { "row:${g.title}:${it.name}" }) { r ->
            // Открытый в соседней панели (UX-24) — выделен цветом и для TalkBack.
            val chosen = r.name == selected
            Card(
                Modifier.fillMaxWidth().semantics { this.selected = chosen }.clickable(onClickLabel = "открыть карточку") { onOpen(r.name) },
                colors = if (chosen) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
            ) {
                Column(Modifier.padding(Tokens.gap)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (r.errors > 0) Text("⚠ ${r.errors}", color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = "ошибок источников: ${r.errors}" })
                    }
                    Text(
                        r.working,
                        color = if (r.working == "работает") Color(com.dpsmpx.dpai.ui.AppPalette.semantic(com.dpsmpx.dpai.ui.AppPalette.Role.DONE, isSystemInDarkTheme())) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(Screens.projectLine(r), style = MaterialTheme.typography.bodySmall)
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
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
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
                } else if (sec.kind == Screens.CardSection.Kind.PULLS) {
                    // PR — касанием к его экрану с действиями (F-12); строки проверок — как прежде.
                    items(c.pulls) { pr ->
                        Text(
                            "  #${pr.number} ${pr.title}" + (if (pr.draft) " (черновик)" else "") + " — ${pr.head}, ${pr.updated} ▸",
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "PR и действия") { open(Nav.Pull(c.name, pr.number.toInt())) }.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(sec.lines.drop(c.pulls.size)) { Text("  $it", style = MaterialTheme.typography.bodySmall) }
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

/**
 * Лента (UX-16): «Вид ▾» и «Проект ▾» в одном ряду, выбранное видно; без
 * совпадений — «Нет событий по фильтру · Сбросить»; вид — меткой; касание
 * события — карточка проекта.
 */
@Composable
private fun FeedScreen(events: List<FeedEvent>, projects: List<String>, onBack: () -> Unit, onProject: (String) -> Unit) {
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = Screens.feed(events, project, kind)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
        Text("Лента", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("Вид", kind?.let { Feed.KINDS[it] ?: it } ?: "все", listOf<Pair<String?, String>>(null to "все виды") + Feed.KINDS.map { (c, t) -> c to t }) { kind = it }
            Choice("Проект", project ?: "все", listOf<Pair<String?, String>>(null to "все проекты") + projects.map { it to it }) { project = it }
        }
        if (rows.isEmpty()) {
            if (project != null || kind != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Нет событий по фильтру", Modifier.weight(1f))
                    TextButton(onClick = { project = null; kind = null }) { Text("Сбросить") }
                }
            } else {
                Text("Событий нет: они появляются со второй синхронизации.")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val keys = contentKeys(rows) { "${it.time}|${it.project}|${it.kind}|${it.text.hashCode()}" }
            items(rows.size, key = { "feed:${keys[it]}" }) { i ->
                val r = rows[i]
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "карточка проекта") { onProject(r.project) }.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${r.time} · ${r.project}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(r.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(r.text)
                }
            }
        }
    }
}

/** Выбор из списка (UX-14, UX-16): «Подпись: выбранное ▾», меню — по касанию; выбранное видно без меню. */
@Composable
internal fun <T> Choice(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.OutlinedButton(onClick = { open = true }) { Text("$label: $current ▾", maxLines = 1) }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, title) in options) androidx.compose.material3.DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onPick(value) })
        }
    }
}

@Composable
private fun DirScreen(nav: Nav.Dir, loader: Loader, onBack: () -> Unit, open: (Nav) -> Unit) {
    var result by remember(nav) { mutableStateOf<Outcome<List<GitHub.Entry>>?>(null) }
    LaunchedEffect(nav) { loader.directory(nav.project, nav.path) { result = it } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") } }
        item { Text("${nav.project}: /${nav.path}", style = MaterialTheme.typography.titleMedium) }
        when (val r = result) {
            null -> item { Text("Загрузка…") }
            is Outcome.Failure -> item { FailureText("Не загрузилось", Screens.failure(r.diagnostics.first())) }
            is Outcome.Success -> items(r.value, key = { "entry:${it.name}" }) { e ->
                // Строка — 48 dp, папка — словом для TalkBack, а не названием знака (UX-15).
                Text(
                    (if (e.dir) "📁 " else "") + e.name,
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(onClickLabel = if (e.dir) "открыть папку" else "открыть файл") { open(if (e.dir) Nav.Dir(nav.project, e.path) else Nav.Doc(nav.project, e.path, null)) }
                        .semantics { contentDescription = (if (e.dir) "папка " else "файл ") + e.name }
                        .padding(vertical = 12.dp),
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
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
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
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, act.url.toUri()))
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
        val keys = contentKeys(items) { "${it.title}|${it.label}" }
        items(items.size, key = { "q:$project:${keys[it]}" }) { n ->
            val i = items[n]
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
// Листается (UX-15): при шрифте 200 % и в альбомной ориентации «Клоны…» не уходят за край.
private fun Settings(view: PultView, onSync: () -> Unit, onSaveToken: (String) -> Unit, onForget: () -> Unit, onClones: () -> Unit, onZone: (Int) -> Unit = {}, onJournal: () -> Unit = {}) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
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
        Text("Fine-grained PAT: Contents, Pull requests, Metadata. Для чтения хватит «Read-only»; для действий с PR (комментарий, ревью, «Влить», «Закрыть») — Pull requests и Contents «Read and write».")
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
    Text("Действия", style = MaterialTheme.typography.titleMedium)
    Text("Всё, что пульт сделал в репозиториях, и отказы — в журнале.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onJournal) { Text("Журнал действий ▸") }
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
    // Пояс показа — один на приложение (ТЗ, §6.3; UX-22): время пульта и «AI» — в нём.
    Text("Время", style = MaterialTheme.typography.titleMedium)
    var zones by remember { mutableStateOf(false) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Часовой пояс показа", Modifier.weight(1f))
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { zones = true }) { Text(Screens.zoneName(view.zone) + " ▾") }
            androidx.compose.material3.DropdownMenu(expanded = zones, onDismissRequest = { zones = false }) {
                for (h in -12..14) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(Screens.zoneName(h * 60)) }, onClick = {
                        zones = false
                        onZone(h * 60)
                    })
                }
            }
        }
    }
    // «О программе» (UX-23, D-54): версия приложения и подсистемы «AI».
    if (view.about.isNotEmpty()) {
        Text("О программе", style = MaterialTheme.typography.titleMedium)
        Text(view.about, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Выбор клонов (D-70, UX-11): полноэкранный лист — поиск, «Все» и «Ни
 * одного», строка — флажок (роль для TalkBack, цель 48 dp) с пометкой
 * «склонирован · N МБ»; реестр — сверху. Закрывается только кнопками и
 * жестом «назад», касание мимо — не «Позже». Снятая отметка клон не
 * удаляет: в нём может быть работа ИИ; удалить — отдельно, с вопросом.
 */
@Composable
fun ClonesDialog(prompt: ClonePrompt, onDone: (Set<String>?) -> Unit, onDelete: (String) -> Unit = {}, busy: Boolean = false) {
    var checked by remember(prompt.first, prompt.checked) { mutableStateOf(prompt.checked) }
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    val shown = prompt.options.filter { query.isBlank() || it.slug.contains(query.trim(), ignoreCase = true) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onDone(null) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(Tokens.gap), verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                Text("Какие репозитории клонировать?", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Клон — полная копия в приложении: «AI» работает в нём, пульт берёт из него файлы без запросов. " +
                        "Первое клонирование — долгое, лучше по Wi-Fi. Неотмеченные пульт читает с GitHub. Выбор меняется в пункте «Ещё».",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(query, { query = it }, label = { Text("Найти репозиторий") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                prompt.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                    TextButton(onClick = { checked = checked + shown.filter { !it.gone }.map { it.slug } }) { Text("Все") }
                    TextButton(onClick = { checked = checked - shown.map { it.slug }.toSet() }) { Text("Ни одного") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.slug }) { o ->
                        val on = o.slug in checked
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = on, role = Role.Checkbox) { checked = if (on) checked - o.slug else checked + o.slug },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(o.slug, style = MaterialTheme.typography.bodyMedium)
                                val marks = listOfNotNull(
                                    "реестр".takeIf { o.registry },
                                    "закрытый".takeIf { o.private },
                                    o.bytes?.let { "склонирован · " + if (it < 1_048_576) "меньше 1 МБ" else "${it / 1_048_576} МБ" },
                                )
                                Text(
                                    if (o.gone) "нет в списке аккаунта — снимите отметку, если не нужен" else marks.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            // Удалить — только не отмеченный клон и не при первом входе; с вопросом (D-70: в клоне может быть работа ИИ).
                            // Не во время синхронизации: клон может обновляться прямо сейчас.
                            if (!prompt.first && !on && o.bytes != null) TextButton(enabled = !busy, onClick = { deleting = o.slug }) { Text("Удалить…", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                    TextButton(onClick = { onDone(null) }) { Text(if (prompt.first) "Позже" else "Отмена") }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Button(onClick = { onDone(checked) }) { Text("Клонировать отмеченные (${checked.size})") }
                }
            }
        }
    }
    deleting?.let { slug ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить клон $slug?") },
            text = { Text("Место на телефоне освободится. Работа ИИ в этом клоне — ветки и незакоммиченное, чего нет на GitHub, — пропадёт. Отметить и склонировать снова можно потом.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(slug) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
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

/**
 * PR из карточки (F-12, F-13): свежий PR с GitHub и действия с уровнями (D-07, D-36).
 * Комментарий, ревью и «Из черновика» — окно «что уйдёт» и «Отправить» (уровень 1);
 * «Влить» и «Закрыть» — окно «что изменится», затем PIN или отпечаток (уровень 2). Если
 * «Влить» не чист (F-13: проверки описания не зелёные, GitHub видит препятствие) — причины
 * и отметка «понимаю»; черновик и конфликт — кнопка недоступна. Итог — строкой и в журнале.
 */
@Composable
private fun PullScreen(project: String, number: Int, checks: String?, loader: Loader, onBack: () -> Unit) {
    var loaded by remember(project, number) { mutableStateOf<Outcome<Pair<com.dpsmpx.dpmeta.model.RepoRef, PullDetail>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(project, number, reload) {
        loaded = null
        loader.pull(project, number) { loaded = it }
    }
    var text by rememberSaveable(project, number) { mutableStateOf("") }
    var pending by remember { mutableStateOf<PullActions.Request?>(null) }
    var accept by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val context = LocalContext.current
    val taps = remember { LinkTaps() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
        Text("$project · PR #$number", style = MaterialTheme.typography.titleMedium)
        when (val l = loaded) {
            null -> Text("Читаю PR с GitHub…", style = MaterialTheme.typography.bodySmall)
            is Outcome.Failure -> {
                Text("PR не прочитан: ${l.errors.first().message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reload++ }) { Text("Повторить") }
            }
            is Outcome.Success -> {
                val (repo, p) = l.value
                Text(p.title, fontWeight = FontWeight.SemiBold)
                for (line in PullActions.lines(p, checks)) Text(line, style = MaterialTheme.typography.bodySmall)
                if (p.url.isNotEmpty()) TextButton(onClick = { external(context, taps, p.url) }) { Text("Ссылка на GitHub") }
                if (PullActions.open(p)) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, label = { Text("Комментарий или текст ревью") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !running,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { pending = PullActions.Request(PullActions.comment(repo, p, text.trim()), PullActions.Way.SHOWN) }, enabled = text.isNotBlank() && !running) { Text("Комментарий…") }
                        OutlinedButton(onClick = { pending = PullActions.Request(PullActions.review(repo, p, true, text.trim()), PullActions.Way.SHOWN) }, enabled = !running) { Text("Одобрить…") }
                        OutlinedButton(onClick = { pending = PullActions.Request(PullActions.review(repo, p, false, text.trim()), PullActions.Way.SHOWN) }, enabled = text.isNotBlank() && !running) { Text("Запросить изменения…") }
                        if (p.draft) OutlinedButton(onClick = { pending = PullActions.Request(PullActions.ready(repo, p), PullActions.Way.SHOWN) }, enabled = !running) { Text("Из черновика…") }
                    }
                    val blocked = PullActions.mergeBlocked(p)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { accept = false; pending = PullActions.Request(PullActions.merge(repo, p), PullActions.Way.DEVICE) }, enabled = blocked == null && !running) { Text("Влить…") }
                        OutlinedButton(onClick = { pending = PullActions.Request(PullActions.close(repo, p), PullActions.Way.DEVICE) }, enabled = !running) {
                            Text("Закрыть…", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    blocked?.let { Text("Влить нельзя: $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                result?.let { (ok, line) -> Text(line, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                pending?.let { req ->
                    val warnings = if (req.command.kind == ActionKind.PR_MERGE) PullActions.mergeWarnings(p, checks) else emptyList()
                    AlertDialog(
                        onDismissRequest = { pending = null },
                        title = { Text(if (req.way == PullActions.Way.SHOWN) "Что уйдёт" else "Что изменится") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (line in PullActions.preview(req.command, p)) Text(line)
                                if (warnings.isNotEmpty()) {
                                    Text("Внимание:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                                    for (w in warnings) Text("• $w", color = MaterialTheme.colorScheme.error)
                                    Row(
                                        Modifier.fillMaxWidth().toggleable(value = accept, role = Role.Checkbox) { accept = it },
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = accept, onCheckedChange = null)
                                        Text("Понимаю, влить всё равно")
                                    }
                                }
                                if (req.way == PullActions.Way.DEVICE) Text("Дальше — PIN или отпечаток.", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = warnings.isEmpty() || accept,
                                onClick = {
                                    pending = null
                                    running = true
                                    result = null
                                    loader.act(req) { r ->
                                        running = false
                                        result = when (r) {
                                            is Outcome.Success -> true to ("Готово: ${r.value.detail}" + (r.value.link?.let { " — $it" } ?: ""))
                                            is Outcome.Failure -> false to "Не выполнено: ${r.errors.first().message}"
                                        }
                                        if (r is Outcome.Success) {
                                            text = ""
                                            reload++
                                        }
                                    }
                                },
                            ) { Text(if (req.way == PullActions.Way.SHOWN) "Отправить" else "Дальше — PIN") }
                        },
                        dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } },
                    )
                }
            }
        }
    }
}

/**
 * Журнал действий (F-44): новые сверху; что, где, когда, как подтверждено и чем
 * кончилось; поиск по всем полям; «Поделиться» — текстом, найденное.
 */
@Composable
private fun JournalScreen(loader: Loader, onBack: () -> Unit) {
    var entries by remember { mutableStateOf<Outcome<List<ActionEntry>>?>(null) }
    LaunchedEffect(Unit) { loader.journal { entries = it } }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val taps = remember { LinkTaps() }
    val rows = (entries as? Outcome.Success)?.value?.filter { it.matches(query) }?.asReversed().orEmpty()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { loader.share("Журнал действий DPmetaApp", rows.joinToString("\n") { journalLine(it) }) }, enabled = rows.isNotEmpty()) { Text("Поделиться") }
            }
        }
        item { Text("Журнал действий", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        when (val e = entries) {
            null -> item { Text("Читаю журнал…", style = MaterialTheme.typography.bodySmall) }
            is Outcome.Failure -> item { Text("Журнал не прочитан: ${e.errors.first().message}", color = MaterialTheme.colorScheme.error) }
            is Outcome.Success -> if (rows.isEmpty()) {
                item { Text(if (e.value.isEmpty()) "Действий из пульта ещё не было." else "Нет записей по поиску.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(rows, key = { it.id }) { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${Screens.time(r.at)} · уровень ${r.level} · ${r.target}", style = MaterialTheme.typography.bodySmall)
                        Text(ActionKind.of(r.kind)?.what ?: r.kind, fontWeight = FontWeight.SemiBold)
                        val bad = r.status == ActionStatus.FAILED || r.status == ActionStatus.REFUSED || r.status == ActionStatus.UNKNOWN
                        Text(r.status.word + (r.detail.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""), color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        r.link?.let { url -> Text(url, Modifier.clickable(onClickLabel = "ссылка") { external(context, taps, url) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

/** Строка журнала для «Поделиться»: время, уровень, действие, цель, итог, ссылка. */
private fun journalLine(r: ActionEntry): String =
    listOf(Screens.time(r.at), "ур. ${r.level}", r.kind, r.target, r.status.word, r.detail, r.link.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")

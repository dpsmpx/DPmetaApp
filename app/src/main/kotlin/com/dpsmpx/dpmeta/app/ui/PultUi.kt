package com.dpsmpx.dpmeta.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.R
import com.dpsmpx.dpmeta.actions.ActionEntry
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionResult
import com.dpsmpx.dpmeta.actions.ActionStatus
import com.dpsmpx.dpmeta.app.screens.PullActions
import com.dpsmpx.dpmeta.app.screens.BranchActions
import com.dpsmpx.dpmeta.app.screens.IssueActions
import com.dpsmpx.dpmeta.app.screens.EditActions
import com.dpsmpx.dpmeta.app.screens.LineDiff
import com.dpsmpx.dpmeta.app.screens.RouteActions
import com.dpsmpx.dpmeta.app.screens.AskActions
import com.dpsmpx.dpmeta.app.data.AskSession
import com.dpsmpx.dpmeta.app.data.CommandSession
import com.dpsmpx.dpmeta.app.screens.CommandActions
import com.dpsmpx.dpmeta.formats.CommandSpec
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
 * Тема пульта — та же, что у «AI» (D-115; D-74; UX-5): нейтральные цвета прототипа и
 * акцент из «Оформления» (файл `UiLook.PREFS`, общий с «AI»); «Обои телефона» —
 * цвета обоев (Android 12+). Тема, размер текста и плотность — оттуда же.
 */
@Composable
fun PultTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences(com.dpsmpx.dpai.ui.UiLook.PREFS, android.content.Context.MODE_PRIVATE) }
    var look by remember { mutableStateOf(com.dpsmpx.dpai.ui.UiLook.fromMap(prefs.all)) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        // Слушатель держится ссылкой: SharedPreferences хранит слабую.
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> look = com.dpsmpx.dpai.ui.UiLook.fromMap(p.all) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = when (look.theme) {
        com.dpsmpx.dpai.ui.UiLook.Theme.SYSTEM -> isSystemInDarkTheme()
        com.dpsmpx.dpai.ui.UiLook.Theme.LIGHT -> false
        com.dpsmpx.dpai.ui.UiLook.Theme.DARK -> true
    }
    val scheme = if (look.accent == com.dpsmpx.dpai.ui.UiLook.Accent.WALLPAPER && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
    } else {
        schemeOf(com.dpsmpx.dpai.ui.UiLook.scheme(look.accent, dark), dark)
    }
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            // Значки строки состояния — под выбранную тему, а не под тему телефона.
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val d = androidx.compose.ui.platform.LocalDensity.current
    // Плотность сжимает dp, размер текста — только sp (как у «AI»).
    val density = androidx.compose.ui.unit.Density(d.density * look.density.factor, d.fontScale * look.text.factor / look.density.factor)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Схема Material 3 из ролей `AppPalette.Scheme`. Копия `schemeOf` оболочки «AI»
 * (`dpai/android/…/Look.kt`, пульт от неё не зависит): новая роль — в обе.
 */
private fun schemeOf(p: com.dpsmpx.dpai.ui.AppPalette.Scheme, dark: Boolean): ColorScheme {
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

    /** Карточка PR (F-11): коммиты, файлы, обсуждение — по кнопке, свежие с GitHub. */
    fun pullCard(project: String, number: Int, done: (Outcome<PullActions.Card>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "карточки PR здесь нет", project))

    /** Действие (F-12): подтверждение способом [PullActions.Request.way] создаёт активность — после PIN, если нужен. */
    fun act(request: PullActions.Request, done: (Outcome<ActionResult>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "действий здесь нет", request.command.target.display))

    /** Репозиторий проекта — для действий над ним (issue, F-26). */
    fun repo(project: String, done: (Outcome<com.dpsmpx.dpmeta.model.RepoRef>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "действий здесь нет", project))

    /** Файл к правке (F-69): с основной ветки, с блобом — свежий. */
    fun editable(project: String, path: String, done: (Outcome<EditActions.Edit>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "правки здесь нет", project))

    /** Ветка перед действием (F-14): основная, голова, сравнение — свежие. */
    fun branch(project: String, name: String, done: (Outcome<BranchActions.View>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "действий с ветками здесь нет", project))

    /** Журнал действий (F-44). */
    fun journal(done: (Outcome<List<ActionEntry>>) -> Unit) = done(Outcome.success(emptyList()))

    /** «Спросить Claude» (F-21): профили Anthropic из «AI»; без оболочки — пусто. */
    fun askProfiles(done: (List<AskActions.Profile>) -> Unit) = done(emptyList())

    /** Модели профиля — из Models API. */
    fun askModels(profile: String, done: (Outcome<List<AskActions.Model>>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "моделей здесь нет", profile))

    /** Открыть историю разговора (читается один раз на процесс). */
    fun askOpen() {}

    /** Вопрос: срез для инструментов берётся на момент отправки; [file] — приложенный автором файл. */
    fun askSend(profile: AskActions.Profile, model: String, effort: String?, question: String, file: String?, fileText: String?) {}

    /** Маршруты Claude Code (F-22) — из подсистемы «AI» через оболочку; без оболочки — пусто. */
    fun routes(done: (List<RouteActions.Route>) -> Unit) = done(emptyList())

    /** Шаблоны поручений (F-23) — `config/briefs/<ключ>.md` в APK; отказ разбора — строкой. */
    fun briefs(done: (Outcome<List<com.dpsmpx.dpmeta.formats.Brief>>) -> Unit) = done(Outcome.success(emptyList()))

    /** Репозиторий проекта и основная ветка — места шаблона (F-23). */
    fun base(project: String, done: (Outcome<Pair<com.dpsmpx.dpmeta.model.RepoRef, String>>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "поручений здесь нет", project))

    /** Реестр команд (F-40; D-82): команды, реестр проектов, проекты с клоном; без оболочки — отказ. */
    fun commands(done: (Outcome<CommandActions.Catalog>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "команд здесь нет", "commands"))

    /** Готовность команды на месте: шаги, рабочее дерево клона. */
    fun commandCheck(spec: CommandSpec, registry: com.dpsmpx.dpmeta.model.Registry, project: String?, done: (Outcome<CommandActions.Checked>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "команд здесь нет", spec.id))

    /** Поставить пакеты Alpine в Linux-среду. */
    fun commandInstall(packages: List<String>, done: (Outcome<List<String>>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "Linux-среды здесь нет", "apk"))

    /** Запуск: итог и вывод — в [CommandSession]; уровень 1 — уже после окна «что изменится». */
    fun commandStart(spec: CommandSpec, checked: CommandActions.Checked) {}

    /** Папка проектов Termux (D-83, D-84): сохранённая или по умолчанию. */
    fun termuxFolder(): String = com.dpsmpx.dpmeta.app.screens.TermuxActions.DEFAULT_FOLDER

    /** Сохранить папку проектов Termux из ввода: итог — путь, как он сохранён, или что не так. */
    fun setTermuxFolder(input: String): Outcome<String> =
        Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "настроек Termux здесь нет", "termux")

    /** Готовность команды в Termux по шагам (F-42): с пробой папки, клона и программы. */
    fun termuxCheck(spec: CommandSpec, registry: com.dpsmpx.dpmeta.model.Registry, project: String?, done: (Outcome<CommandActions.Checked>) -> Unit) =
        done(Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "Termux здесь нет", spec.id))

    /** Попросить разрешение «Запуск команд в Termux»; [done] — когда человек ответил. */
    fun termuxPermit(done: () -> Unit) = done()

    /** Страница приложения в настройках Android — там «Другие разрешения», если окна запроса нет. */
    fun appSettings() {}

    /** Поделиться текстом (экспорт журнала, F-44). */
    fun share(subject: String, text: String) {}
}

/** Значки пунктов левой панели (D-73, D-112): свои векторные рисунки. */
private val Screens.Entry.icon: Int get() = when (this) {
    Screens.Entry.SUMMARY -> R.drawable.ic_pult_summary
    Screens.Entry.PROJECTS -> R.drawable.ic_pult_dev
    Screens.Entry.IDE -> R.drawable.ic_pult_ide
    Screens.Entry.AI -> R.drawable.ic_pult_ai
    Screens.Entry.SETTINGS -> R.drawable.ic_pult_more
    Screens.Entry.HELP -> R.drawable.ic_pult_help
}

/** Ширина левой панели (D-112): на планшете — не во весь экран. */
private val DRAWER_WIDTH = 320.dp

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

    /** Вся лента событий (F-05) — со сводки (D-73). */
    data object Feed : Nav

    /** PR проекта с действиями (F-12) — из карточки. */
    data class Pull(val project: String, val number: Int) : Nav

    /** Журнал действий (F-44) — из «Ещё». */
    data object Journal : Nav

    /** Ветка проекта с действиями (F-14) — из карточки. */
    data class Branch(val project: String, val name: String) : Nav

    /** Правка файла проекта (F-69) — из документа. */
    data class Edit(val project: String, val path: String) : Nav

    /**
     * Поручение Claude маршрутом (F-22, F-23) — из карточки; ответ на вопрос (F-25) — из
     * «Вопросов»: [brief] — шаблон, [source] и [question] — его поля.
     */
    data class Brief(val project: String, val brief: String?, val source: String?, val question: String?) : Nav

    /** «Спросить Claude» (F-21) — из «Ещё»; из документа — с приложенным файлом [project]:[path]. */
    data class Ask(val project: String?, val path: String?) : Nav

    /** Команды проектов (F-40; D-82) — из «Ещё». */
    data object Commands : Nav

    companion object {
        private const val SEP = "\u0001"

        /** Строкой — для сохранения места при смерти процесса (UX-3); поля разделены непечатным знаком. */
        fun encode(n: Nav): String = when (n) {
            is Card -> listOf("card", n.project)
            is Dir -> listOf("dir", n.project, n.path)
            is Doc -> listOf("doc", n.project, n.path, n.anchor.orEmpty())
            Feed -> listOf("feed")
            is Pull -> listOf("pull", n.project, n.number.toString())
            Journal -> listOf("journal")
            is Branch -> listOf("branch", n.project, n.name)
            is Edit -> listOf("edit", n.project, n.path)
            is Brief -> listOf("brief", n.project, n.brief.orEmpty(), n.source.orEmpty(), n.question.orEmpty())
            is Ask -> listOf("ask", n.project.orEmpty(), n.path.orEmpty())
            Commands -> listOf("commands")
        }.joinToString(SEP)

        fun decode(s: String): Nav? {
            val p = s.split(SEP)
            return when (p.firstOrNull()) {
                "card" -> p.getOrNull(1)?.let(::Card)
                "dir" -> if (p.size >= 3) Dir(p[1], p[2]) else null
                "doc" -> if (p.size >= 4) Doc(p[1], p[2], p[3].ifEmpty { null }) else null
                "feed" -> Feed
                "pull" -> p.getOrNull(2)?.toIntOrNull()?.let { Pull(p[1], it) }
                "journal" -> Journal
                "branch" -> if (p.size >= 3) Branch(p[1], p[2]) else null
                "edit" -> if (p.size >= 3) Edit(p[1], p[2]) else null
                "brief" -> if (p.size >= 5) Brief(p[1], p[2].ifEmpty { null }, p[3].ifEmpty { null }, p[4].ifEmpty { null }) else null
                "ask" -> if (p.size >= 3) Ask(p[1].ifEmpty { null }, p[2].ifEmpty { null }) else null
                "commands" -> Commands
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
 * [onAi] — пункт «AI» левой панели (D-53, D-112): открывает окно подсистемы
 * (`true` — на «Запросах», D-76); [onIde] — пункт «IDE» (окно «AI» на «Файлах»);
 * `null` — пункта нет.
 */
@Composable
fun PultApp(
    view: PultView,
    loader: Loader,
    onSync: () -> Unit,
    onSaveToken: (String) -> Unit,
    onForgetToken: () -> Unit,
    onAi: ((requests: Boolean) -> Unit)? = null,
    onIde: (() -> Unit)? = null,
    /**
     * Документ настроек приложения (D-112) — от оболочки: в нём блок пульта — слотом.
     * `null` — настройки пульта своим экраном (сборка без оболочки).
     */
    settingsDocument: (@Composable (query: String, projects: @Composable () -> Unit) -> Unit)? = null,
    /** Раздел с левой панели окна «AI» (D-112): перейти и сбросить [onGoTo]. */
    goTo: com.dpsmpx.dpai.ui.AppSection? = null,
    onGoTo: () -> Unit = {},
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
    var pullFilter by rememberSaveable { mutableStateOf(Screens.PullFilter.WAITING) }
    var pullProject by rememberSaveable { mutableStateOf<String?>(null) }
    val stacks = rememberSaveable(saver = StacksSaver) { mutableStateMapOf() }
    // Поиск документа настроек по ссылке из справки («Настройки → тема»); пункт панели открывает его пустым.
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(goTo) {
        val to = goTo ?: return@LaunchedEffect
        tab = when (to) {
            com.dpsmpx.dpai.ui.AppSection.SUMMARY -> Section.SUMMARY
            com.dpsmpx.dpai.ui.AppSection.PROJECTS -> Section.DEV
            com.dpsmpx.dpai.ui.AppSection.SETTINGS -> Section.MORE
            com.dpsmpx.dpai.ui.AppSection.HELP -> Section.HELP
        }
        onGoTo()
    }
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
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Левая выдвижная панель (D-112): разделы пульта и входы в окно «AI»; «назад» сначала закрывает её.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(Modifier.widthIn(max = DRAWER_WIDTH)) {
                Text("DPmetaApp", Modifier.padding(horizontal = 28.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
                // Порядок — Screens.panelItems: тот же, что сверяет тест панели.
                for (e in Screens.panelItems(ai = onAi != null)) {
                    val section = e.section
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(e.title) },
                        icon = { Icon(painterResource(e.icon), contentDescription = null) },
                        selected = section != null && tab == section,
                        onClick = {
                            scope.launch { drawer.close() }
                            when {
                                // Касание открытого — к его началу, другого — туда, где оставили.
                                section != null -> {
                                    if (section == Section.MORE) settingsQuery = ""
                                    if (tab == section) stacks[section] = emptyList() else tab = section
                                }
                                e == Screens.Entry.IDE -> onIde?.invoke()
                                else -> onAi?.invoke(false)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            // Вкладки раздела — в верхней панели вместо заголовка (D-113): места под список больше.
            val tabs: (@Composable () -> Unit)? = if (tab == Section.DEV) { { DevTabs(devTab) { devTab = it } } } else null
            Header(tab.title, view, onSync, onCancelSync, onMenu = { scope.launch { drawer.open() } }, tabs = tabs)
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
                top is Nav.Feed && s != null -> FeedScreen(view.feed, s.projects.map { it.name }, back) { open(Nav.Card(it)) }
                top is Nav.Pull -> {
                    val checks = remember(s, top) { s?.let { Screens.card(it, top.project) }?.pulls?.firstOrNull { it.number == top.number.toLong() }?.checks }
                    PullScreen(top.project, top.number, checks, loader, back)
                }
                top is Nav.Journal -> JournalScreen(loader, back)
                top is Nav.Edit -> EditScreen(top.project, top.path, loader, back)
                top is Nav.Brief -> BriefScreen(top, loader, back)
                top is Nav.Ask -> AskScreen(top, s != null, loader, back)
                top is Nav.Commands -> CommandsScreen(loader, back)
                top is Nav.Branch -> {
                    val pulls = remember(s, top) { s?.let { Screens.card(it, top.project) }?.pulls?.filter { it.head == top.name }?.map { it.number }.orEmpty() }
                    BranchScreen(top.project, top.name, pulls, loader, back)
                }
                tab == Section.MORE -> {
                    val doc = settingsDocument
                    if (doc != null) {
                        // Один документ на приложение (D-112): блок пульта — без своей прокрутки, внутри документа.
                        doc(settingsQuery) { Settings(view, onSync, onSaveToken, onForgetToken, onClones, onZone, onJournal = { open(Nav.Journal) }, onAsk = { open(Nav.Ask(null, null)) }, onCommands = { open(Nav.Commands) }, onExport = onExport, scroll = false) }
                    } else {
                        Settings(view, onSync, onSaveToken, onForgetToken, onClones, onZone, onJournal = { open(Nav.Journal) }, onAsk = { open(Nav.Ask(null, null)) }, onCommands = { open(Nav.Commands) }, onExport = onExport)
                    }
                }
                // Справка — и без токена и среза (D-112): с неё начинают.
                tab == Section.HELP -> HelpScreen { url ->
                    // Ссылки справки внутрь приложения: app:summary, app:projects, app:ide, app:ai, app:settings?q=…
                    when (HelpLinks.section(url)) {
                        HelpLinks.SUMMARY -> tab = Section.SUMMARY
                        HelpLinks.PROJECTS -> { tab = Section.DEV; devTab = DevTab.PROJECTS }
                        HelpLinks.IDE -> onIde?.invoke()
                        HelpLinks.AI -> onAi?.invoke(false)
                        HelpLinks.SETTINGS -> { settingsQuery = HelpLinks.query(url); tab = Section.MORE }
                    }
                }
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
                            // Плитка считает все открытые PR (`repo.pr.open`) — и список открывается на «Все».
                            Screens.Goes.PULLS -> { tab = Section.DEV; devTab = DevTab.PULLS; pullFilter = Screens.PullFilter.WAITING; pullProject = null }
                            Screens.Goes.QUESTIONS -> { tab = Section.DEV; devTab = DevTab.QUESTIONS }
                            Screens.Goes.SYNC -> tab = Section.MORE
                        }
                    },
                    onProject = { open(Nav.Card(it)) },
                ) { open(Nav.Feed) }
                tab == Section.DEV -> Column(Modifier.fillMaxSize()) {
                    when (devTab) {
                        DevTab.PROJECTS -> Projects(remember(s) { Screens.projects(s) }) { open(Nav.Card(it)) }
                        DevTab.PULLS -> Pulls(remember(s) { Screens.pulls(s) }, s.asOf, pullFilter, pullProject, { pullFilter = it }, { pullProject = it }) { r -> open(Nav.Pull(r.project, r.number.toInt())) }
                        DevTab.QUESTIONS -> Questions(remember(s) { Screens.questions(s) }, remember(s) { Screens.pulls(s) }, loader, open)
                    }
                }
            }
        }
        }
    }
    }
    view.clonePrompt?.let { ClonesDialog(it, onClonesChosen, onDeleteClone, busy = view.busy) }
}

/**
 * Заголовок каждого экрана пульта (D-73; ТЗ, §9): пункт, время среза или
 * «обновляется»; во время синхронизации — тонкая полоса хода под ним.
 */
@Composable
private fun Header(title: String, view: PultView, onSync: () -> Unit, onCancel: () -> Unit, onMenu: () -> Unit, tabs: (@Composable () -> Unit)? = null) {
    Surface(tonalElevation = 3.dp) {
        // Сверху — строка состояния, сбоку — вырез в альбомной ориентации (UX-24).
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(painterResource(R.drawable.ic_pult_menu), contentDescription = "Разделы") }
            // Вкладки раздела — вместо заголовка (D-113); время среза — у заголовка других разделов.
            if (tabs != null) androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { tabs() } else Column(Modifier.weight(1f).padding(horizontal = Tokens.gap, vertical = 6.dp)) {
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
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = androidx.compose.ui.graphics.Color.Transparent, divider = {}) {
        for (t in DevTab.entries) Tab(selected = t == selected, onClick = { onSelect(t) }, text = { Text(t.title, maxLines = 1) })
    }
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
private fun Projects(groups: List<Screens.Group>, selected: String? = null, onOpen: (String) -> Unit) {
    var map by rememberSaveable { mutableStateOf(true) }
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (map) com.dpsmpx.dpmeta.app.screens.ProjectMap.groups(groups, all) else groups
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = map, onClick = { map = true }, label = { Text("Карта") })
                FilterChip(selected = !map, onClick = { map = false }, label = { Text("Список") })
                if (map) FilterChip(selected = all, onClick = { all = !all }, label = { Text("С периферией") })
            }
            if (map) Text("Основные проекты реестра DPmeta. Любой другой репозиторий можно открыть в IDE; клонирование не добавляет его на карту.", style = MaterialTheme.typography.bodySmall)
        }
        for (g in shown) {
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
                        if (map) Text(com.dpsmpx.dpmeta.app.screens.ProjectMap.role(r.name), style = MaterialTheme.typography.bodySmall)
                    }
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
    var issueOpen by rememberSaveable(c.name) { mutableStateOf(false) }
    val todoPath = c.files.firstOrNull { it.first == "TODO" }?.second
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
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
                AssistChip(onClick = { issueOpen = true }, label = { Text("Новый issue…") })
                AssistChip(onClick = { open(Nav.Brief(c.name, null, null, null)) }, label = { Text("Поручить Claude…") })
            }
            if (issueOpen) IssueDialog(c.name, loader) { issueOpen = false }
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
                } else if (sec.kind == Screens.CardSection.Kind.BRANCHES) {
                    // Ветка — касанием к её экрану: влита ли, удалить (F-14).
                    items(c.branches) { b ->
                        Text(
                            "  ${b.name} — ${b.updated}" + (if (b.recent) " · свежая" else "") + " ▸",
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "ветка и действия") { open(Nav.Branch(c.name, b.name)) }.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
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
/**
 * Справка (D-112): документ Markdown из APK — строгое оформление пульта,
 * оглавление по разделам, поиск по абзацам. Ссылки `app:` — внутрь приложения
 * ([onApp]): разделы и документ настроек с готовым поиском; адреса сайтов — по
 * D-75: первое касание копирует, второе открывает (только `https`).
 */
@Composable
private fun HelpScreen(onApp: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val taps = remember { LinkTaps() }
    val linkColor = MaterialTheme.colorScheme.primary
    var anchor by remember { mutableStateOf<String?>(null) }
    val doc by produceState<Pair<DocText.Doc, DocText.Blocks>?>(null, linkColor) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val md = runCatching { context.assets.open(HELP_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("# Справка\n\nФайл справки не прочитан.")
            val d = DocText.build(md)
            val text = DocText.annotated(d, emptyMap(), "", HELP_ASSET, linkColor) { target ->
                when (target) {
                    is Links.Target.Anchor -> anchor = target.anchor
                    is Links.Target.External -> if (target.url.startsWith(HelpLinks.SCHEME)) onApp(target.url) else external(context, taps, target.url)
                    is Links.Target.File -> Unit
                }
            }
            d to DocText.blocks(text, d.headings, markdown = true)
        }
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var hit by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(anchor, doc) {
        val a = anchor ?: return@LaunchedEffect
        doc?.second?.anchorIndex(a)?.let { list.animateScrollToItem(it) }
        anchor = null
    }
    LaunchedEffect(hit) { hit?.let { list.animateScrollToItem(it) } }
    val d = doc
    if (d == null) {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; hit = d.second.find(it) }, label = { Text("Найти в справке") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = query.isNotBlank(), onClick = { hit = d.second.find(query, hit ?: -1) }) { Text("Далее") }
        }
        if (query.isNotBlank() && hit == null) Text("Не найдено", style = MaterialTheme.typography.bodyMedium)
        // Оглавление — разделы второго уровня: касание — к разделу.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((_, h) in d.first.headings.drop(1)) {
                androidx.compose.material3.SuggestionChip(onClick = { anchor = Links.slug(h) }, label = { Text(h) })
            }
        }
        androidx.compose.foundation.text.selection.SelectionContainer {
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(d.second.items) { _, t -> Text(t, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

/** Справка в APK пульта (D-112). */
private const val HELP_ASSET = "help.md"

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
            TextButton(onClick = { open(Nav.Ask(nav.project, nav.path)) }) { Text("Спросить…") }
            TextButton(onClick = { open(Nav.Edit(nav.project, nav.path)) }) { Text("Править…") }
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
 * PR всех проектов (F-11): фильтры «Все», «Ждут автора», «Черновики», «Давние» и проект;
 * касание — экран PR с карточкой и действиями (F-12). Данные — из среза синхронизации.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Pulls(
    rows: List<Screens.PullRow>,
    now: com.dpsmpx.dpai.core.clock.Timestamp,
    filter: Screens.PullFilter,
    project: String?,
    onFilter: (Screens.PullFilter) -> Unit,
    onProject: (String?) -> Unit,
    open: (Screens.PullRow) -> Unit,
) {
    val shown = remember(rows, filter, project) { Screens.filterPulls(rows, filter, project) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in Screens.PullFilter.entries) {
                        val n = Screens.filterPulls(rows, f, project).size
                        FilterChip(selected = f == filter, onClick = { onFilter(f) }, label = { Text("${f.title} $n") })
                    }
                }
                val projects = rows.map { it.project }.distinct().sorted()
                if (projects.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = project == null, onClick = { onProject(null) }, label = { Text("Все проекты") })
                        for (p in projects) FilterChip(selected = p == project, onClick = { onProject(if (p == project) null else p) }, label = { Text(p) })
                    }
                }
                Text("PR: ${shown.size}", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(shown.size, key = { "pr:${shown[it].project}:${shown[it].number}" }) { n ->
            val r = shown[n]
            Column(Modifier.fillMaxWidth().clickable { open(r) }.padding(vertical = 4.dp)) {
                Text("#${r.number} ${r.title}", fontWeight = FontWeight.SemiBold)
                Text(Screens.pullLine(r, now), style = MaterialTheme.typography.bodySmall, color = if (r.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Вопросы автору (F-24, UX-17): по проектам, метка — чипом, касание —
 * `TODO.md` проекта на разделе вопроса.
 */
@Composable
private fun Questions(q: List<Screens.Question>, pulls: List<Screens.PullRow>, loader: Loader, open: (Nav) -> Unit) {
    // F-25: поручения ответа — из журнала действий; PR после поручения — из среза.
    var journal by remember { mutableStateOf<List<ActionEntry>>(emptyList()) }
    LaunchedEffect(q) { loader.journal { r -> journal = r.valueOrNull().orEmpty() } }
    val context = LocalContext.current
    val taps = remember { LinkTaps() }
    QuestionsList(q, open) { i ->
        RouteActions.answered(journal, pulls, i)?.let { a ->
            Column(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
                Text("Ответ поручен ${Screens.time(a.at)}" + if (a.pulls.isEmpty()) " — PR после поручения пока нет" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.session?.let { url -> TextButton(onClick = { external(context, taps, url) }) { Text("Сессия Claude") } }
                for (p in a.pulls) {
                    Text(
                        "PR #${p.number} ${p.title} ▸", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable { open(Nav.Pull(p.project, p.number.toInt())) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionsList(q: List<Screens.Question>, open: (Nav) -> Unit, below: @Composable (Screens.Question) -> Unit) = LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    item { Text("Вопросов автору: ${q.size}", style = MaterialTheme.typography.titleMedium) }
    for ((project, items) in Screens.questionGroups(q)) {
        item(key = "p:$project") { Text(project, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
        val keys = contentKeys(items) { "${it.title}|${it.label}" }
        items(items.size, key = { "q:$project:${keys[it]}" }) { n ->
            val i = items[n]
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = i.path != null) { open(Nav.Doc(i.project, i.path!!, i.section?.let(Links::slug))) }.padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    i.label?.let { AssistChip(onClick = {}, enabled = false, label = { Text(it) }, modifier = Modifier.padding(end = 6.dp)) }
                    Text(i.title, Modifier.weight(1f))
                    // F-25: ответ — поручением сессии репозитория-владельца по шаблону «записать ответ».
                    TextButton(onClick = { open(Nav.Brief(i.project, ANSWER_BRIEF, Screens.questionSource(i), i.title)) }) { Text("Ответить") }
                    if (i.path != null) Text("▸")
                }
                below(i)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
// Листается (UX-15): при шрифте 200 % и в альбомной ориентации «Клоны…» не уходят за край.
private fun Settings(view: PultView, onSync: () -> Unit, onSaveToken: (String) -> Unit, onForget: () -> Unit, onClones: () -> Unit, onZone: (Int) -> Unit = {}, onJournal: () -> Unit = {}, onAsk: () -> Unit = {}, onCommands: () -> Unit = {}, onExport: ((json: Boolean, period: Stats.Period, share: Boolean) -> Unit)? = null, scroll: Boolean = true) = Column(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier, verticalArrangement = Arrangement.spacedBy(Tokens.gap)) {
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
    Text("Claude", style = MaterialTheme.typography.titleMedium)
    Text("Вопросы о состоянии проектов — моделью по ключу API из «AI». Claude только читает срез; действий из разговора нет.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onAsk) { Text("Спросить Claude ▸") }
    Text("Команды проектов", style = MaterialTheme.typography.titleMedium)
    Text("Проверки и отчёты проектов — в Linux-среде приложения или в Termux, в клонах. Только команды из списка; своих здесь не набрать.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onCommands) { Text("Команды ▸") }
    Text("Синхронизация", style = MaterialTheme.typography.titleMedium)
    // Постоянная строка последней синхронизации (UX-10); временные итоги — снэкбаром.
    view.state?.let { st -> Text("Последняя: ${Screens.time(st.asOf)} · ${Screens.summary(st).syncHealth}", style = MaterialTheme.typography.bodySmall) }
    Button(onClick = onSync, enabled = view.hasToken && !view.busy) { Text(if (view.busy) "Синхронизация…" else "Обновить") }
    if (view.status.isNotEmpty()) StatusText(view)
    Spacer(Modifier.height(Tokens.gap))
    Text("Клоны репозиториев", style = MaterialTheme.typography.titleMedium)
    Text(
        "Отмеченные репозитории клонируются в приложение: «AI» работает в них (Linux-среда, ~/projects), пульт берёт из них файлы. " +
            view.clones,
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = onClones, enabled = view.hasToken && !view.busy) { Text("Клоны…") }
    // Выгрузка истории метрик (F-44) — здесь: экрана «Статистика» с графиками больше нет (D-112).
    if (onExport != null) {
        Text("История метрик", style = MaterialTheme.typography.titleMedium)
        var period by rememberSaveable { mutableStateOf(Stats.Period.MONTH) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Stats.Period.entries.forEachIndexed { i, p ->
                SegmentedButton(selected = period == p, onClick = { period = p }, shape = SegmentedButtonDefaults.itemShape(i, Stats.Period.entries.size)) { Text(p.title, maxLines = 1) }
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
            for ((title, json, share) in listOf(Triple("Сохранить CSV", false, false), Triple("Сохранить JSON", true, false), Triple("Поделиться CSV…", false, true), Triple("Поделиться JSON…", true, true))) {
                TextButton(onClick = { onExport(json, period, share) }) { Text(title) }
            }
        }
    }
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
 * Карточка PR (F-11): описание — разделы «Сделано / Осталось / Проверки» выделены, прочие
 * свёрнуты под ними; коммиты, файлы со статистикой и обсуждение — по кнопке (три запроса).
 */
@Composable
private fun PullCardView(project: String, p: PullDetail, loader: Loader) {
    val sections = remember(p) { PullActions.sections(p) }
    var card by remember(project, p.number) { mutableStateOf<Outcome<PullActions.Card>?>(null) }
    var loading by remember(project, p.number) { mutableStateOf(false) }
    var more by rememberSaveable(project, p.number) { mutableStateOf(false) }
    for ((title, text) in sections) {
        val main = PullActions.isMain(title)
        if (!main && !more) continue
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = if (main) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    val hidden = sections.count { !PullActions.isMain(it.first) }
    if (hidden > 0) TextButton(onClick = { more = !more }) { Text(if (more) "Скрыть прочие разделы" else "Прочие разделы описания: $hidden") }
    when (val c = card) {
        null -> TextButton(enabled = !loading, onClick = {
            loading = true
            loader.pullCard(project, p.number) { card = it; loading = false }
        }) { Text("Коммиты, файлы и обсуждение") }
        is Outcome.Failure -> {
            Text("Не прочитано: ${c.errors.first().message}", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { card = null }) { Text("Повторить") }
        }
        is Outcome.Success -> {
            val v = c.value
            Text("Коммиты: ${v.commits.size}" + if (v.commits.size >= com.dpsmpx.dpmeta.github.GitHubWrite.MAX_LIST) " (первые)" else "", fontWeight = FontWeight.SemiBold)
            for (x in v.commits) Text(PullActions.commitLine(x), style = MaterialTheme.typography.bodySmall)
            Text("Файлы: ${v.files.size}" + if (v.files.size >= com.dpsmpx.dpmeta.github.GitHubWrite.MAX_LIST) " (первые)" else "", fontWeight = FontWeight.SemiBold)
            for (f in v.files) Text(PullActions.fileLine(f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Text("Обсуждение: ${v.notes.size}", fontWeight = FontWeight.SemiBold)
            for (n in v.notes) {
                Text(PullActions.noteHead(n), style = MaterialTheme.typography.labelMedium)
                if (n.text.isNotEmpty()) androidx.compose.foundation.text.selection.SelectionContainer { Text(n.text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                PullActions.sizeLine(p).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (p.url.isNotEmpty()) TextButton(onClick = { external(context, taps, p.url) }) { Text("Ссылка на GitHub") }
                PullCardView(project, p, loader)
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

/**
 * Команды проектов (F-40…F-43; D-82, D-84): только из реестра, без ввода аргументов. У
 * команды — где идёт (Linux-среда приложения или Termux), готовность по шагам, рабочее
 * дерево клона; уровень 1 — окно «что изменится» и журнал. Вывод — по ходу (в Termux —
 * целиком в конце); запуск переживает уход с экрана ([CommandSession]).
 */
@Composable
private fun CommandsScreen(loader: Loader, onBack: () -> Unit) {
    var catalog by remember { mutableStateOf<Outcome<CommandActions.Catalog>?>(null) }
    LaunchedEffect(Unit) { loader.commands { catalog = it } }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var folderRound by remember { mutableIntStateOf(0) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") } }
        item { Text("Команды проектов", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Идут в Linux-среде приложения, в клонах ~/projects — там же, где работают терминал, IDE и «AI», или в Termux, в его папке проектов. Чтение — сразу; правка рабочего дерева — после окна «что изменится», с записью в журнал.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { TermuxFolder(loader) { folderRound++ } }
        when (val c = catalog) {
            null -> item { Text("Читаю реестр…", style = MaterialTheme.typography.bodySmall) }
            is Outcome.Failure -> item { Text("Реестр не прочитан: ${c.errors.first().message}", color = MaterialTheme.colorScheme.error) }
            is Outcome.Success -> items(c.value.commands, key = { it.id }) { spec ->
                val open = opened == spec.id
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (open) "свернуть" else "готовность и запуск") { opened = if (open) null else spec.id }.padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(spec.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                (if (spec.anyProject) "любой проект" else spec.project) + " · " + if (spec.level == 0) "только чтение" else "правка рабочего дерева",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (CommandSession.runs.any { (k, r) -> k.startsWith("${spec.id}@") && r.running }) {
                            Text("идёт", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                        }
                        Text(if (open) "▾" else "▸")
                    }
                    if (open) CommandDetail(spec, c.value, loader, folderRound)
                }
            }
        }
    }
}

/**
 * Папка проектов Termux (D-83, D-84): клоны в ней — под своими именами. Выбор — вводом пути:
 * каталог Termux приложению не виден, есть ли папка — скажет проверка готовности команды.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TermuxFolder(loader: Loader, onChanged: () -> Unit) {
    var folder by remember { mutableStateOf(loader.termuxFolder()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Папка проектов в Termux", fontWeight = FontWeight.SemiBold)
        if (!editing) {
            Text(folder, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            TextButton(onClick = {
                input = folder
                problem = null
                editing = true
            }) { Text("Изменить папку") }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    problem = null
                },
                label = { Text("Путь в Termux") },
                singleLine = true,
                isError = problem != null,
                supportingText = { Text(problem ?: "Клоны — в ней под своими именами: …/DPmetaApp, …/DPlearn. «~/» — домашний каталог Termux.") },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                Button(onClick = {
                    when (val r = loader.setTermuxFolder(input)) {
                        is Outcome.Success -> {
                            folder = r.value
                            editing = false
                            onChanged()
                        }
                        is Outcome.Failure -> problem = r.errors.first().message.replaceFirstChar { it.uppercase() } + "."
                    }
                }) { Text("Сохранить") }
                TextButton(onClick = { input = com.dpsmpx.dpmeta.app.screens.TermuxActions.DEFAULT_FOLDER }) { Text("По умолчанию") }
                TextButton(onClick = { editing = false }) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun CommandDetail(spec: CommandSpec, catalog: CommandActions.Catalog, loader: Loader, folderRound: Int = 0) {
    var project by rememberSaveable(spec.id) { mutableStateOf(if (spec.anyProject) catalog.anyProjects.firstOrNull() else spec.project) }
    var where by rememberSaveable(spec.id) { mutableStateOf(CommandActions.Where.LINUX) }
    var round by remember { mutableIntStateOf(0) }
    var checked by remember { mutableStateOf<Outcome<CommandActions.Checked>?>(null) }
    LaunchedEffect(spec.id, project, round, where, folderRound) {
        checked = null
        project?.let { p ->
            if (where == CommandActions.Where.TERMUX) {
                loader.termuxCheck(spec, catalog.registry, p) { checked = it }
            } else {
                loader.commandCheck(spec, catalog.registry, p) { checked = it }
            }
        }
    }
    var installing by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (spec.anyProject) {
            if (catalog.anyProjects.isEmpty()) {
                Text("Нет проектов со своим клоном — отметьте их в «Клоны репозиториев».", style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in catalog.anyProjects) FilterChip(selected = p == project, onClick = { project = p }, label = { Text(p) })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            FilterChip(selected = where == CommandActions.Where.LINUX, onClick = { where = CommandActions.Where.LINUX }, label = { Text("Linux в приложении") })
            FilterChip(selected = where == CommandActions.Where.TERMUX, onClick = { where = CommandActions.Where.TERMUX }, label = { Text("Termux") })
        }
        Text(spec.line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        spec.expect?.let { Text("Ждать: $it.", style = MaterialTheme.typography.bodySmall) }
        when (val ch = checked) {
            null -> if (project != null) Text(if (where == CommandActions.Where.TERMUX) "Спрашиваю Termux…" else "Проверяю готовность…", style = MaterialTheme.typography.bodySmall)
            is Outcome.Failure -> Text(ch.errors.first().message.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.error)
            is Outcome.Success -> {
                val v = ch.value
                val inTermux = v.where == CommandActions.Where.TERMUX
                Text("Каталог: ${v.dir}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (v.tree != null) Text(CommandActions.treeLine(v.tree), style = MaterialTheme.typography.bodySmall)
                for (st in v.steps) {
                    Text((if (st.ok) "✓ " else "✗ ") + st.title, style = MaterialTheme.typography.bodySmall, color = if (st.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    if (!st.ok) st.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp)) }
                }
                if (inTermux && v.steps.any { !it.ok && it.title == com.dpsmpx.dpmeta.app.screens.TermuxActions.NO_PERMISSION }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                        OutlinedButton(onClick = { loader.termuxPermit { round++ } }) { Text("Разрешить") }
                        TextButton(onClick = { loader.appSettings() }) { Text("Настройки приложения") }
                    }
                }
                val key = CommandSession.key(spec.id, v.spot.project, inTermux)
                val run = CommandSession.runs[key]
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.gap)) {
                    if (v.missing.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            installing = true
                            installed = null
                            loader.commandInstall(v.missing) { r ->
                                installing = false
                                installed = when (r) {
                                    is Outcome.Success -> "Пакеты поставлены: ${v.missing.joinToString(", ")}."
                                    is Outcome.Failure -> "Не поставлены: ${r.errors.first().message}"
                                }
                                round++
                            }
                        }, enabled = !installing) { Text(if (installing) "Ставлю…" else "Поставить пакеты") }
                    }
                    if (run?.running == true) {
                        // Termux команду извне не останавливает: кнопки нет, итог придёт сам.
                        if (!inTermux) OutlinedButton(onClick = { CommandSession.stop(key) }) { Text("Остановить") }
                    } else {
                        Button(onClick = { if (spec.level == 1) confirming = true else loader.commandStart(spec, v) }, enabled = v.ready) {
                            Text(if (spec.level == 1) "Запустить…" else "Запустить")
                        }
                    }
                    TextButton(onClick = { round++ }, enabled = run?.running != true) { Text("Проверить снова") }
                }
                installed?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (run != null) {
                    if (run.running) Text(if (inTermux) "Идёт в Termux…" else "Идёт…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    run.result?.let { Text(it, color = if (run.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                    if (run.lines.isNotEmpty()) {
                        val scroll = rememberScrollState()
                        // Вывод — к последней строке по ходу, как в терминале.
                        LaunchedEffect(run.lines.size) { scroll.scrollTo(scroll.maxValue) }
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                run.lines.joinToString("\n"),
                                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(scroll),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("Запустить «${spec.title}»?") },
                        text = { Text(CommandActions.preview(spec, v.spot, v.tree, v.where, v.dir).joinToString("\n")) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                loader.commandStart(spec, v)
                            }) { Text("Запустить") }
                        },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Отмена") } },
                    )
                }
            }
        }
    }
}

/**
 * Имя профиля Claude с цветом — кружком у имени (D-83; ТЗ, F-17): не фон и не текст; набор
 * цветов — свой, вне смысловых (`ProfileColors`). Без цвета — только имя.
 */
@Composable
private fun ProfileName(name: String, color: String?) {
    val c = com.dpsmpx.dpai.ui.ProfileColors.of(color)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (c != null) {
            val dark = isSystemInDarkTheme()
            androidx.compose.foundation.layout.Box(
                // Кружок — не элемент для TalkBack: имя рядом его называет.
                Modifier.size(10.dp).background(Color(c.of(dark)), androidx.compose.foundation.shape.CircleShape)
                    .then(Modifier.clearAndSetSemantics { }),
            )
        }
        Text(name)
    }
}

/** Строка журнала для «Поделиться»: время, уровень, действие, цель, итог, ссылка. */
private fun journalLine(r: ActionEntry): String =
    listOf(Screens.time(r.at), "ур. ${r.level}", r.kind, r.target, r.status.word, r.detail, r.link.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * Ветка из карточки (F-14; D-36, D-37): влита ли в основную, голова, защита; удалить.
 * Влитая — окно «что изменится» и PIN (уровень 2); невлитая — красный экран «что будет
 * потеряно безвозвратно» и ввод имени ветки, без биометрии (уровень 3). Основную и
 * защищённую пульт не удаляет. Итог — строкой и в журнале действий (с SHA головы).
 */
@Composable
private fun BranchScreen(project: String, name: String, openPulls: List<Long>, loader: Loader, onBack: () -> Unit) {
    var loaded by remember(project, name) { mutableStateOf<Outcome<BranchActions.View>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(project, name, reload) {
        loaded = null
        loader.branch(project, name) { loaded = it }
    }
    var asking by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
        Text("$project · ветка $name", style = MaterialTheme.typography.titleMedium)
        result?.let { (ok, line) -> Text(line, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
        when (val l = loaded) {
            null -> Text("Читаю ветку с GitHub…", style = MaterialTheme.typography.bodySmall)
            is Outcome.Failure -> {
                // После удаления ветки на GitHub нет — это не ошибка экрана, а итог выше.
                if (result?.first != true) Text("Ветка не прочитана: ${l.errors.first().message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reload++ }) { Text("Повторить") }
            }
            is Outcome.Success -> {
                val v = l.value
                for (line in BranchActions.lines(v)) Text(line, style = MaterialTheme.typography.bodySmall)
                if (openPulls.isNotEmpty()) Text("Открытые PR из неё: " + openPulls.joinToString(", ") { "#$it" }, style = MaterialTheme.typography.bodySmall)
                val blocked = BranchActions.deleteBlocked(v)
                if (blocked != null) {
                    Text("Удалить нельзя: $blocked", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(onClick = { typed = ""; asking = true }, enabled = !running) {
                        Text(if (v.merged) "Удалить влитую ветку…" else "Удалить невлитую ветку…", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (asking) {
                    val command = BranchActions.delete(v)
                    val go: (PullActions.Request) -> Unit = { req ->
                        asking = false
                        running = true
                        result = null
                        loader.act(req) { r ->
                            running = false
                            result = when (r) {
                                is Outcome.Success -> true to "Готово: ${r.value.detail}"
                                is Outcome.Failure -> false to "Не выполнено: ${r.errors.first().message}"
                            }
                            if (r is Outcome.Success) reload++
                        }
                    }
                    if (v.merged) {
                        AlertDialog(
                            onDismissRequest = { asking = false },
                            title = { Text("Что изменится") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (line in BranchActions.preview(v, openPulls)) Text(line)
                                    Text("Дальше — PIN или отпечаток.", style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            confirmButton = { TextButton(onClick = { go(PullActions.Request(command, PullActions.Way.DEVICE)) }) { Text("Дальше — PIN") } },
                            dismissButton = { TextButton(onClick = { asking = false }) { Text("Отмена") } },
                        )
                    } else {
                        DangerDialog(
                            title = "Что будет потеряно безвозвратно",
                            losses = BranchActions.losses(v, openPulls),
                            name = command.nameToType,
                            typed = typed,
                            onTyped = { typed = it },
                            onDismiss = { asking = false },
                            onConfirm = { go(PullActions.Request(command, PullActions.Way.TYPED, typed)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Красный экран уровня 3 (D-37, F-70): что будет потеряно безвозвратно и ввод [name] —
 * кнопка включается только при точном совпадении (ядро сверяет ещё раз). Без биометрии —
 * слово автора: ввод имени и есть подтверждение.
 */
@Composable
private fun DangerDialog(title: String, losses: List<String>, name: String, typed: String, onTyped: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
        textContentColor = MaterialTheme.colorScheme.onErrorContainer,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (line in losses) Text("• $line")
                Text("Чтобы подтвердить, введите: $name", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = typed, onValueChange = onTyped, singleLine = true, label = { Text("Имя") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == name) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * «Спросить Claude» (F-21): модель API-профиля Anthropic из «AI» отвечает о состоянии
 * экосистемы, читая срез инструментами; ответ потоком, под ним — расход токенов. Файл
 * репозитория уходит в вопрос, только если автор открыл экран из документа и не убрал
 * его. История — на телефоне, зашифрованно. Действий отсюда нет.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AskScreen(nav: Nav.Ask, hasSnapshot: Boolean, loader: Loader, onBack: () -> Unit) {
    LaunchedEffect(Unit) { loader.askOpen() }
    var profiles by remember { mutableStateOf<List<AskActions.Profile>?>(null) }
    LaunchedEffect(Unit) { loader.askProfiles { profiles = it } }
    val saved = AskSession.saved
    val running = AskSession.running
    val ready = profiles.orEmpty().filter { it.hasKey }
    val profile = ready.firstOrNull { it.id == saved?.profile } ?: ready.firstOrNull()
    var models by remember(profile?.id) { mutableStateOf<Outcome<List<AskActions.Model>>?>(null) }
    LaunchedEffect(profile?.id) { profile?.let { p -> loader.askModels(p.id) { models = it } } }
    val modelIds = (models as? Outcome.Success)?.value?.map { it.id }.orEmpty()
    val model = saved?.model?.takeIf { saved.profile == profile?.id && (modelIds.isEmpty() || it in modelIds) }
        ?: AskActions.DEFAULT_MODEL.takeIf { modelIds.isEmpty() || it in modelIds }
        ?: profile?.model?.takeIf { it.isNotBlank() }
        ?: modelIds.firstOrNull()
        ?: AskActions.DEFAULT_MODEL
    val effort = saved?.effort
    var pickModel by remember { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    // Файл — только явным выбором: экран открыт из документа; «Убрать» — вопрос без него.
    var withFile by rememberSaveable(nav) { mutableStateOf(nav.path != null) }
    var file by remember(nav) { mutableStateOf<Outcome<String>?>(null) }
    LaunchedEffect(nav) { if (nav.project != null && nav.path != null) loader.document(nav.project, nav.path) { file = it } }
    val fileText = (file as? Outcome.Success)?.value
    val fileWhy = fileText?.let(AskActions::fileRefusal)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val entries = saved?.entries.orEmpty()
    LaunchedEffect(entries.size, running != null) { if (entries.isNotEmpty() || running != null) list.animateScrollToItem(entries.size + 1) }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Новый разговор?") },
            text = { Text("Прежние вопросы и ответы сотрутся с телефона. Вернуть их нельзя.") },
            confirmButton = { TextButton(onClick = { clearing = false; AskSession.clear() }) { Text("Стереть", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Отмена") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (entries.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("Новый разговор") }
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Спросить Claude", style = MaterialTheme.typography.titleMedium)
                    AskSession.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    when {
                        profiles == null || saved == null -> Text("Читаю профили и историю…", style = MaterialTheme.typography.bodySmall)
                        ready.isEmpty() -> Text(
                            "Нет API-профиля Anthropic с ключом. Добавить: вкладка «AI» → «Модели» → «Добавить профиль» → Anthropic.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> {
                            if (ready.size > 1) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (p in ready) {
                                        FilterChip(
                                            selected = p.id == profile?.id, enabled = running == null, onClick = { AskSession.choose(p.id, null, effort) },
                                            label = { ProfileName(p.name, p.color) },
                                        )
                                    }
                                }
                            }
                            TextButton(enabled = running == null, onClick = { pickModel = !pickModel }) { Text("Модель: $model " + if (pickModel) "▴" else "▾") }
                            if (pickModel) {
                                when (val m = models) {
                                    null -> Text("Читаю список моделей…", style = MaterialTheme.typography.bodySmall)
                                    is Outcome.Failure -> Text("Список моделей не получен: ${m.errors.first().message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    is Outcome.Success -> for (x in m.value) {
                                        Row(Modifier.fillMaxWidth().toggleable(value = x.id == model, role = Role.RadioButton) { AskSession.choose(profile?.id, x.id, effort); pickModel = false }.padding(vertical = 4.dp)) {
                                            Text((if (x.id == model) "● " else "○ ") + (x.name?.let { "$it (${x.id})" } ?: x.id), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            Text("Глубина ответа", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = effort == null, enabled = running == null, onClick = { AskSession.choose(profile?.id, model, null) }, label = { Text("как у модели") })
                                for (e in AskActions.EFFORTS) FilterChip(selected = effort == e, enabled = running == null, onClick = { AskSession.choose(profile?.id, model, e) }, label = { Text(e) })
                            }
                            if (!hasSnapshot) Text("Среза состояния ещё нет: сначала «Обновить» в «Ещё» — иначе Claude нечего читать.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            items(entries.size, key = { "e:$it:${entries[it].at}" }) { i ->
                val e = entries[i]
                if (e.user) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            androidx.compose.foundation.text.selection.SelectionContainer { Text(e.text) }
                            e.file?.let { Text("Файл: $it", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (e.text.isNotEmpty()) androidx.compose.foundation.text.selection.SelectionContainer { Text(e.text) }
                        e.note?.let { Text(it, color = if (e.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        if (!e.failed || e.inputTokens != null) Text(AskActions.usageLine(e), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "running") {
                running?.let { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (r.text.isNotEmpty()) Text(r.text)
                        Text(r.step ?: "Claude отвечает…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (nav.path != null && withFile) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "Файл: ${nav.project}:${nav.path}" + when {
                        file == null -> " — читаю…"
                        file is Outcome.Failure -> " — не прочитан"
                        fileWhy != null -> " — $fileWhy"
                        else -> " (${fileText?.length ?: 0} знаков)"
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = if (file is Outcome.Failure || fileWhy != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { withFile = false }) { Text("Убрать") }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            OutlinedTextField(
                value = asking, onValueChange = { asking = it }, label = { Text("Вопрос") },
                modifier = Modifier.weight(1f), minLines = 1, maxLines = 6, enabled = running == null,
            )
            if (running != null) {
                TextButton(onClick = { AskSession.stop() }) { Text("Стоп") }
            } else {
                val fileReady = !(nav.path != null && withFile) || (fileText != null && fileWhy == null)
                TextButton(
                    enabled = asking.isNotBlank() && profile != null && saved != null && fileReady,
                    onClick = {
                        val p = profile ?: return@TextButton
                        val attach = nav.path != null && withFile
                        loader.askSend(p, model, effort, asking, if (attach) "${nav.project}:${nav.path}" else null, if (attach) fileText else null)
                        asking = ""
                        withFile = false
                    },
                ) { Text("Спросить") }
            }
        }
    }
}

/** Шаблон ответа на вопрос (F-25): `config/briefs/answer-question.md`. */
private const val ANSWER_BRIEF = "answer-question"

/**
 * Поручение Claude (F-22, F-23, F-25; D-07): маршрут, шаблон и его поля → окно «Что
 * уйдёт» с текстом целиком → «Поручить» (уровень 1). Маршруты — из подсистемы «AI»;
 * итог — ссылка на облачную сессию (открыть — касанием, D-75) и строка журнала действий.
 */
@Composable
private fun BriefScreen(nav: Nav.Brief, loader: Loader, onBack: () -> Unit) {
    var routes by remember(nav) { mutableStateOf<List<RouteActions.Route>?>(null) }
    var briefs by remember(nav) { mutableStateOf<Outcome<List<com.dpsmpx.dpmeta.formats.Brief>>?>(null) }
    var base by remember(nav) { mutableStateOf<Outcome<Pair<com.dpsmpx.dpmeta.model.RepoRef, String>>?>(null) }
    var journal by remember(nav) { mutableStateOf<List<ActionEntry>>(emptyList()) }
    var reloadJournal by remember(nav) { mutableIntStateOf(0) }
    LaunchedEffect(nav) {
        loader.routes { routes = it }
        loader.briefs { briefs = it }
        loader.base(nav.project) { base = it }
    }
    LaunchedEffect(nav, reloadJournal) { loader.journal { r -> journal = r.valueOrNull().orEmpty() } }
    var routeId by rememberSaveable(nav) { mutableStateOf<String?>(null) }
    var briefKey by rememberSaveable(nav) { mutableStateOf(nav.brief) }
    val values = remember(nav) {
        mutableStateMapOf<String, String>().apply {
            nav.source?.let { put("source", it) }
            nav.question?.let { put("question", it) }
        }
    }
    var pending by remember { mutableStateOf<Triple<com.dpsmpx.dpmeta.actions.ActionCommand, com.dpsmpx.dpmeta.formats.Brief, String>?>(null) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val taps = remember { LinkTaps() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
        Text("Поручить Claude — ${nav.project}", style = MaterialTheme.typography.titleMedium)
        result?.let { (ok, line) -> Text(line, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
        link?.let { url -> Button(onClick = { external(context, taps, url) }) { Text("Открыть сессию") } }
        val rs = routes
        val bs = briefs
        val b = base
        when {
            rs == null || bs == null || b == null -> Text("Читаю маршруты и шаблоны…", style = MaterialTheme.typography.bodySmall)
            bs is Outcome.Failure -> Text("Шаблоны не разобраны: " + bs.errors.first().let { "${it.message} (${it.subject})" }, color = MaterialTheme.colorScheme.error)
            b is Outcome.Failure -> Text("Не выйдет: ${b.errors.first().message}", color = MaterialTheme.colorScheme.error)
            rs.none { it.hasToken } -> Text(
                "Маршрутов с токеном нет. Завести маршрут: вкладка «AI» → «Настройки AI» → «Маршруты Claude Code».",
                style = MaterialTheme.typography.bodySmall,
            )
            bs is Outcome.Success && bs.value.isEmpty() -> Text("Шаблонов поручений в сборке нет.", color = MaterialTheme.colorScheme.error)
            bs is Outcome.Success -> {
                val list = bs.value
                val (repo, branch) = (b as Outcome.Success).value
                val route = rs.firstOrNull { it.id == routeId && it.hasToken } ?: RouteActions.pick(rs, nav.project)
                val brief = list.firstOrNull { it.key == briefKey } ?: RouteActions.pickBrief(list, route, nav.brief)
                Text("$repo · основная ветка $branch", style = MaterialTheme.typography.bodySmall)
                Text("Маршрут", fontWeight = FontWeight.SemiBold)
                for (r in rs) {
                    Row(Modifier.fillMaxWidth().toggleable(value = r.id == route?.id, enabled = r.hasToken && !running, role = Role.RadioButton) { routeId = r.id }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (r.id == route?.id) "● " else "○ ")
                        ProfileName(r.name + listOfNotNull(r.project, if (r.hasToken) null else "нет токена").joinToString(" · ").let { if (it.isEmpty()) "" else " — $it" }, r.color)
                    }
                }
                // F-19: только то, что знает приложение; расход подписки — на claude.ai.
                if (route != null) {
                    val count = RouteActions.firedLastHour(journal, rs, route, com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now())
                    Text(RouteActions.hourLine(count), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { external(context, taps, RouteActions.USAGE_PAGE) }) { Text("Расход подписки — на claude.ai") }
                }
                Text("Поручение", fontWeight = FontWeight.SemiBold)
                for (x in list) {
                    Row(Modifier.fillMaxWidth().toggleable(value = x.key == brief?.key, enabled = !running, role = Role.RadioButton) { briefKey = x.key }.padding(vertical = 4.dp)) {
                        Text((if (x.key == brief?.key) "● " else "○ ") + x.title)
                    }
                }
                if (brief != null) {
                    for (f in brief.fields) {
                        OutlinedTextField(
                            value = values[f.name].orEmpty(), onValueChange = { values[f.name] = it }, label = { Text(f.label) },
                            singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1, enabled = !running, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val rendered = brief.render(RouteActions.builtIn(nav.project, repo, branch), values.toMap())
                    if (rendered is Outcome.Failure && brief.missing(values.toMap()).isEmpty()) {
                        Text(rendered.errors.first().message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = rendered is Outcome.Success && route != null && !running,
                        onClick = {
                            val text = (rendered as Outcome.Success).value
                            result = null
                            link = null
                            pending = Triple(RouteActions.command(route!!, nav.project, repo, brief, text, nav.question.takeIf { brief.key == ANSWER_BRIEF }), brief, text)
                        },
                    ) { Text("Дальше: что уйдёт") }
                }
            }
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    pending?.let { (c, brief, text) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Что уйдёт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (line in RouteActions.preview(c, brief)) Text(line, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    running = true
                    loader.act(PullActions.Request(c, PullActions.Way.SHOWN, text = text)) { r ->
                        running = false
                        when (r) {
                            is Outcome.Success -> {
                                result = true to "Готово: ${r.value.detail}"
                                link = r.value.link
                            }
                            is Outcome.Failure -> {
                                val e = r.errors.first()
                                // F-20: при пределе — подсказка о другом маршруте; сам пульт не переключает.
                                result = false to "Не выполнено: ${e.message}" + RouteActions.limitHint(e.code, routes.orEmpty(), c.params["route_id"])
                            }
                        }
                        reloadJournal++
                    }
                }) { Text("Поручить") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } },
        )
    }
}

/**
 * Новый issue в репозитории проекта (F-26): заголовок и текст → окно «Что уйдёт» →
 * «Отправить» (уровень 1, D-07). Итог — строкой в окне и в журнале действий.
 */
@Composable
private fun IssueDialog(project: String, loader: Loader, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var command by remember { mutableStateOf<com.dpsmpx.dpmeta.actions.ActionCommand?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val c = command
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (c == null) "Новый issue — $project" else "Что уйдёт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c == null) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Заголовок") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Текст") }, minLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    IssueActions.invalid(title)?.takeIf { title.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else {
                    for (line in IssueActions.preview(c)) Text(line)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                result?.let { (ok, line) -> Text(line, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                result?.first == true -> TextButton(onClick = onClose) { Text("Готово") }
                c == null -> TextButton(
                    enabled = IssueActions.invalid(title) == null && !busy,
                    onClick = {
                        busy = true
                        result = null
                        loader.repo(project) { r ->
                            busy = false
                            when (r) {
                                is Outcome.Success -> command = IssueActions.command(r.value, title, body)
                                is Outcome.Failure -> result = false to "Не выйдет: ${r.errors.first().message}"
                            }
                        }
                    },
                ) { Text("Дальше") }
                else -> TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        loader.act(PullActions.Request(c, PullActions.Way.SHOWN)) { r ->
                            busy = false
                            result = when (r) {
                                is Outcome.Success -> true to ("Готово: ${r.value.detail}" + (r.value.link?.let { " — $it" } ?: ""))
                                is Outcome.Failure -> false to "Не выполнено: ${r.errors.first().message}"
                            }
                        }
                    },
                ) { Text("Отправить") }
            }
        },
        dismissButton = { if (result?.first != true) TextButton(onClick = onClose, enabled = !busy) { Text("Отмена") } },
    )
}

/**
 * Правка файла проекта (F-69; D-36): текст с основной ветки GitHub, сообщение коммита и
 * куда — новая ветка с PR (уровень 1: окно «что уйдёт») или прямо в основную (уровень 2:
 * окно «что изменится» и PIN). В окне — дифф по строкам. Файл на GitHub изменили, пока он
 * был открыт, — отказ, правка не затирает чужую.
 */
@Composable
private fun EditScreen(project: String, path: String, loader: Loader, onBack: () -> Unit) {
    var loaded by remember(project, path) { mutableStateOf<Outcome<EditActions.Edit>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(project, path, reload) {
        loaded = null
        loader.editable(project, path) { loaded = it }
    }
    var text by rememberSaveable(project, path, reload) { mutableStateOf<String?>(null) }
    var message by rememberSaveable(project, path) { mutableStateOf("") }
    var toDefault by rememberSaveable(project, path) { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PullActions.Request?>(null) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val context = LocalContext.current
    val taps = remember { LinkTaps() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("← Назад") }
        Text("Правка: $project · $path", style = MaterialTheme.typography.titleMedium)
        result?.let { (ok, line) ->
            Text(line, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            line.substringAfter(" — ", "").takeIf { it.startsWith("https://") }?.let { url -> TextButton(onClick = { external(context, taps, url) }) { Text("Ссылка") } }
        }
        when (val l = loaded) {
            null -> Text("Читаю файл с GitHub…", style = MaterialTheme.typography.bodySmall)
            is Outcome.Failure -> {
                Text("Файл не прочитан: ${l.errors.first().message}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { reload++ }) { Text("Повторить") }
            }
            is Outcome.Success -> {
                val e = l.value
                val current = text ?: e.blob.text
                // Сообщение по умолчанию — один раз после загрузки; стереть его человек может.
                LaunchedEffect(e) { if (message.isEmpty()) message = EditActions.defaultMessage(e) }
                Text("${e.repo} · ветка ${e.base} · блоб ${e.blob.sha.take(7)}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = current, onValueChange = { text = it }, label = { Text("Текст") }, enabled = !running,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth(), minLines = 8,
                )
                OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Сообщение коммита") }, enabled = !running, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().toggleable(value = !toDefault, role = Role.RadioButton) { toDefault = false }.padding(vertical = 4.dp)) {
                    Text((if (!toDefault) "● " else "○ ") + "Новая ветка и PR")
                }
                Row(Modifier.fillMaxWidth().toggleable(value = toDefault, role = Role.RadioButton) { toDefault = true }.padding(vertical = 4.dp)) {
                    Text((if (toDefault) "● " else "○ ") + "Прямо в ${e.base} — с PIN")
                }
                val why = EditActions.invalid(e, current, message)
                Button(
                    enabled = why == null && !running,
                    onClick = {
                        val branch = EditActions.branchName(e, com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now())
                        val c = EditActions.command(e, current, message, toDefault, branch)
                        pending = PullActions.Request(c, if (toDefault) PullActions.Way.DEVICE else PullActions.Way.SHOWN, text = current)
                    },
                ) { Text("Показать изменения…") }
                why?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                pending?.let { req ->
                    val hunks = remember(req) { LineDiff.hunks(LineDiff.diff(e.blob.text, req.text.orEmpty())) }
                    AlertDialog(
                        onDismissRequest = { pending = null },
                        title = { Text(if (req.way == PullActions.Way.SHOWN) "Что уйдёт" else "Что изменится") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (line in EditActions.preview(req.command)) Text(line)
                                for (h in hunks.take(MAX_DIFF_LINES)) {
                                    Text(
                                        h, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = when {
                                            h.startsWith("+ ") -> Color(com.dpsmpx.dpai.ui.AppPalette.semantic(com.dpsmpx.dpai.ui.AppPalette.Role.DONE, isSystemInDarkTheme()))
                                            h.startsWith("- ") -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                if (hunks.size > MAX_DIFF_LINES) Text("… ещё ${hunks.size - MAX_DIFF_LINES} строк", style = MaterialTheme.typography.bodySmall)
                                if (req.way == PullActions.Way.DEVICE) Text("Дальше — PIN или отпечаток.", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
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
                                        text = null
                                        message = ""
                                        reload++
                                    }
                                }
                            }) { Text(if (req.way == PullActions.Way.SHOWN) "Отправить" else "Дальше — PIN") }
                        },
                        dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } },
                    )
                }
            }
        }
    }
}

/** Сколько строк диффа показывает окно правки — остальное счётом. */
private const val MAX_DIFF_LINES = 400

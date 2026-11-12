package com.dpsmpx.dpmeta.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import androidx.core.content.FileProvider
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.data.PultRepository
import com.dpsmpx.dpmeta.app.data.SecretStore
import com.dpsmpx.dpmeta.app.screens.Export
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.Stats
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpmeta.app.ui.ClonePrompt
import com.dpsmpx.dpmeta.app.ui.Loader
import com.dpsmpx.dpmeta.app.ui.PultApp
import com.dpsmpx.dpmeta.formats.Panel
import com.dpsmpx.dpmeta.formats.PanelFormat
import com.dpsmpx.dpmeta.github.GitHub
import com.dpsmpx.dpmeta.app.ui.PultTheme
import com.dpsmpx.dpmeta.app.ui.SyncEnd
import com.dpsmpx.dpmeta.app.ui.PultView
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Экран пульта (этап 3): сводка, проекты, карточка, вопросы автору — только
 * чтение. Окно закрыто от снимков (`FLAG_SECURE`, T-02: на экране — токен и
 * закрытые проекты); вход — через [Lock] (F-45). Первым экраном приложения
 * его делает наследник в модуле корня `:shell` (D-53) — он же даёт
 * [aiIntent]: пункт «AI» открывает оболочку подсистемы.
 */
open class PultActivity : ComponentActivity() {
    private lateinit var pool: ExecutorService
    private lateinit var repo: PultRepository
    private lateinit var secrets: SecretStore
    private val view = mutableStateOf(PultView(null, "", hasToken = false, busy = false))

    /** Раздел, на который просят перейти с левой панели окна «AI» (D-112); экран сбрасывает его, перейдя. */
    private val goTo = mutableStateOf<com.dpsmpx.dpai.ui.AppSection?>(null)

    private fun takeSection(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_SECTION) ?: return
        goTo.value = com.dpsmpx.dpai.ui.AppSection.entries.firstOrNull { it.name == name }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSection(intent)
    }

    /**
     * Что экспортируется (F-36), пока системный выбор ищет место: формат и срок
     * переживают пересоздание окна (поворот), текст собирается заново из истории.
     */
    private var pending: Pending? = null

    /** Экспорт в ожидании места: формат, срок и срез (время), по которому названо имя файла. */
    private data class Pending(val json: Boolean, val period: Stats.Period, val asOf: Long)

    /** Поколение истории: из двух фоновых чтений на экран попадает более позднее (вход и синхронизация идут вперемешку). */
    private val historyGen = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    private var historyShown = 0L

    /** Первый вход процесса в окно, а не пересоздание (поворот): тогда чистится каталог «Поделиться» (D-67). */
    private var coldStart = true
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { writeExport(it) }
    private val saveJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { writeExport(it) }

    /** Ответ на просьбу разрешения Termux (D-84): экран проверяет готовность снова. */
    private var termuxAsked: (() -> Unit)? = null
    private val askTermux = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        termuxAsked?.invoke()
        termuxAsked = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coldStart = savedInstanceState == null
        savedInstanceState?.getString(PENDING_PERIOD)?.let { p ->
            Stats.Period.entries.firstOrNull { it.name == p }?.let {
                pending = Pending(savedInstanceState.getBoolean(PENDING_JSON), it, savedInstanceState.getLong(PENDING_AS_OF))
            }
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Пул — на процесс (D-59, D-65): синхронизация переживает окно.
        pool = com.dpsmpx.dpmeta.app.data.PultPool.work
        // Клоны репозиториев (D-65) — свой каталог в памяти приложения, рядом с данными пульта.
        repo = PultRepository(NioVfs(filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM, pool, File(filesDir, CLONES_DIR), com.dpsmpx.dpmeta.app.data.PultPool.clones)
        secrets = SecretStore(NioVfs(noBackupFilesDir.toPath().resolve("dpmeta")))
        // Блокировка всего приложения (D-59) уже закрыла окно экраном входа — своей не нужно.
        if (AppLock.installed) open() else Lock.ask(this, "Вход в пульт", pinOnly = false, onOpen = ::open, onDenied = { _, _ -> finish() })
    }

    /**
     * Токен для запроса: ключ Keystore открыт 5 минут после входа (T-01); закрыт — вход здесь же, и
     * запрос продолжается (D-59). Нет токена или отказ от входа — `null` и причина в [status].
     */
    private fun withToken(done: (Secret?) -> Unit) {
        when (val r = secrets.load()) {
            is Outcome.Success -> r.value?.let(done) ?: noToken("нет токена GitHub: пункт «Ещё»", done)
            is Outcome.Failure -> if (r.diagnostics.first().code == DiagCode.DEVICE_AUTH_REQUIRED) {
                afterAuth("Открыть токен GitHub") { ok ->
                    if (!ok) {
                        noToken("токен не открыт: нет входа", done)
                    } else {
                        when (val again = secrets.load()) {
                            is Outcome.Success -> again.value?.let(done) ?: noToken("нет токена GitHub", done)
                            is Outcome.Failure -> noToken("токен не открыт: ${again.diagnostics.first().message}", done)
                        }
                    }
                }
            } else {
                noToken("токен не открыт: ${r.diagnostics.first().message}", done)
            }
        }
    }

    /** Ждут входа для ключа Keystore (D-59): одно окно на все запросы — каталог, файл, сохранение. */
    private var authWaiting: MutableList<(Boolean) -> Unit>? = null
    private var authSignal: CancellationSignal? = null

    private fun afterAuth(subtitle: String, then: (Boolean) -> Unit) {
        authWaiting?.let { it += then; return }
        val queue = mutableListOf(then)
        val signal = CancellationSignal()
        authWaiting = queue
        authSignal = signal
        fun done(ok: Boolean) {
            authWaiting = null
            authSignal = null
            // Окно пересоздано или закрыто (поворот) — ответ не этому экземпляру.
            if (!isDestroyed && !signal.isCanceled) queue.forEach { it(ok) }
        }
        Lock.ask(this, subtitle, pinOnly = false, signal = signal, onOpen = { done(true) }, onDenied = { _, _ -> done(false) })
    }

    private fun noToken(why: String, done: (Secret?) -> Unit) {
        // Ключ мог быть сброшен (блокировку экрана сняли) — вместе с ним удалён и токен.
        view.value = view.value.copy(hasToken = secrets.exists(), statusDetails = "", status = why.replaceFirstChar { it.uppercase() } + ".")
        done(null)
    }

    /** Вход в подсистему «AI» (D-53); нет — пункта «AI» нет: пульт сам о подсистеме не знает. */
    protected open fun aiIntent(): Intent? = null

    /** Вход в «AI» со строки сводки (D-76): [requests] — сразу на «Запросы». */
    protected open fun aiIntent(requests: Boolean): Intent? = aiIntent()

    /** Документ настроек приложения (D-112) — от оболочки; `null` — настройки пульта своим экраном. */
    protected open val settingsDocument: (@androidx.compose.runtime.Composable (projects: @androidx.compose.runtime.Composable () -> Unit) -> Unit)? = null

    /** Пункт «IDE» левой панели (D-112): окно «AI» на «Файлах»; нет подсистемы — пункта нет. */
    protected open fun ideIntent(): Intent? = null

    /**
     * Числа строки «AI» на сводке (D-76) — их даёт оболочка `:shell`;
     * `null` — строки нет. Вызывается не на главном потоке.
     */
    protected open fun aiCounts(): com.dpsmpx.dpmeta.app.screens.Screens.AiCounts? = null

    /** Версия подсистемы «AI» для «О программе» (UX-23, D-54); `null` — подсистемы нет. */
    protected open fun aiVersion(): String? = null

    /**
     * Строка журнала отладки приложения (D-39, F-71): итоги синхронизации и действий пульта —
     * вид, цель, итог или код, без содержимого. Журнал ведёт оболочка (`:shell` — журнал
     * подсистемы «AI», один на приложение); у пульта без оболочки — никуда.
     */
    protected open fun debugLog(tag: String, line: String) {}

    /**
     * Маршруты Claude Code (F-22): их ведёт подсистема «AI» (`DP_AI_Orcestrator:D-116`), пульт
     * видит имя, проект и шаблон. Даёт оболочка `:shell`; без неё маршрутов нет.
     * Вызывается не на главном потоке.
     */
    protected open fun routes(): List<com.dpsmpx.dpmeta.app.screens.RouteActions.Route> = emptyList()

    /**
     * «Спросить Claude» (F-21): API-профили Anthropic ведёт подсистема «AI»; пульт видит
     * имя и модель и шлёт вопрос с инструментами чтения среза. Даёт оболочка `:shell`;
     * без неё профилей нет. Вызываются не на главном потоке.
     */
    protected open fun askProfiles(): List<com.dpsmpx.dpmeta.app.screens.AskActions.Profile> = emptyList()

    protected open fun askModels(profile: String): Outcome<List<com.dpsmpx.dpmeta.app.screens.AskActions.Model>> =
        Outcome.failure(DiagCode.UNSUPPORTED, "моделей нет: профили ведёт подсистема «AI»", "ask")

    protected open fun ask(
        request: com.dpsmpx.dpmeta.app.screens.AskActions.Request,
        onCancel: (() -> Unit) -> Unit,
        onText: (String) -> Unit,
        tool: (String, com.dpsmpx.dpai.core.serialization.JsonObject) -> Pair<String, Boolean>,
    ): Outcome<com.dpsmpx.dpmeta.app.screens.AskActions.Answer> =
        Outcome.failure(DiagCode.UNSUPPORTED, "спросить некого: профили ведёт подсистема «AI»", "ask")

    /** Запуск маршрута [id] с поручением [text] — через оболочку; не на главном потоке. */
    protected open fun fireRoute(id: String, text: String): Outcome<com.dpsmpx.dpmeta.app.screens.RouteActions.Fired> =
        Outcome.failure(DiagCode.UNSUPPORTED, "маршрутов нет: их ведёт подсистема «AI»", "route")

    /**
     * Команды реестра (F-40; D-82) идут в Linux-среде подсистемы «AI» — её даёт оболочка
     * `:shell`; без неё Linux-среды нет. Вызываются не на главном потоке.
     */
    protected open fun commandLinux(): com.dpsmpx.dpmeta.app.screens.CommandActions.Linux = com.dpsmpx.dpmeta.app.screens.CommandActions.Linux.UNSUPPORTED

    /** Каких пакетов Alpine нет в Linux-среде. */
    protected open fun commandMissing(packages: List<String>): List<String> = packages

    /** Поставить пакеты Alpine: итог — последние строки вывода `apk`. */
    protected open fun commandInstall(packages: List<String>): Outcome<List<String>> =
        Outcome.failure(DiagCode.UNSUPPORTED, "Linux-среды нет: её даёт подсистема «AI»", "linux")

    /** Запустить [argv] в каталоге гостя [dir] не дольше [minutes]; строки — в [onLine], остановка — в [onStop]. */
    protected open fun commandRun(
        dir: String,
        argv: List<String>,
        minutes: Int,
        onLine: (String) -> Unit,
        onStop: (() -> Unit) -> Unit,
    ): Outcome<com.dpsmpx.dpmeta.app.screens.CommandActions.Exit> =
        Outcome.failure(DiagCode.UNSUPPORTED, "Linux-среды нет: её даёт подсистема «AI»", "linux")

    /**
     * Команда реестра в Termux (F-41; D-84): вызов уходит в Termux, итог приходит
     * целиком, когда команда кончится; остановить её отсюда нельзя — Termux этого не
     * умеет. Не на главном потоке.
     */
    private fun termuxRun(call: com.dpsmpx.dpmeta.app.screens.TermuxActions.Call, minutes: Int, onLine: (String) -> Unit): Outcome<com.dpsmpx.dpmeta.app.screens.CommandActions.Exit> {
        onLine("Отправлено в Termux, каталог ${call.workdir}. Вывод придёт целиком, когда команда кончится.")
        return when (val r = com.dpsmpx.dpmeta.app.data.TermuxBridge.run(this, call, minutes * 60_000L)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val res = r.value ?: return com.dpsmpx.dpmeta.app.screens.TermuxActions.noResult(minutes)
                com.dpsmpx.dpmeta.app.screens.TermuxActions.lines(res).forEach(onLine)
                com.dpsmpx.dpmeta.app.screens.TermuxActions.exit(res)
            }
        }
    }

    /** «О программе» (UX-23, D-54): версия DPmetaApp из пакета и версия «AI» от оболочки. */
    private fun about(): String {
        val app = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        return "DPmetaApp $app" + (aiVersion()?.let { " · AI $it" } ?: "")
    }

    /** Пояс показа выбран в «Ещё» (UX-22): сохраняется, действует сразу и для «AI». */
    private fun zoneChosen(minutes: Int) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit { putInt(ZONE_MINUTES, minutes) }
        applyZone(this)
        view.value = view.value.copy(zone = minutes)
    }

    /** Опрос чисел «AI», пока пульт на экране: дёшево — оболочка читает уже опубликованный снимок. */
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val aiTick = object : Runnable {
        override fun run() {
            if (!pool.isShutdown) {
                pool.execute {
                    // Сбой чтения — прежние числа, а не пропавшая строка.
                    val c = runCatching { aiCounts() }.getOrElse { view.value.ai }
                    runOnUiThread { if (view.value.ai != c) view.value = view.value.copy(ai = c) }
                }
            }
            ui.postDelayed(this, AI_REFRESH_MS)
        }
    }

    override fun onDestroy() {
        authSignal?.cancel()
        // Пул не останавливается: он на процесс, и прерванная синхронизация роняла бы процесс вместе со службами «AI».
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (opened) entrySync()
        // Опрос — только с оболочкой «AI» (D-76): без неё строки нет.
        if (aiIntent() != null) ui.post(aiTick)
    }

    override fun onPause() {
        ui.removeCallbacks(aiTick)
        super.onPause()
    }

    /** Окно открыто входом ([open]) — до этого синхронизации при входе нет. */
    private var opened = false

    /**
     * Синхронизация при входе (D-65): при каждом возвращении в пульт — после
     * входа по PIN и из фона, — если последняя старше `Rules.ENTRY_SYNC_EVERY`.
     * Токен — только если ключ Keystore уже открыт (вход в приложение или
     * разблокировка телефона в последние 5 минут, T-01): второго окна PIN
     * нет; закрыт — синхронизирует «Обновить» или следующий вход.
     */
    private fun entrySync() {
        val v = view.value
        // Срез ещё читается — свежесть неизвестна; вход проверит её, когда чтение кончится (UX-20).
        if (v.loading || v.busy || !secrets.exists() || repo.syncing()) return
        val now = Clock.SYSTEM.now()
        if (v.state?.let { now - it.asOf < com.dpsmpx.dpmeta.metrics.Rules.ENTRY_SYNC_EVERY } == true) return
        entryAt?.let { if (now - it < com.dpsmpx.dpmeta.metrics.Rules.ENTRY_SYNC_EVERY) return }
        val token = (secrets.load() as? Outcome.Success)?.value ?: return
        entryAt = now
        // Первый вход (D-70): сначала — какие репозитории клонировать; без ответа клонов нет.
        if (!clonesAsked()) askClones(token, first = true) else sync(token)
    }

    /** Токен, ждущий ответа окна клонов: после выбора — синхронизация. */
    private var clonesToken: Secret? = null

    /** Что дописать к итогу ближайшей синхронизации (причина, почему окна клонов не было). */
    private var syncNote: String? = null

    /** Окно выбора клонов (D-70): все репозитории аккаунта, реестр — сверху; первый раз отмечен реестр. */
    private fun askClones(token: Secret, first: Boolean) {
        if (pool.isShutdown || view.value.busy) return
        view.value = view.value.copy(busy = true, statusDetails = "", status = "Ищу репозитории аккаунта…")
        pool.execute {
            val r = runCatching { repo.cloneOptions(token) }.getOrElse { Outcome.failure(DiagCode.INTERNAL_ERROR, "список репозиториев: ${it.javaClass.simpleName}") }.let { o ->
                // Какие уже склонированы и сколько заняли (UX-11) — здесь же, в фоне.
                if (o is Outcome.Success) Outcome.success(o.value.map { it.copy(bytes = runCatching { repo.cloneBytes(it.slug) }.getOrNull()) }) else o
            }
            val chosen = clonesSelected()
            val listed = (r as? Outcome.Success)?.value?.map { it.slug.lowercase() }?.toSet().orEmpty()
            val goneSizes = chosen.filter { it.lowercase() !in listed }.map { it to runCatching { repo.cloneBytes(it) }.getOrNull() }
            runOnUiThread {
                when (r) {
                    is Outcome.Success -> {
                        clonesToken = token
                        // До первого выбора (и после «Позже») отмечен реестр (D-70); дальше — выбор автора.
                        // Выбор сверяется со списком без регистра: переименованный лишь регистром — та же отметка, с новым именем.
                        val bySlug = r.value.associateBy { it.slug.lowercase() }
                        val kept = chosen.mapNotNull { bySlug[it.lowercase()]?.slug }.toSet()
                        // Отмеченные, которых в списке больше нет (нет доступа, переименован, в архиве), — видны отмеченными: их можно снять.
                        // Их клоны занимают место — размер и «Удалить…» и у них (проверка PR #173); размер — уже посчитан в фоне.
                        val gone = goneSizes.map { (s, b) -> com.dpsmpx.dpmeta.app.data.PultRepository.CloneOption(s, registry = false, private = true, gone = true, bytes = b) }
                            .filter { it.slug.lowercase() !in bySlug }
                        val checked = if (first || !clonesAsked()) r.value.filter { it.registry }.map { it.slug }.toSet() else kept + gone.map { it.slug }
                        view.value = view.value.copy(busy = false, statusDetails = "", status = "", clonePrompt = ClonePrompt(r.value + gone, checked, first))
                    }
                    is Outcome.Failure -> {
                        view.value = view.value.copy(busy = false)
                        val why = "Список репозиториев не получен: ${r.diagnostics.first().message}."
                        // Первый вход без списка — синхронизация без клонов (причина — в её итоге); окно спросит при следующем входе.
                        if (first) {
                            syncNote = why
                            sync(token)
                        } else {
                            view.value = view.value.copy(statusDetails = "", status = why)
                        }
                    }
                }
            }
        }
    }

    /** Ответ окна клонов: выбор — сохранить и синхронизировать; «Позже» на первом входе — синхронизация без клонов. */
    private fun clonesChosen(selected: Set<String>?) {
        val prompt = view.value.clonePrompt ?: return
        val token = clonesToken
        clonesToken = null
        view.value = view.value.copy(clonePrompt = null)
        if (selected != null) {
            getSharedPreferences(CLONES_PREFS, MODE_PRIVATE).edit {
                putBoolean(CLONES_ASKED, true)
                putStringSet(CLONES_SELECTED, selected)
            }
            view.value = view.value.copy(clones = clonesLine())
        }
        if (token != null && (selected != null || prompt.first)) sync(token)
    }

    private fun clonesAsked(): Boolean = getSharedPreferences(CLONES_PREFS, MODE_PRIVATE).getBoolean(CLONES_ASKED, false)

    private fun clonesSelected(): Set<String> = getSharedPreferences(CLONES_PREFS, MODE_PRIVATE).getStringSet(CLONES_SELECTED, null).orEmpty().toSet()

    private fun clonesLine(): String = if (!clonesAsked()) "Выбор ещё не сделан." else "Отмечено: ${clonesSelected().size}."

    /** Когда началась последняя синхронизация при входе — не повторять её при каждом возвращении в окно. */
    private var entryAt: com.dpsmpx.dpai.core.clock.Timestamp? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pending?.let { p ->
            outState.putBoolean(PENDING_JSON, p.json)
            outState.putString(PENDING_PERIOD, p.period.name)
            outState.putLong(PENDING_AS_OF, p.asOf)
        }
    }

    private fun open() {
        val hasToken = secrets.exists()
        view.value = PultView(null, "", hasToken, busy = false, clones = clonesLine(), loading = true, zone = zoneMinutes(this), about = about())
        // Срез и лента — в фоне, как история (UX-20, N-02): окно не ждёт диска; вход синхронизирует после чтения.
        loadState()
        // История растёт с каждой синхронизацией — читается в фоне, холодный старт её не ждёт (N-02).
        loadHistory()
        // Файлы прежних «Поделиться» (D-67) живут до следующего входа в пульт — не до поворота.
        if (coldStart) pool.execute { File(cacheDir, EXPORT_DIR).deleteRecursively() }
        // Клоны 0.4.0-dev.8 — плоскими каталогами «владелец_2fимя», без работы ИИ (Linux их не видел): место — назад (D-72).
        // Один раз и без перехода по ссылкам: каталог клонов теперь доступен гостю Linux на запись.
        val prefs = getSharedPreferences(CLONES_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(CLONES_FLAT_GONE, false)) {
            prefs.edit { putBoolean(CLONES_FLAT_GONE, true) }
            pool.execute {
                File(filesDir, CLONES_DIR).listFiles()?.filter { "_2f" in it.name }?.forEach { runCatching { com.dpsmpx.dpmeta.git.Clones.deleteTree(it) } }
            }
        }
        opened = true
        // От края до края (Android 15+ — всегда): отступы панелей даёт Scaffold, значки — по теме экрана (UX-5).
        enableEdgeToEdge()
        // Пункт и строка «AI» — только с оболочкой подсистемы (D-53, D-76).
        val ai: ((Boolean) -> Unit)? = if (aiIntent() != null) { requests -> aiIntent(requests)?.let(::startActivity) } else null
        takeSection(intent)
        setContent {
            PultTheme {
                PultApp(
                    view.value, loader, onSync = ::sync, onSaveToken = ::saveToken, onForgetToken = ::forgetToken,
                    onAi = ai, onIde = ideIntent()?.let { i -> { startActivity(i) } }, onExport = ::export,
                    goTo = goTo.value, onGoTo = { goTo.value = null }, settingsDocument = settingsDocument,
                    onClones = { withToken { token -> if (token != null) askClones(token, first = false) } }, onClonesChosen = ::clonesChosen,
                    onCancelSync = {
                        repo.cancelClones()
                        // Отклик сразу (проверка PR #173): начатый клон ещё доходит до конца.
                        view.value = view.value.copy(cancelling = true)
                    },
                    onDeleteClone = ::deleteClone,
                    onZone = ::zoneChosen,
                )
            }
        }
    }

    /**
     * Экспорт истории за срок (F-36): CSV или JSON, место выбирает человек
     * (системный выбор файла — «Загрузки», облако); сеть приложение не трогает.
     */
    /**
     * Срез, лента и имена — в фоне при открытии окна (UX-20); на экран — если
     * синхронизация не успела раньше. Затем — синхронизация при входе.
     */
    private fun loadState() {
        if (pool.isShutdown) {
            view.value = view.value.copy(loading = false)
            return
        }
        pool.execute {
            val state = (repo.state() as? Outcome.Success)?.value
            val feed = (repo.feed() as? Outcome.Success)?.value.orEmpty()
            val names = repo.names()
            runOnUiThread {
                if (!view.value.loading) return@runOnUiThread
                view.value = view.value.copy(state = view.value.state ?: state, feed = view.value.feed.ifEmpty { feed }, names = view.value.names.ifEmpty { names }, loading = false)
                entrySync()
            }
        }
    }

    /** История — в фоне; на экран — только более позднее чтение, чем уже показанное. */
    private fun loadHistory() {
        if (pool.isShutdown) return
        val gen = historyGen.incrementAndGet()
        pool.execute {
            val history = (repo.history() as? Outcome.Success)?.value.orEmpty()
            runOnUiThread {
                if (gen > historyShown) {
                    historyShown = gen
                    view.value = view.value.copy(history = history)
                }
            }
        }
    }

    private fun export(json: Boolean, period: Stats.Period, share: Boolean) {
        val s = view.value.state ?: return
        val name = Export.fileName(period, s.asOf, if (json) "json" else "csv")
        val what = Pending(json, period, s.asOf.epochMillis)
        if (share) {
            if (pool.isShutdown) return
            pool.execute {
                val text = exportText(what)
                runOnUiThread { if (text == null) noExport() else shareExport(name, text, if (json) "application/json" else "text/csv") }
            }
            return
        }
        pending = what
        if (json) saveJson.launch(name) else saveCsv.launch(name)
    }

    /**
     * Текст экспорта — из истории на диске за срок до среза, по которому
     * названо имя файла: после пересоздания окна и после синхронизации,
     * прошедшей во время выбора места, он тот же. Не в главном потоке.
     */
    private fun exportText(what: Pending): String? {
        val history = (repo.history() as? Outcome.Success)?.value ?: return null
        val asOf = com.dpsmpx.dpai.core.clock.Timestamp(what.asOf)
        val points = Export.points(history, what.period, asOf).filter { it.asOf.epochMillis <= what.asOf }
        return if (what.json) Export.json(points, what.period, asOf) else Export.csv(points)
    }

    private fun noExport() {
        view.value = view.value.copy(statusDetails = "", status = "Экспорт не собран: нет среза или истории.")
    }

    /**
     * «Поделиться» (D-67): у каждой отправки — свой каталог в кэше экспорта
     * (повторное нажатие не стирает файл, который ещё читает прошлый
     * получатель); получателю — право чтения только этого файла через
     * [ExportProvider]. Каталог чистится при следующем входе в пульт.
     */
    private fun shareExport(name: String, text: String, mime: String) {
        val ok = runCatching {
            val dir = File(File(cacheDir, EXPORT_DIR), System.nanoTime().toString()).apply { mkdirs() }
            val file = File(dir, name).apply { writeText(text, Charsets.UTF_8) }
            val uri = FileProvider.getUriForFile(this, ExportProvider.authority(this), file)
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(name, uri)
            startActivity(Intent.createChooser(send, "Поделиться: $name"))
        }.isSuccess
        if (!ok) view.value = view.value.copy(statusDetails = "", status = "Не поделиться: файл экспорта не создан.")
    }

    private fun writeExport(uri: Uri?) {
        val what = pending
        pending = null
        if (uri == null || pool.isShutdown) return
        pool.execute {
            val text = what?.let(::exportText)
            val ok = text != null && runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("нет потока") }.isSuccess
            // Выбор места уже создал файл: не записан — убрать пустой, а не оставить его в «Загрузках».
            if (!ok) runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
            runOnUiThread { view.value = view.value.copy(statusDetails = "", status = if (ok) "Экспорт сохранён." else "Экспорт не сохранён: файл не записан.") }
        }
    }

    /** Каталог и файл — в фоне, итог — в главном потоке; без токена — понятный отказ. */
    private val loader = object : Loader {
        private fun <T> run(work: (Secret) -> Outcome<T>, done: (Outcome<T>) -> Unit) = withToken { token ->
            if (token == null) {
                done(Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "нет токена GitHub или нужен новый вход", "github"))
            } else if (!pool.isShutdown) {
                pool.execute {
                    val r = work(token)
                    runOnUiThread {
                        done(r)
                        view.value = view.value.copy(names = repo.names())
                    }
                }
            }
        }

        /**
         * Сначала клон (UX-9): склонированное читается без сети, токена и окна PIN;
         * клона нет или он не отмечен — GitHub по токену.
         */
        private fun <T> localFirst(local: () -> Outcome<T>?, remote: (Secret) -> Outcome<T>, done: (Outcome<T>) -> Unit) {
            if (pool.isShutdown) return
            pool.execute {
                val r = runCatching(local).getOrNull()
                runOnUiThread {
                    if (r != null) {
                        done(r)
                        // Реестр мог прочитаться из клона — ссылки `Проект:путь` работают и до синхронизации.
                        view.value = view.value.copy(names = repo.names())
                    } else {
                        run(remote, done)
                    }
                }
            }
        }

        override fun directory(project: String, dir: String, done: (Outcome<List<GitHub.Entry>>) -> Unit) =
            localFirst({ repo.localDirectory(project, dir, clonesSelected()) }, { repo.directory(it, project, dir) }, done)

        override fun document(project: String, path: String, done: (Outcome<String>) -> Unit) =
            localFirst({ repo.localDocument(project, path, clonesSelected()) }, { repo.document(it, project, path) }, done)

        /** Панель (F-10) — файл `config/projects/<Проект>.toml`, собранный в APK; нет файла — нет панели. */
        override fun panel(project: String): Outcome<Panel>? {
            val name = "projects/$project.toml"
            val text = runCatching { assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: return null
            return PanelFormat.parse(text, name)
        }

        override fun pull(project: String, number: Int, done: (Outcome<Pair<com.dpsmpx.dpmeta.model.RepoRef, com.dpsmpx.dpmeta.github.PullDetail>>) -> Unit) =
            run({ repo.pull(it, project, number) }, done)

        override fun pullCard(project: String, number: Int, done: (Outcome<com.dpsmpx.dpmeta.app.screens.PullActions.Card>) -> Unit) =
            run({ repo.pullCard(it, project, number) }, done)

        /**
         * Действие с PR (F-12; D-07): подтверждение создаётся здесь и только после ответа
         * человека — окно экрана (уровень 1) или ещё PIN или отпечаток (уровень 2). Отмена
         * PIN — «не подтверждено», исполнитель не вызывается.
         */
        override fun act(request: com.dpsmpx.dpmeta.app.screens.PullActions.Request, done: (Outcome<com.dpsmpx.dpmeta.actions.ActionResult>) -> Unit) {
            val c = request.command
            // Итог — и в журнал отладки (D-39): вид, цель, уровень, итог или код; без текста команды.
            val logged: (Outcome<com.dpsmpx.dpmeta.actions.ActionResult>) -> Unit = { r ->
                debugLog("действия", "${c.kind.code} ${c.target.display} ур.${c.level.code}: " + when (r) {
                    is Outcome.Success -> "готово"
                    is Outcome.Failure -> "${r.errors.first().code}"
                })
                done(r)
            }
            fun go(confirm: (com.dpsmpx.dpmeta.actions.Actions) -> com.dpsmpx.dpmeta.actions.Confirmation) =
                if (c.kind == com.dpsmpx.dpmeta.actions.ActionKind.ROUTE_RUN) {
                    // Маршрут (F-22): токен GitHub не нужен — запуск идёт мостом к «AI».
                    background({ repo.fire(c, confirm, request.text.orEmpty()) { id, t -> fireRoute(id, t) } }, logged)
                } else {
                    run({ repo.act(it, c, confirm, request.text) }, logged)
                }
            when (request.way) {
                com.dpsmpx.dpmeta.app.screens.PullActions.Way.SHOWN -> go { it.shown(c) }
                com.dpsmpx.dpmeta.app.screens.PullActions.Way.TYPED -> go { it.typedName(c, request.typed) }
                com.dpsmpx.dpmeta.app.screens.PullActions.Way.DEVICE -> Lock.ask(
                    this@PultActivity, "Подтвердите: ${c.kind.what}", pinOnly = false,
                    onOpen = { go { it.deviceConfirmed(c) } },
                    onDenied = { _, why -> logged(Outcome.failure(DiagCode.DEVICE_AUTH_REQUIRED, "не подтверждено: $why", c.target.display)) },
                )
            }
        }

        override fun repo(project: String, done: (Outcome<com.dpsmpx.dpmeta.model.RepoRef>) -> Unit) = run({ repo.repoFor(it, project) }, done)

        override fun editable(project: String, path: String, done: (Outcome<com.dpsmpx.dpmeta.app.screens.EditActions.Edit>) -> Unit) =
            run({ repo.editable(it, project, path) }, done)

        override fun branch(project: String, name: String, done: (Outcome<com.dpsmpx.dpmeta.app.screens.BranchActions.View>) -> Unit) =
            run({ repo.branch(it, project, name) }, done)

        override fun journal(done: (Outcome<List<com.dpsmpx.dpmeta.actions.ActionEntry>>) -> Unit) = background({ repo.journal() }, done)

        override fun routes(done: (List<com.dpsmpx.dpmeta.app.screens.RouteActions.Route>) -> Unit) =
            background({ runCatching { this@PultActivity.routes() }.getOrDefault(emptyList()) }, done)

        /** Шаблоны (F-23) — файлы `config/briefs/<ключ>.md`, собранные в APK; один не разобран — отказ с местом. */
        override fun briefs(done: (Outcome<List<com.dpsmpx.dpmeta.formats.Brief>>) -> Unit) = background({
            val names = runCatching { assets.list("briefs")?.filter { it.endsWith(".md") }?.sorted() }.getOrNull().orEmpty()
            val out = mutableListOf<com.dpsmpx.dpmeta.formats.Brief>()
            var bad: Outcome.Failure? = null
            for (n in names) {
                val text = runCatching { assets.open("briefs/$n").use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: continue
                when (val b = com.dpsmpx.dpmeta.formats.BriefFormat.parse(text, n.removeSuffix(".md"), "briefs/$n")) {
                    is Outcome.Success -> out += b.value
                    is Outcome.Failure -> if (bad == null) bad = b
                }
            }
            bad ?: Outcome.success(out.toList())
        }, done)

        override fun base(project: String, done: (Outcome<Pair<com.dpsmpx.dpmeta.model.RepoRef, String>>) -> Unit) =
            run({ repo.base(it, project) }, done)

        override fun askProfiles(done: (List<com.dpsmpx.dpmeta.app.screens.AskActions.Profile>) -> Unit) =
            background({ runCatching { this@PultActivity.askProfiles() }.getOrDefault(emptyList()) }, done)

        override fun askModels(profile: String, done: (Outcome<List<com.dpsmpx.dpmeta.app.screens.AskActions.Model>>) -> Unit) =
            background({ this@PultActivity.askModels(profile) }, done)

        /** История — зашифрованным файлом рядом с данными пульта; читается один раз на процесс. */
        override fun askOpen() =
            com.dpsmpx.dpmeta.app.data.AskSession.load(com.dpsmpx.dpmeta.app.data.AskStore(NioVfs(noBackupFilesDir.toPath().resolve("dpmeta"))))

        override fun askSend(profile: com.dpsmpx.dpmeta.app.screens.AskActions.Profile, model: String, effort: String?, question: String, file: String?, fileText: String?) {
            val v = view.value
            val snap = com.dpsmpx.dpmeta.app.screens.AskActions.Snapshot(v.state, v.feed, v.history, com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now())
            debugLog("спросить", "вопрос: профиль ${profile.name}, модель $model, знаков ${question.length}" + if (file != null) ", с файлом" else "")
            com.dpsmpx.dpmeta.app.data.AskSession.send({ r, c, t, tool -> this@PultActivity.ask(r, c, t, tool) }, profile, model, effort, snap, question, file, fileText)
        }

        /** Работа без токена GitHub — в пуле, итог — на главном потоке. */
        private fun <T> background(work: () -> T, done: (T) -> Unit) {
            if (pool.isShutdown) return
            pool.execute {
                val r = work()
                runOnUiThread { done(r) }
            }
        }

        /** Реестр команд (F-40, F-43; D-82) — `config/commands.toml` в APK, реестр проектов — из среза или клона. */
        override fun commands(done: (Outcome<com.dpsmpx.dpmeta.app.screens.CommandActions.Catalog>) -> Unit) = background({
            val text = runCatching { assets.open(COMMANDS_ASSET).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
            val parsed = if (text == null) {
                Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "реестра команд нет в сборке", COMMANDS_ASSET)
            } else {
                com.dpsmpx.dpmeta.formats.CommandFormat.parse(text, COMMANDS_ASSET)
            }
            when (parsed) {
                is Outcome.Failure -> parsed
                is Outcome.Success -> when (val reg = repo.commandRegistry(clonesSelected())) {
                    is Outcome.Failure -> reg
                    is Outcome.Success -> Outcome.success(
                        com.dpsmpx.dpmeta.app.screens.CommandActions.Catalog(
                            parsed.value.commands,
                            reg.value,
                            com.dpsmpx.dpmeta.app.screens.CommandActions.anyProjects(reg.value) { java.io.File(repo.cloneDir(it), ".git").isDirectory },
                        ),
                    )
                }
            }
        }, done)

        /** Готовность команды на месте (D-82): Linux, пакеты, клон, каталог, скрипт — и рабочее дерево. */
        override fun commandCheck(
            spec: com.dpsmpx.dpmeta.formats.CommandSpec,
            registry: com.dpsmpx.dpmeta.model.Registry,
            project: String?,
            done: (Outcome<com.dpsmpx.dpmeta.app.screens.CommandActions.Checked>) -> Unit,
        ) = background({
            when (val sp = com.dpsmpx.dpmeta.app.screens.CommandActions.spot(spec, registry, project)) {
                is Outcome.Failure -> sp
                is Outcome.Success -> {
                    val spot = sp.value
                    val clone = repo.cloneDir(spot.repo)
                    val hasClone = java.io.File(clone, ".git").isDirectory
                    val workdir = spot.inClone.isEmpty() || java.io.File(clone, spot.inClone).isDirectory
                    val script = com.dpsmpx.dpmeta.app.screens.CommandActions.scriptInClone(spec, spot)?.let { java.io.File(clone, it).isFile }
                    val linux = runCatching { commandLinux() }.getOrDefault(com.dpsmpx.dpmeta.app.screens.CommandActions.Linux.UNSUPPORTED)
                    val missing = if (linux == com.dpsmpx.dpmeta.app.screens.CommandActions.Linux.READY) runCatching { commandMissing(spec.packages) }.getOrDefault(spec.packages) else spec.packages
                    val facts = com.dpsmpx.dpmeta.app.screens.CommandActions.Facts(linux, missing, hasClone, hasClone && workdir, script?.let { hasClone && it })
                    Outcome.success(
                        com.dpsmpx.dpmeta.app.screens.CommandActions.Checked(
                            spot,
                            if (hasClone) repo.worktree(spot.repo) else null,
                            com.dpsmpx.dpmeta.app.screens.CommandActions.readiness(spec, spot, facts),
                            if (linux == com.dpsmpx.dpmeta.app.screens.CommandActions.Linux.READY) missing else emptyList(),
                        ),
                    )
                }
            }
        }, done)

        override fun commandInstall(packages: List<String>, done: (Outcome<List<String>>) -> Unit) =
            background({ runCatching { this@PultActivity.commandInstall(packages) }.getOrElse { Outcome.failure(DiagCode.INTERNAL_ERROR, "сбой: ${it.javaClass.simpleName}", "apk") } }, done)

        /**
         * Запуск команды (D-82): уровень 0 — сразу; уровень 1 — после окна «что изменится»
         * экрана, через политику и журнал `:core:actions` (D-07). Итог — и в журнал отладки
         * (D-39): id, проект, итог; без вывода.
         */
        override fun commandStart(spec: com.dpsmpx.dpmeta.formats.CommandSpec, checked: com.dpsmpx.dpmeta.app.screens.CommandActions.Checked) {
            val termux = checked.where == com.dpsmpx.dpmeta.app.screens.CommandActions.Where.TERMUX
            val call = checked.termux
            if (termux && call == null) return
            val key = com.dpsmpx.dpmeta.app.data.CommandSession.key(spec.id, checked.spot.project, termux)
            val argv = com.dpsmpx.dpmeta.app.screens.CommandActions.argv(spec)
            val job = com.dpsmpx.dpmeta.app.data.CommandSession.Job { onLine, onStop ->
                val started = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now().epochMillis
                fun seconds() = (com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now().epochMillis - started) / 1000
                fun run(): Outcome<com.dpsmpx.dpmeta.app.screens.CommandActions.Exit> =
                    if (call != null) termuxRun(call, spec.timeoutMinutes, onLine) else commandRun(checked.spot.guestDir, argv, spec.timeoutMinutes, onLine, onStop)
                if (spec.level == 1) {
                    val c = com.dpsmpx.dpmeta.app.screens.CommandActions.command(spec, checked.spot, checked.tree, checked.where, checked.dir)
                    when (val r = repo.runCommand(c, { it.shown(c) }, ::run)) {
                        is Outcome.Failure -> r
                        is Outcome.Success -> Outcome.success("Готово: ${r.value.detail} (${seconds()} с).")
                    }
                } else {
                    when (val r = run()) {
                        is Outcome.Failure -> r
                        is Outcome.Success -> if (r.value.code == 0) {
                            Outcome.success(com.dpsmpx.dpmeta.app.screens.CommandActions.exitLine(r.value, seconds()))
                        } else {
                            Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, com.dpsmpx.dpmeta.app.screens.CommandActions.exitLine(r.value, seconds()).removeSuffix("."), key)
                        }
                    }
                }
            }
            com.dpsmpx.dpmeta.app.data.CommandSession.start(key, job) { r ->
                debugLog("команды", "${spec.id} ${checked.spot.project} ур.${spec.level}" + (if (termux) " в Termux" else "") + ": " + when (r) {
                    is Outcome.Success -> "готово"
                    is Outcome.Failure -> "${r.errors.first().code}"
                })
            }
        }

        override fun termuxFolder(): String = termuxFolder(this@PultActivity)

        override fun setTermuxFolder(input: String): Outcome<String> {
            val folder = com.dpsmpx.dpmeta.app.screens.TermuxActions.normalizeFolder(input)
            com.dpsmpx.dpmeta.app.screens.TermuxActions.folderProblem(folder)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, "termux") }
            getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).edit { putString(TERMUX_FOLDER, folder) }
            return Outcome.success(folder)
        }

        /**
         * Готовность в Termux (F-42; D-84): что знает пакетный менеджер, затем — проба
         * `ls` папки, клона, программы и скрипта (только чтение), если Termux можно спросить.
         */
        override fun termuxCheck(
            spec: com.dpsmpx.dpmeta.formats.CommandSpec,
            registry: com.dpsmpx.dpmeta.model.Registry,
            project: String?,
            done: (Outcome<com.dpsmpx.dpmeta.app.screens.CommandActions.Checked>) -> Unit,
        ) = background({
            val t = com.dpsmpx.dpmeta.app.screens.TermuxActions
            when (val sp = com.dpsmpx.dpmeta.app.screens.CommandActions.spot(spec, registry, project)) {
                is Outcome.Failure -> sp
                is Outcome.Success -> {
                    val spot = sp.value
                    val folder = termuxFolder(this@PultActivity)
                    var facts = com.dpsmpx.dpmeta.app.data.TermuxBridge.facts(this@PultActivity)
                    if (t.folderProblem(folder) == null && facts.installed && facts.runCommand && t.versionOk(facts.versionName) && facts.permitted) {
                        val probe = t.probe(t.probePaths(spec, spot, folder))
                        facts = when (val r = com.dpsmpx.dpmeta.app.data.TermuxBridge.run(this@PultActivity, probe, t.PROBE_SECONDS * 1000L)) {
                            is Outcome.Failure -> facts.copy(probe = com.dpsmpx.dpmeta.app.screens.TermuxActions.Result("", "", null, 1, r.errors.first().message), probeSent = true)
                            is Outcome.Success -> facts.copy(probe = r.value, probeSent = true)
                        }
                    }
                    val ready = t.readiness(spec, spot, folder, facts)
                    val dir = ready.clone?.let { t.dir(folder, it, spot) } ?: t.dir(folder, spot.repo.name, spot)
                    Outcome.success(
                        com.dpsmpx.dpmeta.app.screens.CommandActions.Checked(
                            spot,
                            null,
                            ready.steps,
                            where = com.dpsmpx.dpmeta.app.screens.CommandActions.Where.TERMUX,
                            dir = dir,
                            termux = ready.call?.takeIf { ready.ready },
                        ),
                    )
                }
            }
        }, done)

        override fun termuxPermit(done: () -> Unit) {
            termuxAsked = done
            askTermux.launch(com.dpsmpx.dpmeta.app.data.TermuxBridge.PERMISSION)
        }

        override fun appSettings() {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)),
            )
        }

        /** Экспорт журнала (F-44): текстом через системное «Поделиться». */
        override fun share(subject: String, text: String) {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                .putExtra(android.content.Intent.EXTRA_TEXT, text)
            startActivity(android.content.Intent.createChooser(send, subject))
        }
    }

    private fun saveToken(text: String) {
        val r = secrets.save(Secret(text))
        if (r is Outcome.Failure && r.diagnostics.first().code == DiagCode.DEVICE_AUTH_REQUIRED) {
            // Ключ закрыт (вход был давно) — вход и повтор (D-59).
            afterAuth("Сохранить токен GitHub") { ok -> saved(if (ok) secrets.save(Secret(text)) else r) }
        } else {
            saved(r)
        }
    }

    private fun saved(r: Outcome<Unit>) {
        view.value = view.value.copy(hasToken = r is Outcome.Success || secrets.exists(), statusDetails = "", status = if (r is Outcome.Success) "Токен сохранён." else "Токен не сохранён: ${r.diagnostics.first().message}")
        // Первый вход (UX-11, D-70): после токена — сразу окно клонов и синхронизация, а не «Токен сохранён.» и тишина.
        if (r is Outcome.Success) sync()
    }

    /** Удалить не отмеченный клон (UX-11) — после вопроса на экране; окно перечитывает его размер и говорит итог. */
    private fun deleteClone(slug: String) {
        if (pool.isShutdown) return
        pool.execute {
            val r = runCatching { repo.deleteClone(slug) }.getOrElse { Outcome.failure(DiagCode.INTERNAL_ERROR, "клон: ${it.javaClass.simpleName}", slug) }
            val left = runCatching { repo.cloneBytes(slug) }.getOrNull()
            runOnUiThread {
                val p = view.value.clonePrompt ?: return@runOnUiThread
                val note = when (r) {
                    is Outcome.Success -> "Клон $slug удалён."
                    is Outcome.Failure -> "Клон $slug не удалён: ${r.diagnostics.first().message}."
                }
                view.value = view.value.copy(clonePrompt = p.copy(options = p.options.map { if (it.slug == slug) it.copy(bytes = left) else it }, note = note))
            }
        }
    }

    private fun forgetToken() {
        secrets.forget()
        view.value = view.value.copy(hasToken = false, statusDetails = "", status = "Токен удалён.")
    }

    /** «Обновить»: до первого выбора клонов (D-70) — сначала окно выбора. */
    private fun sync() = withToken { token ->
        if (token != null) {
            if (!clonesAsked()) askClones(token, first = true) else sync(token)
        } else {
            // Нет токена или входа — «Обновить» не началось: сказать снэкбаром, а не молча (проверка PR #172).
            view.value = view.value.copy(syncEnded = view.value.syncEnded + 1, syncEnd = SyncEnd.REFUSED)
        }
    }

    private fun sync(token: Secret) {
        if (pool.isShutdown) return
        // Идёт синхронизация прежнего окна (поворот) — не ошибка: итог будет у «Обновить» после неё.
        if (repo.syncing()) {
            view.value = view.value.copy(statusDetails = "", status = "Синхронизация уже идёт — «Обновить», когда закончится.", syncEnded = view.value.syncEnded + 1, syncEnd = SyncEnd.REFUSED)
            return
        }
        view.value = view.value.copy(busy = true, statusDetails = "", status = "")
        val note = syncNote
        syncNote = null
        val app = applicationContext
        val selected = clonesSelected()
        pool.execute {
            // Новый клон — под службой переднего плана (D-78): прошивка не выгрузит процесс на середине клона.
            // Окно уже в фоне или система отказала — клонирование идёт в пуле, как прежде.
            val service = selected.isNotEmpty() && runCatching { repo.missingClones(selected) }.getOrDefault(false) && CloneService.start(app)
            // Сбой внутри синхронизации — отказ на экране, а не падение процесса со службами «AI» (D-59).
            // Ход (UX-10) — в заголовок, из потока синхронизации на главный; при службе — и в её уведомление.
            val progress: (String) -> Unit = { p ->
                if (service) CloneService.progress(app, p)
                runOnUiThread { if (view.value.busy) view.value = view.value.copy(progress = p) }
            }
            val r = try {
                runCatching { repo.sync(token, selected, progress) }.getOrElse { Outcome.failure(DiagCode.INTERNAL_ERROR, "синхронизация прервана: ${it.javaClass.simpleName}") }
            } finally {
                if (service) CloneService.stop(app)
            }
            // Тревоги (D-85): новые события — через правила в уведомления; сбой показа синхронизацию не роняет.
            if (r is Outcome.Success && r.value.events.isNotEmpty()) {
                val line = runCatching { com.dpsmpx.dpmeta.app.data.PultAlerts.post(app, r.value.events, Clock.SYSTEM.now()) }.getOrElse { "сбой: ${it.javaClass.simpleName}" }
                debugLog("тревоги", line)
            }
            val state = (repo.state() as? Outcome.Success)?.value
            val feed = (repo.feed() as? Outcome.Success)?.value.orEmpty()
            runOnUiThread {
                view.value = when (r) {
                    is Outcome.Success -> view.value.copy(
                        state = state,
                        busy = false,
                        status = "Готово: ${r.value.line}.".also { debugLog("пульт", "синхронизация: ${r.value.line}") } + (note?.let { " $it" } ?: ""),
                        statusDetails = "",
                        syncEnded = view.value.syncEnded + 1,
                        syncEnd = SyncEnd.DONE,
                        progress = null,
                        cancelling = false,
                        feed = feed,
                        names = repo.names(),
                    ).also { loadHistory() }
                    is Outcome.Failure -> Screens.failure(r.diagnostics.first()).let { f ->
                        debugLog("пульт", "синхронизация не вышла: ${r.diagnostics.first().code}")
                        view.value.copy(busy = false, status = "Не вышло: ${f.text}." + (note?.let { " $it" } ?: ""), statusDetails = f.details, syncEnded = view.value.syncEnded + 1, syncEnd = SyncEnd.FAILED, progress = null, cancelling = false)
                    }
                }
            }
        }
    }

    companion object {
        /** Раздел пульта по имени `AppSection` — для перехода с левой панели окна «AI» (D-112). */
        const val EXTRA_SECTION = "com.dpsmpx.dpmeta.SECTION"

        /** Как часто строка «AI» сводки перечитывает числа, пока пульт на экране (D-76). */
        private const val AI_REFRESH_MS = 5_000L

        /** Каталог экспорта в кэше — единственный, что виден [ExportProvider] (`res/xml/pult_export_paths.xml`). */
        const val EXPORT_DIR = "pult-export"
        private const val PENDING_JSON = "export.json"
        private const val PENDING_PERIOD = "export.period"
        private const val PENDING_AS_OF = "export.asOf"

        /** Каталог клонов репозиториев экосистемы (D-65) в `filesDir` — раздел проектов гостя `~/projects` (D-111, D-113). */
        const val CLONES_DIR = "linux/rootfs/root/projects"

        /** Есть ли токен GitHub в хранилище пульта — для карты хранилища (D-111): только признак, без значения. */
        fun hasGithubToken(context: android.content.Context): Boolean =
            com.dpsmpx.dpmeta.app.data.SecretStore(NioVfs(context.noBackupFilesDir.toPath().resolve("dpmeta"))).exists()

        /** Прежнее место клонов (до D-111, D-113): переезжает в `~/projects`, на старом месте — ссылка. */
        const val LEGACY_CLONES_DIR = "clones"

        /** Реестр команд в APK (F-40): `config/commands.toml` корня. */
        const val COMMANDS_ASSET = "commands.toml"

        /** Выбор клонов автора (D-70): спрошен ли, какие отмечены (`владелец/имя`). */
        private const val CLONES_PREFS = "dpmeta.clones"
        private const val SETTINGS_PREFS = "dpmeta.settings"
        private const val ZONE_MINUTES = "zone_minutes"
        private const val TERMUX_FOLDER = "termux_folder"

        /** Папка проектов Termux (D-83): выбранная в «Командах» или по умолчанию. */
        fun termuxFolder(context: android.content.Context): String =
            context.getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(TERMUX_FOLDER, null)
                ?: com.dpsmpx.dpmeta.app.screens.TermuxActions.DEFAULT_FOLDER

        /** Пояс показа, минуты от UTC (ТЗ, §6.3; UX-22): по умолчанию +03:00. */
        fun zoneMinutes(context: android.content.Context): Int =
            context.getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getInt(ZONE_MINUTES, com.dpsmpx.dpmeta.app.screens.Screens.DEFAULT_ZONE.totalSeconds / 60)

        /**
         * Пояс показа — всему приложению (UX-22): пульт и «AI» читают его из
         * ядра ([com.dpsmpx.dpai.core.clock.DisplayZone]). Зовёт оболочка при
         * запуске процесса и «Ещё» при выборе.
         */
        fun applyZone(context: android.content.Context) {
            com.dpsmpx.dpai.core.clock.DisplayZone.current = java.time.ZoneOffset.ofTotalSeconds(zoneMinutes(context) * 60)
        }
        private const val CLONES_ASKED = "asked"
        private const val CLONES_SELECTED = "selected"

        /**
         * Манифест экосистемы для «AI» (`DP_AI_Orcestrator:D-114`) — из реестра
         * и отмеченных клонов пульта, без сети и токена; вызывает оболочка
         * `:shell` (D-53), с фонового потока.
         */
        fun ecosystemManifest(context: android.content.Context): Outcome<String> {
            val app = context.applicationContext
            val selected = app.getSharedPreferences(CLONES_PREFS, MODE_PRIVATE).getStringSet(CLONES_SELECTED, null).orEmpty().toSet()
            val repo = PultRepository(NioVfs(app.filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM, com.dpsmpx.dpmeta.app.data.PultPool.work, File(app.filesDir, CLONES_DIR))
            // Испорченный клон или сбой JGit — отказ словами, а не падение окна «Экосистема».
            return runCatching { repo.ecosystemManifest(selected) }
                .getOrElse { Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.ECOSYSTEM_UNAVAILABLE, "манифест из реестра не собран: ${it.javaClass.simpleName}") }
        }
        private const val CLONES_FLAT_GONE = "flat-removed"
    }
}

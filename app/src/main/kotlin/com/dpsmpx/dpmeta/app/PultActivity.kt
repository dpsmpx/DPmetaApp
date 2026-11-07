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

    /**
     * Числа строки «AI» на сводке (D-76) — их даёт оболочка `:shell`;
     * `null` — строки нет. Вызывается не на главном потоке.
     */
    protected open fun aiCounts(): com.dpsmpx.dpmeta.app.screens.Screens.AiCounts? = null

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
        if (v.busy || !secrets.exists() || repo.syncing()) return
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
        val state = (repo.state() as? Outcome.Success)?.value
        val hasToken = secrets.exists()
        val feed = (repo.feed() as? Outcome.Success)?.value.orEmpty()
        view.value = PultView(state, "", hasToken, busy = false, feed = feed, names = repo.names(), clones = clonesLine())
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
        entrySync()
        // От края до края (Android 15+ — всегда): отступы панелей даёт Scaffold, значки — по теме экрана (UX-5).
        enableEdgeToEdge()
        // Пункт и строка «AI» — только с оболочкой подсистемы (D-53, D-76).
        val ai: ((Boolean) -> Unit)? = if (aiIntent() != null) { requests -> aiIntent(requests)?.let(::startActivity) } else null
        setContent {
            PultTheme {
                PultApp(
                    view.value, loader, onSync = ::sync, onSaveToken = ::saveToken, onForgetToken = ::forgetToken,
                    onAi = ai, onExport = ::export,
                    onClones = { withToken { token -> if (token != null) askClones(token, first = false) } }, onClonesChosen = ::clonesChosen,
                    onCancelSync = {
                        repo.cancelClones()
                        // Отклик сразу (проверка PR #173): начатый клон ещё доходит до конца.
                        view.value = view.value.copy(cancelling = true)
                    },
                    onDeleteClone = ::deleteClone,
                )
            }
        }
    }

    /**
     * Экспорт истории за срок (F-36): CSV или JSON, место выбирает человек
     * (системный выбор файла — «Загрузки», облако); сеть приложение не трогает.
     */
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
        pool.execute {
            // Сбой внутри синхронизации — отказ на экране, а не падение процесса со службами «AI» (D-59).
            // Ход (UX-10) — в заголовок, из потока синхронизации на главный.
            val progress: (String) -> Unit = { p -> runOnUiThread { if (view.value.busy) view.value = view.value.copy(progress = p) } }
            val r = runCatching { repo.sync(token, clonesSelected(), progress) }.getOrElse { Outcome.failure(DiagCode.INTERNAL_ERROR, "синхронизация прервана: ${it.javaClass.simpleName}") }
            val state = (repo.state() as? Outcome.Success)?.value
            val feed = (repo.feed() as? Outcome.Success)?.value.orEmpty()
            runOnUiThread {
                view.value = when (r) {
                    is Outcome.Success -> view.value.copy(
                        state = state,
                        busy = false,
                        status = "Готово: ${r.value.line}." + (note?.let { " $it" } ?: ""),
                        statusDetails = "",
                        syncEnded = view.value.syncEnded + 1,
                        syncEnd = SyncEnd.DONE,
                        progress = null,
                        cancelling = false,
                        feed = feed,
                        names = repo.names(),
                    ).also { loadHistory() }
                    is Outcome.Failure -> Screens.failure(r.diagnostics.first()).let { f ->
                        view.value.copy(busy = false, status = "Не вышло: ${f.text}." + (note?.let { " $it" } ?: ""), statusDetails = f.details, syncEnded = view.value.syncEnded + 1, syncEnd = SyncEnd.FAILED, progress = null, cancelling = false)
                    }
                }
            }
        }
    }

    companion object {
        /** Как часто строка «AI» сводки перечитывает числа, пока пульт на экране (D-76). */
        private const val AI_REFRESH_MS = 5_000L

        /** Каталог экспорта в кэше — единственный, что виден [ExportProvider] (`res/xml/pult_export_paths.xml`). */
        const val EXPORT_DIR = "pult-export"
        private const val PENDING_JSON = "export.json"
        private const val PENDING_PERIOD = "export.period"
        private const val PENDING_AS_OF = "export.asOf"

        /** Каталог клонов репозиториев экосистемы (D-65) в `filesDir`. */
        const val CLONES_DIR = "clones"

        /** Выбор клонов автора (D-70): спрошен ли, какие отмечены (`владелец/имя`). */
        private const val CLONES_PREFS = "dpmeta.clones"
        private const val CLONES_ASKED = "asked"
        private const val CLONES_SELECTED = "selected"
        private const val CLONES_FLAT_GONE = "flat-removed"
    }
}

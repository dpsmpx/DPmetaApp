package com.dpsmpx.dpmeta.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.data.PultRepository
import com.dpsmpx.dpmeta.app.data.SecretStore
import com.dpsmpx.dpmeta.app.screens.Export
import com.dpsmpx.dpmeta.app.screens.Stats
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpmeta.app.ui.Loader
import com.dpsmpx.dpmeta.app.ui.PultApp
import com.dpsmpx.dpmeta.formats.Panel
import com.dpsmpx.dpmeta.formats.PanelFormat
import com.dpsmpx.dpmeta.github.GitHub
import com.dpsmpx.dpmeta.app.ui.PultTheme
import com.dpsmpx.dpmeta.app.ui.PultView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Экран пульта (этап 3): сводка, проекты, карточка, вопросы автору — только
 * чтение. Окно закрыто от снимков (`FLAG_SECURE`, T-02: на экране — токен и
 * закрытые проекты); вход — через [Lock] (F-45). Первым экраном приложения
 * его делает наследник в модуле корня `:shell` (D-53) — он же даёт
 * [aiIntent]: вкладка «AI» открывает оболочку подсистемы.
 */
open class PultActivity : ComponentActivity() {
    private lateinit var pool: ExecutorService
    private lateinit var repo: PultRepository
    private lateinit var secrets: SecretStore
    private val view = mutableStateOf(PultView(null, "", hasToken = false, busy = false))

    /** Текст экспорта (F-36), ждущий места от системного выбора; окно пересоздано — потерян, экспорт повторяется. */
    private var pendingExport: String? = null
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { writeExport(it) }
    private val saveJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { writeExport(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        pool = Executors.newFixedThreadPool(THREADS)
        repo = PultRepository(NioVfs(filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM, pool)
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
            is Outcome.Success -> r.value?.let(done) ?: noToken("нет токена GitHub: вкладка «Настройки»", done)
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
        view.value = view.value.copy(hasToken = secrets.exists(), status = why.replaceFirstChar { it.uppercase() } + ".")
        done(null)
    }

    /** Вход в подсистему «AI» (D-53); нет — вкладки «AI» нет: пульт сам о подсистеме не знает. */
    protected open fun aiIntent(): Intent? = null

    override fun onDestroy() {
        authSignal?.cancel()
        if (::pool.isInitialized) pool.shutdownNow()
        super.onDestroy()
    }

    private fun open() {
        val state = (repo.state() as? Outcome.Success)?.value
        val hasToken = secrets.exists()
        val feed = (repo.feed() as? Outcome.Success)?.value.orEmpty()
        val history = (repo.history() as? Outcome.Success)?.value.orEmpty()
        view.value = PultView(state, "", hasToken, busy = false, feed = feed, names = repo.names(), history = history)
        setContent {
            PultTheme {
                PultApp(view.value, loader, onSync = ::sync, onSaveToken = ::saveToken, onForgetToken = ::forgetToken, onAi = aiIntent()?.let { i -> { startActivity(i) } }, onExport = ::export)
            }
        }
    }

    /**
     * Экспорт истории за срок (F-36): CSV или JSON, место выбирает человек
     * (системный выбор файла — «Загрузки», облако); сеть приложение не трогает.
     */
    private fun export(json: Boolean, period: Stats.Period) {
        val s = view.value.state ?: return
        val points = Export.points(view.value.history, period, s.asOf)
        pendingExport = if (json) Export.json(points, period, s.asOf) else Export.csv(points)
        val name = Export.fileName(period, s.asOf, if (json) "json" else "csv")
        if (json) saveJson.launch(name) else saveCsv.launch(name)
    }

    private fun writeExport(uri: Uri?) {
        val text = pendingExport
        pendingExport = null
        if (uri == null) return
        if (text == null || pool.isShutdown) {
            view.value = view.value.copy(status = "Экспорт не сохранён: экран пересоздан — повторите.")
            return
        }
        pool.execute {
            val ok = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("нет потока") }.isSuccess
            runOnUiThread { view.value = view.value.copy(status = if (ok) "Экспорт сохранён." else "Экспорт не сохранён: файл не записан.") }
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

        override fun directory(project: String, dir: String, done: (Outcome<List<GitHub.Entry>>) -> Unit) = run({ repo.directory(it, project, dir) }, done)

        override fun document(project: String, path: String, done: (Outcome<String>) -> Unit) = run({ repo.document(it, project, path) }, done)

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
        view.value = view.value.copy(hasToken = r is Outcome.Success || secrets.exists(), status = if (r is Outcome.Success) "Токен сохранён." else "Токен не сохранён: ${r.diagnostics.first().message}")
    }

    private fun forgetToken() {
        secrets.forget()
        view.value = view.value.copy(hasToken = false, status = "Токен удалён.")
    }

    private fun sync() = withToken { token -> if (token != null) sync(token) }

    private fun sync(token: Secret) {
        if (pool.isShutdown) return
        view.value = view.value.copy(busy = true, status = "")
        pool.execute {
            val r = repo.sync(token)
            val state = (repo.state() as? Outcome.Success)?.value
            runOnUiThread {
                view.value = when (r) {
                    is Outcome.Success -> view.value.copy(
                        state = state,
                        busy = false,
                        status = "Готово: ${r.value.line}.",
                        feed = (repo.feed() as? Outcome.Success)?.value.orEmpty(),
                        names = repo.names(),
                        history = (repo.history() as? Outcome.Success)?.value.orEmpty(),
                    )
                    is Outcome.Failure -> view.value.copy(busy = false, status = "Не вышло: ${r.diagnostics.first().code.name} — ${r.diagnostics.first().message}")
                }
            }
        }
    }

    companion object {
        /** Запросы дат коммитов — пулом (D-50); тот же предел, что у `dpmeta-cli`. */
        const val THREADS = 8
    }
}

package com.dpsmpx.dpmeta.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.data.PultRepository
import com.dpsmpx.dpmeta.app.data.SecretStore
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpmeta.app.ui.Loader
import com.dpsmpx.dpmeta.app.ui.PultApp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        pool = Executors.newFixedThreadPool(THREADS)
        repo = PultRepository(NioVfs(filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM, pool)
        secrets = SecretStore(NioVfs(noBackupFilesDir.toPath().resolve("dpmeta")))
        Lock.ask(this, onOpen = ::open, onDenied = { finish() })
    }

    /** Вход в подсистему «AI» (D-53); нет — вкладки «AI» нет: пульт сам о подсистеме не знает. */
    protected open fun aiIntent(): Intent? = null

    override fun onDestroy() {
        if (::pool.isInitialized) pool.shutdownNow()
        super.onDestroy()
    }

    private fun open() {
        val state = (repo.state() as? Outcome.Success)?.value
        val hasToken = (secrets.load() as? Outcome.Success)?.value != null
        val feed = (repo.feed() as? Outcome.Success)?.value.orEmpty()
        view.value = PultView(state, "", hasToken, busy = false, feed = feed, names = repo.names())
        setContent {
            PultTheme {
                PultApp(view.value, loader, onSync = ::sync, onSaveToken = ::saveToken, onForgetToken = ::forgetToken, onAi = aiIntent()?.let { i -> { startActivity(i) } })
            }
        }
    }

    /** Каталог и файл — в фоне, итог — в главном потоке; без токена — понятный отказ. */
    private val loader = object : Loader {
        private fun <T> run(work: (Secret) -> Outcome<T>, done: (Outcome<T>) -> Unit) {
            val token = (secrets.load() as? Outcome.Success)?.value
                ?: return done(Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "нет токена GitHub или нужен новый вход", "github"))
            pool.execute {
                val r = work(token)
                runOnUiThread {
                    done(r)
                    view.value = view.value.copy(names = repo.names())
                }
            }
        }

        override fun directory(project: String, dir: String, done: (Outcome<List<GitHub.Entry>>) -> Unit) = run({ repo.directory(it, project, dir) }, done)

        override fun document(project: String, path: String, done: (Outcome<String>) -> Unit) = run({ repo.document(it, project, path) }, done)
    }

    private fun saveToken(text: String) {
        val r = secrets.save(Secret(text))
        view.value = view.value.copy(hasToken = r is Outcome.Success, status = if (r is Outcome.Success) "Токен сохранён." else "Токен не сохранён: ${r.diagnostics.first().message}")
    }

    private fun forgetToken() {
        secrets.clear()
        view.value = view.value.copy(hasToken = false, status = "Токен удалён.")
    }

    private fun sync() {
        val token = (secrets.load() as? Outcome.Success)?.value ?: return
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

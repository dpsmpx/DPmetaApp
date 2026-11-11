package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.tools.SharedTools
import com.dpsmpx.dpai.execution.tools.SharedToolsSync
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.minutes

/** Общая папка target «в приложении». Git, проверка и lease — у единственного POSIX-контроллера (D-125). */
object SharedVerify {
    private const val PREFS = "dpai.shared-verify"
    private const val BASE = "/root/.dpmetaapp-verify"
    private val tools = SharedTools("/root/dp-verify", "$BASE/runtime.py")
    private val note = MutableStateFlow("")
    val state: StateFlow<String> = note
    private var lastAttempt = Long.MIN_VALUE

    fun available(context: Context): Boolean = (context.applicationContext as? DpaiApplication)?.sharedToolsAccountAvailable == true
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("automatic", false)
    fun automatic(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("automatic", value).apply()
        if (value) AiBackground.ensureService(context)
    }

    fun connected(context: Context): Boolean =
        (LinuxSetup.sharedToolsStore(context).read() as? Outcome.Success)?.value == tools

    fun refresh(context: Context) {
        note.value = prefs(context).getString("note", "Папка ещё не подключена").orEmpty()
    }

    /** Не ждёт 15-минутную проверку в такте контроллера: отдельная долгая операция. */
    @Synchronized
    fun tick(context: Context) {
        if (!available(context) || !enabled(context)) return
        val now = Clock.SYSTEM.monotonicNanos()
        if (lastAttempt != Long.MIN_VALUE && now - lastAttempt < 60_000_000_000L) return
        if (LongJobs.get(context).busy(*LongJobs.GUEST.toTypedArray())) return
        lastAttempt = now
        // Не запускаем Git и не спрашиваем PIN из фона. Повтор — после следующего входа.
        val account = (context.applicationContext as DpaiApplication).sharedToolsAccount()
        if (account !is Outcome.Success || account.value == null) {
            publish(context, "Ожидается вход в приложение и подключённый GitHub-аккаунт")
            return
        }
        start(context, install = false)
    }

    fun start(context: Context, install: Boolean): Boolean {
        val app = context.applicationContext
        return LongJobs.get(app).start(LongJobs.SHARED_VERIFY, "DP-Verify", excludes = LongJobs.GUEST) {
            val result = synchronize(app, install)
            publish(app, result.note)
            result
        }
    }

    fun disconnect(context: Context): Boolean = LongJobs.get(context).start(
        LongJobs.SHARED_VERIFY, "DP-Verify", excludes = LongJobs.GUEST,
    ) {
        val result = LinuxSetup.sharedToolsStore(context).disable()
        val done = if (result is Outcome.Success) {
            automatic(context, false)
            LongJobs.Done("Подключение к новым ИИ-ходам выключено; папка и история сохранены")
        } else LongJobs.Done("Не удалось отключить общие инструменты", failed = true)
        publish(context, done.note)
        done
    }

    private fun synchronize(context: Context, install: Boolean): LongJobs.Done {
        val store = LinuxSetup.sharedToolsStore(context)
        val current = when (val r = store.read()) {
            is Outcome.Failure -> return LongJobs.Done("Настройка общих инструментов не прочитана", failed = true)
            is Outcome.Success -> r.value
        }
        if (current != null && current != tools) return LongJobs.Done("У target уже настроена другая общая папка; измените её через dpai tools", failed = true)
        val host = LinuxSetup.guestHost(context) ?: return LongJobs.Done("Сначала установите Linux в приложении", failed = true)
        if (install) {
            when (val r = LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, listOf("python3", "git", "ca-certificates"))) {
                is Outcome.Failure -> return LongJobs.Done("Не удалось установить Python и Git", failed = true)
                is Outcome.Success -> if (!r.value.ok) return LongJobs.Done("Не удалось установить Python и Git; проверьте сеть и свободное место", failed = true)
            }
        }
        val token = when (val r = (context.applicationContext as DpaiApplication).sharedToolsAccount()) {
            is Outcome.Failure -> return LongJobs.Done("Нужен вход в приложение по PIN или биометрии", failed = true)
            is Outcome.Success -> r.value ?: return LongJobs.Done("Сначала подключите GitHub-аккаунт в пульте", failed = true)
        }
        val files = NioVfs(File(LinuxSetup.layout(context).rootfs, BASE.removePrefix("/")).toPath())
        files.mkdirs("")
        val source = context.assets.open("shared-verify/runtime.py").use { it.readBytes() }
        // Новые запуски видят целую версию; уже запущенный Python исполняет прочитанный код.
        files.write("runtime.tmp", source)
        files.fsync("runtime.tmp")
        files.rename("runtime.tmp", "runtime.py")
        files.fsyncDir("")
        val first = !File(LinuxSetup.layout(context).rootfs, tools.repository.removePrefix("/") + "/.git").exists()
        val spec = SharedToolsSync.request(tools, "$BASE/state", first, token)
        val completed = when (val r = host.run(spec, 30.minutes, maxStdoutChars = 4096)) {
            is Outcome.Failure -> return LongJobs.Done("Синхронизация не завершена; проверьте Linux и журнал приложения", failed = true)
            is Outcome.Success -> r.value
        }
        val json = (JsonParser().parse(completed.stdout.lastOrNull().orEmpty()) as? Outcome.Success)?.value as? JsonObject
        fun field(key: String) = (json?.get(key) as? JsonString)?.value
        if (!completed.ok) {
            val reason = when (field("reason")) {
                "tools_in_use" -> "Инструменты заняты; обновление отложено до паузы"
                "validation_failed" -> "Проверка версии не прошла; подробности в $BASE/state/validation.log"
                "local_remote_changes", "diverged" -> "Копии разошлись; обе сохранены, нужно разобрать изменения"
                "author_identity_required" -> "В Git не задан автор коммитов"
                "unexpected_remote" -> "Адрес Git отличается от официального DP-Verify; синхронизация остановлена"
                else -> "Синхронизация не выполнена; проверьте сеть, права аккаунта и состояние Git"
            }
            return LongJobs.Done(reason, failed = completed.exitCode != 75)
        }
        val revision = field("revision")?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            ?: return LongJobs.Done("Контроллер не вернул проверенную версию", failed = true)
        when (store.write(tools)) {
            is Outcome.Failure -> return LongJobs.Done("Версия проверена, но подключение к ИИ не сохранено", failed = true)
            is Outcome.Success -> Unit
        }
        if (install) automatic(context, true)
        return LongJobs.Done("Проверенная версия ${revision.take(12)}; подключена к новым ИИ-ходам")
    }

    private fun publish(context: Context, text: String) {
        note.value = text
        prefs(context).edit().putString("note", text).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

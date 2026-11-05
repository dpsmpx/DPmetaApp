package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.accounts.ApiPresets
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ApiProfiles
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Сервер локальной модели на телефоне (D-105): `llama-server` в Linux
 * приложения, только на `127.0.0.1`. Живёт, пока жив процесс приложения;
 * «Остановить» или выход процесса гасят его. Запущен — есть профиль
 * «Телефон: llama.cpp» (D-98): к нему ходят чат и подсказки локального ИИ.
 */
object LocalModel {
    /** Состояние для экрана: идёт ли сервер, какая модель, хвост вывода, причина отказа. */
    data class Status(val running: Boolean = false, val model: String? = null, val port: Int = ApiPresets.PHONE_LLAMA_PORT, val tail: List<String> = emptyList(), val problem: String? = null)

    private const val TAIL = 40
    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state

    @Volatile
    private var process: RunningProcess? = null

    /** Установка `llama-server` (D-105): итог — строка для экрана. Блокирующий вызов — с фонового потока. */
    fun install(context: Context): String = when (val r = LinuxSetup.run(context, LinuxEnvironment.llamaInstall())) {
        is Outcome.Success -> if (r.value.ok) "llama.cpp: ${(r.value.stdout + r.value.stderr).lastOrNull { it.isNotBlank() } ?: "установлен"}" else "Установка завершилась с кодом ${r.value.exitCode}"
        is Outcome.Failure -> "Установка: ${r.errors.first().message}"
    }

    /**
     * Файлы GGUF в каталогах гостя, откуда сервер берёт модель
     * (`LinuxEnvironment.MODEL_DIRS`): путь — как его видит гость. Глубина —
     * до трёх уровней, ссылки не идут (D-99).
     */
    fun models(context: Context): List<String> {
        val out = mutableListOf<String>()
        for (dir in LinuxEnvironment.MODEL_DIRS) {
            // Гостевой /root — домашняя папка Linux в данных приложения; общее хранилище — только при «всех файлах» (D-99).
            val host = when {
                dir.startsWith(LinuxSetup.GUEST_SHARED) -> if (AllFiles.active(context)) AllFiles.root() else null
                else -> File(GuestPaths.home(context), dir.removePrefix("/root/"))
            }
            if (host == null || !host.isDirectory) continue
            host.walkTopDown().maxDepth(3).onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && it.name.endsWith(".gguf") }
                .forEach { f -> out += dir.trimEnd('/') + "/" + f.relativeTo(host).path }
        }
        return out.sorted().take(50)
    }

    /** Запустить сервер с моделью [model] (путь гостя); прежний — остановить. Итог — текст отказа или `null`. */
    @Synchronized
    fun start(context: Context, model: String, port: Int = ApiPresets.PHONE_LLAMA_PORT): String? {
        stop()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val spec = when (val s = LinuxEnvironment.llamaServe(model, port, CONTEXT_TOKENS, threads)) {
            is Outcome.Failure -> return s.errors.first().message
            is Outcome.Success -> s.value
        }
        val host = LinuxSetup.guestHost(context) ?: return "Linux в приложении не установлен"
        val tail = ArrayDeque<String>()
        val p = when (val r = host.start(spec) { line ->
            synchronized(tail) {
                tail.addLast(line.text)
                while (tail.size > TAIL) tail.removeFirst()
                state.value = state.value.copy(tail = tail.toList())
            }
        }) {
            is Outcome.Failure -> return r.errors.first().message.also { state.value = Status(problem = it) }
            is Outcome.Success -> r.value
        }
        process = p
        state.value = Status(running = true, model = model, port = port)
        ModelsStore.init(context)
        return profile(model, port)?.let { why -> "сервер запущен, но профиль не сохранён: $why" }
    }

    /** Остановить сервер, если идёт. */
    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        runCatching { p.stop() }
        state.value = state.value.copy(running = false)
    }

    /** Идёт ли сервер на самом деле: процесс мог выйти сам (нехватка памяти, плохой файл). */
    fun alive(): Boolean = process?.isAlive == true

    /**
     * Профиль «Телефон: llama.cpp» (D-98): OpenAI-совместимый, свой адрес
     * этого устройства, без ключа; модель — имя файла (сервер держит одну).
     */
    private fun profile(model: String, port: Int): String? {
        val name = ApiPresets.phoneLlama(port).name
        val existing: ApiProfile? = ModelsStore.profiles.value.firstOrNull { it.name == name }
        val id = existing?.id ?: ModelsStore.newProfileId()
        val modelName = File(model).nameWithoutExtension.replace(Regex("[^A-Za-z0-9._:/@+-]"), "_").take(ApiProfiles.MAX_MODEL)
        val p = when (val f = ApiProfiles.form(id, name, ProviderKind.OPENAI_COMPATIBLE, ApiPresets.phoneLlama(port).baseUrl, modelName, null, true)) {
            is Outcome.Failure -> return f.errors.first().message
            is Outcome.Success -> f.value
        }
        return ModelsStore.saveProfile(p, null)
    }

    /** Окно контекста сервера, токенов: на телефоне — умеренное, память — не бесконечная. */
    private const val CONTEXT_TOKENS = 4096

    /** Отказ для подсказок, когда сервера нет. */
    val NOT_RUNNING = DiagCode.PROVIDER_UNAVAILABLE
}

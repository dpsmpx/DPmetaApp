package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.accounts.ApiPresets
import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ApiProfiles
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.providers.ProviderHosts
import com.dpsmpx.dpai.providers.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

/**
 * Сервер локальной модели на телефоне (D-105, T18): `llama-server` в Linux
 * приложения на `127.0.0.1` — это все приложения телефона, поэтому у каждого
 * запуска — случайный ключ API сервера (файлом с правами владельца) и
 * свободный порт. Живёт, пока жив процесс приложения. Свой профиль
 * «Телефон: llama.cpp» — по сохранённому id, с ключом этого запуска;
 * сохраняется только после того, как сервер начал слушать.
 */
object LocalModel {
    /** Состояние для экрана: идёт ли сервер, какая модель, порт, хвост вывода, причина отказа. */
    data class Status(val running: Boolean = false, val model: String? = null, val port: Int? = null, val tail: List<String> = emptyList(), val problem: String? = null)

    private const val TAIL = 40
    private const val PREFS = "localmodel"
    private const val KEY_PROFILE = "profile"

    /** Окно контекста сервера, токенов: на телефоне — умеренное (память); подсказкам хватает (`LocalAI.Limits`). */
    private const val CONTEXT_TOKENS = 8192

    /** Сколько ждать, пока сервер загрузит модель и начнёт слушать. */
    private const val START_SECONDS = 180L

    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state

    @Volatile
    private var process: RunningProcess? = null

    /** Поколение запуска: вывод и выход прежнего процесса не трогают состояние нового. */
    @Volatile
    private var generation = 0L

    /** Установка `llama-server` (D-105): итог — строка для экрана. Блокирующий вызов — с фонового потока. */
    fun install(context: Context): String = when (val r = LinuxSetup.runSteps(context, LinuxEnvironment.llamaInstall())) {
        is Outcome.Success -> if (r.value.ok) "llama.cpp: ${(r.value.stdout + r.value.stderr).lastOrNull { it.isNotBlank() } ?: "установлен"}" else "Установка завершилась с кодом ${r.value.exitCode}"
        is Outcome.Failure -> "Установка: ${r.errors.first().message}"
    }

    /**
     * Файлы GGUF в каталогах гостя, откуда сервер берёт модель
     * (`LinuxEnvironment.MODEL_DIRS`), с путём, который сервер примет. Глубина
     * — до трёх уровней; символьные ссылки — ни каталоги, ни файлы.
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
                .filter { it.isFile && it.name.endsWith(".gguf") && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .map { f -> dir.trimEnd('/') + "/" + f.relativeTo(host).path }
                .filter { LinuxEnvironment.modelPathProblem(it) == null }
                .forEach { out += it }
        }
        return out.sorted().take(50)
    }

    /** Профиль сервера телефона — свой, по сохранённому id; чужой профиль с тем же именем — не он. */
    fun ownProfile(context: Context): AccountId? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROFILE, null)?.let { id -> ModelsStore.profiles.value.firstOrNull { it.id.value == id }?.id }

    /** Свой профиль сервера телефона: по id и с адресом этого устройства — перенастроенный человеком уже не он. */
    fun isOwn(context: Context, profile: ApiProfile): Boolean = ownProfile(context) == profile.id && ProviderHosts.isLoopback(profile.host)

    /** Идёт ли сервер на самом деле: процесс мог выйти сам (память, плохой файл). */
    fun alive(): Boolean = state.value.running && process?.isAlive == true

    /**
     * Свой профиль можно спрашивать (T18): сервер идёт и профиль указывает
     * на порт этого запуска; иначе на прежнем порту может слушать кто угодно.
     */
    fun usable(profile: ApiProfile): Boolean = alive() && profile.baseUrl == state.value.port?.let { ApiPresets.phoneLlama(it).baseUrl }

    /**
     * Запустить сервер с моделью [model] (путь гостя); прежний — остановить.
     * Блокирующий вызов — с фонового потока: ждёт, пока сервер ответит своим
     * ключом на `/v1/models` (модель загружена, порт — наш), потом сохраняет
     * свой профиль и только тогда считает сервер готовым (T18). Итог — текст
     * отказа или `null`.
     */
    @Synchronized
    fun start(context: Context, model: String): String? {
        stop()
        LinuxEnvironment.modelPathProblem(model)?.let { return it }
        val host = LinuxSetup.guestHost(context) ?: return "Linux в приложении не установлен"
        val port = freePort() ?: return "свободного порта на этом телефоне не нашлось"
        val key = newKey()
        if (!writeKey(context, key)) return "файл ключа сервера не записан"
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val spec = when (val s = LinuxEnvironment.llamaServe(model, port, CONTEXT_TOKENS, threads)) {
            is Outcome.Failure -> return s.errors.first().message
            is Outcome.Success -> s.value
        }
        val gen = ++generation
        val tail = ArrayDeque<String>()
        // Пробовать порт — только когда свой процесс сказал, что слушает его: иначе ключ уйдёт тому, кто занял порт первым.
        val own = java.util.concurrent.atomic.AtomicBoolean(false)
        state.value = Status(running = false, model = model, port = port)
        val p = when (val r = host.start(spec) { line ->
            if ("listening" in line.text && "$port" in line.text) own.set(true)
            synchronized(tail) {
                tail.addLast(line.text)
                while (tail.size > TAIL) tail.removeFirst()
                val now = tail.toList()
                state.update { if (gen == generation) it.copy(tail = now) else it }
            }
        }) {
            is Outcome.Failure -> return r.errors.first().message.also { why -> state.value = Status(problem = why) }
            is Outcome.Success -> r.value
        }
        process = p
        // Готовность — ответ своим ключом на своём порту; вышел процесс — сразу отказ, без ожидания до конца срока.
        val probe = ApiProfiles.form(ModelsStore.newProfileId(), "проба", ProviderKind.OPENAI_COMPATIBLE, ApiPresets.phoneLlama(port).baseUrl, "probe", null, true)
            .valueOrNull()?.let { Providers.of(it, Secret(key)) }
        val clock = Clock.SYSTEM
        val deadline = clock.monotonicNanos() + START_SECONDS * 1_000_000_000L
        var ready = false
        while (!ready && p.isAlive && clock.monotonicNanos() < deadline) {
            ready = own.get() && probe?.models()?.let { it is Outcome.Success } ?: false
            if (!ready) p.awaitExit(1.seconds)
        }
        if (!ready) {
            stop()
            return if (!p.isAlive) "сервер вышел при запуске — причина в выводе" else "сервер не ответил за $START_SECONDS с — причина в выводе"
        }
        ModelsStore.init(context)
        saveProfile(context, model, port, key)?.let { why ->
            stop()
            return "сервер остановлен: профиль не сохранён — $why"
        }
        state.update { if (gen == generation) it.copy(running = true) else it }
        watchExit(p, gen)
        return null
    }

    /** Остановить сервер, если идёт. */
    @Synchronized
    fun stop() {
        generation++
        val p = process
        process = null
        if (p != null) runCatching { p.stop() }
        state.update { it.copy(running = false) }
    }

    /** Ждать выхода процесса в своём потоке: вышел сам — «не запущен» с причиной в выводе. */
    private fun watchExit(p: RunningProcess, gen: Long) {
        Thread {
            do {
                val code = p.awaitExit(30.seconds)
            } while (code == null)
            synchronized(this) {
                if (gen == generation) {
                    process = null
                    state.update { it.copy(running = false, problem = "сервер вышел — причина в выводе") }
                }
            }
        }.apply { isDaemon = true; name = "llama-server-exit" }.start()
    }

    /**
     * Свой профиль «Телефон: llama.cpp» (D-98): OpenAI-совместимый, адрес
     * этого устройства и порта этого запуска, ключ этого запуска; модель —
     * имя файла (сервер держит одну). Чужие профили не трогаются.
     */
    private fun saveProfile(context: Context, model: String, port: Int, key: String): String? {
        val id = ownProfile(context) ?: ModelsStore.newProfileId()
        val preset = ApiPresets.phoneLlama(port)
        val modelName = File(model).nameWithoutExtension.replace(Regex("[^A-Za-z0-9._:/@+-]"), "_").take(ApiProfiles.MAX_MODEL).ifEmpty { "model" }
        val p = when (val f = ApiProfiles.form(id, preset.name, ProviderKind.OPENAI_COMPATIBLE, preset.baseUrl, modelName, null, true)) {
            is Outcome.Failure -> return f.errors.first().message
            is Outcome.Success -> f.value
        }
        val why = ModelsStore.saveProfile(p, Secret(key))
        if (why == null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROFILE, id.value).apply()
        return why
    }

    /** Свободный порт на `127.0.0.1` (гость Linux делит сеть с приложением): чужой сервер на порту не подменит наш. */
    private fun freePort(): Int? = runCatching { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } }.getOrNull()

    private fun newKey(): String = RandomSource.secure().nextBytes(32).joinToString("") { "%02x".format(it) }

    /**
     * Ключ — файлом гостя, не в аргументах процесса (T18), без следования
     * ссылкам гостя (A-3, `LinuxEnvironment.writeLlamaKey`); диск приложения
     * и вкладка «Файлы» каталог `~/.config/llama` не показывают (D-96).
     */
    private fun writeKey(context: Context, key: String): Boolean =
        LinuxSetup.environment(context).writeLlamaKey(key.toByteArray()) is Outcome.Success
}

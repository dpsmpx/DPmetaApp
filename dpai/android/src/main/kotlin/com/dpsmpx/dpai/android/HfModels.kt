package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.DownloadSink
import com.dpsmpx.dpai.providers.HfFile
import com.dpsmpx.dpai.providers.HfListing
import com.dpsmpx.dpai.providers.HuggingFace
import com.dpsmpx.dpai.providers.UrlTransport
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Модели GGUF с Hugging Face (D-110, D-113, D-119; T21): список файлов модели
 * и загрузка выбранного в `/root/models` Linux-среды — с ходом, отменой,
 * докачкой и проверкой места. Состояние — на процесс, вне экрана: уход с
 * вкладки и поворот не теряют загрузку. Убит процесс — часть `.part`
 * остаётся, «Скачать» продолжит. Токен — только когда модель закрыта
 * (ответ 401 или 403): сначала запрос без него.
 */
object HfModels {
    /** Имя токена в хранилище ключей (`ApiKeys`, Keystore). */
    private const val TOKEN = "huggingface"

    /** Каталог моделей в домашней папке гостя: `/root/models`. */
    private const val DIR = "models"

    /** Запас места сверх файла: Linux-среде и серверу модели тоже нужно место. */
    const val RESERVE: Long = 256L * 1024 * 1024

    /** Ход в состоянии — не чаще, чем через столько байт: строк на гигабайт — сотни, а не тысячи. */
    private const val STEP: Long = 4L * 1024 * 1024

    /** Файл списка: есть ли уже готовый в `/root/models`, сколько есть части. */
    data class Row(val file: HfFile, val present: Boolean, val part: Long)

    /**
     * [busy] — что идёт; [file] — какой файл качается, [have] из [total]; [note] — итог
     * ([failed] — неудача); [needToken] — модель закрыта: нужен токен, и после него —
     * повтор [retry] (путь файла; `null` — список).
     */
    data class State(
        val listing: HfListing? = null,
        val rows: List<Row> = emptyList(),
        val busy: String? = null,
        val file: String? = null,
        val have: Long = 0,
        val total: Long = 0,
        val note: String? = null,
        val failed: Boolean = false,
        val needToken: Boolean = false,
        val retry: String? = null,
        val input: String = "",
    )

    private val flow = MutableStateFlow(State())
    val state: StateFlow<State> = flow

    @Volatile
    private var cancel: Cancel? = null

    fun hasToken(context: Context): Boolean = ApiKeys.hasNamed(context, TOKEN)

    fun saveToken(context: Context, token: String): Boolean = token.isNotBlank() && ApiKeys.putNamed(context, TOKEN, Secret(token.trim()))

    fun forgetToken(context: Context) = ApiKeys.removeNamed(context, TOKEN)

    private fun vfs(context: Context): NioVfs = NioVfs(GuestPaths.home(context).toPath()).also { it.mkdirs(DIR) }

    /** Список файлов GGUF модели [input] (`владелец/имя` или адрес модели). */
    fun list(context: Context, input: String) {
        val app = context.applicationContext
        synchronized(this) {
            if (flow.value.busy != null) return
            flow.value = flow.value.copy(busy = "Читаю список файлов…", note = null, failed = false, needToken = false, input = input)
        }
        Thread {
            val r = withToken(app) { token -> HuggingFace(UrlTransport()).files(input, token) }
            flow.value = when (r) {
                is Outcome.Success -> State(
                    listing = r.value,
                    rows = rows(app, r.value),
                    note = if (r.value.files.isEmpty()) "В модели нет файлов .gguf." else null,
                    input = input,
                )
                is Outcome.Failure -> flow.value.copy(busy = null, note = text(r), failed = true, needToken = needs(r), retry = null)
            }
        }.start()
    }

    /** Скачать [file] из прочитанного списка: проверки до запроса, затем — поток с ходом. */
    fun download(context: Context, file: HfFile) {
        val app = context.applicationContext
        val listing = flow.value.listing ?: return
        val name = file.name
        val guest = "/root/$DIR/$name"
        LinuxEnvironment.modelPathProblem(guest)?.let { return finish("Имя «$name» сервер llama.cpp не примет: $it.", failed = true) }
        val vfs = vfs(app)
        val target = "$DIR/$name"
        val part = "$target.part"
        if (vfs.exists(target)) return finish("Уже скачан: $guest.", failed = false)
        val have = vfs.size(part) ?: 0
        val free = free(app)
        val need = (file.size - have).coerceAtLeast(0)
        if (free < need + RESERVE) {
            return finish("Мало места: нужно ${size(need)} и ${size(RESERVE)} запаса, свободно ${size(free)}.", failed = true)
        }
        val c = Cancel()
        synchronized(this) {
            if (flow.value.busy != null) return
            cancel = c
            flow.value = flow.value.copy(busy = "Скачиваю $name…", file = name, have = have, total = file.size, note = null, failed = false, needToken = false)
        }
        Thread {
            val sink = object : DownloadSink {
                override val size: Long get() = vfs.size(part) ?: 0

                override fun read(offset: Long, length: Int): ByteArray = vfs.readRange(part, offset, length) ?: ByteArray(0)

                override fun append(bytes: ByteArray) = vfs.append(part, bytes)

                override fun reset() {
                    if (vfs.exists(part)) vfs.delete(part)
                }
            }
            var shown = -STEP
            val got = withToken(app) { token ->
                HuggingFace(UrlTransport()).download(listing, file, token, sink, c) { h, t ->
                    if (h - shown >= STEP || h == t) {
                        shown = h
                        flow.value = flow.value.copy(have = h, total = t)
                    }
                }
            }
            val r = when (got) {
                is Outcome.Failure -> got
                is Outcome.Success -> try {
                    // Готовый файл — переименованием: сервер модели не увидит недокачанное под настоящим именем.
                    vfs.fsync(part)
                    vfs.rename(part, target)
                    vfs.fsyncDir(DIR)
                    Outcome.success(Unit)
                } catch (_: IOException) {
                    Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл скачан, но не переименован — «Скачать» повторит", part)
                }
            }
            cancel = null
            AppLogs.info(app, "модели", "Hugging Face ${listing.repo}, $name: " + when (r) {
                is Outcome.Success -> "скачан"
                is Outcome.Failure -> "${r.errors.first().code}"
            })
            flow.value = flow.value.copy(
                busy = null,
                file = null,
                rows = rows(app, listing),
                note = when (r) {
                    is Outcome.Success -> "Скачан: $guest — выберите его в «Найти модели»."
                    is Outcome.Failure -> text(r)
                },
                failed = r is Outcome.Failure,
                needToken = r is Outcome.Failure && needs(r),
                retry = file.path.takeIf { r is Outcome.Failure && needs(r) },
            )
        }.start()
    }

    /** Отмена загрузки человеком: часть удаляется. */
    fun stop() {
        cancel?.cancel()
    }

    /** Повтор после токена: файл, который просили, или список. */
    fun retry(context: Context) {
        val s = flow.value
        val file = s.retry?.let { p -> s.rows.firstOrNull { it.file.path == p }?.file }
        if (file != null) download(context, file) else if (s.input.isNotBlank()) list(context, s.input)
    }

    /**
     * Сколько места можно занять под `/root`: с учётом кэша, который система очистит
     * сама (`StorageManager`); не ответила — свободное по `StatFs`.
     */
    private fun free(context: Context): Long {
        val home = GuestPaths.home(context)
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
        return try {
            sm.getAllocatableBytes(sm.getUuidForPath(home))
        } catch (_: IOException) {
            android.os.StatFs(home.path).availableBytes
        }
    }

    private fun finish(note: String, failed: Boolean) {
        flow.value = flow.value.copy(note = note, failed = failed, needToken = false)
    }

    /** Сначала без токена (D-110); закрыто и токен сохранён — ещё раз с ним. */
    private fun <T> withToken(context: Context, call: (Secret?) -> Outcome<T>): Outcome<T> {
        val first = call(null)
        if (first is Outcome.Failure && needs(first)) {
            val token = ApiKeys.getNamed(context, TOKEN) ?: return first
            return call(token)
        }
        return first
    }

    private fun needs(r: Outcome.Failure): Boolean = r.errors.first().code == DiagCode.PROVIDER_KEY_MISSING

    private fun text(r: Outcome.Failure): String = r.errors.first().message.replaceFirstChar { it.uppercase() } + "."

    private fun rows(context: Context, l: HfListing): List<Row> {
        val vfs = vfs(context)
        return l.files.map { f -> Row(f, vfs.exists("$DIR/${f.name}"), vfs.size("$DIR/${f.name}.part") ?: 0) }
    }

    /** Размер для человека: МиБ целыми, от ГиБ — с десятой. */
    fun size(b: Long): String {
        val mib = 1024L * 1024
        val gib = mib * 1024
        return when {
            b < mib -> "${(b + 1023) / 1024} КиБ"
            b < gib -> "${b / mib} МиБ"
            else -> (b * 10 / gib).let { "${it / 10},${it % 10} ГиБ" }
        }
    }
}

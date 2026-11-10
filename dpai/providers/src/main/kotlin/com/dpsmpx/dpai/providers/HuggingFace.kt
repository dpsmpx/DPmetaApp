package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * Куда идут модели Hugging Face (D-110, D-119; T21): API и `resolve` — только
 * `huggingface.co`, хранилище по переадресации — HTTPS на `huggingface.co`,
 * `hf.co` и их поддомены (хосты хранилища меняются по региону,
 * `docs/research/HUGGING_FACE.md`, §5). Порт — по умолчанию, учётных данных
 * в адресе нет.
 */
object HuggingFaceHosts {
    const val API: String = "huggingface.co"

    private fun clean(url: URI): String? {
        if (url.scheme?.lowercase() != "https" || url.userInfo != null || (url.port != -1 && url.port != 443)) return null
        return url.host?.lowercase()
    }

    /** Адрес API и `resolve`: только `https://huggingface.co`. Токен идёт только сюда. */
    fun api(url: URI): Boolean = clean(url) == API

    /** Адрес хранилища (переадресация): домены Hugging Face и их поддомены. */
    fun storage(url: URI): Boolean {
        val h = clean(url) ?: return false
        return h == API || h == "hf.co" || h.endsWith(".hf.co") || h.endsWith(".huggingface.co")
    }
}

/** Файл модели: путь в репозитории, размер и сумма — SHA-256 файла LFS или SHA-1 git-объекта. */
data class HfFile(val path: String, val size: Long, val sha256: String?, val gitSha1: String?) {
    val name: String get() = path.substringAfterLast('/')
}

/** Список файлов GGUF модели на коммите [revision]; [gated] — файлы закрыты условиями доступа. */
data class HfListing(val repo: String, val revision: String, val gated: Boolean, val files: List<HfFile>)

/**
 * Приёмник скачиваемого файла — часть на диске (D-119): что уже есть, чтение
 * для пересчёта суммы, дописывание, сброс. Реализует вызывающий: запись в
 * файлы — через `Vfs` хранилища (D-24).
 */
interface DownloadSink {
    /** Сколько байт уже есть. */
    val size: Long

    @Throws(IOException::class)
    fun read(offset: Long, length: Int): ByteArray

    @Throws(IOException::class)
    fun append(bytes: ByteArray)

    /** Начать заново: части нет. */
    @Throws(IOException::class)
    fun reset()
}

/**
 * Hugging Face Hub (D-110, D-119): список файлов GGUF модели и скачивание файла
 * с докачкой и сверкой суммы. Сеть — через [Transport] (без своих
 * переадресаций): каждую переадресацию клиент проверяет сам по
 * [HuggingFaceHosts]. Токен — только для закрытой модели, только к
 * `huggingface.co`; без токена закрытая модель — [DiagCode.PROVIDER_KEY_MISSING]
 * («нужен токен»), с отвергнутым — [DiagCode.PROVIDER_AUTH_FAILED].
 */
class HuggingFace(private val transport: Transport) {
    /** Список файлов GGUF модели [repo] (`владелец/имя`): с коммитом, размерами и суммами. */
    fun files(repo: String, token: Secret?, cancel: Cancel = Cancel()): Outcome<HfListing> {
        val r = repoOf(repo) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "репозиторий — «владелец/имя», как в адресе модели на huggingface.co", repo)
        val url = URI("https", HuggingFaceHosts.API, "/api/models/$r", "blobs=true", null)
        val reply = when (val o = open(url, token, emptyMap(), cancel)) {
            is Outcome.Failure -> return o
            is Outcome.Success -> o.value
        }
        return reply.use {
            refusal(it.status, token, r)?.let { f -> return f }
            if (it.status != 200) return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "Hugging Face ответил ${it.status}", r)
            val text = try {
                it.readText(MAX_LIST_CHARS)
            } catch (_: IOException) {
                return Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "список файлов не дочитан", r)
            }
            parse(r, text)
        }
    }

    /**
     * Скачать [file] из [listing] в [sink]: часть, что уже есть, пересчитывается в сумму
     * и докачивается запросом `Range`; сервер прислал файл целиком — заново. [progress] —
     * (сколько есть, сколько всего). Итог — файл целиком и с суммой из списка; сумма не
     * сошлась или байт больше — часть сброшена. Отмена — часть сброшена; обрыв связи —
     * часть остаётся для докачки.
     */
    fun download(
        listing: HfListing,
        file: HfFile,
        token: Secret?,
        sink: DownloadSink,
        cancel: Cancel,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Outcome<Unit> {
        if (file.sha256 == null && file.gitSha1 == null) return Outcome.failure(DiagCode.VALUE_INVALID, "у файла нет суммы — сверить нечем", file.path)
        var have = sink.size
        if (have > file.size) {
            sink.reset()
            have = 0
        }
        var sum = Sum(file)
        // Часть — в сумму заново: процесс мог умереть, сумма в памяти не пережила его.
        var at = 0L
        while (at < have) {
            if (cancel.cancelled) return cancelled(sink, file)
            val chunk = sink.read(at, CHUNK)
            if (chunk.isEmpty()) break
            sum.update(chunk, chunk.size)
            at += chunk.size
        }
        if (at != have) {
            sink.reset()
            have = 0
            sum = Sum(file)
        }
        progress(have, file.size)
        if (have < file.size) {
            val got = fetch(listing, file, token, sink, have, sum, cancel, progress)
            when (got) {
                is Outcome.Failure -> return got
                is Outcome.Success -> {
                    have = got.value.first
                    sum = got.value.second
                }
            }
        }
        if (have != file.size) return Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "связь оборвалась на ${have} из ${file.size} байт — «Скачать» продолжит", file.path)
        if (!sum.matches()) {
            sink.reset()
            return Outcome.failure(DiagCode.HASH_MISMATCH, "сумма файла не совпала со сведениями Hugging Face — файл удалён", file.path)
        }
        return Outcome.success(Unit)
    }

    /** Запрос файла с переадресациями; итог — сколько байт есть и сумма. */
    private fun fetch(
        listing: HfListing,
        file: HfFile,
        token: Secret?,
        sink: DownloadSink,
        start: Long,
        startSum: Sum,
        cancel: Cancel,
        progress: (Long, Long) -> Unit,
    ): Outcome<Pair<Long, Sum>> {
        var have = start
        var sum = startSum
        var url = URI("https", HuggingFaceHosts.API, "/${listing.repo}/resolve/${listing.revision}/${file.path}", null)
        var hops = 0
        while (true) {
            val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
            val reply = when (val o = open(url, token, headers, cancel)) {
                is Outcome.Failure -> return o
                is Outcome.Success -> o.value
            }
            reply.use { r ->
                if (r.status in REDIRECTS) {
                    val next = r.header("Location")?.let { loc ->
                        try {
                            url.resolve(URI(loc))
                        } catch (_: URISyntaxException) {
                            null
                        }
                    } ?: return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "переадресация без адреса", file.path)
                    if (++hops > MAX_REDIRECTS) return Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "больше $MAX_REDIRECTS переадресаций", file.path)
                    if (!HuggingFaceHosts.storage(next)) return Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "переадресация не на хранилище Hugging Face", next.host ?: "$next")
                    url = next
                    return@use
                }
                refusal(r.status, token, file.path)?.let { return it }
                when (r.status) {
                    200 -> if (have > 0) {
                        // Сервер прислал файл целиком — часть не продолжается, а пишется заново.
                        sink.reset()
                        have = 0
                        sum = Sum(file)
                    }
                    206 -> {
                        val from = r.header("Content-Range")?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                        if (from != have) return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "часть пришла не с того места", file.path)
                    }
                    else -> return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "Hugging Face ответил ${r.status}", file.path)
                }
                val buf = ByteArray(READ)
                val block = java.io.ByteArrayOutputStream(BLOCK)
                fun flush() {
                    if (block.size() == 0) return
                    sink.append(block.toByteArray())
                    block.reset()
                    progress(have, file.size)
                }
                try {
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (have + n > file.size) {
                            sink.reset()
                            return Outcome.failure(DiagCode.HASH_MISMATCH, "файл больше, чем в сведениях Hugging Face — удалён", file.path)
                        }
                        sum.update(buf, n)
                        block.write(buf, 0, n)
                        have += n
                        if (block.size() >= BLOCK) flush()
                    }
                    flush()
                } catch (e: IOException) {
                    if (cancel.cancelled) return cancelled(sink, file)
                    // Дописанное — на диске: «Скачать» продолжит с этого места.
                    runCatching { flush() }
                    return Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "связь оборвалась на ${sink.size} из ${file.size} байт — «Скачать» продолжит", file.path)
                }
                if (cancel.cancelled) return cancelled(sink, file)
                return Outcome.success(have to sum)
            }
        }
    }

    private fun cancelled(sink: DownloadSink, file: HfFile): Outcome.Failure {
        runCatching { sink.reset() }
        return Outcome.failure(DiagCode.CANCELLED_BY_USER, "скачивание отменено — часть удалена", file.path)
    }

    /** Запрос: токен — только если адрес — `huggingface.co` (D-119). */
    private fun open(url: URI, token: Secret?, headers: Map<String, String>, cancel: Cancel): Outcome<HttpReply> {
        if (!HuggingFaceHosts.api(url) && !HuggingFaceHosts.storage(url)) {
            return Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "адрес не Hugging Face", url.host ?: "$url")
        }
        val secret = token?.takeIf { HuggingFaceHosts.api(url) }?.let { SecretHeader("Authorization", "Bearer ", it) }
        return transport.open(HttpCall("GET", url, headers + ("Accept" to "application/json, */*"), null, secret), cancel)
    }

    /** 401/403: без токена — нужен токен (не ошибка, D-110); с токеном — не принят. 404, 429, 5xx — своими кодами. */
    private fun refusal(status: Int, token: Secret?, subject: String): Outcome.Failure? = when (status) {
        401, 403 -> if (token == null) {
            Outcome.failure(DiagCode.PROVIDER_KEY_MISSING, "модель закрыта или её нет — нужен токен Hugging Face (только чтение)", subject)
        } else {
            Outcome.failure(DiagCode.PROVIDER_AUTH_FAILED, "токен Hugging Face не открыл модель: проверьте токен и примите условия на странице модели", subject)
        }
        404 -> Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "на Hugging Face такого нет", subject)
        429 -> Outcome.failure(DiagCode.PROVIDER_RATE_LIMITED, "Hugging Face просит подождать (429)", subject)
        in 500..599 -> Outcome.failure(DiagCode.PROVIDER_UNAVAILABLE, "Hugging Face недоступен ($status)", subject)
        else -> null
    }

    private fun parse(repo: String, text: String): Outcome<HfListing> {
        val obj = JsonParser().parse(text).valueOrNull() as? JsonObject
            ?: return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "ответ Hugging Face — не JSON", repo)
        val sha = (obj["sha"] as? JsonString)?.value?.takeIf { SHA1.matches(it) }
            ?: return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "в ответе нет коммита модели", repo)
        val gated = when (val g = obj["gated"]) {
            is JsonBool -> g.value
            is JsonString -> g.value.isNotEmpty() && g.value != "false"
            else -> false
        }
        val siblings = obj["siblings"] as? JsonArray ?: return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "в ответе нет списка файлов", repo)
        val files = siblings.items.mapNotNull { v ->
            val f = v as? JsonObject ?: return@mapNotNull null
            val path = (f["rfilename"] as? JsonString)?.value ?: return@mapNotNull null
            if (!path.lowercase().endsWith(".gguf") || !safePath(path)) return@mapNotNull null
            val size = (f["size"] as? JsonNumber)?.toLongExact()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val lfs = f["lfs"] as? JsonObject
            val sha256 = (lfs?.get("sha256") as? JsonString)?.value?.lowercase()?.takeIf { SHA256.matches(it) }
            val blob = (f["blobId"] as? JsonString)?.value?.lowercase()?.takeIf { SHA1.matches(it) }
            // Файл LFS без своей суммы — git-объект у него указатель, а не модель: сверить нечем.
            HfFile(path, size, sha256, if (lfs == null) blob else null)
        }.sortedBy { it.path }
        return Outcome.success(HfListing(repo, sha, gated, files))
    }

    /** Сумма файла: SHA-256 (LFS) или SHA-1 git-объекта — `blob <размер>\0` и содержимое. */
    private class Sum(private val file: HfFile) {
        private val md: MessageDigest = if (file.sha256 != null) {
            MessageDigest.getInstance("SHA-256")
        } else {
            MessageDigest.getInstance("SHA-1").apply { update("blob ${file.size}\u0000".toByteArray(Charsets.US_ASCII)) }
        }

        fun update(buf: ByteArray, n: Int) = md.update(buf, 0, n)

        fun matches(): Boolean {
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            return hex == (file.sha256 ?: file.gitSha1)
        }
    }

    companion object {
        private const val MAX_LIST_CHARS = 4 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val READ = 64 * 1024
        private const val BLOCK = 1024 * 1024
        private const val CHUNK = 4 * 1024 * 1024
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val SHA1 = Regex("[0-9a-f]{40}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val PART = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")

        /**
         * Репозиторий из ввода человека: `владелец/имя` или адрес модели
         * `https://huggingface.co/владелец/имя[/…]`; иначе — `null`.
         */
        fun repoOf(input: String): String? {
            val t = input.trim().removePrefix("https://").removePrefix("huggingface.co/").removePrefix("hf.co/")
            val parts = t.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val (owner, name) = parts[0] to parts[1]
            if (!PART.matches(owner) || !PART.matches(name) || ".." in owner || ".." in name) return null
            if (parts.size > 2 && parts[2] !in setOf("tree", "blob", "resolve")) return null
            return "$owner/$name"
        }

        /** Путь файла в репозитории: относительный, без «..», без управляющих знаков. */
        fun safePath(p: String): Boolean =
            p.length in 1..512 && !p.startsWith("/") && p.split('/').none { it.isEmpty() || it == "." || it == ".." } && p.none { it.isISOControl() || it == '\\' }
    }
}

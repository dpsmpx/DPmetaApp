package com.dpsmpx.dpmeta.net

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import java.net.URI

/** Запрос: метод, адрес, заголовки и тело; секрет — только [auth], не в заголовках. */
class HttpRequest(
    val method: String,
    val url: URI,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val auth: Secret? = null,
)

/** Ответ: код, заголовки (имена — в нижнем регистре), тело. */
class HttpResponse(val status: Int, headers: Map<String, List<String>>, val body: ByteArray) {
    val headers: Map<String, List<String>> = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val text: String get() = body.toString(Charsets.UTF_8)
}

/**
 * Транспорт — граница с сетью: сбой соединения — `NETWORK_UNREACHABLE`,
 * любой полученный ответ (и 4xx, и 5xx) — успех с кодом. Переадресации не
 * выполняются: адрес назначения прошёл бы мимо списка разрешённых.
 */
fun interface Transport {
    fun send(request: HttpRequest): Outcome<HttpResponse>
}

/**
 * Список разрешённых адресов (ТЗ, §6.8; D-10). Только HTTPS; HTTP — только
 * для адресов [plainHttp] и без секрета (исключение на время для сайта,
 * A-09).
 */
class AllowList(val hosts: Set<String>, val plainHttp: Set<String> = emptySet()) {
    fun check(request: HttpRequest): Outcome<Unit> {
        val host = request.url.host?.lowercase()
        val scheme = request.url.scheme?.lowercase()
        return when {
            host == null || host !in hosts -> Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "адреса нет в списке разрешённых", host ?: request.url.toString())
            request.url.userInfo != null -> Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "учётные данные в адресе запрещены", host)
            scheme == "https" -> Outcome.success(Unit)
            scheme == "http" && host in plainHttp && request.auth == null -> Outcome.success(Unit)
            scheme == "http" && host in plainHttp -> Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "секрет по http не отправляется", host)
            else -> Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "разрешён только https", host)
        }
    }

    companion object {
        /** Адреса пульта: GitHub и API ИИ; личных сайтов автора нет (D-176). */
        val CORE: AllowList = AllowList(
            hosts = setOf("api.github.com", "github.com", "api.anthropic.com"),
        )

    }
}

/** Сохранённый ответ для условного запроса: ETag и тело. */
class CachedResponse(val etag: String, val body: ByteArray, val link: String? = null) {
    val usable: Boolean get() = printable(etag)
}

/**
 * Кэш условных запросов (ТЗ, §6.4, N-03). Ключ — адрес и отпечаток секрета:
 * ответ, полученный с одним токеном, не отдаётся другому.
 */
interface EtagCache {
    fun get(key: String): CachedResponse?

    fun put(key: String, value: CachedResponse)
}

class MemoryEtagCache : EtagCache {
    private val map = LinkedHashMap<String, CachedResponse>()

    @Synchronized
    override fun get(key: String): CachedResponse? = map[key]

    @Synchronized
    override fun put(key: String, value: CachedResponse) {
        map[key] = value
    }

    val keys: Set<String> @Synchronized get() = map.keys.toSet()
}

/**
 * Итог запроса через [Net]: [notModified] — ответ 304 и тело из кэша;
 * [rawSha256] — отпечаток тела (для наблюдения, ТЗ, §6.3).
 */
class Fetched(val status: Int, val body: ByteArray, val notModified: Boolean, val headers: Map<String, List<String>>) {
    val text: String get() = body.toString(Charsets.UTF_8)
    val rawSha256: ContentHash get() = Hashing.sha256(body)

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/** Счёт запросов прогона: для бюджета и для замера A-02. */
data class NetStats(val requests: Int, val notModified: Int, val refused: Int)

/**
 * Сеть приложения (ТЗ, §6.8): каждый запрос — через список разрешённых
 * адресов и бюджет прогона; GET — условный, если в кэше есть ETag.
 *
 * Бюджет — число запросов на один прогон синхронизации: исчерпан —
 * `RUN_BUDGET_EXHAUSTED`, запрос не уходит (остальное — в следующий раз,
 * ТЗ, §6.4).
 *
 * Можно звать из нескольких потоков (даты коммитов — пулом): бюджет и
 * счётчики — под замком, сам запрос — вне его.
 */
class Net(
    private val transport: Transport,
    private val allow: AllowList = AllowList.CORE,
    private val cache: EtagCache = MemoryEtagCache(),
    private val budget: Int = DEFAULT_BUDGET,
) {
    private val lock = Any()
    private var requests = 0
    private var notModified = 0
    private var refused = 0

    val stats: NetStats get() = synchronized(lock) { NetStats(requests, notModified, refused) }

    val remaining: Int get() = synchronized(lock) { budget - requests }

    /** [store] = false — ответ не кладётся в кэш (файлы кода, F-09: «без сохранения в кэше»). */
    fun get(url: URI, headers: Map<String, String> = emptyMap(), auth: Secret? = null, store: Boolean = true): Outcome<Fetched> {
        val key = cacheKey(url, auth)
        // K-05: испорченный ETag не превращается в навсегда неверный заголовок запроса.
        val cached = if (store) cache.get(key)?.takeIf { it.usable } else null
        val h = if (cached != null) headers + ("If-None-Match" to cached.etag) else headers
        val r = when (val s = send(HttpRequest("GET", url, h, null, auth))) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        if (r.status == 304 && cached != null) {
            synchronized(lock) { notModified++ }
            // GitHub может не повторять Link в ответе 304: список всё равно должен быть полным.
            val responseHeaders = if (r.header("link") == null && cached.link != null) r.headers + ("link" to listOf(cached.link)) else r.headers
            return Outcome.success(Fetched(200, cached.body, true, responseHeaders))
        }
        val etag = r.header("etag")
        if (store && r.status == 200 && etag != null && printable(etag)) {
            cache.put(key, CachedResponse(etag, r.body, r.headers["link"]?.joinToString(",")))
        }
        return Outcome.success(Fetched(r.status, r.body, false, r.headers))
    }

    /** Запрос без кэша (POST и прочее): тот же список адресов и бюджет. */
    fun send(request: HttpRequest): Outcome<HttpResponse> {
        allow.check(request).let { if (it is Outcome.Failure) return it.also { synchronized(lock) { refused++ } } }
        synchronized(lock) {
            if (requests >= budget) {
                refused++
                return Outcome.failure(DiagCode.RUN_BUDGET_EXHAUSTED, "бюджет запросов прогона исчерпан ($budget)", request.url.host)
            }
            requests++
        }
        return transport.send(request)
    }

    private fun cacheKey(url: URI, auth: Secret?): String =
        if (auth == null) url.toString() else url.toString() + "#" + Auth.fingerprint(auth)

    companion object {
        /** Предел A-02: полная синхронизация 15 репозиториев — не больше 300 запросов. */
        const val DEFAULT_BUDGET = 300
    }
}

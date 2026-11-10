package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Расход за день по модели (F-19): токены по видам; `null` — Anthropic не сообщил (не ноль, D-13). */
data class UsageRow(
    val day: String,
    val model: String?,
    val uncachedInput: Long?,
    val cacheWrite: Long?,
    val cacheRead: Long?,
    val output: Long?,
    val requests: Long?,
)

/** Стоимость за день по модели (F-19): в долларах, после скидок; `null` — не сообщена. */
data class CostRow(val day: String, val model: String?, val usd: BigDecimal?)

/** Лимиты скорости группы моделей (F-19): запросы и токены в минуту. */
data class RateLimitRow(val group: String, val models: List<String>, val requestsPerMinute: Long?, val inputPerMinute: Long?, val outputPerMinute: Long?)

/** Отчёт: строки и время, до которого данные полны (`data_refreshed_at`; `null` — данных ещё нет). */
data class AdminReport<T>(val rows: List<T>, val refreshedAt: String?)

/**
 * Отчёты Admin API Anthropic — только чтение (`DPmetaApp:D-44`, `DPmetaApp:D-81`;
 * `docs/research/ADMIN_API.md`): расход и стоимость по дням и моделям (Usage and Cost
 * Analytics API, бета) и лимиты скорости. Только `GET` и только пути [PATHS] — других
 * вызовов Admin API в коде нет (проверка `AdminClientTest`). Ключ — Admin API key
 * заголовком `x-api-key`; запрос — через [ProviderHosts].
 */
class AdminClient(private val transport: Transport = UrlTransport()) {
    /** F-18: Admin-ключ проверяется отчётом о расходе за последние сутки. */
    fun check(key: Secret?, now: Timestamp, cancel: Cancel = Cancel()): Outcome<AdminReport<UsageRow>> {
        val to = Instant.ofEpochMilli(now.epochMillis).truncatedTo(ChronoUnit.HOURS)
        return usage(key, Timestamp(to.minus(1, ChronoUnit.DAYS).toEpochMilli()), Timestamp(to.toEpochMilli()), cancel)
    }

    /** Расход по дням и моделям за [from]…[to] (не больше [MAX_DAYS] дней): старые дни — сверху. */
    fun usage(key: Secret?, from: Timestamp, to: Timestamp, cancel: Cancel = Cancel()): Outcome<AdminReport<UsageRow>> =
        analytics(USAGE, key, from, to, cancel) { o ->
            val u = o["usage"].obj() ?: return@analytics null
            val cw = u["cache_creation"].obj()
            val write = listOfNotNull(cw?.get("ephemeral_1h_input_tokens").long(), cw?.get("ephemeral_5m_input_tokens").long()).takeIf { it.isNotEmpty() }?.sum()
            UsageRow(day(o), o["group"].obj()?.get("model").str(), u["uncached_input_tokens"].long(), write, u["cache_read_input_tokens"].long(), u["output_tokens"].long(), u["requests"].long())
        }

    /** Стоимость по дням и моделям, USD: `amount` — центы строкой с шестью знаками. */
    fun cost(key: Secret?, from: Timestamp, to: Timestamp, cancel: Cancel = Cancel()): Outcome<AdminReport<CostRow>> =
        analytics(COST, key, from, to, cancel) { o ->
            val c = o["cost"].obj() ?: return@analytics null
            val usd = c["amount"].str()?.toBigDecimalOrNull()?.takeIf { c["currency"].str() == "USD" }?.movePointLeft(2)
            CostRow(day(o), o["group"].obj()?.get("model").str(), usd)
        }

    /** Лимиты скорости организации: по группам моделей. */
    fun rateLimits(key: Secret?, cancel: Cancel = Cancel()): Outcome<List<RateLimitRow>> {
        val page = when (val r = get(RATE_LIMITS, emptyList(), key, beta = false, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val rows = page["data"].arr().mapNotNull { it.obj() }.map { o ->
            val g = o["group"].obj()
            val limits = o["limits"].arr().mapNotNull { it.obj() }.associate { it["type"].str().orEmpty() to it["value"].long() }
            RateLimitRow(
                g?.get("display_name").str() ?: g?.get("type").str().orEmpty(),
                o["models"].arr().mapNotNull { it.str() },
                limits["requests_per_minute"], limits["input_tokens_per_minute"], limits["output_tokens_per_minute"],
            )
        }
        return Outcome.success(rows)
    }

    private fun <T> analytics(path: String, key: Secret?, from: Timestamp, to: Timestamp, cancel: Cancel, row: (JsonObject) -> T?): Outcome<AdminReport<T>> {
        val start = Instant.ofEpochMilli(from.epochMillis)
        val end = Instant.ofEpochMilli(to.epochMillis)
        if (!end.isAfter(start) || ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "период отчёта — до $MAX_DAYS дней", SUBJECT)
        }
        val base = listOf(
            "starting_at" to start.toString(),
            "ending_at" to end.toString(),
            "time_bucket_width" to "1d",
            "group_by[]" to "model",
            "order_by" to "time",
            "order" to "asc",
            "limit" to "$PAGE_LIMIT",
        )
        val rows = mutableListOf<T>()
        var refreshed: String? = null
        var page: String? = null
        repeat(MAX_PAGES) {
            val q = base + listOfNotNull(page?.let { "page" to it })
            val o = when (val r = get(path, q, key, beta = true, cancel)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            refreshed = o["data_refreshed_at"].str()
            o["data"].arr().mapNotNull { it.obj() }.forEach { d -> row(d)?.let { rows += it } }
            if (o["has_more"] != JsonBool(true)) return Outcome.success(AdminReport(rows, refreshed))
            page = o["next_page"].str() ?: return Outcome.success(AdminReport(rows, refreshed))
        }
        return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "отчёт длиннее $MAX_PAGES страниц", SUBJECT)
    }

    /** Единственный путь в сеть: `GET`, путь — из [PATHS], ключ — заголовком. */
    private fun get(path: String, query: List<Pair<String, String>>, key: Secret?, beta: Boolean, cancel: Cancel): Outcome<JsonObject> {
        if (key == null || key.isEmpty) return Outcome.failure(DiagCode.PROVIDER_KEY_MISSING, "у профиля нет Admin-ключа", SUBJECT)
        require(path in PATHS) { "путь Admin API вне списка: $path" }
        val q = query.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val headers = buildMap {
            put("anthropic-version", AnthropicProvider.VERSION)
            if (beta) put("anthropic-beta", ANALYTICS_BETA)
            put("accept", "application/json")
        }
        val call = HttpCall("GET", URI(BASE + path + if (q.isEmpty()) "" else "?$q"), headers, null, SecretHeader("x-api-key", "", key))
        ProviderHosts.check(call, emptySet()).let { if (it is Outcome.Failure) return it }
        val reply = when (val r = transport.open(call, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return reply.use {
            try {
                if (it.status != 200) return refusal(it)
                when (val v = JsonParser().parse(it.readText(MAX_REPLY_CHARS)).valueOrNull()) {
                    is JsonObject -> Outcome.success(v)
                    else -> Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "ответ — не объект JSON", SUBJECT)
                }
            } catch (e: IOException) {
                if (cancel.cancelled) {
                    Outcome.failure(DiagCode.CANCELLED_BY_USER, "прервано", SUBJECT)
                } else {
                    Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "соединение оборвалось: ${e.javaClass.simpleName}", SUBJECT)
                }
            }
        }
    }

    private fun refusal(reply: HttpReply): Outcome.Failure {
        val status = reply.status
        val detail = try {
            HttpProvider.errorMessage(JsonParser().parse(reply.readText(HttpProvider.MAX_ERROR_CHARS)).valueOrNull())
        } catch (_: IOException) {
            null
        }
        val tail = detail?.let { ": $it" }.orEmpty()
        return when (status) {
            401, 403 -> Outcome.failure(DiagCode.PROVIDER_AUTH_FAILED, "Admin-ключ не принят ($status) — нужен Admin API key организации Console$tail", SUBJECT)
            429 -> Outcome.failure(DiagCode.PROVIDER_RATE_LIMITED, "предел запросов отчётов ($status) — повторите через минуту$tail", SUBJECT)
            in 500..599 -> Outcome.failure(DiagCode.PROVIDER_UNAVAILABLE, "сервис отчётов недоступен ($status)$tail", SUBJECT)
            else -> Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "запрос отчёта отвергнут ($status)$tail", SUBJECT)
        }
    }

    companion object {
        const val SUBJECT = "admin"
        private const val BASE = "https://api.anthropic.com"
        const val USAGE = "/v1/organizations/analytics/usage"
        const val COST = "/v1/organizations/analytics/cost"
        const val RATE_LIMITS = "/v1/organizations/rate_limits"

        /** Все пути Admin API в коде — только чтение (`DPmetaApp:D-44`). */
        val PATHS: Set<String> = setOf(USAGE, COST, RATE_LIMITS)

        const val ANALYTICS_BETA = "analytics-usage-cost-2026-08-24"

        /** Предел периода одного запроса аналитики. */
        const val MAX_DAYS = 31L
        private const val PAGE_LIMIT = 1000
        private const val MAX_PAGES = 10
        private const val MAX_REPLY_CHARS = 4 * 1024 * 1024

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        /** День строки — дата начала корзины (UTC). */
        private fun day(o: JsonObject): String = o["starting_at"].str()?.take(10).orEmpty()
    }
}

/**
 * Сводка отчётов для экрана (F-19): по дням — стоимость и расход по моделям; за период —
 * итог по моделям. Сумма неизвестного — неизвестно (`null`), а не ноль (D-13).
 */
object AdminReports {
    data class Day(val day: String, val usd: BigDecimal?, val models: List<UsageRow>)

    fun days(usage: List<UsageRow>, cost: List<CostRow>): List<Day> {
        val keys = (usage.map { it.day } + cost.map { it.day }).distinct().sorted()
        return keys.map { d ->
            val c = cost.filter { it.day == d }
            Day(d, if (c.isEmpty() || c.any { it.usd == null }) null else c.sumOf { it.usd!! }, usage.filter { it.day == d })
        }
    }

    /** Итог за период по моделям: поля — сумма, если известны во всех строках модели. */
    fun totals(usage: List<UsageRow>): List<UsageRow> = usage.groupBy { it.model }.map { (model, rows) ->
        fun sum(f: (UsageRow) -> Long?): Long? = if (rows.any { f(it) == null }) null else rows.sumOf { f(it)!! }
        UsageRow("", model, sum { it.uncachedInput }, sum { it.cacheWrite }, sum { it.cacheRead }, sum { it.output }, sum { it.requests })
    }.sortedByDescending { it.output ?: -1 }

    /** Стоимость за период: `null`, если хоть один день не известен. */
    fun total(cost: List<CostRow>): BigDecimal? = if (cost.any { it.usd == null }) null else cost.sumOf { it.usd!! }
}

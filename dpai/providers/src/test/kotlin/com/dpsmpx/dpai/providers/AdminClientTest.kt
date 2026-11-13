package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Отчёты Admin API (`DPmetaApp:D-44`, `DPmetaApp:D-81`): только GET и только пути списка; разбор — на синтетических образцах формата. */
class AdminClientTest {
    private val keyText = sampleKey("sk-ant-admin01")
    private val key = Secret(keyText)
    private fun t(s: String) = Timestamp(Instant.parse(s).toEpochMilli())

    private val usagePage1 = """{"data":[{"starting_at":"2026-11-07T00:00:00Z","ending_at":"2026-11-08T00:00:00Z","group":{"model":"claude-opus-5-5","workspace_id":null},
        "usage":{"uncached_input_tokens":1000,"cache_creation":{"ephemeral_1h_input_tokens":5,"ephemeral_5m_input_tokens":7},"cache_read_input_tokens":300,"output_tokens":50,"total_tokens":1362,"requests":4}}],
        "has_more":true,"next_page":"page_2","data_refreshed_at":"2026-11-09T08:00:00Z"}"""
    private val usagePage2 = """{"data":[{"starting_at":"2026-11-08T00:00:00Z","ending_at":"2026-11-09T00:00:00Z","group":{"model":null},"usage":{"output_tokens":9,"new_metric":1}}],
        "has_more":false,"next_page":null,"data_refreshed_at":"2026-11-09T08:00:00Z"}"""

    @Test
    fun usagePagesAndFields() {
        val t = FakeTransport { c -> FakeReply(200, if ("page=page_2" in c.url.rawQuery.orEmpty()) usagePage2 else usagePage1) }
        val r = AdminClient(t).usage(key, t("2026-11-02T00:00:00Z"), t("2026-11-09T00:00:00Z")).valueOrNull()!!
        assertEquals(
            listOf(
                UsageRow("2026-11-07", "claude-opus-5-5", 1000, 12, 300, 50, 4),
                UsageRow("2026-11-08", null, null, null, null, 9, null),
            ),
            r.rows,
            "неизвестное — null, не ноль",
        )
        assertEquals("2026-11-09T08:00:00Z", r.refreshedAt)
        val first = t.calls.first()
        assertEquals("GET", first.method)
        assertEquals(AdminClient.USAGE, first.url.path)
        val q = first.url.query
        assertTrue("starting_at=2026-11-02T00:00:00Z" in q && "time_bucket_width=1d" in q && "group_by[]=model" in q && "order_by=time" in q, q)
        assertEquals(AdminClient.ANALYTICS_BETA, first.headers["anthropic-beta"])
        assertEquals("x-api-key", first.secret!!.name)
        assertFalse(t.calls.any { keyText in it.url.toString() || it.headers.values.any { v -> keyText in v } }, "ключ — только заголовком с секретом")
    }

    @Test
    fun costInDollarsAndRateLimits() {
        val cost = """{"data":[{"starting_at":"2026-11-08T00:00:00Z","group":{"model":"claude-opus-5-5"},"cost":{"amount":"41280.000000","list_amount":"50000.000000","currency":"USD","requests":3}}],"has_more":false,"data_refreshed_at":null}"""
        val limits = """{"data":[{"id":"rlt_1","type":"rate_limit","group":{"type":"model_group","id":"rlg_1","display_name":"Claude Opus 5.5"},"models":["claude-opus-5-5"],
            "limits":[{"type":"requests_per_minute","value":4000},{"type":"input_tokens_per_minute","value":10000000},{"type":"output_tokens_per_minute","value":800000}]},
            {"id":"rlt_2","type":"rate_limit","group":{"type":"batch","id":"rlg_2"},"limits":[]}],"next_page":null}"""
        val t = FakeTransport { c -> FakeReply(200, if (c.url.path == AdminClient.COST) cost else limits) }
        val c = AdminClient(t)
        val r = c.cost(key, t("2026-11-02T00:00:00Z"), t("2026-11-09T00:00:00Z")).valueOrNull()!!
        assertEquals(listOf(CostRow("2026-11-08", "claude-opus-5-5", BigDecimal("412.80000000"))), r.rows)
        assertEquals(0, BigDecimal("412.8").compareTo(r.rows.single().usd), "центы → доллары")
        assertEquals(null, r.refreshedAt)
        val l = c.rateLimits(key).valueOrNull()!!
        assertEquals(RateLimitRow("Claude Opus 5.5", listOf("claude-opus-5-5"), 4000, 10_000_000, 800_000), l.first())
        assertEquals(RateLimitRow("batch", emptyList(), null, null, null), l[1])
        assertEquals(null, t.last.headers["anthropic-beta"], "лимиты — без беты аналитики")
    }

    @Test
    fun refusals() {
        assertEquals(DiagCode.PROVIDER_KEY_MISSING, assertIs<Outcome.Failure>(AdminClient(FakeTransport { FakeReply(200, "{}") }).rateLimits(null)).errors.single().code)
        val long = AdminClient(FakeTransport { FakeReply(200, "{}") }).usage(key, t("2026-09-01T00:00:00Z"), t("2026-11-01T00:00:00Z"))
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(long).errors.single().code, "больше 31 дня — отказ до сети")
        for ((status, code) in listOf(401 to DiagCode.PROVIDER_AUTH_FAILED, 403 to DiagCode.PROVIDER_AUTH_FAILED, 429 to DiagCode.PROVIDER_RATE_LIMITED, 500 to DiagCode.PROVIDER_UNAVAILABLE, 400 to DiagCode.PROVIDER_REQUEST_REJECTED)) {
            val r = AdminClient(FakeTransport { FakeReply(status, """{"type":"error","error":{"type":"x","message":"нет"}}""") }).rateLimits(key)
            assertEquals(code, assertIs<Outcome.Failure>(r).errors.single().code, "$status")
        }
        val check = AdminClient(FakeTransport { FakeReply(200, """{"data":[],"has_more":false,"data_refreshed_at":null}""") }).check(key, t("2026-11-09T12:34:56Z"))
        assertTrue(check is Outcome.Success, "F-18: отчёт за сутки принят — ключ годен")
    }

    @Test
    fun summaryKeepsUnknown() {
        val u = listOf(
            UsageRow("2026-11-07", "m1", 10, 1, 2, 5, 1),
            UsageRow("2026-11-08", "m1", 20, 0, 0, 7, 2),
            UsageRow("2026-11-08", "m2", null, 0, 0, 1, 1),
        )
        val c = listOf(CostRow("2026-11-07", "m1", BigDecimal("1.50")), CostRow("2026-11-08", "m1", BigDecimal("2.00")), CostRow("2026-11-08", "m2", null))
        val days = AdminReports.days(u, c)
        assertEquals(listOf("2026-11-07", "2026-11-08"), days.map { it.day })
        assertEquals(BigDecimal("1.50"), days[0].usd)
        assertEquals(null, days[1].usd, "день с неизвестной стоимостью — неизвестен")
        val totals = AdminReports.totals(u)
        assertEquals(UsageRow("", "m1", 30, 1, 2, 12, 3), totals.first())
        assertEquals(null, totals[1].uncachedInput)
        assertEquals(null, AdminReports.total(c))
        assertEquals(BigDecimal("3.50"), AdminReports.total(c.take(2)))
    }

    @Test
    fun screenLines() {
        assertEquals("$412.80", UsageText.usd(BigDecimal("412.80000000")))
        assertEquals("неизвестно", UsageText.usd(null))
        assertEquals("вход 1\u00A0000 · запись кэша 12 · чтение кэша неизвестно · выход 50 · запросов 4", UsageText.tokens(UsageRow("d", "m", 1000, 12, null, 50, 4)))
        assertEquals("запросов в минуту 4\u00A0000 — claude-opus-5-5", UsageText.limits(RateLimitRow("g", listOf("claude-opus-5-5"), 4000, null, null)))
        assertEquals("пределы в минуту не сообщены", UsageText.limits(RateLimitRow("batch", emptyList(), null, null, null)))
        assertEquals("2026-11-09 08:00 UTC", UsageText.stamp("2026-11-09T08:00:00Z"))
    }

    /** `DPmetaApp:D-44`: в коде модуля нет изменяющих вызовов Admin API — пути только из списка, метод только GET. */
    @Test
    fun onlyGetAndOnlyListedPaths() {
        assertEquals(setOf("/v1/organizations/analytics/usage", "/v1/organizations/analytics/cost", "/v1/organizations/rate_limits"), AdminClient.PATHS)
        val src = File("src/main/kotlin/com/dpsmpx/dpai/providers/AdminClient.kt").readText()
        assertFalse(Regex("HttpCall\\(\\s*\"(POST|PUT|PATCH|DELETE)\"").containsMatchIn(src), "изменяющих методов нет")
        assertEquals(1, Regex("HttpCall\\(").findAll(src).count(), "один путь в сеть")
        val others = File("src/main/kotlin/com/dpsmpx/dpai/providers").listFiles()!!.filter { it.name != "AdminClient.kt" }
        assertTrue(others.none { "/v1/organizations" in it.readText() }, "Admin API — только в AdminClient")
    }
}

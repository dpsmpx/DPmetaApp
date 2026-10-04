package com.dpsmpx.dppass.tests.fuzz

import com.dpsmpx.dppass.config.ConfigLimits
import com.dpsmpx.dppass.config.ConfigLoader
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.subscriptions.PayloadDecoder
import com.dpsmpx.dppass.subscriptions.ShareLinkParser
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Мутационный fuzz с фиксированным зерном (`docs/TESTING.md`, «Fuzz»):
 * воспроизводим, идёт в каждом прогоне hostcheck. Свойство: на любом
 * входе разбор возвращает Outcome, не бросает исключений и укладывается
 * во время. Число итераций — `-Ddppass.fuzz.iterations` (по умолчанию
 * небольшое, для CI); глубокий прогон — тем же тестом с большим числом.
 * Найденный сбой становится файлом корпуса `tests/data/`.
 */
class FuzzTest {
    private val data = File(System.getProperty("dppass.repoRoot"), "tests/data")
    private val iterations = System.getProperty("dppass.fuzz.iterations")?.toIntOrNull() ?: 1500
    private val seed = System.getProperty("dppass.fuzz.seed")?.toLongOrNull() ?: 20261004L

    private val tokens = listOf(
        "{", "}", "[", "]", "\"", ":", ",", "null", "true", "1e999", "-0", "0.0", "\"\\u0000\"", "\"\\ud800\"",
        "\"format_version\": 2", "\"profiles\": []", "\"type\": \"vless\"", "%", "%zz", "@", "#", "?", "&", "=", "://",
        "\n", "\u0000", "\uFEFF", "x".repeat(5000), "[".repeat(200), "\"a\": 1, \"a\": 2",
    )

    private fun mutate(input: String, r: Random): String {
        var s = input
        repeat(1 + r.nextInt(4)) {
            if (s.isEmpty()) {
                s = tokens[r.nextInt(tokens.size)]
                return@repeat
            }
            val i = r.nextInt(s.length)
            s = when (r.nextInt(6)) {
                0 -> s.substring(0, i) + tokens[r.nextInt(tokens.size)] + s.substring(i)
                1 -> s.removeRange(i, minOf(s.length, i + 1 + r.nextInt(16)))
                2 -> s.substring(0, i) + s.substring(i, minOf(s.length, i + 1 + r.nextInt(64))).repeat(2) + s.substring(minOf(s.length, i + 1))
                3 -> s.substring(0, i) + (r.nextInt(32, 0x500)).toChar() + s.substring(minOf(s.length, i + 1))
                4 -> s.substring(0, i)
                else -> s.reversed().take(s.length / 2) + s
            }
        }
        return s
    }

    private inline fun <T> bounded(what: String, input: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = try {
            block()
        } catch (e: Throwable) {
            fail("$what бросил ${e::class.qualifiedName} на входе длиной ${input.length}: ${e.message?.take(200)}")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 2_000, "$what: $ms мс на одном входе")
        return r
    }

    @Test
    fun configLoaderNeverThrows() {
        val seeds = (data.resolve("configs").listFiles()!! + data.resolve("dns").listFiles()!!)
            .filter { it.name.endsWith(".json") && !it.name.endsWith(".expect.json") }.map { it.readText() }
        val loader = ConfigLoader()
        val r = Random(seed)
        repeat(iterations) {
            val input = mutate(seeds[r.nextInt(seeds.size)], r)
            val out = bounded("ConfigLoader", input) { loader.load(input) }
            if (out is Outcome.Failure) assertTrue(out.errors.isNotEmpty())
        }
    }

    @Test
    fun shareLinkParserNeverThrows() {
        val seeds = data.resolve("subscriptions").listFiles()!!.filter { it.name.endsWith(".txt") }
            .flatMap { it.readLines() }.filter { "://" in it }
        val r = Random(seed + 1)
        repeat(iterations * 2) {
            val input = mutate(seeds[r.nextInt(seeds.size)], r)
            bounded("ShareLinkParser", input) { ShareLinkParser.parse(input, "fz") }
        }
    }

    @Test
    fun subscriptionDecoderNeverThrows() {
        val seeds = data.resolve("subscriptions").listFiles()!!.filter { it.name.endsWith(".txt") }.map { it.readText() }
        val r = Random(seed + 2)
        repeat(iterations / 2) {
            val input = mutate(seeds[r.nextInt(seeds.size)], r)
            bounded("PayloadDecoder", input) { PayloadDecoder.decode(input.toByteArray(), "fz", 1000) }
        }
        repeat(200) {
            val bytes = ByteArray(r.nextInt(4096)).also(r::nextBytes)
            bounded("PayloadDecoder(bytes)", "") { PayloadDecoder.decode(bytes, "fz", 1000) }
        }
    }

    // ---- вырожденные входы: детерминированные случаи из постановки ----

    @Test
    fun hugeAndDeepDocumentsAreRejectedQuickly() {
        val loader = ConfigLoader()
        assertEquals(DiagCode.DOCUMENT_TOO_DEEP, bounded("deep", "") { loader.load("{\"a\":" + "[".repeat(200_000)) }.errors.single().code)
        val big = "{\"format\":\"dppass.config\",\"pad\":\"" + "x".repeat(ConfigLimits().maxBytes) + "\"}"
        assertEquals(DiagCode.DOCUMENT_TOO_LARGE, bounded("big", "") { loader.load(big) }.errors.single().code)
    }

    @Test
    fun ruleLimitsAndDuplicatesStayFast() {
        val rules = (0..4096).joinToString(",") { """{"id":"r$it","action":"direct","domain_suffix":["dup.example.net"]}""" }
        val doc = """{"format":"dppass.config","format_version":1,"profiles":[{"id":"p","name":"p","server":"192.0.2.1","port":443,
            "protocol":{"type":"trojan","password":"x"},"security":{"type":"tls"}}],"routing":{"rules":[$rules]},
            "dns":{"servers":[{"id":"d","type":"https","url":"https://dns.example.net/q","route":"tunnel"}],"default":"d"}}"""
        val r = bounded("rules", doc) { ConfigLoader().load(doc) }
        assertTrue(r.errors.any { it.code == DiagCode.LIMIT_EXCEEDED })
        assertTrue(r.diagnostics.count { it.code == DiagCode.RULE_DUPLICATE } >= 4096)
    }

    @Test
    fun invalidGeoAndPackagesAreReported() {
        val doc = """{"format":"dppass.config","format_version":1,"profiles":[{"id":"p","name":"p","server":"192.0.2.1","port":443,
            "protocol":{"type":"trojan","password":"x"},"security":{"type":"tls"}}],
            "routing":{"rules":[{"id":"g","action":"direct","geoip":["RU ","../x"],"apps":["not a package"]}]},
            "dns":{"servers":[{"id":"d","type":"https","url":"https://dns.example.net/q","route":"tunnel"}],"default":"d"}}"""
        val codes = ConfigLoader().load(doc).errors.map { it.code }
        assertTrue(DiagCode.GEO_CODE_INVALID in codes)
        assertTrue(DiagCode.PACKAGE_INVALID in codes)
    }
}

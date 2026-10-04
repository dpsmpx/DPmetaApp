package com.dpsmpx.dppass.tests.regression

import com.dpsmpx.dppass.config.ConfigLoader
import com.dpsmpx.dppass.config.transportClass
import com.dpsmpx.dppass.connection.ActivationResult
import com.dpsmpx.dppass.connection.CandidatePlanner
import com.dpsmpx.dppass.connection.ConnectionReport
import com.dpsmpx.dppass.connection.DPpassCore
import com.dpsmpx.dppass.connection.EngineProfileFilter
import com.dpsmpx.dppass.connection.HealthBook
import com.dpsmpx.dppass.connection.SimulatedHealthChecker
import com.dpsmpx.dppass.core.Cidr
import com.dpsmpx.dppass.core.IpAddress
import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonArray
import com.dpsmpx.dppass.core.JsonBool
import com.dpsmpx.dppass.core.JsonNull
import com.dpsmpx.dppass.core.JsonNumber
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.core.JsonString
import com.dpsmpx.dppass.core.ManualClock
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.engine.EngineCapabilities
import com.dpsmpx.dppass.engine.mock.MockBackend
import com.dpsmpx.dppass.engine.mock.MockBackendCompiler
import com.dpsmpx.dppass.engine.mock.MockProxyEngine
import com.dpsmpx.dppass.network.NetworkAssessor
import com.dpsmpx.dppass.network.NetworkType
import com.dpsmpx.dppass.network.ProbeBudget
import com.dpsmpx.dppass.network.simulation.NetworkCondition
import com.dpsmpx.dppass.network.simulation.SimulatedNetwork
import com.dpsmpx.dppass.routing.RouteEvaluator
import com.dpsmpx.dppass.routing.RouteQuery
import com.dpsmpx.dppass.routing.TableGeoResolver
import com.dpsmpx.dppass.routing.TransportProtocol
import com.dpsmpx.dppass.storage.InMemoryBlobStore
import com.dpsmpx.dppass.subscriptions.FetchedPayload
import com.dpsmpx.dppass.subscriptions.Subscription
import com.dpsmpx.dppass.subscriptions.SubscriptionFetcher
import com.dpsmpx.dppass.subscriptions.SubscriptionSource
import com.dpsmpx.dppass.subscriptions.SubscriptionUpdater
import com.dpsmpx.dppass.subscriptions.UpdateResult
import com.dpsmpx.dppass.tunnel.PlatformTraits
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Корпус регрессий (`docs/TESTING.md`): каждый файл в `tests/data/` с
 * ожиданием `*.expect.json` — отдельный тест. Новый баг — новый файл
 * корпуса, а не правка кода без следа.
 */
class CorpusTest {
    private val data = File(System.getProperty("dppass.repoRoot"), "tests/data")

    private fun json(f: File): JsonObject = Json.parse(f.readText()) as JsonObject
    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonString)?.value
    private fun codes(o: JsonObject?): List<String> = (o?.get("codes") as? JsonArray)?.items?.map { (it as JsonString).value }.orEmpty()

    /** Все коды дерева находок (с причинами). */
    private fun allCodes(ds: List<Diagnostic>): Set<String> = ds.flatMap { listOf(it.code.name) + allCodes(it.causes) }.toSet()

    private fun backend(name: String?): EngineCapabilities = if (name == "mock-limited") MockBackend.LIMITED else MockBackend.FULL

    private fun core(caps: EngineCapabilities) = DPpassCore(
        MockBackendCompiler(caps), MockProxyEngine(caps), InMemoryBlobStore(), ManualClock(1_000_000), "com.dpsmpx.dppass", PlatformTraits(true),
    )

    @TestFactory
    fun configsAndDns(): List<DynamicTest> = listOf("configs", "dns").flatMap { dir ->
        File(data, dir).listFiles { f -> f.name.endsWith(".expect.json") }!!.sorted().map { expectFile ->
            val name = expectFile.name.removeSuffix(".expect.json")
            DynamicTest.dynamicTest("$dir/$name") {
                val expect = json(expectFile)
                val text = File(expectFile.parentFile, "$name.json").readText()
                val load = expect["load"] as JsonObject
                val r = ConfigLoader().load(text)
                val got = allCodes(r.diagnostics)
                assertEquals(str(load, "outcome") == "ok", r.isSuccess, "исход разбора $name: ${r.diagnostics.map { it.render() }}")
                for (c in codes(load)) assertTrue(c in got, "$name: нет ожидаемого кода $c, есть $got")
                if (str(load, "outcome") == "ok") {
                    val unexpected = r.diagnostics.filter { it.severity.name != "INFO" }.map { it.code.name }.toSet() - codes(load).toSet()
                    assertTrue(unexpected.isEmpty(), "$name: неожиданные находки $unexpected")
                }
                val act = expect["activate"] as? JsonObject ?: return@dynamicTest
                runBlocking {
                    val res = core(backend(str(act, "backend"))).importAndActivate(text).valueOrNull() ?: fail("$name: не разобран")
                    val ok = res is ActivationResult.Activated
                    assertEquals(str(act, "outcome") == "activated", ok, "$name: ${res.diagnostics.map { it.render() }}")
                    val gotA = allCodes(res.diagnostics)
                    for (c in codes(act)) assertTrue(c in gotA, "$name: активация без кода $c, есть $gotA")
                }
            }
        }
    }

    @TestFactory
    fun subscriptions(): List<DynamicTest> =
        File(data, "subscriptions").listFiles { f -> f.name.endsWith(".expect.json") }!!.sorted().map { expectFile ->
            val name = expectFile.name.removeSuffix(".expect.json")
            DynamicTest.dynamicTest("subscriptions/$name") {
                val expect = json(expectFile)
                val body = File(expectFile.parentFile, "$name.txt").readBytes()
                val headersFile = File(expectFile.parentFile, "$name.headers.json")
                val headers = if (headersFile.isFile) json(headersFile).fields.mapValues { (it.value as JsonString).value } else emptyMap()
                val clock = ManualClock(0)
                val fetcher = SubscriptionFetcher { _, _ -> Outcome.Success(FetchedPayload(body, headers, 0)) }
                val sub = Subscription(SubscriptionId("corpus"), name, SubscriptionSource.Url(Secret("https://sub.example.com/corpus")))
                val r = runBlocking { SubscriptionUpdater(fetcher, clock).update(sub, EngineProfileFilter(MockBackend.FULL)) }
                val got = allCodes(r.diagnostics)
                when (str(expect, "outcome")) {
                    "ready" -> {
                        val ready = r as? UpdateResult.Ready ?: fail("$name: ожидался Ready, есть $r")
                        assertEquals((expect["profiles"] as JsonNumber).toIntOrNull(), ready.candidate.profiles.size, "$name: число серверов")
                    }
                    "failed" -> assertTrue(r is UpdateResult.Failed, "$name: ожидался отказ")
                }
                for (c in codes(expect)) assertTrue(c in got, "$name: нет кода $c, есть $got")
            }
        }

    @TestFactory
    fun routing(): List<DynamicTest> =
        File(data, "routing").listFiles { f -> f.name.endsWith(".cases.json") }!!.sorted().flatMap { casesFile ->
            val cases = json(casesFile)
            val ir = ConfigLoader().load(File(casesFile.parentFile, str(cases, "config")!!).readText()).valueOrNull()
                ?: fail("${casesFile.name}: конфигурация не разобрана")
            val geoIps = ((cases["geo"] as? JsonObject)?.get("geoip") as? JsonObject)?.fields.orEmpty()
                .mapValues { (_, v) -> (v as JsonArray).items.map { Cidr.parse((it as JsonString).value)!!.cidr } }
            val evaluator = RouteEvaluator(ir.ir.routing, TableGeoResolver(ips = geoIps))
            (cases["cases"] as JsonArray).items.mapIndexed { i, item ->
                val c = item as JsonObject
                val q = c["query"] as JsonObject
                DynamicTest.dynamicTest("routing/${casesFile.name}#$i ${Json.write(q, pretty = false)}") {
                    val query = RouteQuery(
                        domain = str(q, "domain"),
                        ip = str(q, "ip")?.let(IpAddress::parse),
                        port = (q["port"] as? JsonNumber)?.toIntOrNull(),
                        protocol = str(q, "protocol")?.let { TransportProtocol.valueOf(it.uppercase()) },
                        packageName = str(q, "package"),
                    )
                    val d = evaluator.decide(query)
                    assertEquals(str(c, "action")!!.uppercase(), d.action.name, d.reason)
                    val rule = c["rule"]
                    assertEquals(if (rule == null || rule == JsonNull) null else (rule as JsonString).value, d.ruleId?.value)
                }
            }
        }

    @TestFactory
    fun networkConditions(): List<DynamicTest> {
        val table = json(File(data, "network/conditions.json"))
        val configText = File(data, "network/" + str(table, "config")!!).readText()
        return (table["conditions"] as JsonArray).items.map { item ->
            val c = item as JsonObject
            val cond = NetworkCondition.valueOf(str(c, "condition")!!)
            DynamicTest.dynamicTest("network/${cond.name}") {
                runBlocking {
                    val clock = ManualClock(1_000_000)
                    val blocked = (c["blocked_endpoints"] as? JsonArray)?.items?.map { (it as JsonString).value }?.toSet().orEmpty()
                    val net = SimulatedNetwork(cond, blocked)
                    val x = NetworkAssessor(net, ProbeBudget(clock)) { clock.advance(it) }.assess(NetworkType.MOBILE)
                    assertEquals(str(c, "restriction"), x.restriction.name, "${cond.name}: ${x.evidence}")
                    for (k in listOf("dns", "udp", "tls")) str(c, k)?.let { want ->
                        val got = when (k) { "dns" -> x.dns; "udp" -> x.udp; else -> x.tls }
                        assertEquals(want, got.name, "${cond.name}.$k")
                    }
                    val core = core(MockBackend.FULL)
                    core.importAndActivate(configText)
                    val ir = core.activation.state.activeConfig!!
                    val plan = CandidatePlanner.plan(ir, MockBackend.FULL, x, HealthBook(), null, clock.nowMillis())
                    (c["excluded"] as? JsonArray)?.items?.forEach { e ->
                        assertTrue(plan.excluded.any { it.profileId.value == (e as JsonString).value }, "${cond.name}: не исключён $e")
                    }
                    str(c, "only_candidate")?.let { assertEquals(listOf(it), plan.attempts.map { a -> a.profile.id.value }) }
                    str(c, "first_attempt_class")?.let { assertEquals(it, plan.attempts.first().profile.transportClass.name) }
                    val report = core.connect(x, SimulatedHealthChecker(net), null) { clock.advance(it) }!!
                    val connects = (c["connects"] as JsonBool).value
                    assertEquals(connects, report is ConnectionReport.Connected, "${cond.name}: ${report.attempts}")
                    str(c, "verdict")?.let { assertEquals(it, (report as ConnectionReport.Failed).reason.code.name) }
                    str(c, "first_failed")?.let { assertEquals(it, report.attempts.first { a -> !a.ok }.profileId.value) }
                }
            }
        }
    }
}

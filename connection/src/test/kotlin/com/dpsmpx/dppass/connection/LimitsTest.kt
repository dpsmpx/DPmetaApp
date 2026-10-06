package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.core.ManualClock
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.engine.mock.MockBackend
import com.dpsmpx.dppass.engine.mock.MockBackendCompiler
import com.dpsmpx.dppass.engine.mock.MockProxyEngine
import com.dpsmpx.dppass.storage.InMemoryBlobStore
import com.dpsmpx.dppass.storage.Quarantine
import com.dpsmpx.dppass.tunnel.PlatformTraits
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Пределы уровня подключения и хранения (docs/PERFORMANCE_BUDGET.md, §2):
 * значение — как в таблице, поведение на пределе и за ним — проверено.
 */
class LimitsTest {
    @Test
    fun activationHistoryKeepsLast32() = runBlocking {
        val clock = ManualClock(100)
        val blobs = InMemoryBlobStore()
        val service = ActivationService(MockBackendCompiler(), MockProxyEngine(), ActivationStore(blobs, Quarantine(blobs, clock)), clock)
        repeat(40) { i -> service.activate(ir(if (i % 2 == 0) "valid-minimal.json" else "valid-multi.json"), "t", "c$i") }
        val history = service.state.history
        assertEquals(32, history.size, "как в PERFORMANCE_BUDGET §2")
        assertEquals("c39", history.last().sourceChecksum, "новые записи остаются")
        // В истории — переходы (новая запись и «заменена» у прежней), по порядку; отрезаются самые старые.
        assertEquals(history.map { it.revision }.sorted(), history.map { it.revision })
        assertTrue(history.first().revision > 1, "первые записи отрезаны: ${history.first().revision}")
        // После перезапуска — то же самое.
        val again = ActivationService(MockBackendCompiler(), MockProxyEngine(), ActivationStore(blobs, Quarantine(blobs, clock)), clock)
        assertTrue(again.restore().isEmpty())
        assertEquals(history, again.state.history)
    }

    @Test
    fun healthCheckThatNeverAnswersFailsTheAttemptAtTimeout() = runBlocking {
        assertEquals(10_000, ConnectionPolicy().healthTimeoutMillis, "как в PERFORMANCE_BUDGET §2")
        // Механизм — на коротком сроке: проверка, которая не отвечает никогда.
        val policy = ConnectionPolicy(healthTimeoutMillis = 50)
        val clock = ManualClock(0)
        val multi = ir("valid-multi.json")
        val book = HealthBook(policy)
        val plan = CandidatePlanner.plan(multi, MockBackend.FULL, null, book, null, 0, policy)
        val engine = MockProxyEngine()
        val log = com.dpsmpx.dppass.diagnostics.Logger("t", com.dpsmpx.dppass.diagnostics.MemoryLogSink(), clock::nowMillis)
        val silent = HealthChecker { _, _ -> awaitCancellation() }
        val report = ConnectionOrchestrator(MockBackendCompiler(), engine, silent, book, clock, log, policy) { clock.advance(it) }
            .connect(multi, plan, null)
        assertIs<ConnectionReport.Failed>(report)
        assertEquals(policy.maxAttempts, report.attempts.size)
        assertTrue(report.attempts.all { it.failure?.code == DiagCode.HEALTH_CHECK_FAILED && "50 мс" in it.failure.message }, report.attempts.toString())
        assertEquals(null, engine.runningSession(), "сессии по сроку остановлены")
    }

    @Test
    fun subscriptionsUpTo64() {
        val core = DPpassCore(MockBackendCompiler(), MockProxyEngine(), InMemoryBlobStore(), ManualClock(1_000), "com.dpsmpx.dppass", PlatformTraits(true))
        assertEquals(64, SubscriptionService.MAX_SUBSCRIPTIONS, "как в PERFORMANCE_BUDGET §2")
        repeat(SubscriptionService.MAX_SUBSCRIPTIONS) { i ->
            assertTrue(core.subscriptions.add("https://sub$i.example.com/s", null).isSuccess, "подписка $i")
        }
        val over = core.subscriptions.add("https://over.example.com/s", null)
        assertEquals(DiagCode.LIMIT_EXCEEDED, over.errors.single().code)
        assertEquals(64, core.subscriptions.state().valueOrNull()!!.items.size, "лишняя не сохранена")
    }

    @Test
    fun subscriptionUrlUpTo2048NameUpTo64() {
        val core = DPpassCore(MockBackendCompiler(), MockProxyEngine(), InMemoryBlobStore(), ManualClock(1_000), "com.dpsmpx.dppass", PlatformTraits(true))
        val subs = core.subscriptions
        assertEquals(2048, SubscriptionService.MAX_URL, "как в PERFORMANCE_BUDGET §2")
        assertEquals(64, SubscriptionService.MAX_NAME, "как в PERFORMANCE_BUDGET §2")
        val prefix = "https://sub.example.com/"
        val atLimit = prefix + "a".repeat(SubscriptionService.MAX_URL - prefix.length)
        assertTrue(subs.add(atLimit, null).isSuccess, "адрес ровно на пределе")
        assertEquals(DiagCode.SUBSCRIPTION_FETCH_FAILED, subs.add(atLimit + "a", null).errors.single().code)

        // Имя длиннее предела обрезается: при добавлении, у «своих серверов», при переименовании и по хосту адреса.
        val long = "и".repeat(SubscriptionService.MAX_NAME + 10)
        val named = subs.add("https://named.example.com/s", long).valueOrNull()!!
        assertEquals("и".repeat(SubscriptionService.MAX_NAME), named.name)
        assertEquals(SubscriptionService.MAX_NAME, subs.addInline("свои серверы", long).valueOrNull()!!.name.length)
        val renamed = subs.rename(named.id, long + "ж").valueOrNull()!!.find(named.id)!!
        assertEquals("и".repeat(SubscriptionService.MAX_NAME), renamed.name)
        val host = "h".repeat(SubscriptionService.MAX_NAME) + ".example.com"
        assertEquals("h".repeat(SubscriptionService.MAX_NAME), subs.add("https://$host/s", null).valueOrNull()!!.name)
        assertEquals(4, subs.state().valueOrNull()!!.items.size, "отвергнутый адрес не сохранён")
    }

    @Test
    fun policyAppsUpTo1000() {
        fun apps(n: Int) = UserPolicy(appMode = AppMode.EXCEPT, apps = (1..n).map { "com.example.app$it" }.toSet())
        assertEquals(1000, UserPolicy.MAX_APPS, "как в PERFORMANCE_BUDGET §2")
        assertTrue(PolicyBuilder.validate(apps(UserPolicy.MAX_APPS)).none { it.code == DiagCode.LIMIT_EXCEEDED })
        assertEquals(listOf("policy.apps"), PolicyBuilder.validate(apps(UserPolicy.MAX_APPS + 1)).filter { it.code == DiagCode.LIMIT_EXCEEDED }.map { it.path })
    }
}

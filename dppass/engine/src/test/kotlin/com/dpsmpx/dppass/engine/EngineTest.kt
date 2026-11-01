package com.dpsmpx.dppass.engine

import com.dpsmpx.dppass.config.ConfigLoader
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.engine.mock.MockBackend
import com.dpsmpx.dppass.engine.mock.MockBackendCompiler
import com.dpsmpx.dppass.engine.mock.MockBehavior
import com.dpsmpx.dppass.engine.mock.MockProxyEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun ir(name: String) = ConfigLoader().load(
    File(System.getProperty("dppass.repoRoot"), "tests/data/configs/$name").readText(),
).valueOrNull()!!.ir

class CapabilityCheckerTest {
    @Test
    fun limitedBackendRejectsWithPaths() {
        val multi = ir("valid-multi.json")
        val d = CapabilityChecker.check(multi, MockBackend.LIMITED, CompilePlan(ProfileId("xhttp-cdn"), true))
        val codes = d.map { it.code to it.path }
        assertTrue(DiagCode.CAPABILITY_TRANSPORT_UNSUPPORTED to "$.profiles[1].transport" in codes)
        assertTrue(d.any { it.code == DiagCode.CAPABILITY_FEATURE_UNSUPPORTED && it.path == "$.dns.rules" })
    }

    @Test
    fun fullBackendAcceptsEverythingInIr() {
        val multi = ir("valid-multi.json")
        for (p in multi.profiles) {
            assertTrue(CapabilityChecker.check(multi, MockBackend.FULL, CompilePlan(p.id, true)).none { it.isError }, "${p.id}")
        }
    }

    @Test
    fun capabilityDiffShowsWhatChanged() {
        val diff = capabilityDiff(MockBackend.FULL, MockBackend.LIMITED)
        assertTrue("transport:XHTTP" in diff.removed)
        assertTrue(diff.added.isEmpty())
    }
}

class MockBackendTest {
    @Test
    fun compileIsDeterministicAndHidesPayload() {
        val multi = ir("valid-multi.json")
        val c = MockBackendCompiler()
        val a = c.compile(multi, CompilePlan(ProfileId("reality-1"), true)).valueOrNull()!!
        val b = c.compile(multi, CompilePlan(ProfileId("reality-1"), true)).valueOrNull()!!
        assertEquals(a.checksum, b.checksum)
        assertTrue("00000000-0000-4000-8000-000000000001" in a.payloadForEngine())
        assertFalse("00000000-0000-4000-8000-000000000001" in a.toString())
    }

    @Test
    fun loweredAppRulesDoNotReachEngine() {
        val multi = ir("valid-multi.json")
        val payload = MockBackendCompiler().compile(multi, CompilePlan(ProfileId("reality-1"), true)).valueOrNull()!!.payloadForEngine()
        assertFalse("bank-app-direct" in payload)
        assertTrue("ads-block" in payload)
    }

    @Test
    fun engineLifecycle() = runBlocking {
        val multi = ir("valid-minimal.json")
        val compiled = MockBackendCompiler().compile(multi, CompilePlan(multi.profiles[0].id, true)).valueOrNull()!!
        val engine = MockProxyEngine()
        assertTrue(engine.validate(compiled).isSuccess)
        val s = engine.start(compiled, TunInput(10, 1500) { true }).valueOrNull()!!
        assertEquals(EngineState.RUNNING, s.state())
        assertEquals(DiagCode.BACKEND_ALREADY_RUNNING, engine.start(compiled, null).errors.single().code)
        s.stop()
        assertEquals(EngineState.STOPPED, s.state())
        assertTrue(engine.start(compiled, null).isSuccess)
    }

    @Test
    fun rejectionKeepsCause() = runBlocking {
        val multi = ir("valid-minimal.json")
        val compiled = MockBackendCompiler().compile(multi, CompilePlan(multi.profiles[0].id, false)).valueOrNull()!!
        val r = MockProxyEngine(behavior = MockBehavior(rejectConfig = true)).validate(compiled)
        assertTrue(r is Outcome.Failure)
        assertEquals(DiagCode.INTERNAL_ERROR, r.errors.single().causes.single().code)
    }
}

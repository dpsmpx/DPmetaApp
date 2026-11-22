package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.operation.Actor
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.operation.Provenance
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import com.dpsmpx.dpai.core.ids.IdKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AutomationCatalogTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(215))
    private val origin = Provenance(Actor(ActorKind.AGENT, "API"))
    private fun definition() = AutomationDefinition("manual.check", "Проверки", "Проверить проект", AutomationSource.MANUAL,
        AutomationCategory.CHECK, Hashing.sha256("исходный профиль").hex, ActionLevel.REVERSIBLE, listOf("/project"),
        AutomationInvocation.DIRECT, true, verification = "итог прежнего worker")
    private fun catalog(d: AutomationDefinition = definition()) = AutomationCatalog.of(listOf(d)).valueOrNull()!!

    @Test fun freeTextIsBoundedAndRequiredBeforeAnyAuthorization() {
        val d = definition().copy(parameters = listOf(AutomationParameter("command", "Команда", emptyList(), text = true, required = true, maxLength = 32)))
        for (input in listOf(JsonObject.of(), JsonObject.of("command" to JsonString(" ")), JsonObject.of("command" to JsonString("x".repeat(33))),
            JsonObject.of("command" to JsonString("a\u0000b")))) {
            var touched = false
            val result = catalog(d).invoke(d.id, d.revision, input, origin, ids, clock, { false },
                { touched = true; Outcome.success(Unit) }, { _, _ -> touched = true; Outcome.success(JsonObject.of()) })
            assertEquals(OperationStatus.FAILED, result.status)
            assertFalse(touched)
        }
        var received: String? = null
        val result = catalog(d).invoke(d.id, d.revision, JsonObject.of("command" to JsonString("printf test")), origin, ids, clock, { false },
            { Outcome.success(Unit) }, { _, p -> received = p["command"]; Outcome.success(JsonObject.of()) })
        assertEquals(OperationStatus.SUCCEEDED, result.status)
        assertEquals("printf test", received)
        assertTrue(CanonicalJson.write(d.inputSchema()).contains("maxLength"))
    }

    @Test fun changedSourceOrScopeInvalidatesTheRevision() {
        val d = definition()
        assertNotEquals(d.revision, d.copy(sourceHash = Hashing.sha256("другая команда").hex).revision)
        assertNotEquals(d.revision, d.copy(scopes = listOf("/other")).revision)
        assertEquals(d.revision, d.copy(available = false, reason = "занято").revision)
        var authorized = false
        var executed = false
        val result = catalog().invoke(d.id, "старое описание", JsonObject.of(), origin, ids, clock, { false },
            { authorized = true; Outcome.success(Unit) }, { _, _ -> executed = true; Outcome.success(JsonObject.of()) })
        assertEquals(DiagCode.STATE_CONFLICT, result.diagnostics.single().code)
        assertFalse(authorized); assertFalse(executed)
    }

    @Test fun duplicateIdDoesNotSilentlyReplaceAnExistingTool() {
        assertIs<Outcome.Failure>(AutomationCatalog.of(listOf(definition(), definition().copy(title = "подмена"))))
    }

    @Test fun parametersAreCheckedBeforeAuthorizationOrEffects() {
        val d = definition().copy(parameters = listOf(AutomationParameter("MODE", "Режим", listOf("fast", "full"))))
        for (input in listOf(JsonObject.of("other" to JsonString("x")), JsonObject.of("MODE" to JsonString("shell")), JsonObject.of("MODE" to JsonNull))) {
            var touched = false
            val result = catalog(d).invoke(d.id, d.revision, input, origin, ids, clock, { false },
                { touched = true; Outcome.success(Unit) }, { _, _ -> touched = true; Outcome.success(JsonObject.of()) })
            assertEquals(OperationStatus.FAILED, result.status)
            assertFalse(touched)
        }
    }

    @Test fun missingApprovalNeverReachesTheOldExecutor() {
        val d = definition()
        var executed = false
        val result = catalog(d).invoke(d.id, d.revision, JsonObject.of(), origin, ids, clock, { false },
            { Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "проект занят") }, { _, _ -> executed = true; Outcome.success(JsonObject.of()) })
        assertEquals(OperationStatus.FAILED, result.status)
        assertFalse(executed)
    }

    @Test fun openUiAndUnavailableEntriesAreNotExecuted() {
        for (d in listOf(definition().copy(invocation = AutomationInvocation.OPEN_UI), definition().copy(available = false, reason = "нет SDK"))) {
            var touched = false
            val result = catalog(d).invoke(d.id, d.revision, JsonObject.of(), origin, ids, clock, { false },
                { touched = true; Outcome.success(Unit) }, { _, _ -> touched = true; Outcome.success(JsonObject.of()) })
            assertEquals(OperationStatus.FAILED, result.status); assertFalse(touched)
        }
    }

    @Test fun cancellationAfterAuthorizationDoesNotStartACommand() {
        val d = definition()
        var cancelled = false
        var executed = false
        val result = catalog(d).invoke(d.id, d.revision, JsonObject.of(), origin, ids, clock, { cancelled },
            { cancelled = true; Outcome.success(Unit) }, { _, _ -> executed = true; Outcome.success(JsonObject.of()) })
        assertEquals(DiagCode.CANCELLED_BY_USER, result.diagnostics.single().code); assertFalse(executed)
    }

    @Test fun resultKeepsTheExecutorsFailureAndValidArguments() {
        val d = definition().copy(parameters = listOf(AutomationParameter("MODE", "Режим", listOf("fast", "full"))))
        val result = catalog(d).invoke(d.id, d.revision, JsonObject.of("MODE" to JsonString("full")), origin, ids, clock,
            { false }, { Outcome.success(Unit) }, { _, values ->
                assertEquals(mapOf("MODE" to "full"), values)
                Outcome.failure(DiagCode.PROCESS_EXIT_NONZERO, "проверка упала")
            })
        assertEquals(OperationStatus.FAILED, result.status)
        assertEquals(JsonString("FAILED"), AutomationJson.result(result)["status"])
        assertEquals(JsonNull, AutomationJson.result(result)["data"])
    }

    @Test fun unknownResourceFactsAreNotZeroAndRawSourceIsNotExposed() {
        val d = definition()
        assertEquals(JsonNull, d.toJson()["memoryBytes"])
        assertEquals(JsonNull, d.toJson()["diskBytes"])
        assertEquals(JsonNull, d.toJson()["deterministic"])
        assertFalse(CanonicalJson.write(d.toJson()).contains("sourceHash"))
        assertTrue(CanonicalJson.write(d.toJson()).contains("inputSchema"))
    }

    @Test fun verificationAdapterDoesNotInventUnconfiguredLevels() {
        val p = VerificationProfile(ids.next(IdKinds.PROJECT), mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("тесты", listOf("test")))))
        val entries = VerificationAutomations.describe(p, "/project")
        assertEquals(listOf("verification.normal"), entries.filter { it.available }.map { it.id })
        assertTrue(entries.all { it.invocation == AutomationInvocation.OPEN_UI })
        assertIs<Outcome.Success<*>>(AutomationCatalog.of(entries))
    }
}

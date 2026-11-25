package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DevelopmentReportsTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/development/$name")!!.bufferedReader().use { it.readText() }

    @Test fun junitCountsActualCasesAndPreservesSeedWithoutClaimingFreshness() {
        val parsed = DevelopmentReports.junit(fixture("junit.xml")).valueOrNull()!!
        assertTrue(parsed.complete)
        val report = DevelopmentReports.tests(parsed)
        assertEquals(json(1), report["passed"]); assertEquals(json(1), report["failed"]); assertEquals(json(1), report["skipped"])
        assertEquals(json(false), report["freshnessVerified"])
        assertEquals(json(false), report["flakinessProven"])
        assertEquals("223", parsed.cases.single { it.status == "FAILED" }.seed)
    }

    @Test fun changedStatusDoesNotProveAFlakyTestAndMissingCountersDoNotProveCompleteness() {
        val before = DevelopmentReports.junit(fixture("junit.xml")).valueOrNull()!!
        val after = DevelopmentReports.junit(fixture("junit.xml").replace("<failure message=\"synthetic assertion\"/>", "")).valueOrNull()!!
        assertFalse(after.complete, "сводка failures расходится с testcases")
        val report = DevelopmentReports.tests(after, before)
        assertEquals(json(1), report["changedCount"])
        assertEquals(json(false), report["flakinessProven"])
        val unknown = DevelopmentReports.junit("<testsuite><testcase name=\"a\"/></testsuite>").valueOrNull()!!
        assertFalse(unknown.complete)
    }

    @Test fun cdataContainingXmlExamplesIsNotADtdDeclaration() {
        val report = fixture("junit-cdata.xml")
        val parsed = DevelopmentReports.junit(report).valueOrNull()!!
        assertTrue(parsed.complete)
        assertEquals(1, parsed.cases.size)
    }

    @Test fun rejectsExternalEntitiesMalformedXmlDuplicateCasesAndDeepNesting() {
        val invalid = listOf(
            "<!DOCTYPE testsuite [<!ENTITY secret SYSTEM 'file:///must-not-read'>]><testsuite tests='0'>&secret;</testsuite>",
            "<testsuite><testcase name='broken'></testsuite>",
            "<testsuite tests='2'><testcase name='same'/><testcase name='same'/></testsuite>",
            "<testsuite>" + "<nested>".repeat(65) + "</nested>".repeat(65) + "</testsuite>",
            "<unrelated><testcase name='a'/></unrelated>",
        )
        invalid.forEach { assertIs<Outcome.Failure>(DevelopmentReports.junit(it)) }
        assertIs<Outcome.Failure>(DevelopmentReports.junit("<testsuite tests='0'/>") { true })
    }

    @Test fun sarifKeepsAnalyzerEvidenceAndOmitsExternalPaths() {
        val good = DevelopmentReports.sarif(fixture("quality.json")).valueOrNull()!!
        assertEquals(json(1), good["findings"])
        assertEquals(json(true), good["complete"])
        val item = (good["items"] as JsonArray).items.single() as JsonObject
        assertEquals(json("src/main.kt"), item["path"])
        val external = DevelopmentReports.sarif(fixture("quality.json").replace("src/main.kt", "../../outside.key")).valueOrNull()!!
        assertEquals(JsonNull, ((external["items"] as JsonArray).items.single() as JsonObject)["path"])
        assertIs<Outcome.Failure>(DevelopmentReports.sarif("{\"version\":\"unknown\"}"))
    }

    @Test fun comparesExactNumbersOnlyUnderSameMethodEnvironmentAndUnits() {
        val base = fixture("measurements.json")
        val current = base.replace("12.10", "12.12").replace("aaaa", "bbbb")
        val report = DevelopmentReports.measurements(current, base).valueOrNull()!!
        val metric = (report["metrics"] as JsonArray).items.first() as JsonObject
        assertEquals(JsonNumber("0.02"), metric["delta"])
        assertEquals(json(false), report["measurementAuthenticityVerified"])
        for (changed in listOf(base.replace("synthetic-host", "other"), base.replace("warm-run-3", "cold-run"), base.replace("\"ms\"", "\"seconds\""), base.replace("12.10", "1e999"))) {
            assertIs<Outcome.Failure>(DevelopmentReports.measurements(changed, base))
        }
    }

    @Test fun outputCorpusIsStrictJsonWithExplicitScope() {
        val o = JsonParser().parse(fixture("report.json")).valueOrNull() as JsonObject
        assertEquals(json("dpai.development-report"), o["format"])
        assertEquals(json("READ_ONLY"), o["effect"])
        assertEquals(json(false), o["contentSnapshot"])
    }
}

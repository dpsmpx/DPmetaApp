package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Типы предметной области (ТЗ, §6.3): строгие ссылки, наблюдение и его отпечаток (вход вычисления, D-34). */
class ModelTest {
    private val payload = JsonObject(linkedMapOf("open" to JsonNumber.of(3), "title" to JsonString("этап 2")))

    private fun observation(asOf: Long = 1_000, raw: String = "ответ") =
        Observation.of(ObservationSource.GITHUB, "dpsmpx/DPmetaApp", "pulls", Timestamp(asOf), "github.pulls.v1", payload, Hashing.sha256(raw)).valueOrNull()!!

    @Test
    fun repoRefIsStrict() {
        assertEquals("dpsmpx/DPmetaApp", RepoRef.parse("dpsmpx/DPmetaApp").valueOrNull().toString())
        for (bad in listOf("dpsmpx", "a/b/c", "-x/y", "x/..", "x/y.git", "x/y?z", "x/ y", "x/y#1", "x-/y")) {
            assertIs<Outcome.Failure>(RepoRef.parse(bad), bad)
        }
    }

    @Test
    fun sameContentIsTheSameObservationRegardlessOfTime() {
        assertEquals(observation(asOf = 1).id, observation(asOf = 999_999).id, "повтор того же ответа не дублируется")
        assertNotEquals(observation(raw = "a").id, observation(raw = "b").id)
    }

    @Test
    fun badFieldsAreRefused() {
        val h = Hashing.sha256("x")
        assertIs<Outcome.Failure>(Observation.of(ObservationSource.SITE, "", "probe", Timestamp(0), "site.probe.v1", payload, h))
        assertIs<Outcome.Failure>(Observation.of(ObservationSource.SITE, "dpsmpx.com", "Probe", Timestamp(0), "site.probe.v1", payload, h))
        assertIs<Outcome.Failure>(Observation.of(ObservationSource.SITE, "dpsmpx.com\n", "probe", Timestamp(0), "site.probe.v1", payload, h))
    }

    /** Уровни 0–3 по порядку (D-07; уровень 3 — D-37: доступен с предупреждением и вводом имени). */
    @Test
    fun levelsZeroToThree() {
        assertEquals(listOf(0, 1, 2, 3), ActionLevel.entries.map { it.code })
        assertEquals(ActionLevel.DANGEROUS, ActionLevel.entries.last())
    }
}

package com.dpsmpx.dpai.core.events

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(11))
    private val task = ids.next(IdKinds.TASK)

    private fun event() = EventEnvelope(
        id = ids.next(IdKinds.EVENT),
        type = EventType.TASK_CREATED,
        stream = StreamRef.of(task),
        sequence = 1,
        occurredAt = clock.now(),
        recordedAt = clock.now(),
        correlationId = ids.next(IdKinds.CORRELATION),
        payload = JsonObject.of("title" to json("Собрать ядро"), "priority" to json("HIGH")),
    )

    @Test
    fun roundTripThroughCanonicalJsonKeepsStableId() {
        val e = event()
        val text = CanonicalJson.write(e.toJson())
        val back = EventEnvelope.fromJson(JsonParser().parse(text).valueOrNull()!!)
        assertEquals(e, back.valueOrNull())
        assertTrue(text.contains("\"format\":\"dpai.event\""))
        assertTrue(text.contains(e.id.value))
    }

    @Test
    fun strictReadingCollectsAllFindings() {
        val good = event().toJson()
        val broken = JsonObject(
            good.fields + mapOf(
                "type" to JsonString("TASK_TELEPORTED"),
                "sequence" to json(0),
                "extra" to json(true),
            ) - "recordedAt",
        )
        val f = assertIs<Outcome.Failure>(EventEnvelope.fromJson(broken))
        assertEquals(
            setOf(DiagCode.EVENT_TYPE_UNKNOWN, DiagCode.VALUE_INVALID, DiagCode.FIELD_MISSING, DiagCode.FIELD_UNKNOWN),
            f.errors.map { it.code }.toSet(),
        )
    }

    @Test
    fun newerFormatAndWrongStreamAreRejected() {
        val good = event().toJson()
        val newer = JsonObject(good.fields + ("version" to json(2)))
        assertEquals(DiagCode.FORMAT_VERSION_NEWER, assertIs<Outcome.Failure>(EventEnvelope.fromJson(newer)).errors.single().code)
        val session = ids.next(IdKinds.SESSION)
        val wrong = JsonObject(good.fields + ("stream" to JsonObject.of("kind" to json("task"), "id" to json(session.value))))
        assertEquals(DiagCode.ID_WRONG_KIND, assertIs<Outcome.Failure>(EventEnvelope.fromJson(wrong)).errors.single().code)
    }

    @Test
    fun streamRefs() {
        assertEquals("task:${task.value}", StreamRef.of(task).toString())
        assertEquals(StreamRef.SYSTEM, StreamRef.parse("system", "system").valueOrNull())
        assertIs<Outcome.Failure>(StreamRef.parse("galaxy", "x"))
        assertFailsWith<IllegalArgumentException> { StreamRef.of(ids.next(IdKinds.EVENT)) }
        assertFailsWith<IllegalArgumentException> { event().copy(sequence = 0) }
    }
}

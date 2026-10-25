package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.security.Proposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours

/** События и снимок слияний и коммитов задач (`dpai.merges-state` v2; v1 читается). */
class MergesProjectionTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(214))
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    private fun events(): List<MergeEvent> {
        val t = ids.next(IdKinds.TASK)
        val other = ids.next(IdKinds.TASK)
        val pending = PendingMerge(t, ids.next(IdKinds.APPROVAL), Proposal("git.merge", "/repo#main", listOf("task/${t.value}")), clock.now() + 24.hours)
        return listOf(
            MergeEvent.Committed(t, TaskCommit(hash, "task/${t.value}", clock.now())),
            MergeEvent.Requested(pending, clock.now()),
            MergeEvent.Merged(t, pending.approval, clock.now()),
            MergeEvent.Committed(other, TaskCommit("f".repeat(64), "task/${other.value}", clock.now())),
            MergeEvent.Declined(other, ids.next(IdKinds.APPROVAL), clock.now()),
        )
    }

    @Test
    fun everyEventSurvivesItsPayload() {
        for (e in events()) {
            val back = MergesProjection.decode(MergesProjection.type(e), e.at, MergesProjection.payload(e))
            assertEquals(e, back?.valueOrNull(), "$e: ${back?.diagnostics}")
        }
        assertEquals(EventType.GIT_COMMIT_CREATED, MergesProjection.type(events().first()))
    }

    @Test
    fun snapshotKeepsCommitsAndReadsVersionOne() {
        val state = events().fold(MergesState.EMPTY, MergesProjection::applyEvent)
        assertEquals(2, state.commits.size)
        assertEquals(state, MergesProjection.decode(MergesProjection.encode(state)).valueOrNull())
        // Снимок v1 — без коммитов: читается, коммитов нет.
        val v1 = JsonObject(LinkedHashMap(MergesProjection.encode(state.copy(commits = emptyMap())).fields).apply {
            put("version", JsonNumber.of(1))
            remove("commits")
        })
        assertEquals(state.copy(commits = emptyMap()), MergesProjection.decode(v1).valueOrNull())
    }

    @Test
    fun malformedHashIsRefusedNotDropped() {
        val e = events().first() as MergeEvent.Committed
        val bad = JsonObject(LinkedHashMap(MergesProjection.payload(e).fields).apply { put("commit", JsonString("не хеш")) })
        assertIs<Outcome.Failure>(MergesProjection.decode(EventType.GIT_COMMIT_CREATED, e.at, bad))
        val snapshot = MergesProjection.encode(MergesState(commits = mapOf(e.task to e.commit)))
        val broken = JsonObject(LinkedHashMap(snapshot.fields).apply { put("commits", com.dpsmpx.dpai.core.serialization.JsonArray(listOf(bad))) })
        assertIs<Outcome.Failure>(MergesProjection.decode(broken), "запись коммита с неверным хешем в снимке — отказ, а не пропуск")
        assertFailsWith<IllegalArgumentException> { TaskCommit("ABC", "b", clock.now()) }
    }
}

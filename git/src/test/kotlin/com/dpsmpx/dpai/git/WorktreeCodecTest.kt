package com.dpsmpx.dpai.git

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** События и снимок деревьев (D-09, D-12): туда и обратно без потерь, чтение строгое. */
class WorktreeCodecTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(55))
    private val repo = Repository(ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), "/src/app")
    private val tree = ids.next(IdKinds.WORKTREE)
    private val session = ids.next(IdKinds.SESSION)

    private val events = listOf(
        WorktreeEvent.RepositoryRegistered(repo, clock.now()),
        WorktreeEvent.Created(Worktree(tree, repo.id, "/src/trees/t1", "task/t1"), clock.now()),
        WorktreeEvent.Claimed(tree, session, clock.now()),
        WorktreeEvent.Released(tree, session, clock.now()),
        WorktreeEvent.Removed(tree, clock.now()),
    )

    @Test
    fun eventsRoundTripThroughCanonicalJson() {
        for (e in events) {
            val text = CanonicalJson.write(WorktreeCodec.payload(e))
            val back = WorktreeCodec.decode(WorktreeCodec.type(e), e.at, JsonParser().parse(text).valueOrNull() as JsonObject)
            assertEquals(e, back?.valueOrNull(), text)
        }
        assertNull(WorktreeCodec.decode(com.dpsmpx.dpai.core.events.EventType.TASK_CREATED, clock.now(), JsonObject(emptyMap())))
    }

    @Test
    fun stateRoundTripsAndUnknownFieldIsRefused() {
        val state = Worktrees.applyAll(WorktreesState.EMPTY, events.take(3))
        assertEquals(session, state.worktrees.getValue(tree).owner)
        val json = WorktreeCodec.encodeState(state)
        assertEquals(state, WorktreeCodec.decodeState(JsonParser().parse(CanonicalJson.write(json)).valueOrNull() as JsonObject).valueOrNull())
        val extra = JsonObject(json.fields + ("лишнее" to JsonString("x")))
        assertEquals(DiagCode.FIELD_UNKNOWN, (WorktreeCodec.decodeState(extra) as Outcome.Failure).diagnostics.first().code)
    }
}

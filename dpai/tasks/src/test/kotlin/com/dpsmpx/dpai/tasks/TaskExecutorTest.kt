package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Исполнитель задачи и ревью в политике (D-100): `dpai.tasks-state` v8, прежние записи — Claude. */
class TaskExecutorTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(7))

    private fun state(policy: TaskPolicy): TasksState {
        val id = ids.next(IdKinds.TASK)
        val events = Tasks().create(TasksState(), id, "задача", "цель", Priority.NORMAL, clock.now(), taskPolicy = policy).valueOrNull()!!
        return Tasks.applyAll(TasksState(), events)
    }

    @Test
    fun policyRoundTrip() {
        val p = TaskPolicy(executor = Executor.CODEX, reviewExecutor = Executor.GEMINI, reviews = setOf(ReviewRole.CODE))
        assertEquals(Executor.GEMINI, p.reviewer)
        assertEquals(Executor.CODEX, TaskPolicy(executor = Executor.CODEX).reviewer, "ревьюер по умолчанию — исполнитель задачи")
        val s = state(p)
        val json = TaskCodec.encodeState(s)
        assertEquals(s, TaskCodec.decodeState(json).valueOrNull())
        val claude = TaskCodec.encodeState(state(TaskPolicy())).toString()
        assertFalse("executor" in claude, "Claude не пишется: записи до D-100 читаются как Claude")
    }

    @Test
    fun unknownExecutorRefused() {
        val json = TaskCodec.encodeState(state(TaskPolicy(executor = Executor.GEMINI))).toString().replace("\"gemini\"", "\"copilot\"")
        val r = TaskCodec.decodeState(JsonParser().parse(json).valueOrNull() as JsonObject)
        assertIs<Outcome.Failure>(r)
        assertTrue(r.errors.any { "copilot" in it.message })
    }
}

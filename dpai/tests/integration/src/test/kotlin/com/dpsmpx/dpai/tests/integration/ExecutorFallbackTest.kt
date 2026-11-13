package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.accounts.LimitLevel
import com.dpsmpx.dpai.accounts.LimitWindow
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.ChatRole
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.efficiency.EfficiencyPolicy
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import org.junit.jupiter.api.io.TempDir

/**
 * Исполнители задач (D-100): задача идёт на слот своего исполнителя; у
 * всех слотов исполнителя предел — к следующему по кругу Claude → Codex →
 * Gemini, с флагами того CLI, а не Claude Code. Круг — только по настройке
 * человека (D-165); без неё задача ждёт, причина называет, кому её можно
 * передать, а передаёт человек командой `task.executor`.
 */
class ExecutorFallbackTest {
    @TempDir
    lateinit var dir: Path

    private fun Orchestrator.run(c: Command): OperationResult<*> = submit(c).also { runCommands() }.get()

    /** Слот исполнителя [executor] с входом аккаунтом на target стенда. */
    private fun slot(h: AccountsHook, s0: AccountsState, executor: Executor, dirName: String): AccountsState {
        var s = s0
        val sub = h.ids.next(IdKinds.SUBSCRIPTION)
        s = Accounts.applyAll(s, h.logic.registerSubscription(s, sub, executor.title, h.now, maxParallelSessions = 16, rotationAllowed = true, executor = executor).valueOrNull()!!)
        val slot = h.ids.next(IdKinds.ACCOUNT)
        s = Accounts.applyAll(s, h.logic.registerSlot(s, slot, sub, h.target, "/home/dev/$dirName", h.now).valueOrNull()!!)
        val method = if (executor == Executor.CODEX) AuthStatus.CHATGPT else AuthStatus.GOOGLE
        return Accounts.applyAll(s, h.logic.observeAuthStatus(s, slot, AuthStatus(true, method, null, null, null), h.now).valueOrNull()!!)
    }

    private fun limitClaude(h: AccountsHook, s: AccountsState): AccountsState =
        Accounts.applyAll(s, h.logic.observeLimit(s, h.claudeSlot, LimitWindow.SESSION, LimitLevel.REACHED, h.now + 3.hours, h.now).valueOrNull()!!)

    @Test
    fun claudeAtLimitGoesToCodexWithItsFlags() {
        val h = EfficiencyHarness(dir.resolve("a"), EfficiencyPolicy(), accountsHook = { k -> limitClaude(k, slot(k, k.state, Executor.CODEX, ".codex")) })
        assertEquals(OperationStatus.SUCCEEDED, h.orchestrator.run(Command.SetExecutorSwitch(true)).status)
        assertTrue(h.orchestrator.control.autoExecutorSwitch)
        val t = h.orchestrator.addTask("правка", "поправить README", Priority.NORMAL, policy = TaskPolicy(model = "opus", effort = "high")).valueOrNull()!!
        h.run()
        assertEquals(TaskState.DONE, h.orchestrator.tasks.tasks.getValue(t).state, "${h.orchestrator.tasks.tasks.getValue(t).lastReason} ${h.orchestrator.sessions.sessions.values.map { it.state to it.lastReason }} ${h.starts.map { it.request.executor }}")
        val start = h.starts.first()
        assertEquals(Executor.CODEX, start.request.executor)
        // Флаги Claude Code (режим разрешений, запасная модель) Codex не получает; модель и усилие политики — для Claude.
        assertNull(start.request.permissionMode)
        assertNull(start.request.model)
        assertNull(start.request.effort)
        val bodies = h.orchestrator.postedSince(0).first.mapNotNull {
            when (it) {
                is com.dpsmpx.dpai.notifications.NotificationDecision.Post -> it.notification.body
                is com.dpsmpx.dpai.notifications.NotificationDecision.Update -> it.notification.body
                else -> null
            }
        }
        assertTrue(bodies.any { "Codex" in it }, "человек видит исполнителя задачи: $bodies")
    }

    @Test
    fun taskExecutorIsKept() {
        val h = EfficiencyHarness(dir.resolve("b"), EfficiencyPolicy(), accountsHook = { k -> slot(k, k.state, Executor.GEMINI, ".gemini") })
        val t = h.orchestrator.addTask("правка", "поправить README", Priority.NORMAL, policy = TaskPolicy(executor = Executor.GEMINI, model = "gemini-x-pro")).valueOrNull()!!
        h.run()
        assertEquals(TaskState.DONE, h.orchestrator.tasks.tasks.getValue(t).state)
        assertEquals(Executor.GEMINI, h.starts.first().request.executor)
        assertEquals("gemini-x-pro", h.starts.first().request.model, "модель политики — исполнителю задачи")
        // Задача Claude при свободном слоте Claude — на Claude, а не на Gemini.
        val c = h.orchestrator.addTask("вторая", "ещё правка", Priority.NORMAL).valueOrNull()!!
        h.run()
        val second = h.starts.first { h.orchestrator.sessions.sessions[it.session]?.task == c }
        assertEquals(Executor.CLAUDE, second.request.executor)
    }

    @Test
    fun withoutTheSettingTheTaskWaitsAndTheHumanHandsItOver() {
        val h = EfficiencyHarness(dir.resolve("d"), EfficiencyPolicy(), accountsHook = { k -> limitClaude(k, slot(k, k.state, Executor.CODEX, ".codex")) })
        assertTrue(!h.orchestrator.control.autoExecutorSwitch, "по умолчанию переход выключен")
        val t = h.orchestrator.addTask("правка", "поправить README", Priority.NORMAL, policy = TaskPolicy(model = "opus", effort = "high")).valueOrNull()!!
        h.run(maxTicks = 3)
        assertTrue(h.starts.isEmpty(), "без настройки задача не уходит к Codex сама: ${h.starts.map { it.request.executor }}")
        assertEquals(listOf(Executor.CODEX), h.orchestrator.handoverCandidates(t))
        val why = h.orchestrator.waitingReason(t).orEmpty()
        assertTrue("Claude Code предел" in why && "Codex" in why, why)
        // Исполнитель без слота — отказ словами, задача не тронута.
        val none = h.orchestrator.run(Command.SetExecutor(t, Executor.GEMINI))
        assertEquals(OperationStatus.FAILED, none.status)
        assertEquals(DiagCode.ACCOUNT_NONE_AVAILABLE, none.diagnostics.first().code)
        assertEquals(Executor.CLAUDE, h.orchestrator.tasks.tasks.getValue(t).policy.executor)
        // Передача человеком: модель и усилие Claude снимаются, ход — у Codex.
        assertEquals(OperationStatus.SUCCEEDED, h.orchestrator.run(Command.SetExecutor(t, Executor.CODEX)).status)
        val policy = h.orchestrator.tasks.tasks.getValue(t).policy
        assertEquals(Executor.CODEX, policy.executor)
        assertNull(policy.model)
        assertNull(policy.effort)
        h.run()
        assertEquals(TaskState.DONE, h.orchestrator.tasks.tasks.getValue(t).state)
        assertEquals(Executor.CODEX, h.starts.first().request.executor)
        assertTrue(h.orchestrator.chat(t).any { it.role == ChatRole.EVENT && "передана Codex" in it.text }, h.orchestrator.chat(t).toString())
        assertTrue(h.orchestrator.handoverCandidates(t).isEmpty(), "завершённую задачу передавать некому")
    }

    @Test
    fun busyIsNotLimit() {
        // Слотов Codex нет вовсе: задача Codex ждёт свой исполнитель, а не уходит к Gemini или Claude.
        val h = EfficiencyHarness(dir.resolve("c"), EfficiencyPolicy(), accountsHook = { k -> slot(k, k.state, Executor.GEMINI, ".gemini") })
        val t = h.orchestrator.addTask("правка", "поправить README", Priority.NORMAL, policy = TaskPolicy(executor = Executor.CODEX)).valueOrNull()!!
        h.run(maxTicks = 3)
        assertTrue(h.starts.isEmpty())
        assertEquals(TaskState.READY, h.orchestrator.tasks.tasks.getValue(t).state)
    }
}

package com.dpsmpx.dpai.execution.target

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TargetLifecycle
import com.dpsmpx.dpai.core.state.TargetState
import com.dpsmpx.dpai.core.state.TransitionMode
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.ssh.SshProcessHost
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Реестр targets и их здоровья (ТЗ, §11, §14): регистрация, пробы,
 * `doctor`, выключение. Каждый вызов агента идёт через [AgentClient]
 * своего target; для `MOCK` хост задаёт [mockHost], для `IN_APP` —
 * [inAppHost] (агент в процессе приложения, D-75).
 *
 * Смены состояния возвращаются шагом [TargetHealthTracker.Step] — в
 * журнал их записывает оркестратор (D-12, D-24).
 */
class ExecutionTargetManager(
    private val clock: Clock,
    private val policy: TargetHealthPolicy = TargetHealthPolicy(),
    private val local: ProcessHost = LocalProcessHost(clock),
    private val mockHost: ProcessHost? = null,
    /**
     * Хост SSH-targets: на JVM — системный OpenSSH (D-31); в приложении
     * Android, где его нет, — клиент на JVM (D-35, модуль `jsch`).
     */
    private val sshHost: (SshEndpoint, TargetPlatform) -> ProcessHost = { e, p -> SshProcessHost(e, p, local) },
    /** Версия контроллера: `doctor` сверяет с ней версию агента (`null` — не сверять). */
    private val controllerVersion: String? = null,
    /** Канал к агенту в процессе приложения (`InAppAgentHost`, D-75); `null` — target «в приложении» не регистрируется. */
    private val inAppHost: ProcessHost? = null,
) {
    private val targets = LinkedHashMap<ExecutionTargetId, ExecutionTarget>()
    private val health = HashMap<ExecutionTargetId, TargetHealth>()

    @Synchronized
    fun register(target: ExecutionTarget): Outcome<ExecutionTarget> {
        if (target.id in targets) return Outcome.failure(DiagCode.VALUE_INVALID, "target уже зарегистрирован", "target ${target.id}")
        if (target.type == TargetType.MOCK && mockHost == null) return Outcome.failure(DiagCode.UNSUPPORTED, "нет хоста для MOCK target", "target ${target.id}")
        if (target.type == TargetType.IN_APP && inAppHost == null) return Outcome.failure(DiagCode.UNSUPPORTED, "нет агента в приложении для IN_APP target", "target ${target.id}")
        targets[target.id] = target
        health[target.id] = TargetHealth()
        return Outcome.success(target)
    }

    @Synchronized
    fun all(): List<ExecutionTarget> = targets.values.toList()

    @Synchronized
    fun health(id: ExecutionTargetId): TargetHealth? = health[id]

    fun client(id: ExecutionTargetId): Outcome<AgentClient> {
        val t = synchronized(this) { targets[id] } ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого target", "target $id")
        return Outcome.success(AgentClient(hostFor(t), t.agentCommand, t.agentHome, "target ${t.id}"))
    }

    /**
     * Проба: агент отвечает на `doctor`. Пока предохранитель открыт и пауза
     * не прошла, проба не идёт и здоровье не меняется (ТЗ, §81).
     */
    fun probe(id: ExecutionTargetId): Outcome<TargetHealthTracker.Step> {
        val current = synchronized(this) { health[id] } ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого target", "target $id")
        if (current.state == TargetState.DISABLED) return Outcome.failure(DiagCode.TARGET_DISABLED, "target выключен", "target $id")
        if (!current.probeAllowed(clock.now(), policy)) return Outcome.success(TargetHealthTracker.Step(current, null))
        val client = when (val c = client(id)) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        val started = clock.monotonicNanos()
        val reply = client.doctor()
        val latency = (clock.monotonicNanos() - started).nanoseconds
        val result = ProbeResult(
            at = clock.now(),
            reachable = reply.isSuccess,
            latency = if (reply.isSuccess) Measure.observed(latency) else Measure.unknown("проба не дошла"),
            diagnostics = reply.diagnostics,
        )
        return Outcome.success(record(id, result), reply.diagnostics.filterNot { it.isError })
    }

    /** Применить готовый итог пробы — для проб, сделанных снаружи (и тестов). */
    @Synchronized
    fun record(id: ExecutionTargetId, probe: ProbeResult): TargetHealthTracker.Step {
        val step = TargetHealthTracker.observe(health.getValue(id), probe, policy)
        health[id] = step.health
        return step
    }

    fun doctor(id: ExecutionTargetId): Outcome<DoctorResult> {
        val client = when (val c = client(id)) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        val sent = clock.now()
        val report = client.doctor()
        return Outcome.success(TargetDoctor.evaluate(report, sent, clock.now(), controllerVersion = controllerVersion))
    }

    /** Выключить (явно) или включить target: включённый начинает с `UNKNOWN`. */
    @Synchronized
    fun setEnabled(id: ExecutionTargetId, enabled: Boolean): Outcome<TargetHealthTracker.Step> {
        val h = health[id] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого target", "target $id")
        if (enabled && h.state != TargetState.DISABLED) return Outcome.success(TargetHealthTracker.Step(h, null))
        val to = if (enabled) TargetState.UNKNOWN else TargetState.DISABLED
        return when (val t = TargetLifecycle.table.transition(h.state, to, TransitionMode.EXPLICIT, "target $id")) {
            is Outcome.Failure -> t
            is Outcome.Success -> {
                val next = if (enabled) TargetHealth() else h.copy(state = TargetState.DISABLED)
                health[id] = next
                Outcome.success(TargetHealthTracker.Step(next, h.state.takeIf { t.value.changed }))
            }
        }
    }

    private fun hostFor(t: ExecutionTarget): ProcessHost = when (t.type) {
        TargetType.LOCAL -> local
        TargetType.MOCK -> mockHost!!
        TargetType.IN_APP -> inAppHost!!
        TargetType.TERMUX_LOCAL, TargetType.LINUX_SSH, TargetType.WINDOWS_REMOTE -> sshHost(t.ssh!!, t.platform)
    }
}

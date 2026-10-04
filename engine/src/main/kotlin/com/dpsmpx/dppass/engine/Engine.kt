package com.dpsmpx.dppass.engine

import com.dpsmpx.dppass.config.ConfigIr
import com.dpsmpx.dppass.config.ProtocolKind
import com.dpsmpx.dppass.config.SecurityKind
import com.dpsmpx.dppass.config.TransportKind
import com.dpsmpx.dppass.config.VlessFlow
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.Outcome

/** Возможности, которые бывают не у каждого ядра. */
enum class EngineFeature {
    /** Ядро само знает приложение-владельца соединения (правила с apps внутри ядра). */
    APP_RULES_IN_ENGINE,
    /** IP-правила к домену после резолва (DomainResolution.IF_NO_DOMAIN_MATCH). */
    DOMAIN_RESOLUTION_IF_NO_MATCH,
    DNS_DOH,
    DNS_DOT,
    DNS_RULES,
    GEOSITE,
    GEOIP,
    /** Ядро принимает fd TUN от платформы. */
    TUN_FD,
    /** Ядро различает сеть устройства (условие network в правилах). */
    NETWORK_CONDITION,
}

/**
 * Матрица возможностей одного ядра одной версии (D-04, D-18).
 *
 * Смена версии ядра — это смена матрицы: разница матриц
 * (`capabilityDiff`) — обязательная часть обзора обновления.
 */
data class EngineCapabilities(
    val backendId: String,
    val backendVersion: String,
    val protocols: Set<ProtocolKind>,
    val transports: Set<TransportKind>,
    val securities: Set<SecurityKind>,
    val flows: Set<VlessFlow>,
    val features: Set<EngineFeature>,
)

data class CapabilityDiff(val added: Set<String>, val removed: Set<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
}

fun capabilityDiff(old: EngineCapabilities, new: EngineCapabilities): CapabilityDiff {
    fun flat(c: EngineCapabilities): Set<String> =
        c.protocols.map { "protocol:$it" }.toSet() + c.transports.map { "transport:$it" } +
            c.securities.map { "security:$it" } + c.flows.map { "flow:$it" } + c.features.map { "feature:$it" }
    val a = flat(old)
    val b = flat(new)
    return CapabilityDiff(added = b - a, removed = a - b)
}

/**
 * Скомпилированная конфигурация конкретного ядра. Содержимое — с
 * секретами, поэтому `toString()` его не показывает; контрольная сумма
 * — для журнала и истории активаций.
 */
class CompiledConfig(
    val backendId: String,
    val backendVersion: String,
    val profileId: ProfileId,
    private val payload: String,
    val checksum: String,
) {
    /** Только для передачи ядру и для тестов компилятора. */
    fun payloadForEngine(): String = payload

    override fun toString(): String = "CompiledConfig($backendId $backendVersion, profile=$profileId, sha256=${checksum.take(12)})"
}

/** Что именно компилировать: какой профиль активен и есть ли TUN. */
data class CompilePlan(val profileId: ProfileId, val withTun: Boolean)

/**
 * IR → конфигурация ядра. Обязан сохранить смысл IR или отказать с
 * диагностикой; приблизительная замена допустима только как явный
 * SEMANTIC_FALLBACK в находках (D-05).
 */
interface BackendCompiler {
    val capabilities: EngineCapabilities

    fun compile(ir: ConfigIr, plan: CompilePlan): Outcome<CompiledConfig>
}

/** Вход TUN для ядра: fd от платформы и MTU. Домен про fd больше ничего не знает. */
data class TunInput(val fd: Int, val mtu: Int)

enum class EngineState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

data class TrafficStats(val uplinkBytes: Long, val downlinkBytes: Long)

/** Запущенное ядро. */
interface EngineSession {
    val compiled: CompiledConfig

    fun state(): EngineState

    fun stats(): TrafficStats

    suspend fun stop()
}

/**
 * Граница ядра (D-04). Реализации: фиктивная [mock] (сейчас) и Xray
 * (этап 2 `ROADMAP`). Домен не знает, какая из них работает.
 */
interface ProxyEngine {
    val capabilities: EngineCapabilities

    /** Проверка конфигурации самим ядром без запуска (у Xray — `-test`). */
    suspend fun validate(config: CompiledConfig): Outcome<Unit>

    suspend fun start(config: CompiledConfig, tun: TunInput?): Outcome<EngineSession>
}

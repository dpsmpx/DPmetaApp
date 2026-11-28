package com.dpsmpx.dpai.accounts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

enum class AiMode { SINGLE, MULTI_SEQUENTIAL }

/** API-профиль и CLI имеют разные источники идентичности; совпадение имён не делает их одной моделью. */
enum class AiBackend { ANTHROPIC_API, OPENAI_API, GEMINI_API, CLAUDE, CODEX, GEMINI, CLOUD_ROUTINE }

data class AiModelRef(val backend: AiBackend, val model: String?, val profile: AccountId? = null, val address: String? = null) {
    val api: Boolean get() = backend in setOf(AiBackend.ANTHROPIC_API, AiBackend.OPENAI_API, AiBackend.GEMINI_API)
    val native: Boolean get() = backend in setOf(AiBackend.CLAUDE, AiBackend.CODEX, AiBackend.GEMINI)
    init {
        require(model == null || model.length in 1..128 && model.none { it.isISOControl() || it.isWhitespace() })
        require(!api || profile != null && address != null && ApiProfiles.baseUrl(address) == null)
        require(api || address == null)
        require(!native || profile == null)
        require(backend != AiBackend.CLOUD_ROUTINE || profile != null && model == null)
    }
    fun json(): JsonObject = JsonObject.of("backend" to JsonString(backend.name), "model" to (model?.let(::JsonString) ?: JsonNull),
        "profile" to (profile?.value?.let(::JsonString) ?: JsonNull), "address" to (address?.let(::JsonString) ?: JsonNull))
    companion object {
        fun api(profile: ApiProfile, model: String = profile.model): AiModelRef = AiModelRef(when (profile.kind) {
            ProviderKind.ANTHROPIC -> AiBackend.ANTHROPIC_API
            ProviderKind.OPENAI_COMPATIBLE -> AiBackend.OPENAI_API
            ProviderKind.GEMINI -> AiBackend.GEMINI_API
        }, model, profile.id, profile.baseUrl)
        fun native(executor: Executor, model: String?): AiModelRef = AiModelRef(when (executor) {
            Executor.CLAUDE -> AiBackend.CLAUDE
            Executor.CODEX -> AiBackend.CODEX
            Executor.GEMINI -> AiBackend.GEMINI
        }, model)
        fun read(raw: JsonValue): AiModelRef {
            val o = raw as JsonObject
            require(o.fields.keys == setOf("backend", "model", "profile", "address"))
            fun optional(name: String): String? = if (o[name] == JsonNull) null else (o[name] as JsonString).value
            return AiModelRef(AiBackend.valueOf((o["backend"] as JsonString).value), optional("model"),
                optional("profile")?.let { IdKinds.ACCOUNT.parse(it).valueOrNull() ?: error("id не аккаунта") }, optional("address"))
        }
    }
}

/** Одна активная сессия в начальных режимах; разрешённые модели задаёт только человек. */
data class AiModePolicy(val mode: AiMode, val allowed: List<AiModelRef>) {
    init {
        require(allowed.size in 1..64 && allowed.distinct().size == allowed.size)
        require(allowed.all { it.model != null && it.backend != AiBackend.CLOUD_ROUTINE })
        require(mode != AiMode.SINGLE || allowed.size == 1)
    }
    val revision: String get() = Hashing.sha256(json()).hex
    fun json(): JsonObject = JsonObject.of("mode" to JsonString(mode.name), "allowed" to JsonArray(allowed.map { it.json() }))
    companion object {
        fun read(raw: JsonValue): AiModePolicy {
            val o = raw as JsonObject
            require(o.fields.keys == setOf("mode", "allowed"))
            val refs = (o["allowed"] as JsonArray).items.also { require(it.size in 1..64) }.map(AiModelRef::read)
            return AiModePolicy(AiMode.valueOf((o["mode"] as JsonString).value), refs)
        }
    }
}

/** Резерв CLI/облачного вызова переживает процесс приложения; текст и ключи в него не входят. */
data class AiReservation(val owner: String, val model: AiModelRef, val target: ExecutionTargetId? = null, val reference: String? = null, val turn: Int? = null, val location: String? = null) {
    init {
        require(owner.length in 1..200 && owner.none(Char::isISOControl))
        require(reference == null || reference.length <= 500 && reference.none(Char::isISOControl))
        require(turn == null || turn > 0 && model.native)
        require(location == null || model.native && location.matches(Regex("[0-9a-f]{64}")))
    }
}

data class AiRoutingState(val policy: AiModePolicy? = null, val pending: List<AiReservation> = emptyList()) {
    init { require(pending.size <= 256 && pending.map { it.owner }.distinct().size == pending.size && pending.none { it.model.api }) }
    val revision: String get() = policy?.revision ?: "unconfigured"
    fun json(): JsonObject = JsonObject.of("format" to JsonString("dpai.model-routing"), "version" to JsonNumber.of(1),
        "policy" to (policy?.json() ?: JsonNull), "pending" to JsonArray(pending.map {
            JsonObject.of("owner" to JsonString(it.owner), "model" to it.model.json(),
                "target" to (it.target?.value?.let(::JsonString) ?: JsonNull), "reference" to (it.reference?.let(::JsonString) ?: JsonNull),
                "turn" to (it.turn?.let { n -> JsonNumber.of(n) } ?: JsonNull), "location" to (it.location?.let(::JsonString) ?: JsonNull))
        }))
    companion object {
        fun read(raw: JsonValue): Outcome<AiRoutingState> = try {
            val o = raw as JsonObject
            require(o.fields.keys == setOf("format", "version", "policy", "pending"))
            require(o["format"] == JsonString("dpai.model-routing") && o["version"] == JsonNumber.of(1))
            val pending = (o["pending"] as JsonArray).items.also { require(it.size <= 256) }.map {
                val r = it as JsonObject
                require(r.fields.keys == setOf("owner", "model", "target", "reference", "turn", "location"))
                val target = if (r["target"] == JsonNull) null else IdKinds.TARGET.parse((r["target"] as JsonString).value).valueOrNull() ?: error("id не target")
                AiReservation((r["owner"] as JsonString).value, AiModelRef.read(r["model"]!!), target,
                    if (r["reference"] == JsonNull) null else (r["reference"] as JsonString).value,
                    if (r["turn"] == JsonNull) null else (r["turn"] as JsonNumber).toLongExact()!!.also { n -> require(n in 1..Int.MAX_VALUE) }.toInt(),
                    if (r["location"] == JsonNull) null else (r["location"] as JsonString).value)
            }
            Outcome.success(AiRoutingState(if (o["policy"] == JsonNull) null else AiModePolicy.read(o["policy"]!!), pending))
        } catch (_: RuntimeException) { Outcome.failure(DiagCode.VALUE_INVALID, "неверная или неподдержанная настройка режима ИИ") }
    }
}

/**
 * Общий допуск, не планировщик задач. API держит резерв на всё общение с инструментами;
 * CLI — до подтверждённого завершения. Запись намерения CLI предшествует запуску.
 * Неизвестный исход сохраняет резерв и запрещает самовольный повтор или смену режима.
 */
class AiAdmission(private var installed: Boolean = true) {
    private var state = AiRoutingState()
    private var writer: (AiRoutingState) -> Outcome<Unit> = { Outcome.success(Unit) }
    private var problem: String? = null
    private val api = LinkedHashMap<String, AiModelRef>()

    @Synchronized fun install(snapshot: Outcome<AiRoutingState>, persist: (AiRoutingState) -> Outcome<Unit>) {
        check(!installed || api.isEmpty() && state.pending.isEmpty()) { "нельзя переустановить допуск с активными вызовами" }
        installed = true
        writer = persist
        when (snapshot) {
            is Outcome.Success -> { state = snapshot.value; problem = null }
            is Outcome.Failure -> { problem = snapshot.errors.joinToString("; ") { it.message } }
        }
    }
    @Synchronized fun snapshot(): AiRoutingState = state
    @Synchronized fun failure(): String? = problem
    @Synchronized fun active(): Int = api.size + state.pending.size
    @Synchronized fun configured(): Boolean = installed && state.policy != null

    @Synchronized fun configure(expected: String, policy: AiModePolicy?, nativeQuiescent: Boolean): Outcome<Unit> {
        problem?.let { return Outcome.failure(DiagCode.STATE_CONFLICT, "настройка ИИ не прочитана: $it") }
        if (state.revision != expected) return Outcome.failure(DiagCode.STATE_CONFLICT, "настройка режима уже изменилась")
        if (active() != 0 || !nativeQuiescent) return busy()
        return commit(state.copy(policy = policy))
    }

    @Synchronized fun check(model: AiModelRef): Outcome<Unit> {
        if (!installed) return Outcome.success(Unit)
        problem?.let { return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "режим ИИ недоступен: $it") }
        val policy = state.policy ?: return Outcome.success(Unit)
        if (model !in policy.allowed) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "модель не разрешена в режиме ${policy.mode}")
        if (active() != 0) return busy()
        return Outcome.success(Unit)
    }

    @Synchronized fun acquire(owner: String, model: AiModelRef, target: ExecutionTargetId? = null, turn: Int? = null, location: String? = null): Outcome<Permit> {
        if (!installed) return Outcome.success(Permit(this, owner, false))
        when (val checked = check(model)) { is Outcome.Failure -> return checked; is Outcome.Success -> Unit }
        if (owner in api || state.pending.any { it.owner == owner }) return busy()
        if (!model.api && state.pending.size >= 256) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "достигнут предел незавершённых ИИ-вызовов")
        if (model.api) api[owner] = model else {
            when (val saved = commit(state.copy(pending = state.pending + AiReservation(owner, model, target, turn = turn, location = location)))) {
                is Outcome.Failure -> return saved
                is Outcome.Success -> Unit
            }
        }
        return Outcome.success(Permit(this, owner, true))
    }

    /** Сверка уже существующего процесса не запускает новую модель и допускается при несовпадении политики. */
    @Synchronized fun recover(owner: String, model: AiModelRef, target: ExecutionTargetId?, location: String? = null): Outcome<Unit> {
        if (!installed) return Outcome.success(Unit)
        state.pending.firstOrNull { it.owner == owner }?.let {
            return if (it.target == target && it.location == location) Outcome.success(Unit)
            else Outcome.failure(DiagCode.STATE_CONFLICT, "адрес прежнего ИИ-запуска изменён; требуется сверка исходного target")
        }
        require(!model.api)
        if (state.pending.size >= 256) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "достигнут предел незавершённых ИИ-вызовов")
        return commit(state.copy(pending = state.pending + AiReservation(owner, model, target, location = location)))
    }

    /** Вызывает только код, получивший подтверждение завершения, либо человек после сверки облачного маршрута. */
    @Synchronized fun release(owner: String): Outcome<Unit> {
        api.remove(owner)
        if (state.pending.none { it.owner == owner }) return Outcome.success(Unit)
        return commit(state.copy(pending = state.pending.filterNot { it.owner == owner }))
    }

    @Synchronized fun cloudReference(owner: String, reference: String): Outcome<Unit> {
        if (reference.length !in 1..500 || reference.any(Char::isISOControl)) return Outcome.failure(DiagCode.VALUE_INVALID, "неверная ссылка облачной сессии")
        return commit(state.copy(pending = state.pending.map { if (it.owner == owner) it.copy(reference = reference) else it }))
    }

    private fun commit(next: AiRoutingState): Outcome<Unit> {
        problem?.let { return Outcome.failure(DiagCode.STATE_CONFLICT, "режим ИИ не прочитан: $it") }
        return when (val saved = writer(next)) {
            is Outcome.Failure -> saved
            is Outcome.Success -> Outcome.success(Unit).also { state = next }
        }
    }
    private fun <T> busy(): Outcome<T> = Outcome.failure(DiagCode.RUNNER_TURN_ACTIVE, "ИИ-вызов идёт или ожидает сверки; дождитесь завершения либо остановите его")

    class Permit internal constructor(private val gate: AiAdmission, val owner: String, private val tracked: Boolean) {
        fun release(): Outcome<Unit> = if (tracked) gate.release(owner) else Outcome.success(Unit)
    }
    companion object { val global: AiAdmission = AiAdmission(installed = false) }
}

package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.config.ConfigIr
import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.core.Ids
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.diagnostics.map
import com.dpsmpx.dppass.routing.DnsPolicy
import com.dpsmpx.dppass.routing.DnsRoute
import com.dpsmpx.dppass.routing.DnsServer
import com.dpsmpx.dppass.routing.DnsTransport
import com.dpsmpx.dppass.routing.RoutingPolicy
import com.dpsmpx.dppass.storage.BlobStore
import com.dpsmpx.dppass.storage.Quarantine
import com.dpsmpx.dppass.subscriptions.DeviceId
import com.dpsmpx.dppass.subscriptions.Subscription
import com.dpsmpx.dppass.subscriptions.SubscriptionCodec
import com.dpsmpx.dppass.subscriptions.SubscriptionFetcher
import com.dpsmpx.dppass.subscriptions.SubscriptionSource
import com.dpsmpx.dppass.subscriptions.SubscriptionUpdater
import com.dpsmpx.dppass.subscriptions.SubscriptionsState
import com.dpsmpx.dppass.subscriptions.UpdateResult

/**
 * Базовая политика подписки без своей конфигурации пользователя (D-36):
 * всё через туннель, локальные сети мимо него, DNS — DoH через туннель
 * по IP-адресу (имя самого резолвера резолвить нечем). Серверов в ней
 * нет — их даёт подписка.
 */
object BasePolicy {
    const val DNS_ID: String = "remote"
    const val DOH_URL: String = "https://1.1.1.1/dns-query"

    val ir: ConfigIr = ConfigIr(
        name = null,
        profiles = emptyList(),
        routing = RoutingPolicy(),
        dns = DnsPolicy(
            servers = listOf(DnsServer(DNS_ID, DnsTransport.Https(DOH_URL), DnsRoute.TUNNEL)),
            defaultServerId = DNS_ID,
        ),
    )
}

/**
 * Хранение подписок одной записью `subscriptions/state` (D-36):
 * атомарно (от [BlobStore]), в зашифрованном хранилище на устройстве
 * (D-19). Не прочиталось — исходник уходит в карантин, а не затирается.
 */
class SubscriptionBook(private val blobs: BlobStore, private val quarantine: Quarantine) {
    fun load(): Outcome<SubscriptionsState> {
        val bytes = when (val r = blobs.read(KEY)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value ?: return Outcome.Success(SubscriptionsState.EMPTY)
        }
        return when (val parsed = SubscriptionCodec.read(String(bytes, Charsets.UTF_8))) {
            is Outcome.Success -> parsed
            is Outcome.Failure -> when (val kept = quarantine.keep("subscriptions", bytes, parsed.errors.first())) {
                is Outcome.Failure -> Outcome.Failure(
                    listOf(
                        Diagnostic.error(DiagCode.STORAGE_CORRUPTED, "подписки не прочитаны и не сохранены в карантин")
                            .copy(causes = parsed.diagnostics + kept.diagnostics),
                    ),
                )
                is Outcome.Success -> Outcome.Success(
                    SubscriptionsState.EMPTY,
                    listOf(
                        Diagnostic.warning(DiagCode.STORAGE_CORRUPTED, "подписки не прочитаны; исходник сохранён в карантин, начато с пустого")
                            .copy(causes = parsed.diagnostics),
                    ),
                )
            }
        }
    }

    fun save(state: SubscriptionsState): Outcome<Unit> = blobs.write(KEY, SubscriptionCodec.write(state).toByteArray(Charsets.UTF_8))

    companion object {
        const val KEY: String = "subscriptions/state"
    }
}

/** Итог обновления подписки: что вернул конвейер и, если дошло, как прошла активация. */
data class SubscriptionRun(val subscription: Subscription, val update: UpdateResult, val activation: ActivationResult?) {
    val applied: Boolean get() = activation is ActivationResult.Activated
    val diagnostics: List<Diagnostic> get() = update.diagnostics + activation?.diagnostics.orEmpty()
}

/**
 * Подписки в приложении (D-36): список, добавление, идентификатор
 * устройства (D-33), обновление с активацией. Состояние читается один
 * раз и дальше меняется только через запись: что не сохранено, того нет.
 */
class SubscriptionService(
    private val book: SubscriptionBook,
    private val core: DPpassCore,
    private val clock: Clock,
) {
    @Volatile
    private var cached: SubscriptionsState? = null

    fun state(): Outcome<SubscriptionsState> = cached?.let { Outcome.Success(it) } ?: book.load().also { r ->
        if (r is Outcome.Success) cached = r.value
    }

    private fun commit(next: SubscriptionsState): Outcome<SubscriptionsState> = when (val w = book.save(next)) {
        is Outcome.Failure -> w
        is Outcome.Success -> Outcome.Success(next.also { cached = it })
    }

    /** Новая подписка по адресу `https://…`. Ничего не загружает: это делает [refresh]. */
    fun add(url: String, name: String?): Outcome<Subscription> {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") || trimmed.length > MAX_URL || trimmed.any { it.isWhitespace() }) {
            return Outcome.failure(DiagCode.SUBSCRIPTION_FETCH_FAILED, "адрес подписки — только https:// без пробелов, до $MAX_URL символов")
        }
        val current = when (val s = state()) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        val n = (current.items.mapNotNull { it.id.value.removePrefix("s").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val id = SubscriptionId("s$n").also { require(Ids.isValid(it.value)) }
        val title = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: hostOf(trimmed)
        val sub = Subscription(id, title, SubscriptionSource.Url(Secret(trimmed)))
        return commit(current.copy(items = current.items + sub)).map { sub }
    }

    fun remove(id: SubscriptionId): Outcome<SubscriptionsState> = edit { st ->
        st.copy(items = st.items.filter { it.id != id }, activeId = st.activeId?.takeIf { it != id })
    }

    /** Идентификатор устройства для этой подписки (D-33): включить — новое случайное значение, выключить — забыть. */
    fun setDeviceId(id: SubscriptionId, enabled: Boolean): Outcome<SubscriptionsState> = edit { st ->
        st.copy(items = st.items.map { if (it.id == id) it.copy(deviceId = if (enabled) it.deviceId ?: DeviceId.generate() else null) else it })
    }

    /** Сброс: новое случайное значение; для поставщика это новое устройство (D-33). */
    fun resetDeviceId(id: SubscriptionId): Outcome<SubscriptionsState> = edit { st ->
        st.copy(items = st.items.map { if (it.id == id && it.deviceId != null) it.copy(deviceId = DeviceId.generate()) else it })
    }

    private fun edit(change: (SubscriptionsState) -> SubscriptionsState): Outcome<SubscriptionsState> = when (val s = state()) {
        is Outcome.Failure -> s
        is Outcome.Success -> commit(change(s.value))
    }

    /**
     * Загрузить и применить подписку: её серверы становятся серверами
     * активной конфигурации, маршрутизация и DNS — пользователя или
     * [BasePolicy] (D-36). Подписка, которая сейчас не применена,
     * загружается заново, даже если тело не изменилось: её серверов нигде,
     * кроме ответа поставщика, нет.
     */
    suspend fun refresh(id: SubscriptionId, fetcher: SubscriptionFetcher, appVersion: String): Outcome<SubscriptionRun> {
        val st = when (val s = state()) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        val sub = st.find(id) ?: return Outcome.failure(DiagCode.SUBSCRIPTION_FETCH_FAILED, "нет подписки $id")
        val forUpdate = if (st.activeId == id) sub else sub.copy(lastSuccessful = null)
        val update = SubscriptionUpdater(fetcher, clock, appVersion).update(forUpdate, core.profileFilter())
        core.subscriptionNotes(update)
        val (next, activation) = when (update) {
            is UpdateResult.Ready -> {
                val base = core.activation.state.activeConfig ?: BasePolicy.ir
                val (s, a) = core.activateSubscription(base, update.candidate)
                s to a
            }
            is UpdateResult.Unchanged -> update.subscription to null
            is UpdateResult.Failed -> update.subscription to null
        }
        // Неудача не стирает прошлую удачную ревизию (D-08): у переданной копии её могло не быть.
        val kept = if (next.lastSuccessful == null) next.copy(lastSuccessful = sub.lastSuccessful) else next
        val activeId = if (activation is ActivationResult.Activated) id else st.activeId
        val saved = commit(st.copy(items = st.items.map { if (it.id == id) kept else it }, activeId = activeId))
        if (saved is Outcome.Failure) return saved
        return Outcome.Success(SubscriptionRun(kept, update, activation))
    }

    private companion object {
        const val MAX_URL = 2048
        const val MAX_NAME = 64

        fun hostOf(url: String): String = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringAfterLast('@').take(MAX_NAME)
    }
}

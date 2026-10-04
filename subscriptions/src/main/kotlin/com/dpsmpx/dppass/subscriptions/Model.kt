package com.dpsmpx.dppass.subscriptions

import com.dpsmpx.dppass.config.ServerProfile
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret

/**
 * Подписка — версионированный объект (D-08). Неизменяем: обновление
 * создаёт новый объект целиком, и хранилище заменяет его атомарно, так
 * что состояния «частично обновлённая подписка» не бывает.
 */
data class Subscription(
    val id: SubscriptionId,
    val name: String,
    val source: SubscriptionSource,
    val fetchPolicy: FetchPolicy = FetchPolicy(),
    val updatePolicy: UpdatePolicy = UpdatePolicy(),
    /** Что из подписки берётся: только серверы или ещё и маршрутизация поставщика. */
    val binding: RoutingBinding = RoutingBinding.USER_ROUTING,
    val metadata: SubscriptionMetadata = SubscriptionMetadata(),
    val lastSuccessful: SubscriptionRevision? = null,
    val lastFailed: FailedRevision? = null,
    /** Счётчик ревизий: растёт на каждой удачной загрузке с изменениями. */
    val revisionCounter: Long = 0,
) {
    fun recordSuccess(revision: SubscriptionRevision, metadata: SubscriptionMetadata): Subscription =
        copy(lastSuccessful = revision, metadata = metadata, revisionCounter = revision.number)

    fun recordFailure(failure: FailedRevision): Subscription = copy(lastFailed = failure)
}

/** Адрес подписки — секрет: в нём обычно токен доступа. */
sealed interface SubscriptionSource {
    data class Url(val url: Secret) : SubscriptionSource
}

/**
 * Как загружать. Идентификатор устройства не отправляется никогда
 * (D-12): у DPpass нет HWID, и в запрос он не добавляется даже по
 * просьбе поставщика.
 */
data class FetchPolicy(
    val timeoutMillis: Long = 20_000,
    val maxBytes: Int = 2 shl 20,
    val maxEntries: Int = 1000,
)

data class UpdatePolicy(
    /** Интервал автообновления; null — только вручную. */
    val intervalMinutes: Long? = 12 * 60,
    val onlyUnmetered: Boolean = false,
)

enum class RoutingBinding {
    /** Маршрутизация и DNS — пользователя; из подписки — только серверы (по умолчанию). */
    USER_ROUTING,
    /** Пользователь явно разрешил брать маршрутизацию и DNS поставщика (D-11). */
    PROVIDER_ROUTING,
}

data class TrafficInfo(val uploadBytes: Long?, val downloadBytes: Long?, val totalBytes: Long?, val expireEpochSeconds: Long?)

/** Информационные метаданные: показываются, но не меняют поведение клиента. */
data class SubscriptionMetadata(
    val title: String? = null,
    val updateIntervalHours: Int? = null,
    val traffic: TrafficInfo? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val announce: String? = null,
)

data class SubscriptionRevision(
    val number: Long,
    val checksum: String,
    val fetchedAtMillis: Long,
    val profileCount: Int,
)

data class FailedRevision(val checksum: String?, val failedAtMillis: Long, val reason: Diagnostic)

/** Ответ источника. Заголовки — с именами в нижнем регистре. */
data class FetchedPayload(val bytes: ByteArray, val headers: Map<String, String>, val fetchedAtMillis: Long) {
    override fun equals(other: Any?): Boolean =
        other is FetchedPayload && other.bytes.contentEquals(bytes) && other.headers == headers && other.fetchedAtMillis == fetchedAtMillis

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + headers.hashCode()
}

/**
 * Загрузка. Настоящая HTTP-загрузка — этап 3 `ROADMAP` (с ограничением
 * размера во время чтения, таймаутом и без идентификаторов устройства);
 * в тестах и `cli` — из памяти или файла.
 */
fun interface SubscriptionFetcher {
    suspend fun fetch(source: SubscriptionSource, policy: FetchPolicy): Outcome<FetchedPayload>
}

/** Фильтр серверов по возможностям ядра; реализует `connection/` поверх `engine/`. */
fun interface ProfileFilter {
    fun filter(profiles: List<ServerProfile>): FilterResult
}

data class FilterResult(val usable: List<ServerProfile>, val notes: List<Diagnostic>)

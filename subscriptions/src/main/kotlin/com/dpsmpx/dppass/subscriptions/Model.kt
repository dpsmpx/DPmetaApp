package com.dpsmpx.dppass.subscriptions

import com.dpsmpx.dppass.config.ServerProfile
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret
import java.security.SecureRandom

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
    /**
     * Идентификатор для лимита устройств поставщика (`x-hwid`, D-33):
     * null — не отправляется (по умолчанию). Включает только пользователь;
     * значение — [DeviceId.generate], своё у каждой подписки.
     */
    val deviceId: Secret? = null,
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
 * Случайный идентификатор подписки для лимита устройств поставщика
 * (D-33). Не из устройства: идентификаторов системы и железа DPpass не
 * читает (D-12). У каждой подписки свой, поэтому поставщики не свяжут
 * подписки одного пользователя; сброс — новое значение.
 */
object DeviceId {
    /** Что принимает Remnawave в `x-hwid`: 10–64 символа латиницы, цифр, «=» и «-». */
    val FORMAT: Regex = Regex("[A-Za-z0-9=-]{10,64}")

    /** 128 случайных бит — 32 шестнадцатеричные цифры (D-21: случайность криптографическая). */
    fun generate(random: SecureRandom = SecureRandom()): Secret {
        val bytes = ByteArray(16).also(random::nextBytes)
        return Secret(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
}

/**
 * Всё, что уходит поставщику в запросе подписки, — и ничего больше
 * (D-12, D-33): адрес, собственный `User-Agent`, одинаковый у всех
 * установок одной версии, и `x-hwid` — только если пользователь включил
 * его для этой подписки. Модели и ОС устройства в запросе нет.
 * Загрузчик (этап 3) переводит это в заголовки один к одному.
 */
data class SubscriptionRequest(val source: SubscriptionSource, val userAgent: String, val deviceId: Secret?) {
    companion object {
        const val DEVICE_ID_HEADER: String = "x-hwid"

        fun of(subscription: Subscription, appVersion: String): SubscriptionRequest =
            SubscriptionRequest(subscription.source, "DPpass/$appVersion", subscription.deviceId)
    }
}

/**
 * Как загружать: тайм-аут и пределы. Что отправляется поставщику —
 * [SubscriptionRequest].
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
 * размера во время чтения, таймаутом и ровно с заголовками [request]);
 * в тестах и `cli` — из памяти или файла.
 */
fun interface SubscriptionFetcher {
    suspend fun fetch(request: SubscriptionRequest, policy: FetchPolicy): Outcome<FetchedPayload>
}

/** Фильтр серверов по возможностям ядра; реализует `connection/` поверх `engine/`. */
fun interface ProfileFilter {
    fun filter(profiles: List<ServerProfile>): FilterResult
}

data class FilterResult(val usable: List<ServerProfile>, val notes: List<Diagnostic>)

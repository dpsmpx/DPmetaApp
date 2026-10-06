package com.dpsmpx.dppass.subscriptions

import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome

/**
 * «Загрузка» своих серверов (D-42): тело уже у пользователя, сети нет.
 * Предел размера — тот же, что у загрузки по сети; дальше — тот же
 * конвейер (разбор, проверка, фильтр ядра, ревизия, D-08).
 *
 * Здесь `reveal()` разрешён (`code.forbidden`, `secret-reveal`): тело — секрет,
 * а конвейеру нужны его байты.
 */
class InlineSubscriptionFetcher(private val clock: Clock) : SubscriptionFetcher {
    override suspend fun fetch(request: SubscriptionRequest, policy: FetchPolicy): Outcome<FetchedPayload> {
        val source = request.source as? SubscriptionSource.Inline
            ?: return Outcome.failure(DiagCode.SUBSCRIPTION_FETCH_FAILED, "не свои серверы — их загружает сеть")
        val bytes = source.body.reveal().toByteArray(Charsets.UTF_8)
        if (bytes.size > policy.maxBytes) return Outcome.failure(DiagCode.SUBSCRIPTION_TOO_LARGE, "больше ${policy.maxBytes} байт")
        return Outcome.Success(FetchedPayload(bytes, emptyMap(), clock.nowMillis()))
    }
}

/** Источник по виду подписки: свои серверы — без сети, адрес — [network]. */
class SourceFetcher(private val network: SubscriptionFetcher, private val inline: SubscriptionFetcher) : SubscriptionFetcher {
    override suspend fun fetch(request: SubscriptionRequest, policy: FetchPolicy): Outcome<FetchedPayload> = when (request.source) {
        is SubscriptionSource.Inline -> inline.fetch(request, policy)
        is SubscriptionSource.Url -> network.fetch(request, policy)
    }
}

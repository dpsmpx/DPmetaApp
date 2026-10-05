package com.dpsmpx.dppass.app.ui

import com.dpsmpx.dppass.config.ProtocolKind
import com.dpsmpx.dppass.config.SecurityKind
import com.dpsmpx.dppass.config.ServerProfile
import com.dpsmpx.dppass.config.TransportKind
import com.dpsmpx.dppass.connection.CandidateHealth
import com.dpsmpx.dppass.connection.HealthState
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.FailureKind
import com.dpsmpx.dppass.subscriptions.SubscriptionsState
import java.util.Locale
import kotlin.math.roundToLong

// Логика экранов без Android (D-34): что показать, решается здесь и
// проверяется на хосте (app/src/test); экраны только раскладывают это по Views.

/** Здоровье строки сервера: что известно о нём с запуска процесса (D-15). */
enum class RowHealth { UNKNOWN, HEALTHY, FAILED, PAUSED }

data class ServerRow(
    val id: ProfileId,
    val name: String,
    /** Протокол, защита и транспорт: «VLESS · REALITY · TCP». */
    val summary: String,
    val health: RowHealth,
    /** Сколько ещё секунд пауза после отказа ([RowHealth.PAUSED]). */
    val pauseSeconds: Long,
    val lastFailure: FailureKind?,
    val selected: Boolean,
    val connected: Boolean,
)

object ServerList {
    /**
     * Строки в порядке конфигурации — порядок пользователя или поставщика,
     * а не план (план меняется от здоровья, список — нет).
     */
    fun rows(
        profiles: List<ServerProfile>,
        health: (ProfileId) -> CandidateHealth,
        selected: ProfileId?,
        connected: ProfileId?,
        nowMillis: Long,
    ): List<ServerRow> {
        val chosen = effectiveSelection(profiles, selected)
        return profiles.map { p ->
            val h = health(p.id)
            val paused = h.state == HealthState.FAILED && h.backoffUntilMillis > nowMillis
            ServerRow(
                id = p.id,
                name = p.name,
                summary = summary(p),
                health = when {
                    paused -> RowHealth.PAUSED
                    h.state == HealthState.HEALTHY -> RowHealth.HEALTHY
                    h.state == HealthState.FAILED -> RowHealth.FAILED
                    else -> RowHealth.UNKNOWN
                },
                pauseSeconds = if (paused) (h.backoffUntilMillis - nowMillis + 999) / 1000 else 0,
                lastFailure = h.lastFailure?.kind,
                selected = p.id == chosen,
                connected = p.id == connected,
            )
        }
    }

    /** Выбор, которого в конфигурации больше нет (подписка обновилась), — «автоматически». */
    fun effectiveSelection(profiles: List<ServerProfile>, selected: ProfileId?): ProfileId? =
        selected?.takeIf { id -> profiles.any { it.id == id } }

    fun summary(p: ServerProfile): String {
        val parts = mutableListOf(
            when (p.protocol.kind) {
                ProtocolKind.VLESS -> "VLESS"
                ProtocolKind.TROJAN -> "Trojan"
                ProtocolKind.SHADOWSOCKS -> "Shadowsocks 2022"
                ProtocolKind.HYSTERIA2 -> "Hysteria2"
            },
        )
        // У Hysteria2 TLS — часть протокола, а транспорт — QUIC.
        if (p.protocol.kind != ProtocolKind.HYSTERIA2) {
            when (p.security.kind) {
                SecurityKind.TLS -> parts += "TLS"
                SecurityKind.REALITY -> parts += "REALITY"
                SecurityKind.NONE -> Unit
            }
        }
        when (p.transport.kind) {
            TransportKind.TCP -> parts += "TCP"
            TransportKind.WEBSOCKET -> parts += "WebSocket"
            TransportKind.GRPC -> parts += "gRPC"
            TransportKind.XHTTP -> parts += "XHTTP"
            TransportKind.HTTPUPGRADE -> parts += "HTTPUpgrade"
            TransportKind.NATIVE -> if (p.protocol.kind == ProtocolKind.HYSTERIA2) parts += "QUIC"
        }
        return parts.joinToString(SEPARATOR)
    }

    const val SEPARATOR: String = " · "
}

/** Что сейчас активно: своя конфигурация, подписка или ничего (источник записи активации). */
sealed interface ConfigSource {
    data object None : ConfigSource

    data object Own : ConfigSource

    data class FromSubscription(val id: SubscriptionId) : ConfigSource

    companion object {
        /** `ActivationRecord.source`: `user-import` или `subscription:<id>#<ревизия>`. */
        fun of(source: String?): ConfigSource = when {
            source == null -> None
            source.startsWith(SUBSCRIPTION) -> FromSubscription(SubscriptionId(source.removePrefix(SUBSCRIPTION).substringBefore('#')))
            else -> Own
        }

        private const val SUBSCRIPTION = "subscription:"
    }
}

data class SubscriptionRow(
    val id: SubscriptionId,
    val name: String,
    /** Её серверы — серверы активной конфигурации сейчас. */
    val active: Boolean,
    val profileCount: Int?,
    val updatedAtMillis: Long?,
    /** Последняя загрузка не удалась (позже последней удачной). */
    val failure: Diagnostic?,
    val usedBytes: Long?,
    val totalBytes: Long?,
    val expireMillis: Long?,
    val expired: Boolean,
    val deviceIdEnabled: Boolean,
    /** Объявление поставщика — текст как есть, без управляющих символов и не длиннее [ANNOUNCE_LIMIT]. */
    val announce: String?,
)

const val ANNOUNCE_LIMIT: Int = 300

object SubscriptionList {
    fun rows(state: SubscriptionsState, source: ConfigSource, nowMillis: Long): List<SubscriptionRow> = state.items.map { s ->
        val ok = s.lastSuccessful
        val failed = s.lastFailed?.takeIf { f -> ok == null || f.failedAtMillis >= ok.fetchedAtMillis }
        val traffic = s.metadata.traffic
        val used = traffic?.let { t -> if (t.uploadBytes == null && t.downloadBytes == null) null else (t.uploadBytes ?: 0) + (t.downloadBytes ?: 0) }
        val expire = traffic?.expireEpochSeconds?.takeIf { it > 0 }?.let { it * 1000 }
        SubscriptionRow(
            id = s.id,
            name = s.metadata.title?.takeIf { it.isNotBlank() } ?: s.name,
            active = source == ConfigSource.FromSubscription(s.id),
            profileCount = ok?.profileCount,
            updatedAtMillis = ok?.fetchedAtMillis,
            failure = failed?.reason,
            usedBytes = used,
            // 0 у поставщиков — «без предела».
            totalBytes = traffic?.totalBytes?.takeIf { it > 0 },
            expireMillis = expire,
            expired = expire != null && expire <= nowMillis,
            deviceIdEnabled = s.deviceId != null,
            announce = s.metadata.announce?.let(::plain)?.takeIf { it.isNotEmpty() },
        )
    }

    /** Текст поставщика — только печатаемые символы и переводы строк; лишнее обрезается. */
    fun plain(text: String): String = text
        .filter { it == '\n' || !it.isISOControl() }
        .trim()
        .let { if (it.length > ANNOUNCE_LIMIT) it.take(ANNOUNCE_LIMIT - 1) + "…" else it }
}

object Formats {
    /**
     * Байты в единицах [units] (Б, КБ, МБ, ГБ, ТБ — по 1024): до 10 —
     * с одним знаком после запятой, дальше — целым.
     */
    fun bytes(n: Long, units: List<String>, locale: Locale): String {
        require(units.isNotEmpty())
        var value = n.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (unit == 0 || value >= 10) value.roundToLong().toString() else String.format(locale, "%.1f", value)
        return "$text ${units[unit]}"
    }
}

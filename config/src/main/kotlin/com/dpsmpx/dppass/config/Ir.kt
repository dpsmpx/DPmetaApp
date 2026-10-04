package com.dpsmpx.dppass.config

import com.dpsmpx.dppass.core.Endpoint
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.routing.DnsPolicy
import com.dpsmpx.dppass.routing.RoutingPolicy

/**
 * Нормализованная модель конфигурации DPpass (Config IR, D-05).
 *
 * Не знает ни одного ядра: в ней нет полей Xray или sing-box, только
 * смысл. Из неё компиляторы ядер строят свои конфигурации; интерфейс
 * пользователя работает с ней (или с документом), но никогда — с JSON
 * ядра. Все секреты — [Secret].
 */
data class ConfigIr(
    val name: String?,
    val profiles: List<ServerProfile>,
    val routing: RoutingPolicy,
    val dns: DnsPolicy,
) {
    fun profile(id: ProfileId): ServerProfile? = profiles.firstOrNull { it.id == id }
}

data class ServerProfile(
    val id: ProfileId,
    val name: String,
    val endpoint: Endpoint,
    val protocol: ProtocolSpec,
    val transport: TransportSpec,
    val security: SecuritySpec,
    val hints: CandidateHints = CandidateHints(),
)

/**
 * Подсказки для политики подключения: приоритет и в каких сетях
 * кандидат осмыслен. [reachableUnderAllowlist] — пользователь или
 * поставщик утверждает, что точка входа доступна из сети в режиме
 * белого списка (например, узел внутри страны); DPpass этого не
 * проверяет заранее и не обещает (D-13).
 */
data class CandidateHints(
    val priority: Int = 0,
    val reachableUnderAllowlist: Boolean = false,
)

enum class ProtocolKind { VLESS, TROJAN, SHADOWSOCKS, HYSTERIA2 }

sealed interface ProtocolSpec {
    val kind: ProtocolKind

    data class Vless(val id: Secret, val flow: VlessFlow = VlessFlow.NONE) : ProtocolSpec {
        override val kind: ProtocolKind get() = ProtocolKind.VLESS
    }

    data class Trojan(val password: Secret) : ProtocolSpec {
        override val kind: ProtocolKind get() = ProtocolKind.TROJAN
    }

    data class Shadowsocks(val method: ShadowsocksMethod, val password: Secret) : ProtocolSpec {
        override val kind: ProtocolKind get() = ProtocolKind.SHADOWSOCKS
    }

    data class Hysteria2(val password: Secret, val obfsPassword: Secret? = null) : ProtocolSpec {
        override val kind: ProtocolKind get() = ProtocolKind.HYSTERIA2
    }
}

enum class VlessFlow(val wire: String) { NONE("none"), VISION("vision") }

/** Только методы Shadowsocks-2022 (D-16: старые AEAD — без защиты от повтора). */
enum class ShadowsocksMethod(val wire: String) {
    BLAKE3_AES_128_GCM("2022-blake3-aes-128-gcm"),
    BLAKE3_AES_256_GCM("2022-blake3-aes-256-gcm"),
    BLAKE3_CHACHA20_POLY1305("2022-blake3-chacha20-poly1305"),
}

enum class TransportKind { TCP, WEBSOCKET, GRPC, XHTTP, HTTPUPGRADE, NATIVE }

/**
 * Класс транспорта — то, по чему политика подключения подбирает кандидата
 * под сеть: TCP-поток (TLS, REALITY, Shadowsocks поверх TCP), HTTP/CDN
 * (WebSocket, gRPC, XHTTP, HTTPUpgrade), UDP/QUIC (Hysteria2).
 */
enum class TransportClass { TCP_STREAM, HTTP_CDN, UDP_QUIC }

sealed interface TransportSpec {
    val kind: TransportKind

    data object Tcp : TransportSpec {
        override val kind: TransportKind get() = TransportKind.TCP
    }

    data class WebSocket(val path: String, val host: String?) : TransportSpec {
        override val kind: TransportKind get() = TransportKind.WEBSOCKET
    }

    data class Grpc(val serviceName: String) : TransportSpec {
        override val kind: TransportKind get() = TransportKind.GRPC
    }

    data class XHttp(val path: String, val host: String?, val mode: XHttpMode) : TransportSpec {
        override val kind: TransportKind get() = TransportKind.XHTTP
    }

    data class HttpUpgrade(val path: String, val host: String?) : TransportSpec {
        override val kind: TransportKind get() = TransportKind.HTTPUPGRADE
    }

    /** Протокол со своим транспортом (Hysteria2 — QUIC). */
    data object Native : TransportSpec {
        override val kind: TransportKind get() = TransportKind.NATIVE
    }
}

enum class XHttpMode(val wire: String) { AUTO("auto"), PACKET_UP("packet-up"), STREAM_UP("stream-up"), STREAM_ONE("stream-one") }

enum class SecurityKind { NONE, TLS, REALITY }

enum class TlsFingerprint(val wire: String) { CHROME("chrome"), FIREFOX("firefox"), SAFARI("safari"), RANDOMIZED("randomized") }

sealed interface SecuritySpec {
    val kind: SecurityKind

    data object None : SecuritySpec {
        override val kind: SecurityKind get() = SecurityKind.NONE
    }

    /**
     * Обычный TLS с проверкой сертификата. Отключить проверку
     * (`allowInsecure`) в IR нельзя — такая конфигурация отвергается
     * при разборе (D-11).
     */
    data class Tls(val serverName: String?, val alpn: List<String>, val fingerprint: TlsFingerprint?) : SecuritySpec {
        override val kind: SecurityKind get() = SecurityKind.TLS
    }

    data class Reality(
        val serverName: String,
        val publicKey: String,
        val shortId: Secret,
        val fingerprint: TlsFingerprint,
        val spiderX: String?,
    ) : SecuritySpec {
        override val kind: SecurityKind get() = SecurityKind.REALITY
    }
}

/** Класс транспорта профиля: от него зависит, в какой сети кандидат осмыслен. */
val ServerProfile.transportClass: TransportClass
    get() = when {
        protocol.kind == ProtocolKind.HYSTERIA2 -> TransportClass.UDP_QUIC
        transport.kind == TransportKind.TCP -> TransportClass.TCP_STREAM
        else -> TransportClass.HTTP_CDN
    }

package com.dpsmpx.dppass.config

import com.dpsmpx.dppass.core.Host
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.routing.DnsValidator
import com.dpsmpx.dppass.routing.RoutingValidator
import java.util.Base64

/**
 * Проверка смысла IR, не зависящая от ядра: сочетания протокола,
 * транспорта и защиты, форматы ключей, ссылки, пределы. Что умеет или
 * не умеет конкретное ядро — отдельная проверка возможностей (`engine/`).
 */
object ConfigValidator {
    private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val SHORT_ID = Regex("""([0-9a-f]{2}){0,8}""")
    private const val SERVER_NAME_MESSAGE = "имя сервера (server_name; в ссылке — sni) должно быть доменным именем (IDN — в punycode)"

    fun validate(ir: ConfigIr, limits: ConfigLimits = ConfigLimits()): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        if (ir.profiles.isEmpty()) out += Diagnostic.error(DiagCode.PROFILES_EMPTY, "нет ни одного профиля сервера", "$.profiles")
        if (ir.profiles.size > limits.maxProfiles) {
            out += Diagnostic.error(DiagCode.LIMIT_EXCEEDED, "профилей больше ${limits.maxProfiles}", "$.profiles")
        }
        if (ir.routing.rules.size > limits.maxRules) {
            out += Diagnostic.error(DiagCode.LIMIT_EXCEEDED, "правил больше ${limits.maxRules}", "$.routing.rules")
        }
        val values = ir.routing.rules.sumOf { r ->
            val c = r.conditions
            c.domains.size + c.ipCidrs.size + c.geoSites.size + c.geoIps.size + c.apps.size + c.ports.size
        }
        if (values > limits.maxRuleValues) {
            out += Diagnostic.error(DiagCode.LIMIT_EXCEEDED, "значений в правилах больше ${limits.maxRuleValues}", "$.routing.rules")
        }
        val ids = HashSet<String>()
        for ((i, p) in ir.profiles.withIndex()) {
            val path = "$.profiles[$i]"
            if (!ids.add(p.id.value)) out += Diagnostic.error(DiagCode.ID_DUPLICATE, "повторный id профиля «${p.id}»", "$path.id")
            out += validateProfile(p, path)
        }
        out += RoutingValidator.validate(ir.routing)
        out += DnsValidator.validate(ir.dns)
        return out
    }

    /**
     * Проверка одного профиля — и для документа, и для записи подписки.
     *
     * Запись подписки приходит мимо `DocumentMapper` (ссылка → IR напрямую),
     * поэтому всё, что определяет смысл профиля, проверяется здесь, даже
     * если разбор документа уже проверил то же самое: один и тот же профиль
     * не может быть верным ссылкой и неверным документом (D-05). Так —
     * имя сервера и `host` транспорта.
     */
    fun validateProfile(p: ServerProfile, path: String): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        fun err(code: DiagCode, msg: String, at: String) {
            out += Diagnostic.error(code, msg, at)
        }

        fun secretPresent(s: Secret, at: String): Boolean {
            if (s.isEmpty) {
                err(DiagCode.PASSWORD_EMPTY, "пустое секретное значение", at)
                return false
            }
            if (s.reveal() == Secret.REDACTED) {
                err(DiagCode.SECRET_PLACEHOLDER, "вместо секрета — метка из отредактированного экспорта; такой профиль не подключится", at)
                return false
            }
            return true
        }

        when (val proto = p.protocol) {
            is ProtocolSpec.Vless -> {
                if (secretPresent(proto.id, "$path.protocol.id") && !UUID.matches(proto.id.reveal())) {
                    err(DiagCode.UUID_INVALID, "id VLESS должен быть UUID", "$path.protocol.id")
                }
                if (p.security is SecuritySpec.None) {
                    err(DiagCode.SECURITY_REQUIRED, "VLESS без TLS или REALITY передаёт данные открыто", "$path.security")
                }
                if (proto.flow == VlessFlow.VISION && (p.transport !is TransportSpec.Tcp || p.security is SecuritySpec.None)) {
                    err(DiagCode.FLOW_REQUIRES_TCP_TLS, "flow vision работает только поверх TCP с TLS или REALITY", "$path.protocol.flow")
                }
            }
            is ProtocolSpec.Trojan -> {
                secretPresent(proto.password, "$path.protocol.password")
                if (p.security !is SecuritySpec.Tls) err(DiagCode.SECURITY_REQUIRED, "Trojan требует TLS", "$path.security")
            }
            is ProtocolSpec.Shadowsocks -> {
                if (secretPresent(proto.password, "$path.protocol.password")) {
                    val need = if (proto.method == ShadowsocksMethod.BLAKE3_AES_128_GCM) 16 else 32
                    val decoded = runCatching { Base64.getDecoder().decode(proto.password.reveal()) }.getOrNull()
                    if (decoded == null || decoded.size != need) {
                        err(DiagCode.PASSWORD_FORMAT_INVALID, "для ${proto.method.wire} пароль — ключ base64 длиной $need байт", "$path.protocol.password")
                    }
                }
                if (p.security !is SecuritySpec.None) {
                    err(DiagCode.TRANSPORT_PROTOCOL_MISMATCH, "Shadowsocks шифрует сам: security должно быть none", "$path.security")
                }
                if (p.transport !is TransportSpec.Tcp) {
                    err(DiagCode.TRANSPORT_PROTOCOL_MISMATCH, "Shadowsocks в IR v1 — только поверх tcp", "$path.transport")
                }
            }
            is ProtocolSpec.Hysteria2 -> {
                secretPresent(proto.password, "$path.protocol.password")
                proto.obfsPassword?.let { secretPresent(it, "$path.protocol.obfs_password") }
                if (p.transport !is TransportSpec.Native) {
                    err(DiagCode.TRANSPORT_PROTOCOL_MISMATCH, "Hysteria2 работает поверх своего QUIC: transport должен быть native", "$path.transport")
                }
                if (p.security !is SecuritySpec.Tls) err(DiagCode.SECURITY_REQUIRED, "Hysteria2 требует TLS", "$path.security")
            }
        }
        if (p.transport is TransportSpec.Native && p.protocol !is ProtocolSpec.Hysteria2) {
            err(DiagCode.TRANSPORT_PROTOCOL_MISMATCH, "transport native — только у Hysteria2", "$path.transport")
        }
        when (val sec = p.security) {
            is SecuritySpec.Reality -> {
                if (p.protocol !is ProtocolSpec.Vless) {
                    err(DiagCode.TRANSPORT_PROTOCOL_MISMATCH, "REALITY в IR v1 — только с VLESS", "$path.security")
                }
                if (p.transport !is TransportSpec.Tcp && p.transport !is TransportSpec.XHttp && p.transport !is TransportSpec.Grpc) {
                    err(DiagCode.TRANSPORT_PROTOCOL_MISMATCH, "REALITY работает поверх tcp, xhttp или grpc", "$path.transport")
                }
                val key = runCatching { Base64.getUrlDecoder().decode(sec.publicKey) }.getOrNull()
                if (sec.publicKey.length != 43 || key == null || key.size != 32) {
                    err(DiagCode.REALITY_PUBLIC_KEY_INVALID, "public_key — 32 байта X25519 в base64url без «=» (43 символа)", "$path.security.public_key")
                }
                if (!SHORT_ID.matches(sec.shortId.reveal())) {
                    err(DiagCode.REALITY_SHORT_ID_INVALID, "short_id — чётное число шестнадцатеричных цифр в нижнем регистре, до 16", "$path.security.short_id")
                }
                if (Host.normalizeDomain(sec.serverName) == null) {
                    err(DiagCode.SERVER_NAME_INVALID, SERVER_NAME_MESSAGE, "$path.security.server_name")
                }
            }
            is SecuritySpec.Tls -> {
                if (sec.alpn.any { it.isEmpty() || it.length > 32 }) {
                    err(DiagCode.FIELD_TYPE, "значение alpn пустое или длиннее 32 символов", "$path.security.alpn")
                }
                if (sec.serverName != null && Host.normalizeDomain(sec.serverName) == null) {
                    err(DiagCode.SERVER_NAME_INVALID, SERVER_NAME_MESSAGE, "$path.security.server_name")
                }
            }
            SecuritySpec.None -> Unit
        }
        val hostHeader = when (val t = p.transport) {
            is TransportSpec.WebSocket -> t.host
            is TransportSpec.HttpUpgrade -> t.host
            is TransportSpec.XHttp -> t.host
            TransportSpec.Tcp, TransportSpec.Native, is TransportSpec.Grpc -> null
        }
        if (hostHeader != null && Host.normalizeDomain(hostHeader) == null) {
            err(DiagCode.DOMAIN_INVALID, "host должен быть доменным именем (IDN — в punycode)", "$path.transport.host")
        }
        return out
    }
}

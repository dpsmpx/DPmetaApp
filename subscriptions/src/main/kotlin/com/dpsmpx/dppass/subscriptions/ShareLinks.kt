package com.dpsmpx.dppass.subscriptions

import com.dpsmpx.dppass.config.CandidateHints
import com.dpsmpx.dppass.config.ProtocolSpec
import com.dpsmpx.dppass.config.SecuritySpec
import com.dpsmpx.dppass.config.ServerProfile
import com.dpsmpx.dppass.config.TlsFingerprint
import com.dpsmpx.dppass.config.TransportSpec
import com.dpsmpx.dppass.config.VlessFlow
import com.dpsmpx.dppass.config.XHttpMode
import com.dpsmpx.dppass.core.Checksums
import com.dpsmpx.dppass.core.Endpoint
import com.dpsmpx.dppass.core.Host
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret

/**
 * Ссылки вида `vless://…` и `trojan://…` → профиль IR.
 *
 * Это чужой формат без строгой спецификации, поэтому правило строже
 * обычного: известный параметр переводится в IR; известный «по
 * умолчанию» (например, `encryption=none`) принимается; **любой другой
 * параметр делает ссылку непригодной** с предупреждением и списком
 * имён. Иначе профиль подключался бы не так, как задумал поставщик, —
 * это молчаливая потеря смысла (D-05). Список растёт вместе с IR.
 *
 * Сообщения не содержат значений параметров и самой ссылки: в них
 * UUID и ключи.
 */
object ShareLinkParser {
    private val SUPPORTED = setOf("vless", "trojan")
    private val KNOWN_OTHER = setOf("vmess", "ss", "hysteria2", "hy2", "tuic", "wireguard", "wg", "socks", "http", "https", "anytls", "naive+https")

    private val VLESS_PARAMS = setOf("type", "security", "sni", "fp", "pbk", "sid", "spx", "flow", "path", "host", "servicename", "mode", "alpn", "encryption", "headertype", "allowinsecure", "insecure")
    private val TROJAN_PARAMS = setOf("type", "security", "sni", "fp", "path", "host", "servicename", "mode", "alpn", "headertype", "allowinsecure", "insecure")

    fun isLink(line: String): Boolean = SCHEME.find(line.trim())?.range?.first == 0

    /** [idPrefix] — префикс устойчивых id (обычно id подписки). */
    fun parse(link: String, idPrefix: String): Outcome<ServerProfile> {
        val text = link.trim()
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
            ?: return Outcome.failure(DiagCode.SHARE_LINK_INVALID, "не ссылка вида схема://…")
        if (scheme !in SUPPORTED) {
            val known = if (scheme in KNOWN_OTHER) "ещё не поддерживается" else "неизвестна"
            return Outcome.Failure(listOf(Diagnostic.error(DiagCode.SHARE_LINK_INVALID, "схема $scheme://").withCause(
                Diagnostic.warning(DiagCode.SHARE_LINK_SCHEME_UNSUPPORTED, "схема $scheme:// $known"),
            )))
        }
        val parts = split(text.substring(scheme.length + 3))
            ?: return Outcome.failure(DiagCode.SHARE_LINK_INVALID, "ссылка $scheme:// без «секрет@хост:порт»")
        val host = Host.parse(parts.host) ?: return Outcome.failure(DiagCode.SHARE_LINK_INVALID, "адрес сервера — не домен и не IP")
        val port = parts.port.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return Outcome.failure(DiagCode.SHARE_LINK_INVALID, "порт вне 1..65535")
        val q = parts.query
        val known = if (scheme == "vless") VLESS_PARAMS else TROJAN_PARAMS
        val unknown = q.keys.filter { it !in known }
        if (unknown.isNotEmpty()) return unsupported(unknown.sorted().joinToString())
        if (q["encryption"].let { it != null && it != "none" }) return unsupported("encryption (кроме none)")
        if (q["headertype"].let { it != null && it != "none" }) return unsupported("headerType (кроме none)")
        if (q["allowinsecure"] in setOf("1", "true") || q["insecure"] in setOf("1", "true")) {
            return Outcome.failure(DiagCode.INSECURE_TLS_REJECTED, "ссылка отключает проверку сертификата — такой профиль не принимается")
        }

        val transport = when (val type = q["type"] ?: "tcp") {
            "tcp", "raw" -> TransportSpec.Tcp
            "ws" -> TransportSpec.WebSocket(q["path"] ?: "/", q["host"])
            "httpupgrade" -> TransportSpec.HttpUpgrade(q["path"] ?: "/", q["host"])
            "grpc" -> TransportSpec.Grpc(q["servicename"] ?: return Outcome.failure(DiagCode.SHARE_LINK_INVALID, "grpc без serviceName"))
            "xhttp", "splithttp" -> {
                val mode = XHttpMode.entries.firstOrNull { it.wire == (q["mode"] ?: "auto") } ?: return unsupported("mode=${q["mode"]}")
                TransportSpec.XHttp(q["path"] ?: "/", q["host"], mode)
            }
            else -> return unsupported("type=$type")
        }
        val fp = q["fp"]?.let { f -> TlsFingerprint.entries.firstOrNull { it.wire == f } ?: return unsupported("fp=$f") }
        val security = when (val s = q["security"] ?: if (scheme == "trojan") "tls" else "none") {
            "none" -> SecuritySpec.None
            "tls" -> SecuritySpec.Tls(q["sni"], q["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(), fp)
            "reality" -> SecuritySpec.Reality(
                serverName = q["sni"] ?: return Outcome.failure(DiagCode.SHARE_LINK_INVALID, "reality без sni"),
                publicKey = q["pbk"] ?: return Outcome.failure(DiagCode.SHARE_LINK_INVALID, "reality без pbk"),
                shortId = Secret(q["sid"] ?: ""),
                fingerprint = fp ?: TlsFingerprint.CHROME,
                spiderX = q["spx"],
            )
            else -> return unsupported("security=$s")
        }
        val secret = Secret(parts.user)
        val protocol = if (scheme == "vless") {
            val flow = when (q["flow"]) {
                null, "", "none" -> VlessFlow.NONE
                "xtls-rprx-vision" -> VlessFlow.VISION
                else -> return unsupported("flow")
            }
            ProtocolSpec.Vless(secret, flow)
        } else {
            ProtocolSpec.Trojan(secret)
        }
        // Устойчивый id: от всего, кроме имени-фрагмента. Хеш — не секрет, но и не обратим.
        val id = "$idPrefix.${Checksums.sha256(text.substringBefore('#')).take(12)}"
        val name = parts.fragment?.takeIf { it.isNotBlank() && it.length <= 128 && it.none { c -> c.code < 0x20 } }?.trim()
            ?: "${host}:$port"
        return Outcome.Success(ServerProfile(ProfileId(id), name, Endpoint(host, port), protocol, transport, security, CandidateHints()))
    }

    private fun unsupported(what: String): Outcome<ServerProfile> = Outcome.Failure(
        listOf(
            Diagnostic.error(DiagCode.SHARE_LINK_INVALID, "ссылка содержит то, что DPpass пока не выражает без потери смысла")
                .withCause(Diagnostic.warning(DiagCode.SHARE_LINK_PARAM_UNSUPPORTED, "неподдержанные параметры: $what")),
        ),
    )

    private class Parts(val user: String, val host: String, val port: String, val query: Map<String, String>, val fragment: String?)

    private fun split(rest: String): Parts? {
        val fragmentAt = rest.indexOf('#')
        val beforeFragment = if (fragmentAt >= 0) rest.substring(0, fragmentAt) else rest
        val fragment = if (fragmentAt >= 0) percentDecode(rest.substring(fragmentAt + 1)) else null
        val queryAt = beforeFragment.indexOf('?')
        val authority = (if (queryAt >= 0) beforeFragment.substring(0, queryAt) else beforeFragment).removeSuffix("/")
        val queryText = if (queryAt >= 0) beforeFragment.substring(queryAt + 1) else ""
        val at = authority.lastIndexOf('@')
        if (at <= 0) return null
        val user = percentDecode(authority.substring(0, at)) ?: return null
        val hostPort = authority.substring(at + 1)
        val (host, port) = if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0 || end + 2 > hostPort.length || hostPort[end + 1] != ':') return null
            hostPort.substring(0, end + 1) to hostPort.substring(end + 2)
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return null
            hostPort.substring(0, colon) to hostPort.substring(colon + 1)
        }
        val query = LinkedHashMap<String, String>()
        if (queryText.isNotEmpty()) {
            for (pair in queryText.split('&')) {
                if (pair.isEmpty()) continue
                val k = percentDecode(pair.substringBefore('='))?.lowercase() ?: return null
                val v = percentDecode(pair.substringAfter('=', "")) ?: return null
                if (query.containsKey(k)) return null // повторный параметр — неоднозначность
                query[k] = v
            }
        }
        if (user.isEmpty() || host.isEmpty()) return null
        return Parts(user, host, port, query, fragment)
    }

    /** %XX → байты UTF-8; «+» остаётся «+» (это не форма HTML). */
    internal fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hex = s.substring(i + 1, i + 3)
                if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                out.write(hex.toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        // String(bytes, charset), а не ByteArrayOutputStream.toString(Charset): второго нет на Android ниже API 33.
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]{1,15})://""")
}

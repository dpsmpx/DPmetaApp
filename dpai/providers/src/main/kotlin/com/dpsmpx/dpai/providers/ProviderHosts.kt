package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Список адресов провайдеров (D-98; `DPmetaApp:D-10`, `DPmetaApp:D-52`):
 * запрос уходит только на встроенный адрес или на адрес своего сервера,
 * который человек разрешил в профиле отметкой. Остальное —
 * `HOST_NOT_ALLOWED`, запрос не отправлен.
 *
 * Только HTTPS. HTTP — к адресам частной сети и этого устройства (свой
 * сервер, Ollama, llama.cpp), а с ключом — только к этому устройству:
 * ключ не идёт открытым текстом по сети. Хост частной сети — только
 * числом (`192.168.1.5`): имя разрешилось бы куда угодно.
 */
object ProviderHosts {
    val BUILT_IN: Set<String> = setOf(
        "api.anthropic.com",
        "api.openai.com",
        "openrouter.ai",
        "api.deepseek.com",
        "api.mistral.ai",
        "generativelanguage.googleapis.com",
    )

    /** [custom] — хосты, разрешённые человеком (свой сервер профиля). */
    fun check(call: HttpCall, custom: Set<String>): Outcome<Unit> {
        val host = call.url.host?.lowercase()?.removeSurrounding("[", "]")
        val scheme = call.url.scheme?.lowercase()
        return when {
            host.isNullOrEmpty() -> refuse("в адресе нет хоста", call.url.toString())
            call.url.userInfo != null -> refuse("учётные данные в адресе запрещены", host)
            host !in BUILT_IN && host !in custom -> refuse("адреса нет в списке разрешённых", host)
            scheme == "https" -> Outcome.success(Unit)
            scheme != "http" -> refuse("разрешены только https и http", host)
            host in BUILT_IN -> refuse("к провайдеру — только https", host)
            !isPrivate(host) -> refuse("http — только к адресу частной сети или этого устройства", host)
            call.secret != null && !isLoopback(host) -> refuse("ключ по http — только на это устройство; для сети — https", host)
            else -> Outcome.success(Unit)
        }
    }

    /** Это устройство: `localhost`, `127.0.0.0/8`, `::1`. */
    fun isLoopback(host: String): Boolean {
        val h = host.lowercase().removeSurrounding("[", "]")
        if (h == "localhost" || h == "::1") return true
        val ip = ipv4(h) ?: return false
        return ip[0] == 127
    }

    /** Частная сеть числом: 10/8, 172.16/12, 192.168/16, 100.64/10 (Tailscale), 169.254/16, это устройство. */
    fun isPrivate(host: String): Boolean {
        if (isLoopback(host)) return true
        val ip = ipv4(host.lowercase()) ?: return false
        return ip[0] == 10 ||
            (ip[0] == 172 && ip[1] in 16..31) ||
            (ip[0] == 192 && ip[1] == 168) ||
            (ip[0] == 100 && ip[1] in 64..127) ||
            (ip[0] == 169 && ip[1] == 254)
    }

    private fun ipv4(h: String): List<Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null else p.toInt() }
        return nums.takeIf { n -> n.all { it in 0..255 } }
    }

    private fun refuse(message: String, subject: String) = Outcome.failure(DiagCode.HOST_NOT_ALLOWED, message, subject)
}

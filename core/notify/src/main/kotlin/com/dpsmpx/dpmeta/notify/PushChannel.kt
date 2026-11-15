package com.dpsmpx.dpmeta.notify

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import java.net.URI

/**
 * Мгновенный канал (D-152; F-55) без Android: сервер ntfy, тема, адреса потока и
 * отправки, паузы переподключения и память повторов. Соединение держит служба
 * приложения; здесь — только правила, чтобы их проверяли тесты.
 */
object PushChannel {
    const val DEFAULT_SERVER = "https://ntfy.sh"

    /** Тема — длинная и случайная: кто её не знает, тот не подпишется и не напишет. */
    private val TOPIC = Regex("[A-Za-z0-9_-]{20,64}")
    private val HOST = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+")
    private val NTFY_ID = Regex("[A-Za-z0-9]{6,32}")

    /** Новая тема: `dpmeta-` и 24 случайных шестнадцатеричных знака (96 бит). */
    fun newTopic(random: RandomSource): String = "dpmeta-" + hex(random.nextBytes(12))

    /** Новый ключ подписи: 32 случайных байта шестнадцатеричной строкой (её же берёт хук). */
    fun newKeyText(random: RandomSource): String = hex(random.nextBytes(32))

    fun topicValid(topic: String): Boolean = TOPIC.matches(topic)

    /**
     * Адрес сервера из ввода: только `https://хост[:порт]`, без пути, имени и пароля;
     * без схемы — `https://` подставляется. Итог — без косой черты в конце.
     */
    fun server(text: String): Outcome<String> {
        fun bad(why: String): Outcome<String> = Outcome.failure(DiagCode.VALUE_INVALID, "адрес сервера: $why", "push.server")
        val t = text.trim().trimEnd('/')
        if (t.isEmpty()) return Outcome.success(DEFAULT_SERVER)
        val withScheme = if ("://" in t) t else "https://$t"
        val u = runCatching { URI(withScheme) }.getOrNull() ?: return bad("не адрес")
        if (u.scheme?.lowercase() != "https") return bad("только https")
        if (u.rawUserInfo != null) return bad("без имени и пароля в адресе")
        val host = u.host?.lowercase() ?: return bad("нет имени сервера")
        if (!HOST.matches(host)) return bad("имя сервера «$host» не подходит")
        if (!u.rawPath.isNullOrEmpty() || u.rawQuery != null || u.rawFragment != null) return bad("без пути — только сервер")
        val port = if (u.port == -1 || u.port == 443) "" else ":${u.port}"
        return Outcome.success("https://$host$port")
    }

    /** Имя сервера для списка адресов (D-10): `ntfy.sh` в нём и так есть. */
    fun host(server: String): String? = runCatching { URI(server).host?.lowercase() }.getOrNull()

    /** Адрес потока темы; [since] — id последнего сообщения ntfy: пропущенное за обрыв придёт. */
    fun streamUrl(server: String, topic: String, since: String?): URI =
        URI("$server/$topic/json" + if (since != null && NTFY_ID.matches(since)) "?since=$since" else "")

    /** Адрес отправки — тот, что задаётся хуку в `DPMETA_PUSH_URL`. */
    fun publishUrl(server: String, topic: String): String = "$server/$topic"

    /**
     * Пауза перед новым соединением после [failures] обрывов подряд, секунды: 5, 10, 30,
     * 60, 120, дальше — 300. Удачное соединение сбрасывает счёт.
     */
    fun backoffSeconds(failures: Int): Long = when {
        failures <= 1 -> 5
        failures == 2 -> 10
        failures == 3 -> 30
        failures == 4 -> 60
        failures == 5 -> 120
        else -> 300
    }

    /** Сколько ждать строки потока: ntfy шлёт keepalive раз в 45 с — с запасом вдвое. */
    const val READ_TIMEOUT_MS = 100_000

    /** Сообщение ntfy: id (для `since`) и текст; служебные строки — `null`. */
    data class Frame(val id: String?, val text: String)

    fun frame(line: String): Frame? {
        val o = JsonParser().parse(line).valueOrNull() as? JsonObject ?: return null
        if ((o["event"] as? JsonString)?.value != "message") return null
        val text = (o["message"] as? JsonString)?.value ?: return null
        return Frame((o["id"] as? JsonString)?.value?.takeIf { NTFY_ID.matches(it) }, text)
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

/** Id уже принятых событий (повтор — отказ, D-152): последние [limit], старые забываются. */
class SeenIds(private val limit: Int = 500) {
    private val ids = LinkedHashSet<String>()

    @Synchronized
    fun snapshot(): Set<String> = ids.toSet()

    @Synchronized
    fun add(id: String) {
        ids.remove(id)
        ids.add(id)
        while (ids.size > limit) ids.remove(ids.first())
    }
}

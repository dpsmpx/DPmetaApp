package com.dpsmpx.dppass.connection

import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonBool
import com.dpsmpx.dppass.core.JsonNumber
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.core.JsonParseException
import com.dpsmpx.dppass.core.JsonString
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret
import com.dpsmpx.dppass.engine.ShareSpec
import com.dpsmpx.dppass.storage.BlobStore
import com.dpsmpx.dppass.storage.Quarantine
import java.security.SecureRandom

/**
 * Раздача туннеля другим устройствам (D-51): включена ли, порт, логин и
 * пароль входа. Логин и пароль — секреты: пишутся только в зашифрованное
 * хранилище (D-19), наружу — только на экран настроек (`FLAG_SECURE`).
 */
data class ShareSettings(val enabled: Boolean, val port: Int, val user: Secret, val password: Secret) {
    init {
        require(port in PORT_FROM until PORT_UNTIL) { "порт раздачи вне $PORT_FROM..${PORT_UNTIL - 1}" }
    }

    /** Вход для ядра на этих интерфейсах раздачи; раздача выключена или интерфейсов нет — null. */
    fun spec(endpoints: List<ShareSpec.Endpoint>): ShareSpec? {
        val usable = endpoints.distinctBy { it.address }
        return if (enabled && usable.isNotEmpty()) ShareSpec(usable, port, user, password) else null
    }

    companion object {
        /** Случайный порт — из этого диапазона (T11: не известный порт прокси). */
        const val PORT_FROM: Int = 20000
        const val PORT_UNTIL: Int = 60000
        const val USER_LENGTH: Int = 8
        const val PASSWORD_LENGTH: Int = 20
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

        /** Новые случайные порт, логин и пароль (D-51); раздача — выключена. */
        fun generate(random: SecureRandom = SecureRandom()): ShareSettings {
            fun word(n: Int) = String(CharArray(n) { ALPHABET[random.nextInt(ALPHABET.length)] })
            return ShareSettings(false, PORT_FROM + random.nextInt(PORT_UNTIL - PORT_FROM), Secret(word(USER_LENGTH)), Secret(word(PASSWORD_LENGTH)))
        }
    }
}

/**
 * Документ `dppass.share` версии 1 — запись `settings/share` (D-51).
 * Чтение строгое: что не читается, уходит в карантин (D-06), а не
 * становится «раздачей по умолчанию» с чужим паролем.
 */
object ShareCodec {
    const val FORMAT: String = "dppass.share"
    const val VERSION: Int = 1

    fun write(s: ShareSettings): String = Json.write(
        Json.obj(
            "format" to Json.str(FORMAT),
            "format_version" to Json.num(VERSION),
            "enabled" to Json.bool(s.enabled),
            "port" to Json.num(s.port),
            "user" to Json.str(s.user.reveal()),
            "password" to Json.str(s.password.reveal()),
        ),
        pretty = false,
    )

    fun read(text: String): Outcome<ShareSettings> {
        val root = try {
            Json.parse(text) as? JsonObject
        } catch (e: JsonParseException) {
            null
        } ?: return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "настройки раздачи — не объект JSON")
        fun bad(what: String): Outcome<ShareSettings> = Outcome.failure(DiagCode.STORAGE_CORRUPTED, "настройки раздачи: $what")
        if ((root["format"] as? JsonString)?.value != FORMAT || (root["format_version"] as? JsonNumber)?.toIntOrNull() != VERSION) {
            return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "неизвестный формат настроек раздачи")
        }
        val known = setOf("format", "format_version", "enabled", "port", "user", "password")
        root.fields.keys.firstOrNull { it !in known }?.let { return bad("неизвестное поле «$it»") }
        val enabled = (root["enabled"] as? JsonBool)?.value ?: return bad("enabled")
        val port = (root["port"] as? JsonNumber)?.toIntOrNull()?.takeIf { it in ShareSettings.PORT_FROM until ShareSettings.PORT_UNTIL } ?: return bad("port")
        val user = (root["user"] as? JsonString)?.value?.takeIf { it.isNotEmpty() } ?: return bad("user")
        val password = (root["password"] as? JsonString)?.value?.takeIf { it.isNotEmpty() } ?: return bad("password")
        return Outcome.Success(ShareSettings(enabled, port, Secret(user), Secret(password)))
    }
}

/** Хранение настроек раздачи одной записью (атомарно — от [BlobStore]). */
class ShareStore(private val blobs: BlobStore, private val quarantine: Quarantine) {
    /** Записи нет — новые случайные настройки (раздача выключена), они и сохраняются. */
    fun load(): Outcome<ShareSettings> {
        val bytes = when (val r = blobs.read(KEY)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value ?: return fresh()
        }
        return when (val parsed = ShareCodec.read(String(bytes, Charsets.UTF_8))) {
            is Outcome.Success -> parsed
            is Outcome.Failure -> when (val kept = quarantine.keep("share", bytes, parsed.errors.first())) {
                is Outcome.Failure -> Outcome.Failure(
                    listOf(
                        Diagnostic.error(DiagCode.STORAGE_CORRUPTED, "настройки раздачи не прочитаны и не сохранены в карантин")
                            .copy(causes = parsed.diagnostics + kept.diagnostics),
                    ),
                )
                is Outcome.Success -> when (val f = fresh()) {
                    is Outcome.Failure -> f
                    is Outcome.Success -> Outcome.Success(
                        f.value,
                        listOf(
                            Diagnostic.warning(DiagCode.STORAGE_CORRUPTED, "настройки раздачи не прочитаны; исходник сохранён в карантин, раздача выключена, пароль новый")
                                .copy(causes = parsed.diagnostics),
                        ),
                    )
                }
            }
        }
    }

    fun save(s: ShareSettings): Outcome<Unit> = blobs.write(KEY, ShareCodec.write(s).toByteArray(Charsets.UTF_8))

    private fun fresh(): Outcome<ShareSettings> {
        val s = ShareSettings.generate()
        return when (val w = save(s)) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.Success(s)
        }
    }

    companion object {
        const val KEY: String = "settings/share"
    }
}

/**
 * Раздача в приложении (D-51): прочитать, включить или выключить, сменить
 * логин и пароль. Каждая смена сохраняется сразу; что не сохранилось,
 * того нет. Переподключение туннеля с новыми входами — дело оболочки.
 */
class ShareService(private val store: ShareStore) {
    @Volatile
    private var cached: ShareSettings? = null

    fun current(): Outcome<ShareSettings> = cached?.let { Outcome.Success(it) } ?: store.load().also { r ->
        if (r is Outcome.Success) cached = r.value
    }

    fun setEnabled(on: Boolean): Outcome<ShareSettings> = change { it.copy(enabled = on) }

    /** Новые порт, логин и пароль: прежние другие устройства больше не подойдут. */
    fun regenerate(): Outcome<ShareSettings> = change { ShareSettings.generate().copy(enabled = it.enabled) }

    private fun change(f: (ShareSettings) -> ShareSettings): Outcome<ShareSettings> {
        val now = when (val c = current()) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        val next = f(now)
        return when (val w = store.save(next)) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.Success(next.also { cached = it })
        }
    }
}

package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Резервная копия приложения `dpmeta.app-backup` v1 (`DPmetaApp:D-151`; D-182): разделы
 * с настройками, документами хранилищ, текстовыми файлами и секретами. Без сети и
 * процессов: что брать и куда писать — у оболочки. Секреты в файл — только под
 * шифром ([encode]); значения раскрываются только здесь, при записи архива.
 */
object AppBackup {
    const val FORMAT = "dpmeta.app-backup"
    const val VERSION = 1

    /** Предел архива без шифра: настройки, а не данные проектов. */
    const val MAX_BYTES = 16 shl 20

    /** Раздел секретов — его значения только под паролем. */
    const val SECRETS = "secrets"

    /** Значение `SharedPreferences` с типом — восстанавливается тем же типом. */
    sealed interface Pref {
        data class Str(val value: String) : Pref
        data class Int32(val value: Int) : Pref
        data class Int64(val value: Long) : Pref
        data class Bool(val value: Boolean) : Pref
        data class Real(val value: Float) : Pref
        data class Strings(val value: Set<String>) : Pref

        companion object {
            /** Из значения `SharedPreferences.all`; чужой тип — `null`. */
            fun of(v: Any?): Pref? = when (v) {
                is String -> Str(v)
                is Int -> Int32(v)
                is Long -> Int64(v)
                is Boolean -> Bool(v)
                is Float -> Real(v)
                is Set<*> -> if (v.all { it is String }) Strings(v.map { it as String }.toSet()) else null
                else -> null
            }
        }
    }

    /**
     * Раздел: [prefs] — файл настроек → ключ → значение; [docs] — хранилище → ключ →
     * документ; [files] — имя → текст; [secrets] — имя → значение.
     */
    data class Section(
        val id: String,
        val prefs: Map<String, Map<String, Pref>> = emptyMap(),
        val docs: Map<String, Map<String, JsonObject>> = emptyMap(),
        val files: Map<String, String> = emptyMap(),
        val secrets: Map<String, Secret> = emptyMap(),
    ) {
        val empty: Boolean get() = prefs.values.all { it.isEmpty() } && docs.values.all { it.isEmpty() } && files.isEmpty() && secrets.isEmpty()
    }

    data class Archive(val createdAt: Long, val appVersion: String?, val sections: List<Section>) {
        val hasSecrets: Boolean get() = sections.any { it.secrets.isNotEmpty() }

        fun section(id: String): Section? = sections.firstOrNull { it.id == id }

        /** Только разделы [ids] — для восстановления выбранного. */
        fun select(ids: Set<String>): Archive = copy(sections = sections.filter { it.id in ids })
    }

    private val ID = Regex("[a-z][a-z0-9.-]{0,40}")
    private val NAME = Regex("[A-Za-z0-9._:@-]{1,200}")
    private val MAGIC = "DPAICC1\n".toByteArray(Charsets.US_ASCII)

    /** Файл зашифрован (шифр облачной копии, D-107) — нужен пароль. */
    fun sealed(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /**
     * Архив в байты: без секретов — JSON; с секретами — только через [seal] (шифр паролем
     * автора, F-47). Секреты и нет шифра — отказ, ничего не пишется.
     */
    fun encode(a: Archive, seal: ((ByteArray) -> Outcome<ByteArray>)?): Outcome<ByteArray> {
        if (a.hasSecrets && seal == null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "секреты в копию — только под паролем", FORMAT)
        val bytes = CanonicalJson.write(toJson(a)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "копия больше ${MAX_BYTES shr 20} МиБ — это не настройки", FORMAT)
        return if (seal != null) seal(bytes) else Outcome.success(bytes)
    }

    /** Байты в архив; зашифрованный — через [open] (пароль спрашивает экран). */
    fun decode(bytes: ByteArray, open: ((ByteArray) -> Outcome<ByteArray>)?): Outcome<Archive> {
        val plain = if (sealed(bytes)) {
            open ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "копия под паролем — введите пароль", FORMAT)
            when (val r = open(bytes)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        } else bytes
        if (plain.size > MAX_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${MAX_BYTES shr 20} МиБ — это не копия настроек", FORMAT)
        return read(plain.toString(Charsets.UTF_8))
    }

    private fun toJson(a: Archive): JsonObject = JsonObject(
        linkedMapOf(
            "format" to JsonString(FORMAT),
            "version" to JsonNumber.of(VERSION),
            "createdAt" to JsonNumber.of(a.createdAt),
            "appVersion" to (a.appVersion?.let(::JsonString) ?: JsonNull),
            "sections" to JsonArray(a.sections.map(::sectionJson)),
        ),
    )

    private fun sectionJson(s: Section): JsonObject {
        val fields = linkedMapOf<String, JsonValue>("id" to JsonString(s.id))
        if (s.prefs.isNotEmpty()) fields["prefs"] = JsonObject(s.prefs.mapValues { (_, m) -> JsonObject(m.mapValues { (_, v) -> prefJson(v) }) })
        if (s.docs.isNotEmpty()) fields["docs"] = JsonObject(s.docs.mapValues { (_, m) -> JsonObject(m) })
        if (s.files.isNotEmpty()) fields["files"] = JsonObject(s.files.mapValues { (_, v) -> JsonString(v) })
        // Значение секрета — только сюда и только перед шифром (encode проверил).
        if (s.secrets.isNotEmpty()) fields["secrets"] = JsonObject(s.secrets.mapValues { (_, v) -> JsonString(v.reveal()) })
        return JsonObject(fields)
    }

    private fun prefJson(p: Pref): JsonObject = when (p) {
        is Pref.Str -> JsonObject.of("t" to JsonString("s"), "v" to JsonString(p.value))
        is Pref.Int32 -> JsonObject.of("t" to JsonString("i"), "v" to JsonNumber.of(p.value))
        is Pref.Int64 -> JsonObject.of("t" to JsonString("l"), "v" to JsonNumber.of(p.value))
        is Pref.Bool -> JsonObject.of("t" to JsonString("b"), "v" to JsonBool(p.value))
        is Pref.Real -> JsonObject.of("t" to JsonString("f"), "v" to JsonString(p.value.toString()))
        is Pref.Strings -> JsonObject.of("t" to JsonString("ss"), "v" to JsonArray(p.value.sorted().map(::JsonString)))
    }

    /** Разобрать текст архива; чужой формат, иная версия или неверное поле — отказ с путём. */
    fun read(text: String): Outcome<Archive> {
        val root = JsonParser().parse(text).valueOrNull() as? JsonObject
            ?: return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "это не копия приложения", FORMAT)
        if ((root["format"] as? JsonString)?.value != FORMAT) return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "это не копия приложения", FORMAT)
        val version = (root["version"] as? JsonNumber)?.toLongExact()
        if (version != VERSION.toLong()) return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "версия копии $version не поддерживается — обновите приложение", FORMAT)
        val createdAt = (root["createdAt"] as? JsonNumber)?.toLongExact() ?: return bad("createdAt")
        val appVersion = (root["appVersion"] as? JsonString)?.value
        val list = (root["sections"] as? JsonArray)?.items ?: return bad("sections")
        val sections = ArrayList<Section>()
        for ((i, raw) in list.withIndex()) {
            val o = raw as? JsonObject ?: return bad("sections[$i]")
            val id = (o["id"] as? JsonString)?.value?.takeIf { ID.matches(it) } ?: return bad("sections[$i].id")
            if (sections.any { it.id == id }) return bad("sections[$i].id: повтор $id")
            val prefs = (o["prefs"] as? JsonObject)?.fields?.entries?.associate { (file, m) ->
                if (!NAME.matches(file)) return bad("$id.prefs.$file")
                val values = (m as? JsonObject)?.fields?.entries?.associate { (k, v) ->
                    if (k.length > 200 || k.any { it.isISOControl() }) return bad("$id.prefs.$file.$k")
                    k to (pref(v) ?: return bad("$id.prefs.$file.$k"))
                } ?: return bad("$id.prefs.$file")
                file to values
            }.orEmpty()
            val docs = (o["docs"] as? JsonObject)?.fields?.entries?.associate { (store, m) ->
                if (!NAME.matches(store)) return bad("$id.docs.$store")
                val values = (m as? JsonObject)?.fields?.entries?.associate { (k, v) ->
                    if (!NAME.matches(k)) return bad("$id.docs.$store.$k")
                    k to (v as? JsonObject ?: return bad("$id.docs.$store.$k"))
                } ?: return bad("$id.docs.$store")
                store to values
            }.orEmpty()
            val files = (o["files"] as? JsonObject)?.fields?.entries?.associate { (name, v) ->
                if (!NAME.matches(name)) return bad("$id.files.$name")
                name to ((v as? JsonString)?.value ?: return bad("$id.files.$name"))
            }.orEmpty()
            val secrets = (o["secrets"] as? JsonObject)?.fields?.entries?.associate { (name, v) ->
                if (!NAME.matches(name)) return bad("$id.secrets.$name")
                name to Secret((v as? JsonString)?.value?.takeIf { it.isNotEmpty() } ?: return bad("$id.secrets.$name"))
            }.orEmpty()
            if (secrets.isNotEmpty() && id != SECRETS) return bad("$id.secrets: секреты — только в разделе $SECRETS")
            sections += Section(id, prefs, docs, files, secrets)
        }
        return Outcome.success(Archive(createdAt, appVersion, sections))
    }

    private fun pref(v: JsonValue): Pref? {
        val o = v as? JsonObject ?: return null
        val raw = o["v"]
        return when ((o["t"] as? JsonString)?.value) {
            "s" -> (raw as? JsonString)?.value?.let(Pref::Str)
            "i" -> (raw as? JsonNumber)?.toLongExact()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { Pref.Int32(it.toInt()) }
            "l" -> (raw as? JsonNumber)?.toLongExact()?.let(Pref::Int64)
            "b" -> (raw as? JsonBool)?.value?.let(Pref::Bool)
            "f" -> (raw as? JsonString)?.value?.toFloatOrNull()?.let(Pref::Real)
            "ss" -> (raw as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: return null }?.toSet()?.let(Pref::Strings)
            else -> null
        }
    }

    private fun bad(where: String): Outcome<Archive> = Outcome.failure(DiagCode.VALUE_INVALID, "копия повреждена: $where", FORMAT)
}

/**
 * Где отозвать секрет (F-46; `DPmetaApp:D-151`): страница сервиса, выдавшего его. Нет
 * своей страницы — `null`, человеку — подсказка словами.
 */
object SecretRevoke {
    const val GITHUB_TOKENS = "https://github.com/settings/personal-access-tokens"
    const val HUGGING_FACE = "https://huggingface.co/settings/tokens"
    const val ANTHROPIC_KEYS = "https://console.anthropic.com/settings/keys"
    const val ANTHROPIC_ADMIN = "https://console.anthropic.com/settings/admin-keys"
    const val OPENAI_KEYS = "https://platform.openai.com/api-keys"
    const val GEMINI_KEYS = "https://aistudio.google.com/apikey"
    const val CLAUDE_CODE = "https://claude.ai/code"

    /** Страница ключей API по адресу профиля [host]; неизвестный сервис — `null`. */
    fun apiKey(host: String): String? {
        val h = host.lowercase().trimEnd('.')
        return when {
            h == "api.anthropic.com" -> ANTHROPIC_KEYS
            h == "api.openai.com" -> OPENAI_KEYS
            h == "generativelanguage.googleapis.com" -> GEMINI_KEYS
            h == "openrouter.ai" -> "https://openrouter.ai/settings/keys"
            h == "api.mistral.ai" -> "https://console.mistral.ai/api-keys"
            h == "api.deepseek.com" -> "https://platform.deepseek.com/api_keys"
            else -> null
        }
    }

    /** Слово, которое вводят для «Стереть отмеченные» (как уровень 3, `DPmetaApp:D-37`). */
    const val ERASE_WORD = "СТЕРЕТЬ"

    fun eraseConfirmed(typed: String): Boolean = typed.trim() == ERASE_WORD
}

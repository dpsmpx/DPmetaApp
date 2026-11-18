package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore

/**
 * Выбор поколения ключа подписи (D-203). Секретов здесь нет. Перед удалением
 * прежнего ключа и основная, и резервная версии DocumentStore должны указывать
 * на проверенный новый: восстановление LKG не воскрешает старый выбор.
 * Android-оболочка сериализует обращения вместе с операциями Keystore.
 */
internal class SigningSlots(
    private val documents: DocumentStore,
    private val generation: () -> String = { hex(RandomSource.secure().nextBytes(16)) },
) {
    fun active(project: String?, aliases: Collection<String>): Outcome<String?> {
        val token = when (val r = token(project)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val read = load()
        if (read is Outcome.Failure) return read
        val slots = (read as Outcome.Success).value
        val alias = if (token in slots) {
            slots[token]?.let { if (it == LEGACY) legacyAlias(project) else alias(token, it) }
        } else {
            if (aliases.any { it.startsWith("$PREFIX$token:") }) {
                return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "выбор поколения ключа подписи отсутствует")
            }
            legacyAlias(project).takeIf { it in aliases }
        }
        if (alias != null && alias !in aliases) {
            return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "выбранный ключ подписи отсутствует в Keystore")
        }
        return Outcome.success(alias, read.diagnostics)
    }

    /** stage возвращает успех только после проверки ключа и необходимой переносимой копии. */
    fun <T> replace(
        project: String?,
        aliases: Collection<String>,
        replaceExisting: Boolean,
        stage: (String) -> Outcome<T>,
        discard: (String) -> Outcome<Unit>,
        cleanup: (String) -> Outcome<Unit>,
    ): Outcome<T> {
        val token = when (val r = token(project)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (!replaceExisting && aliases.any { belongs(it, project) }) {
            return Outcome.failure(DiagCode.STATE_CONFLICT, "ключ уже есть — сначала удалите прежний")
        }
        val read = load()
        if (read is Outcome.Failure) return read
        var slots = (read as Outcome.Success).value
        if (token !in slots) {
            if (aliases.any { it.startsWith("$PREFIX$token:") }) {
                return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "поколения ключа есть, но их выбор потерян")
            }
            // До создания пробы фиксируем прежний выбор, включая отсутствие ключа.
            slots = slots + (token to LEGACY.takeIf { legacyAlias(project) in aliases })
            val baseline = commit(slots)
            if (baseline is Outcome.Failure) return baseline
        }
        val id = generation()
        if (!GENERATION.matches(id)) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный идентификатор поколения ключа")
        val next = alias(token, id)
        if (next in aliases) return Outcome.failure(DiagCode.STATE_CONFLICT, "поколение ключа уже существует")
        val staged = stage(next)
        if (staged is Outcome.Failure) {
            val removed = discard(next)
            return Outcome.Failure(staged.diagnostics + if (removed is Outcome.Failure) removed.diagnostics else emptyList())
        }
        val committed = commit(slots + (token to id))
        // После попытки commit результат может быть неопределённым. Оба ключа
        // сохраняются: reader вправе выбрать дописанный tmp или новую основную.
        if (committed is Outcome.Failure) return committed
        val cleaned = cleanup(next)
        val warnings = read.warnings + staged.warnings + if (cleaned is Outcome.Failure) {
            listOf(Diagnostic.warning(DiagCode.STORAGE_IO_FAILED, "новый ключ включён, очистка прежнего ключа не завершена"))
        } else cleaned.warnings
        return Outcome.success((staged as Outcome.Success).value, warnings)
    }

    /** Надгробие сохраняется прежде очистки: LKG и недописанная версия не возвращают удалённый ключ. */
    fun delete(project: String?, cleanup: () -> Outcome<Unit>): Outcome<Unit> {
        val token = when (val r = token(project)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val read = load()
        if (read is Outcome.Failure) return read
        val saved = commit((read as Outcome.Success).value + (token to null))
        if (saved is Outcome.Failure) return saved
        return cleanup()
    }

    private fun load(): Outcome<Map<String, String?>> {
        val read = documents.read(KEY)
        if (read is Outcome.Failure) return read
        val stored = (read as Outcome.Success).value ?: return Outcome.success(emptyMap())
        val d = DiagnosticCollector()
        val f = JsonFields(stored.content, "$", d)
        FormatHeader.read(f, FORMAT, 1..1, d)
        val list = f.array("slots")
        f.finish()
        val out = linkedMapOf<String, String?>()
        for ((i, item) in list?.items.orEmpty().withIndex()) {
            val o = item as? JsonObject
            if (o == null) {
                d.error(DiagCode.FIELD_TYPE, "запись выбора ключа — объект", "$.slots[$i]")
                continue
            }
            val row = JsonFields(o, "$.slots[$i]", d)
            val scope = row.string("scope")
            val value = row.raw("slot")
            val slot = when (value) {
                JsonNull -> null
                is JsonString -> value.value.takeIf { it == LEGACY || GENERATION.matches(it) }.also {
                    if (it == null) d.error(DiagCode.VALUE_INVALID, "неверное поколение ключа", row.path)
                }
                else -> null.also { d.error(DiagCode.FIELD_TYPE, "поколение ключа — строка или null", row.path) }
            }
            row.finish()
            if (scope == null || decodeToken(scope) == null) {
                d.error(DiagCode.VALUE_INVALID, "неверный уровень ключа", row.path)
            } else if (out.containsKey(scope)) {
                d.error(DiagCode.VALUE_INVALID, "уровень ключа повторён", row.path)
            } else out[scope] = slot
        }
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out, read.diagnostics)
    }

    private fun commit(slots: Map<String, String?>): Outcome<Unit> {
        val doc = JsonObject(linkedMapOf(
            *FormatHeader.write(FORMAT, 1),
            "slots" to JsonArray(slots.toSortedMap().map { (scope, slot) ->
                JsonObject.of("scope" to JsonString(scope), "slot" to (slot?.let(::JsonString) ?: JsonNull))
            }),
        ))
        repeat(2) {
            val written = documents.write(KEY, doc)
            if (written is Outcome.Failure) return written
            val checked = documents.read(KEY)
            if (checked is Outcome.Failure) return checked
            val saved = (checked as Outcome.Success).value
            if (saved?.content != doc || saved.fromLastKnownGood || checked.diagnostics.any { it.code == DiagCode.STORAGE_RECOVERED_LKG }) {
                return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "выбор ключа подписи не подтверждён основной записью")
            }
        }
        return Outcome.success(Unit)
    }

    companion object {
        const val BASE = "dpai-apk-signing"
        const val FORMAT = "dpai.signing-slots"
        const val KEY = "active"
        private const val PREFIX = "$BASE:v2:"
        private const val LEGACY = "legacy"
        private val GENERATION = Regex("[0-9a-f]{32}")

        data class Scope(val project: String?)

        fun legacyAlias(project: String?): String = if (project == null) BASE else "$BASE@$project"

        fun belongs(alias: String, project: String?): Boolean =
            alias == legacyAlias(project) || token(project).valueOrNull()?.let { alias.startsWith("$PREFIX$it:") } == true

        fun scope(alias: String): Scope? = when {
            alias == BASE -> Scope(null)
            alias.startsWith("$BASE@") -> Scope(alias.removePrefix("$BASE@"))
            alias.startsWith(PREFIX) -> alias.removePrefix(PREFIX).split(':').takeIf { it.size == 2 && GENERATION.matches(it[1]) }?.let { decodeToken(it[0]) }
            else -> null
        }

        fun copyName(alias: String): String? = alias.takeIf { it.startsWith(PREFIX) && scope(it) != null }
            ?.removePrefix(PREFIX)?.replace(':', '.')?.let { "sign2-$it.p12" }

        fun copyBelongs(name: String, project: String?): Boolean = token(project).valueOrNull()?.let {
            name.startsWith("sign2-$it.") && name.removePrefix("sign2-$it.").removeSuffix(".p12").let(GENERATION::matches) && name.endsWith(".p12")
        } == true

        private fun alias(token: String, generation: String): String = "$PREFIX$token:$generation"

        private fun token(project: String?): Outcome<String> {
            if (project == null) return Outcome.success("g")
            val bytes = project.toByteArray(Charsets.UTF_8)
            if (project.isBlank() || project.any(Char::isISOControl) || bytes.size > 80 || bytes.toString(Charsets.UTF_8) != project) {
                return Outcome.failure(DiagCode.VALUE_INVALID, "неверный id проекта для ключа подписи")
            }
            return Outcome.success("p" + hex(bytes))
        }

        private fun decodeToken(token: String): Scope? {
            if (token == "g") return Scope(null)
            val h = token.removePrefix("p")
            if (!token.startsWith('p') || h.isEmpty() || h.length % 2 != 0 || h.length > 160 || h.any { it !in "0123456789abcdef" }) return null
            val project = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
            return Scope(project).takeIf { token(project).valueOrNull() == token }
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

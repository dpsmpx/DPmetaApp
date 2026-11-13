package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Ключ и реализация шифрования принадлежат платформе; имя записи аутентифицируется как AAD (D-135). */
interface DraftCipher {
    fun seal(record: String, bytes: ByteArray): Outcome<ByteArray>
    fun open(record: String, bytes: ByteArray): Outcome<ByteArray>
}

data class SavedFileDraft(val key: String, val original: ByteArray, val text: String, val selection: EditorSelection, val guestPath: String?)
data class DraftRestore(val drafts: List<SavedFileDraft>, val unreadable: Set<String>, val previous: Set<String>)

/** Только копии: хранилище не имеет доступа к исходным FileTree (D-135). IO сериализует вызывающий. */
class EditorDraftStore(private val vfs: Vfs, private val cipher: DraftCipher) {
    private val bounded = object : Vfs by vfs {
        override fun read(path: String): ByteArray? {
            if ((vfs.size(path) ?: 0) > MAX_RECORD) throw IOException("копия черновика превышает предел")
            return vfs.read(path)
        }
    }
    private val docs = DocumentStore(bounded, DIR)

    fun save(draft: SavedFileDraft): Outcome<Unit> {
        val bytes = encode(draft) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "черновик не записан: размер, выделение или Unicode недопустимы")
        val id = recordId(draft.key)
        val sealed = when (val r = cipher.seal(id, bytes)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (sealed.size > MAX_PAYLOAD + 1024) return Outcome.failure(DiagCode.VALUE_INVALID, "зашифрованная копия превышает предел")
        val content = JsonObject.of("format" to JsonString(FORMAT), "version" to JsonNumber.of(1), "sealed" to JsonString(Base64.getEncoder().encodeToString(sealed)))
        return when (val r = docs.write(id, content)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    fun remove(key: String): Outcome<Unit> = docs.delete(recordId(key))

    /** Только для явно подтверждённого удаления недоступной копии. */
    fun removeUnavailable(record: String): Outcome<Unit> = if (RECORD.matches(record)) docs.delete(record)
        else Outcome.failure(DiagCode.VALUE_INVALID, "неверное имя копии черновика")

    /** .prev/.tmp тоже учитываются: прерванная запись/удаление не прячет копию от восстановления. */
    fun restore(): Outcome<DraftRestore> {
        val ids = try {
            vfs.list(DIR).map { it.removeSuffix(".prev").removeSuffix(".tmp") }.filter { RECORD.matches(it) }.distinct().sorted()
        } catch (_: IOException) {
            return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "каталог черновиков не прочитан; копии оставлены на месте")
        }
        val restored = mutableListOf<SavedFileDraft>()
        val unreadable = mutableSetOf<String>()
        val previous = mutableSetOf<String>()
        for (id in ids) {
            val r = docs.read(id)
            val stored = r.valueOrNull()
            if (r is Outcome.Failure || stored == null) { unreadable += id; continue }
            val content = stored.content
            val version = (content["version"] as? JsonNumber)?.toLongExact()
            val sealed = (content["sealed"] as? JsonString)?.value
            if ((content["format"] as? JsonString)?.value != FORMAT || version != 1L || sealed == null) { unreadable += id; continue }
            val raw = try { Base64.getDecoder().decode(sealed) } catch (_: IllegalArgumentException) { unreadable += id; continue }
            val plain = cipher.open(id, raw).valueOrNull()
            val draft = plain?.let(::decode)
            if (draft == null || recordId(draft.key) != id || restored.size >= MAX_OPEN) { unreadable += id; continue }
            restored += draft
            if (stored.fromLastKnownGood || r.diagnostics.isNotEmpty()) previous += id
        }
        return Outcome.success(DraftRestore(restored, unreadable, previous))
    }

    private fun encode(draft: SavedFileDraft): ByteArray? { return try {
        if (!draft.key.startsWith("file:") || draft.original.size > MAX_ORIGINAL || draft.text.length > MAX_TEXT || draft.selection.start !in 0..draft.text.length || draft.selection.end !in 0..draft.text.length) return null
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(MAGIC)
            out.bytes(utf8(draft.key), MAX_KEY)
            out.bytes(draft.original, MAX_ORIGINAL)
            out.bytes(utf8(draft.text), MAX_TEXT_BYTES)
            out.writeInt(draft.selection.start); out.writeInt(draft.selection.end)
            out.writeBoolean(draft.guestPath != null)
            draft.guestPath?.let { out.bytes(utf8(it), MAX_KEY) }
        }
        output.toByteArray().takeIf { it.size <= MAX_PAYLOAD }
    } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } }

    private fun decode(bytes: ByteArray): SavedFileDraft? { return try {
        if (bytes.size > MAX_PAYLOAD) return null
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC) return null
            val key = text(input.bytes(MAX_KEY))
            val original = input.bytes(MAX_ORIGINAL)
            val current = text(input.bytes(MAX_TEXT_BYTES))
            val selection = EditorSelection(input.readInt(), input.readInt())
            val flag = input.readUnsignedByte()
            val path = when (flag) { 0 -> null; 1 -> text(input.bytes(MAX_KEY)); else -> return null }
            if (!key.startsWith("file:") || current.length > MAX_TEXT || selection.start !in 0..current.length || selection.end !in 0..current.length || input.available() != 0) return null
            SavedFileDraft(key, original, current, selection, path)
        }
    } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } }

    private fun DataOutputStream.bytes(bytes: ByteArray, limit: Int) {
        if (bytes.size > limit) throw IOException("поле черновика превышает предел")
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.bytes(limit: Int): ByteArray {
        val size = readInt()
        if (size !in 0..limit || size > available()) throw IOException("длина поля черновика недопустима")
        return ByteArray(size).also(::readFully)
    }
    private fun utf8(text: String): ByteArray {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }
    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    companion object {
        const val MAX_OPEN = 8
        private const val FORMAT = "dpai.editor-draft"
        private const val DIR = "drafts"
        private const val MAGIC = 0x44504431
        private const val MAX_KEY = 16 * 1024
        private const val MAX_ORIGINAL = 2 * 1024 * 1024
        private const val MAX_TEXT = 2 * 1024 * 1024
        private const val MAX_TEXT_BYTES = 3 * MAX_TEXT
        private const val MAX_PAYLOAD = MAX_ORIGINAL + MAX_TEXT_BYTES + 2 * MAX_KEY + 64
        private const val MAX_RECORD = 12 * 1024 * 1024L
        private val RECORD = Regex("[a-f0-9]{64}")
        fun recordId(key: String): String = Hashing.sha256(key).hex
    }
}

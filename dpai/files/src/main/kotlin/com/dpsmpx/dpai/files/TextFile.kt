package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Текст файла для просмотра и правки (D-99): только UTF-8, без нулевых
 * байтов, до [MAX_EDIT_BYTES]. Двоичный файл не «показывается как
 * получится» — отказ (D-05): правка такого «текста» испортила бы файл.
 */
object TextFile {
    const val MAX_EDIT_BYTES: Long = 2L * 1024 * 1024

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic")

    fun decode(bytes: ByteArray, max: Long = MAX_EDIT_BYTES): Outcome<String> {
        if (bytes.size > max) return Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${max / 1024} КиБ — не для правки")
        if (bytes.any { it == 0.toByte() }) return Outcome.failure(DiagCode.VALUE_INVALID, "двоичный файл — не текст")
        val body = if (bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
            Outcome.success(text)
        } catch (_: CharacterCodingException) {
            Outcome.failure(DiagCode.VALUE_INVALID, "не UTF-8 — не для правки")
        }
    }

    fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGES
}

/**
 * Файлы в чат «Модели» (D-98, D-99): текст файла — в вопрос, только по
 * выбору человека; блок кода с ограждением длиннее любой серии обратных
 * кавычек в тексте — файл не «закрывает» блок раньше времени.
 */
object ChatAttachments {
    const val MAX_BYTES: Long = 256L * 1024

    fun render(name: String, text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return "Файл «$name»:\n$fence\n$text\n$fence"
    }

    /** Вопрос с файлами: сначала вопрос, затем файлы по порядку. */
    fun message(question: String, files: List<Pair<String, String>>): String =
        (listOf(question.trim()).filter { it.isNotEmpty() } + files.map { (n, t) -> render(n, t) }).joinToString("\n\n")
}

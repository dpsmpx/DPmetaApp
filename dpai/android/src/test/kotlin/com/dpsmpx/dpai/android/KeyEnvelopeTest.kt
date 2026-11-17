package com.dpsmpx.dpai.android

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Настоящий AES-GCM: связь с именем, совместимость v1 и отказ от повреждённых данных. */
class KeyEnvelopeTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun boundEnvelopeRejectsAnotherNameOrAlias() {
        val key = key()
        val plain = "синтетические данные".toByteArray(Charsets.UTF_8)
        val sealed = KeyEnvelope.seal(key, "wrap", "first", plain)
        assertFalse(KeyEnvelope.isLegacy(sealed))
        assertContentEquals(plain, KeyEnvelope.open(key, "wrap", "first", sealed))
        assertFailsWith<AEADBadTagException> { KeyEnvelope.open(key, "wrap", "second", sealed) }
        assertFailsWith<AEADBadTagException> { KeyEnvelope.open(key, "other", "first", sealed) }
    }

    @Test
    fun legacyCiphertextCanBeReadAndSealedAsBoundVersion() {
        val key = key()
        val plain = "прежний формат".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val legacy = cipher.iv + cipher.doFinal(plain)
        assertTrue(KeyEnvelope.isLegacy(legacy))
        val opened = KeyEnvelope.open(key, "wrap", "first", legacy)
        assertContentEquals(plain, opened)
        val migrated = KeyEnvelope.seal(key, "wrap", "first", opened)
        assertFalse(KeyEnvelope.isLegacy(migrated))
        assertContentEquals(plain, KeyEnvelope.open(key, "wrap", "first", migrated))
        assertFailsWith<AEADBadTagException> { KeyEnvelope.open(key, "wrap", "second", migrated) }
    }

    @Test
    fun corruptionFutureVersionAndShortEnvelopeAreRejected() {
        val key = key()
        val sealed = KeyEnvelope.seal(key, "wrap", "first", byteArrayOf(1, 2, 3))
        val broken = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertFailsWith<AEADBadTagException> { KeyEnvelope.open(key, "wrap", "first", broken) }
        val future = sealed.copyOf().apply { this[KeyEnvelope.VERSION_OFFSET] = 99 }
        assertFailsWith<IllegalArgumentException> { KeyEnvelope.open(key, "wrap", "first", future) }
        assertFailsWith<IllegalArgumentException> { KeyEnvelope.open(key, "wrap", "first", sealed.copyOf(KeyEnvelope.VERSION_OFFSET + 1)) }
    }
    /** Ошибка файловой системы возникает до обращения к Android Keystore. */
    @Test
    fun readFailureIsNotMissingKey(@TempDir dir: Path) {
        val notDirectory = dir.resolve("обычный-файл")
        Files.writeString(notDirectory, "не каталог")
        val result = assertIs<Outcome.Failure>(KeyWrap.read(notDirectory.toFile(), "wrap", false, "first"))
        assertEquals(DiagCode.STORAGE_IO_FAILED, result.errors.single().code)
    }

}

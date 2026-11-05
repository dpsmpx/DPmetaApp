package com.dpsmpx.dpai.sync

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифр копии (D-107, T20): ключ — из пароля человека (PBKDF2-HMAC-SHA256,
 * своя соль у каждой копии), шифр — AES-256-GCM. Заголовок открытый, но
 * связан с шифротекстом (связанные данные GCM): подменённое число повторов
 * или соль копию не расшифруют. Пароль раскрывается только здесь — в
 * вывод ключа (правило `secret-reveal`).
 *
 * Вид файла: `DPAICC1\n`, длина заголовка (4 байта), заголовок
 * `dpai.cloud-copy` v1 (канонический JSON), шифротекст с тегом.
 */
object CloudCrypto {
    const val FORMAT = "dpai.cloud-copy"
    const val VERSION = 1
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 12

    /** Пределы числа повторов при чтении: меньше — слабо, больше — копия заставит телефон считать часами. */
    const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_HEADER = 4096
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val CIPHER = "AES-256-GCM"
    private val MAGIC = "DPAICC1\n".toByteArray(Charsets.US_ASCII)

    /** Что не так с паролем копии; `null` — годится. */
    fun passphraseProblem(passphrase: Secret): String? = when {
        passphrase.reveal().length < MIN_PASSPHRASE -> "пароль копии — не короче $MIN_PASSPHRASE знаков"
        passphrase.reveal().isBlank() -> "пароль копии из одних пробелов"
        else -> null
    }

    /** Зашифровать [plain]; [iterations] меньше [ITERATIONS] — только для тестов. */
    fun seal(plain: ByteArray, passphrase: Secret, random: RandomSource, iterations: Int = ITERATIONS): Outcome<ByteArray> {
        passphraseProblem(passphrase)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, FORMAT) }
        val salt = random.nextBytes(SALT_BYTES)
        val nonce = random.nextBytes(NONCE_BYTES)
        val header = CanonicalJson.bytes(
            JsonObject(
                linkedMapOf(
                    *FormatHeader.write(FORMAT, VERSION),
                    "kdf" to JsonString(KDF),
                    "iterations" to JsonNumber.of(iterations),
                    "salt" to JsonString(b64(salt)),
                    "cipher" to JsonString(CIPHER),
                    "nonce" to JsonString(b64(nonce)),
                ),
            ),
        )
        val prefix = MAGIC + ByteBuffer.allocate(4).putInt(header.size).array() + header
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(prefix)
            Outcome.success(prefix + c.doFinal(plain))
        } catch (e: GeneralSecurityException) {
            Outcome.failure(DiagCode.INTERNAL_ERROR, "шифр недоступен: ${e.javaClass.simpleName}", FORMAT)
        }
    }

    /**
     * Расшифровать копию. Неверный пароль и подмена неотличимы — оба
     * `BACKUP_INVALID`: копия не читается, данные не тронуты.
     */
    fun open(sealed: ByteArray, passphrase: Secret, minIterations: Int = MIN_ITERATIONS): Outcome<ByteArray> {
        if (sealed.size < MAGIC.size + 4 || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "это не копия контура", FORMAT)
        }
        val len = ByteBuffer.wrap(sealed, MAGIC.size, 4).int
        val start = MAGIC.size + 4
        if (len !in 2..MAX_HEADER || start + len > sealed.size) return Outcome.failure(DiagCode.BACKUP_INVALID, "заголовок копии повреждён", FORMAT)
        val headerBytes = sealed.copyOfRange(start, start + len)
        val d = DiagnosticCollector()
        val f = JsonParser().parse(headerBytes.toString(Charsets.UTF_8)).valueOrNull()?.let { JsonFields.root(it, d) }
            ?: return Outcome.failure(DiagCode.BACKUP_INVALID, "заголовок копии не JSON", FORMAT)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val kdf = f.string("kdf")
        val iterations = f.int("iterations")
        val salt = f.string("salt")?.let(::unb64)
        val cipher = f.string("cipher")
        val nonce = f.string("nonce")?.let(::unb64)
        f.finish()
        if (d.hasErrors) return Outcome.Failure(d.all)
        if (kdf != KDF || cipher != CIPHER) return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "шифр копии не тот: $kdf, $cipher", FORMAT)
        if (iterations == null || iterations !in minIterations..MAX_ITERATIONS) return Outcome.failure(DiagCode.BACKUP_INVALID, "число повторов вне пределов", FORMAT)
        if (salt?.size != SALT_BYTES || nonce?.size != NONCE_BYTES) return Outcome.failure(DiagCode.BACKUP_INVALID, "соль или nonce копии повреждены", FORMAT)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(sealed, 0, start + len)
            Outcome.success(c.doFinal(sealed, start + len, sealed.size - start - len))
        } catch (_: AEADBadTagException) {
            Outcome.failure(DiagCode.BACKUP_INVALID, "неверный пароль или копия повреждена", FORMAT)
        } catch (e: GeneralSecurityException) {
            Outcome.failure(DiagCode.BACKUP_INVALID, "копия не расшифрована: ${e.javaClass.simpleName}", FORMAT)
        }
    }

    private fun key(passphrase: Secret, salt: ByteArray, iterations: Int): SecretKeySpec {
        val chars = passphrase.reveal().toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun unb64(s: String): ByteArray? = try {
        Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
        null
    }
}

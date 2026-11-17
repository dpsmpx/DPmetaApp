package com.dpsmpx.dpai.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Переносимые ключи (D-184, `DPmetaApp:D-157`): закрытый ключ хранится файлом в
 * `noBackupFilesDir/dpai-keys`, зашифрованным AES-256-GCM ключом Android Keystore
 * [alias]. Ключ Keystore не извлекается; [userAuth] — расшифровка и шифрование только
 * после входа PIN или отпечатком (окно — [AUTH_SECONDS]).
 */
internal object KeyWrap {
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV = 12
    const val AUTH_SECONDS = 300

    /** [base] — `noBackupFilesDir` приложения: файлы ключей не уходят в облачную копию Android. */
    fun vfs(base: java.io.File): NioVfs = NioVfs(base.toPath().resolve("dpai-keys")).also { it.mkdirs("") }

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private fun key(alias: String, userAuth: Boolean): SecretKey {
        (store().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .apply {
                        if (userAuth) {
                            setUserAuthenticationRequired(true)
                            setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                        }
                    }
                    .build(),
            )
        }.generateKey()
    }

    /** Зашифровать [plain] и записать файлом [name]; исходные байты затираются. */
    @Synchronized
    fun write(base: java.io.File, alias: String, userAuth: Boolean, name: String, plain: ByteArray): Outcome<Unit> = try {
        val c = Cipher.getInstance(TRANSFORMATION).apply {
            try {
                init(Cipher.ENCRYPT_MODE, key(alias, userAuth))
            } catch (e: KeyPermanentlyInvalidatedException) {
                // Как SecretStore.save: сброшенный ключ не оживёт; новую копию
                // можно записать новым ключом, прежние файлы уже не расшифровать.
                store().deleteEntry(alias)
                init(Cipher.ENCRYPT_MODE, key(alias, userAuth))
            }
        }
        val sealed = c.iv + c.doFinal(plain)
        val v = vfs(base)
        v.write("$name.tmp", sealed)
        // Прочитать именно записанное до замены прежнего файла; неверная
        // запись или ключ оставляют прежнюю копию на месте.
        val saved = v.read("$name.tmp")
        val verify = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(alias, userAuth), GCMParameterSpec(128, saved, 0, IV))
        }.doFinal(saved, IV, saved.size - IV)
        try {
            check(verify.contentEquals(plain)) { "записанная копия ключа не совпадает" }
        } finally {
            verify.fill(0)
        }
        v.rename("$name.tmp", name)
        Outcome.success(Unit)
    } catch (e: UserNotAuthenticatedException) {
        Outcome.failure(DiagCode.DEVICE_AUTH_REQUIRED, "нужен вход по PIN или отпечатку", name)
    } catch (e: Exception) {
        Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "ключ не записан", e))
    } finally {
        plain.fill(0)
        runCatching { vfs(base).let { if (it.exists("$name.tmp")) it.delete("$name.tmp") } }
    }

    /** Прочитать и расшифровать файл [name]; нет файла — `null`. */
    fun read(base: java.io.File, alias: String, userAuth: Boolean, name: String): Outcome<ByteArray?> {
        val sealed = runCatching { vfs(base).read(name) }.getOrNull() ?: return Outcome.success(null)
        if (sealed.size <= IV) return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "файл ключа испорчен", name)
        return try {
            val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(alias, userAuth), GCMParameterSpec(128, sealed, 0, IV)) }
            Outcome.success(c.doFinal(sealed, IV, sealed.size - IV))
        } catch (e: UserNotAuthenticatedException) {
            Outcome.failure(DiagCode.DEVICE_AUTH_REQUIRED, "нужен вход по PIN или отпечатку", name)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Outcome.failure(DiagCode.STORAGE_CORRUPTED, "ключ шифра сброшен: блокировку экрана снимали — копию ключа восстановите из файла", name)
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_CORRUPTED, "файл ключа не расшифрован", e))
        }
    }

    fun exists(base: java.io.File, name: String): Boolean = runCatching { vfs(base).exists(name) }.getOrDefault(false)

    fun delete(base: java.io.File, name: String) {
        runCatching { vfs(base).let { if (it.exists(name)) it.delete(name) } }
    }
}

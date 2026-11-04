package com.dpsmpx.dpmeta.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpmeta.net.Secrets
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Секреты пульта — только на устройстве, под ключом Android Keystore (D-09;
 * ТЗ, §6.6): AES-256-GCM, ключ не покидает Keystore, на диске — шифротекст
 * в `noBackupFilesDir` (в резервную копию не попадает, T-02). Ключ требует
 * входа пользователя (T-01): после биометрии или PIN — [AUTH_SECONDS] секунд,
 * дальше расшифровка отказывает до нового входа. Сейчас один секрет — токен
 * GitHub (PAT, запасной путь D-17).
 */
class SecretStore(private val vfs: Vfs, private val path: String = "github.token") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // T-01: ключ работает только после входа — биометрией или PIN (Lock, F-45).
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .build(),
        )
        return gen.generateKey()
    }

    fun save(secret: Secret): Outcome<Unit> = try {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.iv + c.doFinal(Secrets.bytes(secret))
        vfs.mkdirs("")
        vfs.write(path, sealed)
        Outcome.success(Unit)
    } catch (e: UserNotAuthenticatedException) {
        Outcome.failure(DiagCode.DEVICE_AUTH_REQUIRED, "нужен вход по PIN или биометрии", path)
    } catch (e: GeneralSecurityException) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "секрет не зашифрован: ${e.javaClass.simpleName}", path)
    } catch (e: IOException) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "секрет не записан: ${e.javaClass.simpleName}", path)
    }

    fun load(): Outcome<Secret?> {
        val sealed = vfs.read(path) ?: return Outcome.success(null)
        if (sealed.size <= IV) return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "файл секрета испорчен", path)
        return try {
            val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV)) }
            Outcome.success(Secret(c.doFinal(sealed, IV, sealed.size - IV).toString(Charsets.UTF_8)))
        } catch (e: UserNotAuthenticatedException) {
            // Вход был раньше [AUTH_SECONDS] — секрет цел, нужен новый вход (D-59): не «испорчен».
            Outcome.failure(DiagCode.DEVICE_AUTH_REQUIRED, "нужен вход по PIN или биометрии", path)
        } catch (e: GeneralSecurityException) {
            Outcome.failure(DiagCode.STORAGE_CORRUPTED, "секрет не расшифрован: ${e.javaClass.simpleName}", path)
        }
    }

    /** Секрет сохранён — без расшифровки и без входа: «есть ли токен» не спрашивает PIN. */
    fun exists(): Boolean = vfs.exists(path)

    fun clear() {
        if (vfs.exists(path)) vfs.delete(path)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "dpmeta.secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = 12

        /** Сколько ключ работает после входа: хватает на синхронизацию по кнопке. */
        const val AUTH_SECONDS = 300
    }
}

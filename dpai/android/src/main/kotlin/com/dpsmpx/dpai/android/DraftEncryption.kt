package com.dpsmpx.dpai.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.DraftCipher
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Отдельный ключ для приватных копий исходников; шифрование вне UI (D-135). */
internal object DraftEncryption : DraftCipher {
    private const val ALIAS = "dpai-editor-drafts-v1"
    private const val IV = 12

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw GeneralSecurityException("ключ черновиков недоступен")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    override fun seal(record: String, bytes: ByteArray): Outcome<ByteArray> = crypt {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(record.toByteArray(Charsets.UTF_8)) }
        cipher.iv + cipher.doFinal(bytes)
    }

    override fun open(record: String, bytes: ByteArray): Outcome<ByteArray> = crypt {
        if (bytes.size < IV + 16) throw GeneralSecurityException("копия черновика обрезана")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 0, IV)); updateAAD(record.toByteArray(Charsets.UTF_8))
        }
        cipher.doFinal(bytes, IV, bytes.size - IV)
    }

    private fun crypt(action: () -> ByteArray): Outcome<ByteArray> = try { Outcome.success(action()) }
    catch (_: GeneralSecurityException) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "Keystore не сохранил или не открыл копию черновика; исходные данные оставлены") }
    catch (_: java.io.IOException) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "хранилище ключа черновиков недоступно") }
}

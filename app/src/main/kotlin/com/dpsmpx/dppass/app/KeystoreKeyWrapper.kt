package com.dpsmpx.dppass.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.dpsmpx.dppass.storage.KeyWrapper
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключ-обёртка в Android Keystore (D-19): неизвлекаемый AES-256-GCM.
 * Конверт, атомарная запись обёрнутого ключа и разбор отказов — в
 * `WrappedKeyProvider` (`storage/`, проверяется на хосте); здесь —
 * только вызовы Keystore. Постоянные отказы — [KeyWrapper.KeyLost]
 * (ключ необратимо недействителен или не восстанавливается); прочие
 * исключения Keystore считаются временными.
 *
 * На устройстве ещё не проверялось (`TODO.md`, «На устройстве»).
 */
class KeystoreKeyWrapper : KeyWrapper {
    override fun exists(): Boolean = keyStore().containsAlias(ALIAS)

    override fun create() {
        if (exists()) return
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        gen.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        init(cipher, Cipher.ENCRYPT_MODE, null)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(sealed: ByteArray): ByteArray {
        if (sealed.size <= NONCE + TAG) throw KeyWrapper.KeyLost("обёрнутый ключ короче nonce и тега (${sealed.size} байт)")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        init(cipher, Cipher.DECRYPT_MODE, GCMParameterSpec(TAG * 8, sealed, 0, NONCE))
        return cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    private fun init(cipher: Cipher, mode: Int, spec: GCMParameterSpec?) {
        try {
            if (spec == null) cipher.init(mode, kek()) else cipher.init(mode, kek(), spec)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyWrapper.KeyLost("ключ Keystore необратимо недействителен", e)
        }
    }

    private fun kek(): SecretKey = try {
        keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeyWrapper.KeyLost("ключа Keystore нет")
    } catch (e: UnrecoverableKeyException) {
        throw KeyWrapper.KeyLost("ключ Keystore не восстанавливается", e)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dppass.storage.kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE = 12
        const val TAG = 16
    }
}

package com.dpsmpx.dppass.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dpsmpx.dppass.storage.KeyProvider
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключ шифрования хранилища (D-19) — схема «конверт»:
 * ключ данных (32 случайных байта) хранится только зашифрованным
 * ключом Android Keystore, который из Keystore не извлекается.
 * Ключ данных расшифровывается в память при запуске процесса.
 *
 * На устройстве ещё не проверялось (`TODO.md`, «На устройстве»).
 */
class KeystoreKeyProvider(private val dir: File) : KeyProvider {
    @Volatile
    private var cached: ByteArray? = null

    override fun key(): ByteArray = cached ?: synchronized(this) {
        cached ?: loadOrCreate().also { cached = it }
    }

    private fun loadOrCreate(): ByteArray {
        val wrapped = File(dir, "dek.wrapped")
        val kek = kek()
        if (wrapped.isFile) {
            val bytes = wrapped.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, bytes, 0, NONCE))
            return cipher.doFinal(bytes, NONCE, bytes.size - NONCE)
        }
        val dek = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val sealed = cipher.iv + cipher.doFinal(dek)
        dir.mkdirs()
        val tmp = File(dir, ".dek.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(wrapped)) error("не удалось сохранить ключ хранилища")
        return dek
    }

    private fun kek(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dppass.storage.kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE = 12
    }
}

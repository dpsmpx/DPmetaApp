package com.dpsmpx.dpai.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.AccountId
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключи API-профилей (D-98; D-14, T16): ключ AES-256 в Android Keystore
 * шифрует ключ провайдера (AES-GCM), шифр — в настройках приложения вне
 * копий (`allowBackup="false"`), вне копии настроек (D-51) и вне диска
 * приложения (D-96: тот отдаёт только `files/`). Ключ Keystore не
 * покидает устройство: после переустановки шифр не расшифровать —
 * ключ вводится заново (`PROVIDER_KEY_MISSING`).
 *
 * `reveal()` — только здесь и в транспорте провайдеров (правило
 * `secret-reveal`).
 */
object ApiKeys {
    private const val ALIAS = "dpai-api-keys"
    private const val PREFS = "dpai.api-keys"
    private const val TAG_BITS = 128

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    /** Ключ профиля; пустой — удалить. Сбой Keystore — `false`: ключ не сохранён, человеку — сказать. */
    @Synchronized
    fun put(context: Context, id: AccountId, secret: Secret): Boolean {
        if (secret.isEmpty) {
            remove(context, id)
            return true
        }
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = c.iv + c.doFinal(secret.reveal().toByteArray(Charsets.UTF_8))
            prefs(context).edit().putString(id.value, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /** Ключ профиля или `null`: нет его или шифр не расшифровать (новая установка, сброс Keystore). */
    @Synchronized
    fun get(context: Context, id: AccountId): Secret? {
        val text = prefs(context).getString(id.value, null) ?: return null
        return try {
            val sealed = Base64.decode(text, Base64.NO_WRAP)
            if (sealed.size <= IV_BYTES) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            Secret(c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).toString(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun has(context: Context, id: AccountId): Boolean = prefs(context).contains(id.value)

    @Synchronized
    fun remove(context: Context, id: AccountId) {
        prefs(context).edit().remove(id.value).commit()
    }

    /** Длина IV у AES-GCM Keystore. */
    private const val IV_BYTES = 12
}

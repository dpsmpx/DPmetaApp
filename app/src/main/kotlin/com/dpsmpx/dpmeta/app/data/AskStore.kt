package com.dpsmpx.dpmeta.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpmeta.app.screens.AskActions
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * История «Спросить Claude» (F-21) — локально и зашифрованно (ТЗ, §6.6): документ
 * `dpmeta.ask` под AES-256-GCM, ключ не покидает Android Keystore, имя записи —
 * дополнительными данными; файл — в `noBackupFilesDir`, в резервную копию не идёт.
 * Ключ не требует входа на каждое чтение: приложение уже за PIN (D-59), а секретов в
 * переписке нет — ключи API остаются в подсистеме «AI».
 */
class AskStore(private val vfs: Vfs, private val path: String = "ask.sealed") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Нет файла — пустая история. Не расшифрован или не разобран — отказ: новая запись его заменит. */
    fun load(): Outcome<AskActions.Saved> {
        val sealed = try {
            vfs.read(path)
        } catch (e: IOException) {
            return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "история не прочитана: ${e.javaClass.simpleName}", path)
        } ?: return Outcome.success(AskActions.Saved())
        if (sealed.size <= IV) return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "файл истории испорчен", path)
        return try {
            val c = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV))
                updateAAD(path.toByteArray(Charsets.UTF_8))
            }
            val text = c.doFinal(sealed, IV, sealed.size - IV).toString(Charsets.UTF_8)
            when (val v = JsonParser().parse(text)) {
                is Outcome.Failure -> Outcome.failure(DiagCode.STORAGE_CORRUPTED, "история не разобрана", path)
                is Outcome.Success -> AskActions.fromJson(v.value)
            }
        } catch (e: GeneralSecurityException) {
            Outcome.failure(DiagCode.STORAGE_CORRUPTED, "история не расшифрована: ${e.javaClass.simpleName}", path)
        }
    }

    fun save(saved: AskActions.Saved): Outcome<Unit> = try {
        val c = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(path.toByteArray(Charsets.UTF_8))
        }
        val sealed = c.iv + c.doFinal(CanonicalJson.write(AskActions.toJson(saved)).toByteArray(Charsets.UTF_8))
        vfs.mkdirs("")
        vfs.write(path, sealed)
        Outcome.success(Unit)
    } catch (e: GeneralSecurityException) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "история не зашифрована: ${e.javaClass.simpleName}", path)
    } catch (e: IOException) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "история не записана: ${e.javaClass.simpleName}", path)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "dpmeta.ask"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = 12
        private const val TAG_BITS = 128
    }
}

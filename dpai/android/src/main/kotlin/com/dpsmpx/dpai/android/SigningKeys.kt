package com.dpsmpx.dpai.android

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Ключ подписи APK автора в Android Keystore (D-157): импорт один раз из файла
 * PKCS#12, подпись — только после входа (PIN или сильная биометрия, действует
 * минуту). Закрытый ключ из Keystore не извлекается; файл и пароль не хранятся.
 */
internal object SigningKeys {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "dpai-apk-signing"

    /** Сколько секунд после входа ключ подписывает без нового входа. */
    private const val AUTH_SECONDS = 60

    data class Info(val subject: String, val certSha256: String, val algorithm: String)

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    /** Импортированный ключ; нет его или Keystore не отвечает — `null`. */
    fun info(): Info? = runCatching {
        val cert = store().getCertificateChain(ALIAS)?.firstOrNull() as? X509Certificate ?: return null
        Info(cert.subjectX500Principal.name, ApkSign.sha256(cert.encoded), cert.publicKey.algorithm)
    }.getOrNull()

    /** Ключ и цепочка для [ApkSign.sign]; подписывать можно только после входа — иначе apksig получит отказ Keystore. */
    fun key(): Pair<PrivateKey, List<X509Certificate>>? = runCatching {
        val ks = store()
        val key = ks.getKey(ALIAS, null) as? PrivateKey ?: return null
        val chain = ks.getCertificateChain(ALIAS)?.mapNotNull { it as? X509Certificate }.orEmpty()
        if (chain.isEmpty()) null else key to chain
    }.getOrNull()

    /**
     * Импортировать ключ [alias] (`null` — единственный) из файла [bytes] с паролем
     * [password]. Прежний ключ не заменяется молча: сначала его удалить.
     */
    fun import(context: Context, bytes: ByteArray, password: Secret, alias: String?): Outcome<Info> {
        if (info() != null) return Outcome.failure(DiagCode.STATE_CONFLICT, "ключ подписи уже есть — сначала удалите прежний")
        val chars = password.reveal().toCharArray()
        val parsed = try {
            when (val r = KeyFile.read(bytes, chars, alias)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        } finally {
            chars.fill('\u0000')
        }
        val algorithm = parsed.key.algorithm
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .apply { if (algorithm == KeyProperties.KEY_ALGORITHM_RSA) setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1) }
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
        return try {
            store().setEntry(ALIAS, KeyStore.PrivateKeyEntry(parsed.key, parsed.chain.toTypedArray()), protection)
            val info = info() ?: return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "ключ не сохранился в Keystore")
            AppLogs.info(context, "подпись", "ключ подписи импортирован: ${info.subject}, SHA-256 ${info.certSha256}")
            Outcome.success(info)
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "Keystore не принял ключ ($algorithm)", e))
        }
    }

    fun delete(context: Context): Outcome<Unit> {
        val before = info() ?: return Outcome.success(Unit)
        return try {
            store().deleteEntry(ALIAS)
            AppLogs.info(context, "подпись", "ключ подписи удалён: ${before.subject}, SHA-256 ${before.certSha256}")
            Outcome.success(Unit)
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "ключ не удалён", e))
        }
    }
}

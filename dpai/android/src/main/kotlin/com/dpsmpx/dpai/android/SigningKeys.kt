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
 * Ключи подписи APK автора в Android Keystore (D-157, D-178): глобальный и свой у
 * проекта ([project] — id проекта, `null` — глобальный). Импорт из файла PKCS#12 или
 * созданный ключ с копией-файлом у автора; подпись — только после входа (PIN или
 * сильная биометрия, действует минуту). Закрытый ключ из Keystore не извлекается;
 * файл и пароль приложение не хранит.
 */
internal object SigningKeys {
    private const val STORE = "AndroidKeyStore"

    /** Глобальный ключ — прежнее имя: импортированный до D-178 ключ стал глобальным. */
    private const val ALIAS = "dpai-apk-signing"

    private fun alias(project: String?): String = if (project == null) ALIAS else "$ALIAS@$project"

    private fun level(project: String?): String = if (project == null) "глобальный" else "проекта $project"

    /** Чем подписывать сборку проекта: свой ключ, иначе глобальный, иначе ничем (D-178). */
    enum class Choice(val title: String) { PROJECT("ключом проекта"), GLOBAL("глобальным ключом"), NONE("не подписываются") }

    fun choose(hasProject: Boolean, hasGlobal: Boolean): Choice = when {
        hasProject -> Choice.PROJECT
        hasGlobal -> Choice.GLOBAL
        else -> Choice.NONE
    }

    /** Ключ для сборок проекта [project]: выбор и сведения; ключа нет — [Choice.NONE] и `null`. */
    fun forProject(project: String): Pair<Choice, Info?> {
        val own = info(project)
        val global = if (own == null) info(null) else null
        return when (val c = choose(own != null, global != null)) {
            Choice.PROJECT -> c to own
            Choice.GLOBAL -> c to global
            Choice.NONE -> c to null
        }
    }

    /** Сколько секунд после входа ключ подписывает без нового входа. */
    private const val AUTH_SECONDS = 60

    data class Info(val subject: String, val certSha256: String, val algorithm: String)

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    /** Ключ уровня [project]; нет его или Keystore не отвечает — `null`. */
    fun info(project: String?): Info? = runCatching {
        val cert = store().getCertificateChain(alias(project))?.firstOrNull() as? X509Certificate ?: return null
        Info(cert.subjectX500Principal.name, ApkSign.sha256(cert.encoded), cert.publicKey.algorithm)
    }.getOrNull()

    /** Ключ и цепочка для [ApkSign.sign]; подписывать можно только после входа — иначе apksig получит отказ Keystore. */
    fun key(project: String?): Pair<PrivateKey, List<X509Certificate>>? = runCatching {
        val ks = store()
        val key = ks.getKey(alias(project), null) as? PrivateKey ?: return null
        val chain = ks.getCertificateChain(alias(project))?.mapNotNull { it as? X509Certificate }.orEmpty()
        if (chain.isEmpty()) null else key to chain
    }.getOrNull()

    /**
     * Импортировать ключ [alias] (`null` — единственный) из файла [bytes] с паролем
     * [password] уровнем [project]. Прежний ключ уровня не заменяется молча: сначала его удалить.
     */
    fun import(context: Context, bytes: ByteArray, password: Secret, alias: String?, project: String?): Outcome<Info> {
        if (info(project) != null) return Outcome.failure(DiagCode.STATE_CONFLICT, "ключ уже есть — сначала удалите прежний")
        val chars = password.reveal().toCharArray()
        val parsed = try {
            when (val r = KeyFile.read(bytes, chars, alias)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        } finally {
            chars.fill('\u0000')
        }
        return put(context, parsed, project, "импортирован")
    }

    /**
     * Создать ключ уровня [project] (D-178): файл PKCS#12 от [KeyFile.create] уже сохранён
     * автором; он читается обратно тем же паролем и только тогда ключ кладётся в Keystore.
     */
    fun adopt(context: Context, file: ByteArray, password: CharArray, project: String?): Outcome<Info> {
        if (info(project) != null) return Outcome.failure(DiagCode.STATE_CONFLICT, "ключ уже есть — сначала удалите прежний")
        val parsed = when (val r = KeyFile.read(file, password, KeyFile.ALIAS)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return put(context, parsed, project, "создан, копия — файлом у автора")
    }

    private fun put(context: Context, parsed: KeyFile.Parsed, project: String?, what: String): Outcome<Info> {
        val algorithm = parsed.key.algorithm
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .apply { if (algorithm == KeyProperties.KEY_ALGORITHM_RSA) setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1) }
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
        return try {
            store().setEntry(alias(project), KeyStore.PrivateKeyEntry(parsed.key, parsed.chain.toTypedArray()), protection)
            val info = info(project) ?: return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "ключ не сохранился в Keystore")
            AppLogs.info(context, "подпись", "ключ подписи (${level(project)}) $what: ${info.subject}, SHA-256 ${info.certSha256}")
            Outcome.success(info)
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "Keystore не принял ключ ($algorithm)", e))
        }
    }

    fun delete(context: Context, project: String?): Outcome<Unit> {
        val before = info(project) ?: return Outcome.success(Unit)
        return try {
            store().deleteEntry(alias(project))
            AppLogs.info(context, "подпись", "ключ подписи (${level(project)}) удалён: ${before.subject}, SHA-256 ${before.certSha256}")
            Outcome.success(Unit)
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "ключ не удалён", e))
        }
    }
}

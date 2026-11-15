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
 * сильная биометрия, действует минуту). Закрытый ключ из Keystore не извлекается.
 * С D-184 (`DPmetaApp:D-157`) рядом — копия файла .p12 с паролем, зашифрованная ключом
 * Keystore со входом: она уходит в резервную копию под паролем и из неё восстанавливает
 * ключ. У ключей, импортированных раньше, копии нет — их импортируют заново.
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
        try {
            val parsed = when (val r = KeyFile.read(bytes, chars, alias)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val info = when (val r = put(context, parsed, project, "импортирован")) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            seal(context, bytes, chars, alias, project)
            return Outcome.success(info)
        } finally {
            chars.fill('\u0000')
        }
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
        val info = when (val r = put(context, parsed, project, "создан, копия — файлом у автора")) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        seal(context, file, password, KeyFile.ALIAS, project)
        return Outcome.success(info)
    }

    private const val WRAP = "dpai-sign-wrap"
    private const val SEALED = "dpai.signing-key"

    private fun sealedName(project: String?): String = "sign-" + (project?.lowercase()?.filter { it.isLetterOrDigit() || it == '_' || it == '-' } ?: "global") + ".p12"

    /** Есть ли копия ключа уровня [project] для резервной копии (D-184). */
    fun portable(context: Context, project: String?): Boolean = KeyWrap.exists(context.applicationContext.noBackupFilesDir, sealedName(project))

    /** Копия файла и пароля — ключом Keystore со входом; не вышло — ключ работает, но в копию не попадёт. */
    private fun seal(context: Context, file: ByteArray, password: CharArray, alias: String?, project: String?) {
        val doc = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(com.dpsmpx.dpai.core.serialization.JsonObject(linkedMapOf<String, com.dpsmpx.dpai.core.serialization.JsonValue>(
            "format" to com.dpsmpx.dpai.core.serialization.JsonString(SEALED),
            "version" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(1),
            "p12" to com.dpsmpx.dpai.core.serialization.JsonString(java.util.Base64.getEncoder().encodeToString(file)),
            "password" to com.dpsmpx.dpai.core.serialization.JsonString(String(password)),
        ).apply { alias?.let { put("alias", com.dpsmpx.dpai.core.serialization.JsonString(it)) } }))
        when (val w = KeyWrap.write(context.applicationContext.noBackupFilesDir, WRAP, userAuth = true, sealedName(project), doc.toByteArray(Charsets.UTF_8))) {
            is Outcome.Failure -> AppLogs.info(context, "подпись", "копия ключа (${level(project)}) для резервной копии не сохранена: ${w.diagnostics.first().message}")
            is Outcome.Success -> AppLogs.info(context, "подпись", "копия ключа (${level(project)}) для резервной копии сохранена")
        }
    }

    /** Значение для копии под паролем; нужен недавний вход (окно [KeyWrap.AUTH_SECONDS]). */
    fun exportValue(context: Context, project: String?): Outcome<Secret?> = when (val r = KeyWrap.read(context.applicationContext.noBackupFilesDir, WRAP, userAuth = true, sealedName(project))) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(r.value?.let { b -> try { Secret(String(b, Charsets.UTF_8)) } finally { b.fill(0) } })
    }

    /** Ключ из копии (D-184): прежний ключ уровня заменяется; копия снова сохраняется. */
    fun importValue(context: Context, project: String?, value: Secret): Outcome<Info> {
        val o = com.dpsmpx.dpai.core.serialization.JsonParser().parse(value.reveal()).valueOrNull() as? com.dpsmpx.dpai.core.serialization.JsonObject
        val p12 = (o?.get("p12") as? com.dpsmpx.dpai.core.serialization.JsonString)?.value?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        val pw = (o?.get("password") as? com.dpsmpx.dpai.core.serialization.JsonString)?.value
        if ((o?.get("format") as? com.dpsmpx.dpai.core.serialization.JsonString)?.value != SEALED || p12 == null || pw == null) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "ключ подписи в копии не читается")
        }
        val alias = (o["alias"] as? com.dpsmpx.dpai.core.serialization.JsonString)?.value
        if (info(project) != null) delete(context, project)
        return import(context, p12, Secret(pw), alias, project).also { p12.fill(0) }
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

    /** Все ключи подписи в Keystore: `null` — глобальный, иначе id проекта (`DPmetaApp:D-151`). */
    fun all(): List<String?> = try {
        store().aliases().toList().filter { it == ALIAS || it.startsWith("$ALIAS@") }.map { if (it == ALIAS) null else it.removePrefix("$ALIAS@") }
    } catch (_: Exception) {
        emptyList()
    }

    fun delete(context: Context, project: String?): Outcome<Unit> {
        val before = info(project) ?: return Outcome.success(Unit)
        return try {
            store().deleteEntry(alias(project))
            KeyWrap.delete(context.applicationContext.noBackupFilesDir, sealedName(project))
            AppLogs.info(context, "подпись", "ключ подписи (${level(project)}) удалён: ${before.subject}, SHA-256 ${before.certSha256}")
            Outcome.success(Unit)
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "ключ не удалён", e))
        }
    }
}

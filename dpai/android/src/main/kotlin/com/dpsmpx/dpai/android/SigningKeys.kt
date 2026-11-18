package com.dpsmpx.dpai.android

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.UserNotAuthenticatedException
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Ключи подписи двух уровней (D-178), переносимая копия (D-184) и проверенное
 * переключение поколений (D-203). Вход PIN/биометрией нужен для подписи и копии.
 * Операции выбора, импорта и удаления сериализованы, Context не сохраняется.
 */
internal object SigningKeys {
    private const val WRAP = "dpai-sign-wrap"
    private const val SEALED = "dpai.signing-key"
    private const val AUTH_SECONDS = 60
    private const val MAX_FILE = 1 shl 20

    enum class Choice(val title: String) { PROJECT("ключом проекта"), GLOBAL("глобальным ключом"), NONE("не подписываются") }
    data class Info(val subject: String, val certSha256: String, val algorithm: String)

    fun choose(hasProject: Boolean, hasGlobal: Boolean): Choice = when {
        hasProject -> Choice.PROJECT
        hasGlobal -> Choice.GLOBAL
        else -> Choice.NONE
    }

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun slots(context: Context) = SigningSlots(DocumentStore(NioVfs(context.applicationContext.noBackupFilesDir.toPath()), "signing-slots"))
    private fun level(project: String?) = if (project == null) "глобальный" else "проекта $project"
    private fun legacyCopy(project: String?) = "sign-" + (project?.lowercase()?.filter { it.isLetterOrDigit() || it == '_' || it == '-' } ?: "global") + ".p12"
    private fun copyName(alias: String, project: String?) = SigningSlots.copyName(alias) ?: legacyCopy(project)

    private inline fun <T> access(block: (KeyStore) -> Outcome<T>): Outcome<T> = try {
        block(store())
    } catch (e: UserNotAuthenticatedException) {
        Outcome.failure(DiagCode.DEVICE_AUTH_REQUIRED, "для ключа подписи нужен новый вход по PIN или отпечатку")
    } catch (e: Exception) {
        if (generateSequence<Throwable>(e) { it.cause }.take(8).any { it is UserNotAuthenticatedException }) {
            Outcome.failure(DiagCode.DEVICE_AUTH_REQUIRED, "для ключа подписи нужен новый вход по PIN или отпечатку")
        } else Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "хранилище ключей подписи недоступно", e))
    }

    private fun certificate(ks: KeyStore, alias: String): Info? = (ks.getCertificate(alias) as? X509Certificate)?.let {
        Info(it.subjectX500Principal.name, ApkSign.sha256(it.encoded), it.publicKey.algorithm)
    }

    @Synchronized
    fun info(context: Context, project: String?): Outcome<Info?> = access { ks ->
        when (val r = slots(context).active(project, ks.aliases().toList())) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val selected = r.value
                if (selected == null) Outcome.success(null, r.diagnostics)
                else certificate(ks, selected)?.let { Outcome.success(it, r.diagnostics) }
                    ?: Outcome.failure(DiagCode.STORAGE_CORRUPTED, "у выбранного ключа подписи нет сертификата")
            }
        }
    }

    /** Ошибка проектного выбора не превращается в разрешение использовать глобальный ключ. */
    @Synchronized
    fun forProject(context: Context, project: String): Outcome<Pair<Choice, Info?>> {
        val own = info(context, project)
        if (own is Outcome.Failure) return own
        if ((own as Outcome.Success).value != null) return Outcome.success(Choice.PROJECT to own.value, own.diagnostics)
        val global = info(context, null)
        if (global is Outcome.Failure) return global
        return Outcome.success(if ((global as Outcome.Success).value == null) Choice.NONE to null else Choice.GLOBAL to global.value, own.diagnostics + global.diagnostics)
    }

    /** Выбор и сертификат должны совпасть с теми, которые человек видел перед подтверждением. */
    @Synchronized
    fun key(context: Context, project: String, expected: Choice, certSha256: String?): Outcome<Pair<PrivateKey, List<X509Certificate>>> {
        val selected = forProject(context, project)
        if (selected is Outcome.Failure) return selected
        val current = (selected as Outcome.Success).value
        if (expected == Choice.NONE || certSha256 == null || current.first != expected || current.second?.certSha256 != certSha256) {
            return Outcome.failure(DiagCode.STATE_CONFLICT, "ключ подписи изменился — откройте сведения об APK заново")
        }
        val scope = project.takeIf { expected == Choice.PROJECT }
        return access { ks ->
            when (val r = slots(context).active(scope, ks.aliases().toList())) {
                is Outcome.Failure -> r
                is Outcome.Success -> r.value?.let { alias ->
                    when (val loaded = entry(ks, alias)) {
                        is Outcome.Failure -> loaded
                        is Outcome.Success -> if (ApkSign.sha256(loaded.value.second.first().encoded) == certSha256) loaded
                            else Outcome.failure(DiagCode.STATE_CONFLICT, "сертификат ключа изменился — откройте сведения заново")
                    }
                } ?: Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "ключ подписи отсутствует")
            }
        }
    }

    private fun entry(ks: KeyStore, alias: String): Outcome<Pair<PrivateKey, List<X509Certificate>>> {
        val key = ks.getKey(alias, null) as? PrivateKey ?: return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "закрытый ключ подписи не прочитан")
        val chain = ks.getCertificateChain(alias)?.map { it as? X509Certificate ?: return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "неверная цепочка сертификатов") }.orEmpty()
        if (chain.isEmpty()) return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "у ключа подписи нет цепочки сертификатов")
        return Outcome.success(key to chain)
    }

    @Synchronized
    fun import(context: Context, bytes: ByteArray, password: Secret, alias: String?, project: String?): Outcome<Info> {
        val chars = password.reveal().toCharArray()
        return try { install(context, bytes, chars, alias, project, replaceExisting = false, copyRequired = false) }
        finally { chars.fill('\u0000') }
    }

    @Synchronized
    fun adopt(context: Context, file: ByteArray, password: CharArray, project: String?): Outcome<Info> =
        install(context, file, password, KeyFile.ALIAS, project, replaceExisting = false, copyRequired = false)

    private fun install(context: Context, file: ByteArray, password: CharArray, sourceAlias: String?, project: String?, replaceExisting: Boolean, copyRequired: Boolean): Outcome<Info> {
        if (project != null) {
            val id = IdKinds.PROJECT.parse(project)
            if (id is Outcome.Failure) return id
        }
        if (file.size > MAX_FILE) return Outcome.failure(DiagCode.VALUE_INVALID, "файл ключа больше 1 МБ")
        val parsed = when (val r = KeyFile.read(file, password, sourceAlias)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return access { ks ->
            if (parsed.key.algorithm !in setOf("RSA", "EC")) return@access Outcome.failure(DiagCode.UNSUPPORTED, "для подписи поддерживаются ключи RSA и EC")
            if (!SigningKeyProof.matches(parsed.key, parsed.chain.first().publicKey)) return@access Outcome.failure(DiagCode.VALUE_INVALID, "закрытый ключ не соответствует сертификату")
            slots(context).replace(project, ks.aliases().toList(), replaceExisting,
                stage = { next -> access {
                    when (val saved = put(ks, parsed, next)) {
                        is Outcome.Failure -> saved
                        is Outcome.Success -> {
                            val copied = seal(context, file, password, parsed.alias, copyName(next, project))
                            if (copied is Outcome.Failure && copyRequired) copied
                            else Outcome.success(saved.value, if (copied is Outcome.Failure) listOf(Diagnostic.warning(DiagCode.STORAGE_IO_FAILED, "ключ сохранён, но копия под паролем не создана")) else copied.warnings)
                        }
                    }
                } },
                discard = { next -> access { eraseOne(context, it, next, project) } },
                cleanup = { keep -> access { cleanup(context, it, project, keep) } },
            ).also { result ->
                if (result is Outcome.Success) AppLogs.info(context, "подпись", "ключ подписи (${level(project)}) включён: SHA-256 ${result.value.certSha256}")
            }
        }
    }

    /** Запись только в свободный alias; проверяем именно импортированный Keystore-ключ. */
    private fun put(ks: KeyStore, parsed: KeyFile.Parsed, alias: String): Outcome<Info> {
        if (ks.containsAlias(alias)) return Outcome.failure(DiagCode.STATE_CONFLICT, "alias нового ключа уже занят")
        val algorithm = parsed.key.algorithm
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .apply { if (algorithm == KeyProperties.KEY_ALGORITHM_RSA) setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1) }
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
        ks.setEntry(alias, KeyStore.PrivateKeyEntry(parsed.key, parsed.chain.toTypedArray()), protection)
        val saved = when (val r = entry(ks, alias)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (!saved.second.first().encoded.contentEquals(parsed.chain.first().encoded) || !SigningKeyProof.matches(saved.first, saved.second.first().publicKey)) {
            return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "импортированный ключ не прошёл проверку подписью")
        }
        return certificate(ks, alias)?.let { Outcome.success(it) } ?: Outcome.failure(DiagCode.STORAGE_CORRUPTED, "сертификат импортированного ключа не прочитан")
    }

    private fun seal(context: Context, file: ByteArray, password: CharArray, alias: String, name: String): Outcome<Unit> {
        val doc = JsonObject(linkedMapOf(
            *FormatHeader.write(SEALED, 1),
            "p12" to JsonString(java.util.Base64.getEncoder().encodeToString(file)),
            "password" to JsonString(String(password)), "alias" to JsonString(alias),
        ))
        return KeyWrap.write(context.applicationContext.noBackupFilesDir, WRAP, true, name, CanonicalJson.write(doc).toByteArray(Charsets.UTF_8))
    }

    /** Не data class: пароль не должен попадать в автоматически созданный toString. */
    private class Material(val file: ByteArray, val password: CharArray, val alias: String?) {
        fun clear() { file.fill(0); password.fill('\u0000') }
    }

    private fun material(text: String): Outcome<Material> {
        val parsed = JsonParser().parse(text)
        if (parsed is Outcome.Failure) return parsed
        val d = DiagnosticCollector()
        val f = JsonFields.root((parsed as Outcome.Success).value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, SEALED, 1..1, d)
        val encoded = f.string("p12")
        val password = f.string("password")
        val alias = if (f.has("alias")) f.string("alias") else null
        f.finish()
        if (d.hasErrors || encoded == null || password == null) return Outcome.Failure(d.all)
        if (encoded.length > (MAX_FILE + 2) / 3 * 4) return Outcome.failure(DiagCode.VALUE_INVALID, "копия ключа больше предела")
        val bytes = try { java.util.Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "копия ключа подписи не читается")
        }
        if (bytes.size > MAX_FILE) {
            bytes.fill(0)
            return Outcome.failure(DiagCode.VALUE_INVALID, "копия ключа больше предела")
        }
        return Outcome.success(Material(bytes, password.toCharArray(), alias))
    }

    @Synchronized
    fun importValue(context: Context, project: String?, value: Secret): Outcome<Info> {
        val decoded = when (val r = material(value.reveal())) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return try { install(context, decoded.file, decoded.password, decoded.alias, project, replaceExisting = true, copyRequired = true) }
        finally { decoded.clear() }
    }

    @Synchronized
    fun portable(context: Context, project: String?): Boolean = access { ks ->
        when (val r = slots(context).active(project, ks.aliases().toList())) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value?.let { KeyWrap.exists(context.applicationContext.noBackupFilesDir, copyName(it, project)) } == true)
        }
    }.valueOrNull() == true

    @Synchronized
    fun exportValue(context: Context, project: String?): Outcome<Secret?> = access { ks ->
        val active = when (val r = slots(context).active(project, ks.aliases().toList())) {
            is Outcome.Failure -> return@access r
            is Outcome.Success -> r.value ?: return@access Outcome.success(null)
        }
        val bytes = when (val r = KeyWrap.read(context.applicationContext.noBackupFilesDir, WRAP, true, copyName(active, project))) {
            is Outcome.Failure -> return@access r
            is Outcome.Success -> r.value ?: return@access Outcome.success(null)
        }
        try {
            val text = bytes.toString(Charsets.UTF_8)
            val decoded = when (val r = material(text)) {
                is Outcome.Failure -> return@access r
                is Outcome.Success -> r.value
            }
            try {
                val parsed = when (val r = KeyFile.read(decoded.file, decoded.password, decoded.alias)) {
                    is Outcome.Failure -> return@access r
                    is Outcome.Success -> r.value
                }
                if (ApkSign.sha256(parsed.chain.first().encoded) != certificate(ks, active)?.certSha256 || !SigningKeyProof.matches(parsed.key, parsed.chain.first().publicKey)) {
                    return@access Outcome.failure(DiagCode.STORAGE_CORRUPTED, "переносимая копия не соответствует выбранному ключу подписи")
                }
                Outcome.success(Secret(text))
            } finally { decoded.clear() }
        } finally { bytes.fill(0) }
    }

    /** Включая прежние/неочищенные поколения: аварийный отзыв не должен их пропустить. */
    @Synchronized
    fun allChecked(): Outcome<List<String?>> = access { ks -> Outcome.success(ks.aliases().toList().mapNotNull(SigningSlots::scope).map { it.project }.distinct()) }

    @Synchronized
    fun all(): List<String?> = allChecked().valueOrNull().orEmpty()

    private fun eraseOne(context: Context, ks: KeyStore, alias: String, project: String?): Outcome<Unit> {
        val erased = KeyWrap.deleteChecked(context.applicationContext.noBackupFilesDir, copyName(alias, project))
        if (erased is Outcome.Failure) return erased
        ks.deleteEntry(alias)
        return Outcome.success(Unit)
    }

    private fun cleanup(context: Context, ks: KeyStore, project: String?, keep: String?): Outcome<Unit> {
        val kept = keep?.let { copyName(it, project) }
        val vfs = KeyWrap.vfs(context.applicationContext.noBackupFilesDir)
        val names = vfs.list("").map { it.removeSuffix(".tmp") }.distinct()
        val aliases = ks.aliases().toList()
        // Старый формат имени был неинъективным. Не стираем общую копию,
        // пока другой уровень её использует или его выбор не прочитан.
        if (legacyCopy(project) in names) {
            for (other in aliases.mapNotNull(SigningSlots::scope).map { it.project }.distinct().filter { it != project && legacyCopy(it) == legacyCopy(project) }) {
                val selected = slots(context).active(other, aliases)
                if (selected !is Outcome.Success || selected.value != null) {
                    return Outcome.failure(DiagCode.STATE_CONFLICT, "прежнее имя копии ключа используется другим уровнем; копия сохранена")
                }
            }
        }
        val files = names.filter {
            it != kept && (it == legacyCopy(project) || SigningSlots.copyBelongs(it, project))
        }
        for (file in files) {
            val r = KeyWrap.deleteChecked(context.applicationContext.noBackupFilesDir, file)
            if (r is Outcome.Failure) return r
        }
        ks.aliases().toList().filter { it != keep && SigningSlots.belongs(it, project) }.forEach(ks::deleteEntry)
        return Outcome.success(Unit)
    }

    @Synchronized
    fun delete(context: Context, project: String?): Outcome<Unit> = access { ks ->
        slots(context).delete(project) { cleanup(context, ks, project, null) }.also {
            if (it is Outcome.Success) AppLogs.info(context, "подпись", "все поколения ключа подписи (${level(project)}) удалены")
        }
    }
}

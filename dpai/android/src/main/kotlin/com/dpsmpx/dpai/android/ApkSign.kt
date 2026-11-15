package com.dpsmpx.dpai.android

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Подпись и проверка APK (D-157) — без Android API: библиотека apksig. Ключ —
 * объект [PrivateKey]: у ключа из Android Keystore подпись делает сам Keystore,
 * ключ из процесса не выходит. Подписанный файл пишется рядом временным и
 * переименовывается только после проверки подписи: обрыв не оставит
 * полуподписанный APK под настоящим именем.
 */
internal object ApkSign {
    /** Что известно о подписи APK: схемы, отпечаток SHA-256 сертификата подписавшего и субъект. */
    data class Signature(val verified: Boolean, val schemes: List<String>, val certSha256: String?, val subject: String?, val problems: List<String>)

    fun inspect(apk: File): Outcome<Signature> = try {
        val r = ApkVerifier.Builder(apk).build().verify()
        val cert = r.signerCertificates.firstOrNull()
        Outcome.success(
            Signature(
                verified = r.isVerified,
                schemes = listOfNotNull("v1".takeIf { r.isVerifiedUsingV1Scheme }, "v2".takeIf { r.isVerifiedUsingV2Scheme }, "v3".takeIf { r.isVerifiedUsingV3Scheme }, "v3.1".takeIf { r.isVerifiedUsingV31Scheme }),
                certSha256 = cert?.let { sha256(it.encoded) },
                subject = cert?.subjectX500Principal?.name,
                problems = r.allErrors.take(5).map { it.toString().take(300) },
            ),
        )
    } catch (e: Exception) {
        Outcome.failure(DiagCode.VALUE_INVALID, "файл не читается как APK: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}", apk.name)
    }

    /**
     * Подписать [input] в [output] ключом [key] с цепочкой [chain]. Прежние подписи
     * снимаются (v1, v2, v3; v4 не пишется). Итог — подпись нового файла; она
     * обязана совпасть с сертификатом [chain]`[0]`, иначе файл не появится.
     */
    fun sign(input: File, output: File, key: PrivateKey, chain: List<X509Certificate>): Outcome<Signature> {
        if (chain.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "у ключа нет сертификата")
        if (input.canonicalPath == output.canonicalPath) return Outcome.failure(DiagCode.VALUE_INVALID, "подписанный файл — рядом, под другим именем")
        val dir = output.parentFile ?: return Outcome.failure(DiagCode.VALUE_INVALID, "нет папки для подписанного файла")
        val tmp = File(dir, ".dpai-tmp-" + output.name)
        try {
            val config = ApkSigner.SignerConfig.Builder("DPMETA", com.android.apksig.KeyConfig.Jca(key), chain).build()
            val builder = ApkSigner.Builder(listOf(config))
                .setInputApk(input)
                .setOutputApk(tmp)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false)
                .setDebuggableApkPermitted(true)
                .setCreatedBy("DPmetaApp")
            builder.build().sign()
        } catch (e: Exception) {
            tmp.delete()
            val auth = generateSequence<Throwable>(e) { it.cause }.any { it.javaClass.simpleName == "UserNotAuthenticatedException" }
            return Outcome.failure(
                if (auth) {
                    Diagnostic.error(DiagCode.DEVICE_AUTH_REQUIRED, "ключ подписи требует входа: подтвердите PIN или отпечаток и повторите")
                } else {
                    Diagnostic.fromThrowable(DiagCode.VALUE_INVALID, "APK не подписан", e, input.name)
                },
            )
        }
        val checked = inspect(tmp)
        val sig = (checked as? Outcome.Success)?.value
        val expected = sha256(chain[0].encoded)
        if (sig == null || !sig.verified || sig.certSha256 != expected) {
            tmp.delete()
            val why = (checked as? Outcome.Failure)?.diagnostics?.first()?.message ?: sig?.problems?.firstOrNull() ?: "сертификат подписавшего не тот"
            return Outcome.failure(DiagCode.HASH_MISMATCH, "подписанный файл не прошёл проверку подписи — не сохранён: $why", output.name)
        }
        if (!tmp.renameTo(output)) {
            tmp.delete()
            return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "подписанный файл не переименован", output.name)
        }
        return Outcome.success(sig)
    }

    /** Имя подписанной копии: `app-debug.apk` → `app-debug-signed.apk`. */
    fun signedName(name: String): String = name.removeSuffix(".apk") + "-signed.apk"

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** SHA-256 файла потоком: APK в десятки мегабайт не держится в памяти целиком. */
    fun fileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Файл ключа автора (PKCS#12: `.p12`, `.pfx` и `.jks`, созданные keytool с JDK 9+)
 * в ключ и цепочку (D-157). Файл и пароль в памяти — только на время разбора.
 * Хранилище JKS старого формата Android не читает — отказ с советом, как
 * перевести его в PKCS#12.
 */
internal object KeyFile {
    data class Parsed(val alias: String, val key: PrivateKey, val chain: List<X509Certificate>)

    /** Ключи в файле: имена записей с закрытым ключом. */
    fun aliases(bytes: ByteArray, password: CharArray): Outcome<List<String>> = load(bytes, password).let { r ->
        when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.aliases().toList().filter { r.value.isKeyEntry(it) }.sorted())
        }
    }

    /** Ключ [alias] (`null` — единственный в файле); пароль ключа — тот же, что у файла, иначе [keyPassword]. */
    fun read(bytes: ByteArray, password: CharArray, alias: String? = null, keyPassword: CharArray? = null): Outcome<Parsed> {
        val store = when (val r = load(bytes, password)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val keys = store.aliases().toList().filter { store.isKeyEntry(it) }
        val name = alias ?: keys.singleOrNull() ?: return Outcome.failure(
            if (keys.isEmpty()) Diagnostic.error(DiagCode.VALUE_INVALID, "в файле нет закрытого ключа")
            else Diagnostic.error(DiagCode.FIELD_MISSING, "в файле несколько ключей: выберите один — ${keys.sorted().joinToString()}"),
        )
        if (name !in keys) return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "ключа «$name» в файле нет", name)
        val key = try {
            store.getKey(name, keyPassword ?: password) as? PrivateKey
        } catch (e: Exception) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.VALUE_INVALID, "пароль ключа не подошёл", e, name))
        } ?: return Outcome.failure(DiagCode.VALUE_INVALID, "запись «$name» — не закрытый ключ", name)
        val chain = store.getCertificateChain(name)?.mapNotNull { it as? X509Certificate }.orEmpty()
        if (chain.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "у ключа «$name» нет сертификата X.509", name)
        return Outcome.success(Parsed(name, key, chain))
    }

    /** Не короче — пароль файла созданного ключа (D-178). */
    const val MIN_PASSWORD = 12
    const val ALIAS = "dpmetaapp"

    /** PKCS#12 и keytool надёжно понимают только пароль из печатных знаков ASCII (JDK: «Password is not ASCII»). */
    const val PASSWORD_RULE = "пароль файла ключа — латинские буквы, цифры, пробел и знаки ASCII: кириллицу PKCS#12 и keytool не принимают"

    fun passwordUsable(password: CharArray): Boolean = password.all { it in ' '..'~' }
    const val YEARS = 30

    /**
     * Новый ключ подписи (D-178): RSA 4096 и самоподписанный сертификат X.509 v3 на [years]
     * лет от [now], CN — [name]; итог — байты PKCS#12 под паролем [password] (ключ [ALIAS]).
     * Ключ живёт только в этих байтах: положить его в Keystore — после сохранения файла.
     */
    fun create(name: String, password: CharArray, now: java.time.Instant, random: com.dpsmpx.dpai.core.clock.RandomSource, years: Int = YEARS, bits: Int = 4096): Outcome<ByteArray> {
        val cn = name.trim()
        if (cn.isEmpty() || cn.length > 64 || cn.any { it.isISOControl() }) return Outcome.failure(DiagCode.VALUE_INVALID, "имя в сертификате — от 1 до 64 знаков, без управляющих")
        if (password.size < MIN_PASSWORD) return Outcome.failure(DiagCode.VALUE_INVALID, "пароль файла ключа — не короче $MIN_PASSWORD знаков")
        if (!passwordUsable(password)) return Outcome.failure(DiagCode.VALUE_INVALID, PASSWORD_RULE)
        return try {
            // Ключевую пару создаёт генератор JCA своим криптостойким источником; серийный номер — из RandomSource (D-07).
            val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
            val cert = SelfSigned.certificate(pair, cn, now, now.atZone(java.time.ZoneOffset.UTC).plusYears(years.toLong()).toInstant(), random)
            val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            store.setKeyEntry(ALIAS, pair.private, password, arrayOf(cert))
            val out = java.io.ByteArrayOutputStream()
            store.store(out, password)
            Outcome.success(out.toByteArray())
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "ключ не создан", e))
        }
    }

    private fun load(bytes: ByteArray, password: CharArray): Outcome<KeyStore> {
        val store = KeyStore.getInstance("PKCS12")
        return try {
            store.load(bytes.inputStream(), password)
            Outcome.success(store)
        } catch (e: java.io.IOException) {
            // Неверный пароль PKCS#12 — IOException с причиной UnrecoverableKeyException; не PKCS#12 — тоже IOException.
            val wrongPassword = generateSequence<Throwable>(e) { it.cause }.any { it is java.security.UnrecoverableKeyException || it is javax.crypto.BadPaddingException }
            Outcome.failure(
                if (wrongPassword) Diagnostic.error(DiagCode.VALUE_INVALID, "пароль хранилища ключей не подошёл")
                else Diagnostic.error(DiagCode.VALUE_INVALID, "файл не читается как PKCS#12. JKS старого формата переведите: keytool -importkeystore -srckeystore old.jks -destkeystore key.p12 -deststoretype pkcs12"),
            )
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.VALUE_INVALID, "файл ключа не прочитан", e))
        }
    }
}

/**
 * Самоподписанный сертификат X.509 v3 для ключа APK (D-178) — DER вручную: на
 * Android нет открытого построителя сертификатов, а тянуть библиотеку ради одного
 * сертификата незачем. Подпись SHA256withRSA, имя — CN в UTF8String, без расширений.
 */
internal object SelfSigned {
    fun certificate(pair: java.security.KeyPair, cn: String, from: java.time.Instant, to: java.time.Instant, random: com.dpsmpx.dpai.core.clock.RandomSource): X509Certificate {
        val sha256Rsa = seq(byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b), byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), tlv(0x0c, cn.toByteArray(Charsets.UTF_8)))))
        val validity = seq(time(from), time(to))
        // Серийный номер — положительный, 16 случайных байт (RFC 5280: не длиннее 20).
        val serial = random.nextBytes(16).also { it[0] = (it[0].toInt() and 0x7f or 0x01).toByte() }
        val tbs = seq(tlv(0xa0, tlv(0x02, byteArrayOf(2))), tlv(0x02, serial), sha256Rsa, name, validity, name, pair.public.encoded)
        val signer = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }
        val der = seq(tbs, sha256Rsa, tlv(0x03, byteArrayOf(0) + signer.sign()))
        return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    /** UTCTime до 2049 года, GeneralizedTime — с 2050 (RFC 5280, 4.1.2.5). */
    private fun time(t: java.time.Instant): ByteArray {
        val z = t.atZone(java.time.ZoneOffset.UTC)
        return if (z.year in 1950..2049) tlv(0x17, java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(z).toByteArray())
        else tlv(0x18, java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").format(z).toByteArray())
    }

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(tag)
        when {
            body.size < 0x80 -> out.write(body.size)
            body.size < 0x100 -> { out.write(0x81); out.write(body.size) }
            body.size < 0x10000 -> { out.write(0x82); out.write(body.size shr 8); out.write(body.size and 0xff) }
            else -> error("DER длиннее 64 КиБ")
        }
        out.write(body)
        return out.toByteArray()
    }
}

package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Подпись настоящим apksig ключом, созданным в тесте (D-157). Сертификат собирается
 * тут же из DER: файлов ключей в репозитории нет и не будет (`DPmetaApp:CLAUDE.md`, «Секреты и подпись»).
 */
class ApkSignTest {
    private val dir: File = Files.createTempDirectory("apksign").toFile()

    /** Самоподписанный сертификат X.509 v3 с RSA-ключом — минимальный DER. */
    private fun selfSigned(pair: KeyPair, cn: String): X509Certificate {
        fun tlv(tag: Int, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            when {
                body.size < 128 -> out.write(body.size)
                body.size < 256 -> { out.write(0x81); out.write(body.size) }
                else -> { out.write(0x82); out.write(body.size shr 8); out.write(body.size and 0xff) }
            }
            out.write(body)
            return out.toByteArray()
        }
        fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        val sha256Rsa = seq(byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b), byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), tlv(0x0c, cn.toByteArray()))))
        val validity = seq(tlv(0x17, "260101000000Z".toByteArray()), tlv(0x17, "491231235959Z".toByteArray()))
        val tbs = seq(tlv(0xa0, tlv(0x02, byteArrayOf(2))), tlv(0x02, byteArrayOf(1)), sha256Rsa, name, validity, name, pair.public.encoded)
        val signer = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }
        val cert = seq(tbs, sha256Rsa, tlv(0x03, byteArrayOf(0) + signer.sign()))
        return CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
    }

    private fun keys(cn: String): Pair<KeyPair, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return pair to selfSigned(pair, cn)
    }

    /**
     * Двоичный AndroidManifest.xml (AXML) — минимум, который читает apksig:
     * `<manifest package="com.example.test"><uses-sdk android:minSdkVersion="24"/></manifest>`.
     */
    private fun axml(): ByteArray {
        val strings = listOf("minSdkVersion", "android", "http://schemas.android.com/apk/res/android", "manifest", "package", "com.example.test", "uses-sdk")
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        fun le32(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
        fun chunk(type: Int, headerSize: Int, body: ByteArray): ByteArray = le16(type) + le16(headerSize) + le32(8 + body.size) + body
        // Пул строк UTF-16.
        val data = ByteArrayOutputStream()
        val offsets = ByteArrayOutputStream()
        for (str in strings) {
            offsets.write(le32(data.size()))
            data.write(le16(str.length))
            for (c in str) data.write(le16(c.code))
            data.write(le16(0))
        }
        while (data.size() % 4 != 0) data.write(0)
        val poolHeader = le32(strings.size) + le32(0) + le32(0) + le32(28 + strings.size * 4) + le32(0)
        val pool = chunk(0x0001, 28, poolHeader + offsets.toByteArray() + data.toByteArray())
        val resMap = chunk(0x0180, 8, le32(0x0101020c))
        val none = -1
        fun node(type: Int, rest: ByteArray) = chunk(type, 16, le32(1) + le32(none) + rest)
        fun attr(ns: Int, name: Int, raw: Int, type: Int, data: Int) = le32(ns) + le32(name) + le32(raw) + le16(8) + byteArrayOf(0, type.toByte()) + le32(data)
        fun start(name: Int, vararg attrs: ByteArray) = node(0x0102, le32(none) + le32(name) + le16(20) + le16(20) + le16(attrs.size) + le16(0) + le16(0) + le16(0) + attrs.fold(ByteArray(0)) { a, b -> a + b })
        val body = node(0x0100, le32(1) + le32(2)) +
            start(3, attr(none, 4, 5, 0x03, 5)) +
            start(6, attr(2, 0, none, 0x10, 24)) +
            node(0x0103, le32(none) + le32(6)) +
            node(0x0103, le32(none) + le32(3)) +
            node(0x0101, le32(1) + le32(2))
        return chunk(0x0003, 8, pool + resMap + body)
    }

    /** Простейший APK: двоичный манифест и один файл. */
    private fun unsigned(name: String): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml"))
            z.write(axml())
            z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex"))
            z.write("dex\n035\u0000 test".toByteArray())
            z.closeEntry()
        }
        return f
    }

    @Test
    fun signsVerifiesAndReplacesPreviousSignature() {
        val (pair, cert) = keys("DPmeta test")
        val input = unsigned("app-debug.apk")
        val output = File(dir, ApkSign.signedName(input.name))
        assertEquals("app-debug-signed.apk", output.name)
        val signed = ApkSign.sign(input, output, pair.private, listOf(cert))
        if (signed is Outcome.Failure) error(signed.diagnostics.joinToString { "${it.code}: ${it.message} / ${it.error}" })
        val sig = (signed as Outcome.Success).value
        assertTrue(sig.verified)
        assertTrue(sig.schemes.containsAll(listOf("v2", "v3")), sig.schemes.toString())
        assertEquals(ApkSign.sha256(cert.encoded), sig.certSha256)
        assertTrue(sig.subject!!.contains("DPmeta test"))
        // Переподпись другим ключом снимает прежнюю подпись: подписавший — только новый.
        val (pair2, cert2) = keys("Other")
        val again = File(dir, "again.apk")
        val sig2 = (ApkSign.sign(output, again, pair2.private, listOf(cert2)) as Outcome.Success).value
        assertEquals(ApkSign.sha256(cert2.encoded), sig2.certSha256)
        assertFalse(dir.listFiles()!!.any { it.name.startsWith(".dpai-tmp-") }, "временный файл убран")
    }

    @Test
    fun unsignedOrBrokenInputIsReportedNotSigned() {
        val (pair, cert) = keys("DPmeta test")
        val notZip = File(dir, "x.apk").apply { writeText("not a zip") }
        val r = ApkSign.sign(notZip, File(dir, "x-signed.apk"), pair.private, listOf(cert))
        assertEquals(DiagCode.VALUE_INVALID, (r as Outcome.Failure).diagnostics.first().code)
        assertFalse(File(dir, "x-signed.apk").exists())
        assertFalse((ApkSign.inspect(unsigned("plain.apk")) as Outcome.Success).value.verified)
        assertIs<Outcome.Failure>(ApkSign.sign(notZip, notZip, pair.private, listOf(cert)))
        assertIs<Outcome.Failure>(ApkSign.sign(unsigned("y.apk"), File(dir, "y2.apk"), pair.private, emptyList()))
    }

    @Test
    fun keyFileIsReadByAliasAndWrongPasswordIsSaidInWords() {
        val (pair, cert) = keys("DPmeta test")
        val (pair2, cert2) = keys("Second")
        // Хранилище PKCS#12 собирается в тесте из частей: пароль — не секрет, а строка теста.
        val password = listOf("test", "only", "pass").joinToString("-").toCharArray()
        fun store(vararg entries: Triple<String, KeyPair, X509Certificate>): ByteArray {
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            for ((alias, p, c) in entries) ks.setKeyEntry(alias, p.private, password, arrayOf(c))
            return ByteArrayOutputStream().also { ks.store(it, password) }.toByteArray()
        }
        val one = store(Triple("upload", pair, cert))
        val parsed = (KeyFile.read(one, password) as Outcome.Success).value
        assertEquals("upload", parsed.alias)
        assertEquals(cert, parsed.chain.single())
        val two = store(Triple("a", pair, cert), Triple("b", pair2, cert2))
        assertEquals(listOf("a", "b"), (KeyFile.aliases(two, password) as Outcome.Success).value)
        assertEquals(DiagCode.FIELD_MISSING, (KeyFile.read(two, password) as Outcome.Failure).diagnostics.first().code)
        assertEquals(cert2, (KeyFile.read(two, password, alias = "b") as Outcome.Success).value.chain.single())
        val wrong = KeyFile.read(one, "nope".toCharArray()) as Outcome.Failure
        assertTrue(wrong.diagnostics.first().message.contains("пароль"), wrong.diagnostics.first().message)
        assertTrue((KeyFile.read("garbage".toByteArray(), password) as Outcome.Failure).diagnostics.first().message.contains("PKCS#12"))
        // Ключ, прочитанный из файла, подписывает APK.
        val signed = ApkSign.sign(unsigned("z.apk"), File(dir, "z-signed.apk"), parsed.key, parsed.chain)
        assertTrue((signed as Outcome.Success).value.verified)
    }
}

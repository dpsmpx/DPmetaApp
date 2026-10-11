package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Форматы ключей SSH (RFC 4253, §6.6; RFC 5656, §3) для ключа ECDSA P-256
 * приложения (D-35): в Android 11 JSch умеет только ECDSA
 * (`docs/research/SSH_ON_ANDROID.md`).
 */
object SshKeys {
    const val ECDSA_P256 = "ecdsa-sha2-nistp256"
    private const val CURVE = "nistp256"

    /** Открытый ключ в формате SSH: имя, кривая, точка без сжатия. */
    fun ecdsaBlob(key: ECPublicKey): ByteArray {
        val point = ByteArrayOutputStream().apply {
            write(4)
            write(fixed(key.w.affineX, 32))
            write(fixed(key.w.affineY, 32))
        }.toByteArray()
        return ssh { string(ECDSA_P256.toByteArray()); string(CURVE.toByteArray()); string(point) }
    }

    /** Подпись SSH из подписи JCA `SHA256withECDSA` (DER: SEQUENCE { r, s }). */
    fun ecdsaSignature(der: ByteArray): ByteArray {
        val (r, s) = parseDer(der)
        val inner = ssh { mpint(r); mpint(s) }
        return ssh { string(ECDSA_P256.toByteArray()); string(inner) }
    }

    /** Строка для `authorized_keys` на target. */
    fun authorizedKey(key: ECPublicKey, comment: String): String =
        "$ECDSA_P256 ${Base64.getEncoder().encodeToString(ecdsaBlob(key))} $comment"

    /** Отпечаток как у `ssh-keygen -l`: `SHA256:` и base64 без выравнивания. */
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** Имя хоста в known_hosts: `[host]:port`, если порт не 22, — как пишет OpenSSH. */
    fun knownHostsName(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"

    internal fun parseDer(der: ByteArray): Pair<BigInteger, BigInteger> {
        var i = 0
        fun len(): Int {
            val b = der[i++].toInt() and 0xff
            if (b < 0x80) return b
            var n = 0
            repeat(b and 0x7f) { n = (n shl 8) or (der[i++].toInt() and 0xff) }
            return n
        }
        fun int(): BigInteger {
            require(der[i++].toInt() == 0x02) { "в подписи DER ожидалось INTEGER" }
            val n = len()
            return BigInteger(der.copyOfRange(i, i + n)).also { i += n }
        }
        require(der[i++].toInt() == 0x30) { "подпись DER — не SEQUENCE" }
        len()
        return int() to int()
    }

    private fun fixed(v: BigInteger, size: Int): ByteArray {
        val b = v.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
        return ByteArray(size - b.size) + b
    }

    private class Writer {
        val out = ByteArrayOutputStream()

        fun string(b: ByteArray) {
            val n = b.size
            out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            out.write(b)
        }

        /** mpint SSH — то же, что `BigInteger.toByteArray()`: дополнительный код, старший байт первым. */
        fun mpint(v: BigInteger) = string(v.toByteArray())
    }

    private fun ssh(body: Writer.() -> Unit): ByteArray = Writer().apply(body).out.toByteArray()
}

/**
 * Ключ входа ECDSA P-256, подпись которого делает [signDer] — в
 * приложении это Android Keystore: закрытый ключ не покидает устройство
 * (D-35, T6). [signDer] получает данные и возвращает подпись
 * `SHA256withECDSA` в DER.
 */
class EcdsaSignerIdentity(
    private val name: String,
    private val publicKey: ECPublicKey,
    private val signDer: (ByteArray) -> ByteArray,
) : Identity {
    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun getPublicKeyBlob(): ByteArray = SshKeys.ecdsaBlob(publicKey)

    override fun getSignature(data: ByteArray): ByteArray = SshKeys.ecdsaSignature(signDer(data))

    override fun getSignature(data: ByteArray, alg: String?): ByteArray = getSignature(data)

    override fun getAlgName(): String = SshKeys.ECDSA_P256

    override fun getName(): String = name

    override fun isEncrypted(): Boolean = false

    override fun clear() {}
}

/** Ключ хоста, полученный для подтверждения человеком: строка known_hosts и отпечаток. */
data class ProbedHostKey(val knownHostsLine: String, val type: String, val fingerprint: String)

/**
 * Первое знакомство с target (T2): получить ключ хоста, ничего не
 * запоминая и не входя. Пользователь сверяет [ProbedHostKey.fingerprint]
 * с тем, что показывает сам target (`ssh-keygen -l -f …`), и только тогда
 * строка попадает в known_hosts. Молчаливого «доверять при первом
 * подключении» нет.
 */
object HostKeyProbe {
    fun fetch(host: String, port: Int, timeout: Duration = 10.seconds): Outcome<ProbedHostKey> {
        var seen: Pair<String, ByteArray>? = null
        val capture = object : HostKeyRepository {
            override fun check(h: String, key: ByteArray): Int {
                seen = h to key
                return HostKeyRepository.NOT_INCLUDED
            }

            override fun add(hostkey: HostKey?, ui: UserInfo?) {}

            override fun remove(host: String?, type: String?) {}

            override fun remove(host: String?, type: String?, key: ByteArray?) {}

            override fun getKnownHostsRepositoryID(): String = "dpai-probe"

            override fun getHostKey(): Array<HostKey> = emptyArray()

            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        val jsch = JSch().apply { hostKeyRepository = capture }
        // Отказ JSch на любом шаге (ревью A-78) — Outcome, а не исключение из экрана target (D-05).
        try {
            val session = jsch.getSession("dpai-probe", host, port)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.connect(timeout.inWholeMilliseconds.toInt())
            session.disconnect()
        } catch (e: JSchException) {
            if (seen == null) return Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, e.message?.take(300) ?: "отказ SSH", "ssh $host:$port")
        }
        val (_, key) = seen ?: return Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "сервер не показал ключ хоста", "ssh $host:$port")
        val hk = try {
            HostKey(SshKeys.knownHostsName(host, port), key)
        } catch (e: JSchException) {
            return Outcome.failure(DiagCode.SSH_HOST_KEY_REJECTED, "ключ хоста не разобран: ${e.message?.take(200)}", "ssh $host:$port")
        }
        return Outcome.success(ProbedHostKey("${hk.host} ${hk.type} ${hk.key}", hk.type, SshKeys.fingerprint(key)))
    }
}

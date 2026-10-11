package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Ключ ECDSA с внешней подписью и знакомство с хостом (D-35, T2). */
class SshKeysTest {
    @TempDir
    lateinit var dir: Path

    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val identity = EcdsaSignerIdentity("тест", pair.public as ECPublicKey) { data ->
        Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign()
    }

    private fun strings(blob: ByteArray): List<ByteArray> {
        val b = ByteBuffer.wrap(blob)
        return buildList { while (b.hasRemaining()) add(ByteArray(b.int).also(b::get)) }
    }

    @Test
    fun signatureIsSshBlobThatVerifies() {
        val data = "данные для подписи".toByteArray()
        val (alg, inner) = strings(identity.getSignature(data))
        assertEquals(SshKeys.ECDSA_P256, String(alg))
        val (r, s) = strings(inner).map(::BigInteger)
        // Обратно в DER и проверка открытым ключом — подпись та же.
        fun derInt(v: BigInteger) = v.toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
        val body = derInt(r) + derInt(s)
        val der = ByteArrayOutputStream().apply { write(0x30); write(body.size); write(body) }.toByteArray()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(data) }.verify(der))
    }

    @Test
    fun publicBlobHasCurveAndUncompressedPoint() {
        val (alg, curve, point) = strings(identity.publicKeyBlob)
        assertEquals(SshKeys.ECDSA_P256, String(alg))
        assertEquals("nistp256", String(curve))
        assertEquals(65, point.size)
        assertEquals(4, point[0].toInt())
        assertTrue(SshKeys.authorizedKey(pair.public as ECPublicKey, "c").startsWith("ecdsa-sha2-nistp256 AAAA"))
        assertEquals("[h]:8022", SshKeys.knownHostsName("h", 8022))
        assertEquals("h", SshKeys.knownHostsName("h", 22))
    }

    /**
     * На настоящем POSIX target: ключ хоста из пробы → known_hosts → вход
     * файлом; затем временный ключ ECDSA с внешней подписью записывается в
     * authorized_keys, входит и удаляется.
     */
    @Test
    fun probedHostKeyAndSignerIdentityWorkOnRealTarget() {
        val spec = System.getProperty("dpai.test.ssh")
        assumeTrue(spec != null && spec.split(":")[2] == "posix", "dpai.test.ssh (posix) не задан: проверка — на настоящем target")
        val (userHost, port) = spec!!.split(":", limit = 4)
        val host = userHost.substringAfter('@')
        val user = userHost.substringBefore('@')
        val probed = HostKeyProbe.fetch(host, port.toInt()).valueOrNull()!!
        assertTrue(probed.fingerprint.startsWith("SHA256:"))
        val known = dir.resolve("known_hosts").also { it.writeText(probed.knownHostsLine + "\n") }.toString()
        val byFile = JschProcessHost(SshEndpoint.of(host, user, port.toInt(), System.getProperty("dpai.test.ssh.identity"), known).valueOrNull()!!, TargetPlatform.POSIX)
        val tag = "dpai-signer-test-${System.identityHashCode(this)}"
        val line = SshKeys.authorizedKey(pair.public as ECPublicKey, tag)
        val added = byFile.run(ProcessSpec(listOf("sh", "-c", "echo '$line' >> ~/.ssh/authorized_keys")), 30.seconds)
        assertTrue(added is Outcome.Success && added.value.ok, added.toString())
        try {
            val bySigner = JschProcessHost(SshEndpoint.of(host, user, port.toInt(), null, known).valueOrNull()!!, TargetPlatform.POSIX, identities = listOf(identity))
            val r = bySigner.run(ProcessSpec(listOf("echo", "ok")), 30.seconds)
            assertEquals(listOf("ok"), r.valueOrNull()?.stdout, r.toString())
        } finally {
            byFile.run(ProcessSpec(listOf("sh", "-c", "sed -i '/$tag/d' ~/.ssh/authorized_keys")), 30.seconds)
        }
    }
}

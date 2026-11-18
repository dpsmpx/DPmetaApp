package com.dpsmpx.dpai.android

import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SigningKeyProofTest {
    @Test fun rsaAndEcRejectAnotherCertificatesKey() {
        for ((algorithm, bits) in listOf("RSA" to 2048, "EC" to 256)) {
            val generator = KeyPairGenerator.getInstance(algorithm).apply { initialize(bits) }
            val pair = generator.generateKeyPair()
            val other = generator.generateKeyPair()
            assertTrue(SigningKeyProof.matches(pair.private, pair.public))
            assertFalse(SigningKeyProof.matches(pair.private, other.public))
        }
    }
}

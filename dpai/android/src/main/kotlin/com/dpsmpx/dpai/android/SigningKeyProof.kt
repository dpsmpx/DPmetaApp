package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.RandomSource
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/** Проверка фактического ключа Keystore, а не только сохранённого рядом сертификата (D-203). */
internal object SigningKeyProof {
    /** Ошибку провайдера или входа Android-оболочка превращает в соответствующий Outcome. */
    fun matches(privateKey: PrivateKey, publicKey: PublicKey): Boolean {
        if (privateKey.algorithm != publicKey.algorithm) return false
        val algorithm = when (privateKey.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> return false
        }
        val challenge = RandomSource.secure().nextBytes(32)
        var signed: ByteArray? = null
        try {
            val signer = Signature.getInstance(algorithm)
            signer.initSign(privateKey)
            signer.update(challenge)
            signed = signer.sign()
            val verifier = Signature.getInstance(algorithm)
            verifier.initVerify(publicKey)
            verifier.update(challenge)
            return verifier.verify(signed)
        } finally {
            challenge.fill(0)
            signed?.fill(0)
        }
    }
}

package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Импорт SSH: настоящая пара принимается; несовместимые половины и чужая версия — до записи. */
class PortableSshKeyTest {
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun roundTripAndMismatchedKeyAreCheckedBeforeImport() {
        val original = pair()
        val text = KeystoreKey.encode(original)
        val decoded = assertNotNull(KeystoreKey.decode(text))
        assertContentEquals(original.public.encoded, decoded.public.encoded)
        assertTrue(original.private.encoded.contentEquals(decoded.private.encoded), "закрытый ключ сохранился без вывода его байтов")
        val document = JsonParser().parse(text).valueOrNull() as JsonObject
        val another = JsonParser().parse(KeystoreKey.encode(pair())).valueOrNull() as JsonObject
        val mixed = JsonObject(document.fields + ("public" to another.fields.getValue("public")))
        assertNull(KeystoreKey.decode(CanonicalJson.write(mixed)), "закрытый и открытый ключи разных пар")
        val future = JsonObject(document.fields + ("version" to JsonNumber.of(2)))
        assertNull(KeystoreKey.decode(CanonicalJson.write(future)), "чужой формат не угадывается")
    }
}

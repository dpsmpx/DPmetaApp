package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DiagnosticInputTest {
    @Test fun selectedBackupIsBoundedBeforeParsingIncludingMissingDeclaredSize() {
        val bytes = "Привет".toByteArray()
        assertEquals("Привет", SettingsBackups.readSelected(bytes.inputStream(), bytes.size).valueOrNull())
        assertIs<Outcome.Failure>(SettingsBackups.readSelected(bytes.inputStream(), bytes.size - 1))
        var read = 0
        val stream = object : java.io.InputStream() { override fun read(): Int { read++; return 'a'.code } }
        assertIs<Outcome.Failure>(SettingsBackups.readSelected(stream, 20))
        assertEquals(21, read)
    }
    @Test fun crashMessagesAndThreadNamesAreRedactedIncludingCauses() {
        val secret = "synthetic" + "SecretValue123"
        val error = IllegalStateException("password=$secret", IllegalArgumentException("api_key=$secret"))
        val text = CrashRecord.describe("token=$secret", error)
        assertFalse(secret in text)
        assertEquals(true, "IllegalStateException" in text && "IllegalArgumentException" in text)
    }
}

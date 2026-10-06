package com.dpsmpx.dpai.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Образцы секретов собираются из частей: готовых ключей и токенов в
 * репозитории нет (`tools/verify/check_secrets.py`).
 */
class RedactorTest {
    private val r = Redactor.STANDARD

    private fun assertRedacted(text: String, secret: String) {
        val out = r.redact(text)
        assertFalse(out.contains(secret), "секрет остался: $out")
        assertTrue(out.contains(Redactor.MARK) || out.contains(Redactor.PRIVATE_KEY_MARK), "нет пометки: $out")
    }

    @Test
    fun knownTokenFormats() {
        val anthropic = "sk-" + "ant-" + "api03-" + "A1b2C3d4".repeat(6)
        val github = "gh" + "p_" + "a1B2c3D4e5".repeat(4)
        val aws = "AK" + "IA" + "ABCDEFGHIJKLMNOP"
        assertRedacted("ключ $anthropic в выводе", anthropic)
        assertRedacted("push с токеном $github", github)
        assertRedacted("aws $aws", aws)
    }

    @Test
    fun privateKeyBlock() {
        val body = "MIIEow" + "IBAAKCAQEA".repeat(5)
        val pem = "-----BEGIN " + "RSA PRIVATE KEY-----\n" + body + "\n-----END RSA PRIVATE KEY-----"
        assertRedacted("файл:\n$pem\nконец", body)
    }

    @Test
    fun secretPairsHeadersAndUrls() {
        assertEquals("password=${Redactor.MARK} user=alice", r.redact("password=hunter2hunter2 user=alice"))
        assertRedacted("Authorization: Bearer abcDEF123456", "abcDEF123456")
        assertRedacted("api_key: \"qwerty123456\"", "qwerty123456")
        assertEquals("https://${Redactor.MARK}@example.org/repo.git", r.redact("https://user:pa55word@example.org/repo.git"))
        assertRedacted("https://example.org/cb?token=t0k3nvalue&x=1", "t0k3nvalue")
    }

    @Test
    fun longMixedTokensButNotIdsHashesUuidsOrPaths() {
        val token = "Zx9" + "Qw8Er7Ty6Ui5Op4As3Df2Gh1Jk0".repeat(2)
        assertRedacted("получен $token от сервера", token)
        val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val hash = "sha256:" + "0123456789abcdef".repeat(4)
        val id = "tsk_01J9Z3M4N5P6Q7R8S9T0V1W2X3"
        val path = "core/src/main/kotlin/com/dpsmpx/dpai/core/v2/Thing2.kt"
        val text = "сессия $uuid, хеш $hash, задача $id, файл $path"
        assertEquals(text, r.redact(text))
    }

    @Test
    fun plainProseIsUntouched() {
        val text = "Задача #184: тесты 559/559, ветка feature/gameplay-42, время 10:15"
        assertEquals(text, r.redact(text))
    }
}

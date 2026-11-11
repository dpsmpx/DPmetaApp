package com.dpsmpx.dpai.core.diagnostics

import com.dpsmpx.dpai.core.clock.Clock
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

    /** STRICT (A-6): ключ без цифр и путь с длинным сегментом прячутся; id, UUID, хеши и обычный текст — нет. */
    @Test
    fun strictCatchesWhatStandardMisses() {
        val strict = Redactor.STRICT
        val lettersOnly = "AbCdEfGhIjKlMnOpQrStUvWxYz"
        assertEquals("ключ $lettersOnly", r.redact("ключ $lettersOnly"), "обычный уровень пропускает ключ без цифр")
        assertFalse(lettersOnly in strict.redact("ключ $lettersOnly"))
        val base64Path = "aGVsbG8gd29ybGQgdGhpcyBpcyBsb25n/x"
        assertFalse("aGVsbG8gd29ybGQgdGhpcyBpcyBsb25n" in strict.redact("секрет $base64Path"))
        val text = "сессия 0f8fad5b-d9cb-469f-a165-70867728950e, задача tsk_01J9Z3M4N5P6Q7R8S9T0V1W2X3, " +
            "хеш sha256:" + "0123456789abcdef".repeat(4) + ", файл core/src/main/kotlin/Thing.kt, нужно вмешательство"
        assertEquals(text, strict.redact(text))
    }

    /** Ревью 8 октября 2026: строка stream-json после правки остаётся JSON, секреты скрыты. */
    @Test
    fun jsonLineStaysParsable() {
        val key = "sk-" + "ant-" + "api03-" + "Zx9".repeat(12)
        val line = """{"type":"assistant","message":{"content":[{"type":"text","text":"password: \"hunter2-x\" и $key"}]}}"""
        assertTrue(com.dpsmpx.dpai.core.serialization.JsonParser().parse(r.redact(line)) is com.dpsmpx.dpai.core.result.Outcome.Failure, "правка как текста ломает JSON — поэтому redactLine")
        val out = r.redactLine(line)
        assertTrue(com.dpsmpx.dpai.core.serialization.JsonParser().parse(out).isSuccess, out)
        assertFalse("hunter2" in out, out)
        assertFalse(key in out, out)
        assertEquals(r.redact("token=abc обычный текст"), r.redactLine("token=abc обычный текст"))
        assertEquals(r.redact("{ не json"), r.redactLine("{ не json"))
    }

    /** «имя=id» в строке журнала — id не скрывается; токен в значении и base64 с паддингом — скрываются. */
    @Test
    fun nameEqualsIdIsNotAToken() {
        val ids = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.ManualClock(), com.dpsmpx.dpai.core.clock.RandomSource.seeded(1))
        val req = ids.next(com.dpsmpx.dpai.core.ids.IdKinds.REQUEST).value
        assertEquals("USER_REQUEST_DELIVERED request=$req channel=overlay", Redactor.STANDARD.redact("USER_REQUEST_DELIVERED request=$req channel=overlay"))
        val op = ids.next(com.dpsmpx.dpai.core.ids.IdKinds.OPERATION).value
        assertEquals("key=$op", Redactor.STANDARD.redact("key=$op"), "у OperationId приставка из двух букв")
        val branch = "task/" + ids.next(com.dpsmpx.dpai.core.ids.IdKinds.TASK).value
        assertEquals("Ветка задачи $branch → main", Redactor.STRICT.redact("Ветка задачи $branch → main"), "ветка задачи в уведомлении слияния")
        val secretInPath = "Zx9".repeat(10)
        assertTrue(secretInPath !in Redactor.STRICT.redact("dir/$secretInPath"), "токен в сегменте пути по-прежнему скрыт")
        val token = "Ab3" + "x9".repeat(18)
        assertEquals(Redactor.MARK, Redactor.STANDARD.redact("value=$token"))
        val padded = "QUJD" + "a1".repeat(14) + "=="
        assertEquals(Redactor.MARK, Redactor.STANDARD.redact(padded))
    }

    /** Ревью A-70: ветка задачи в уведомлении о слиянии видна — путь из id оркестратора не токен. */
    @Test
    fun taskBranchIsNotAToken() {
        val text = "Ветка задачи task/tsk_01M4EWP74S6EWVHWSM33QD5YK0 → main."
        assertEquals(text, Redactor.STRICT.redact(text))
    }

    /** Аудит C-01: имя с приставкой (`DB_PASSWORD`, `GITHUB_TOKEN`) — секретное, как без неё. */
    @Test
    fun prefixedSecretNames() {
        val value = "pw" + "-x7".repeat(3)
        for (name in listOf("DB_PASSWORD", "GITHUB_TOKEN", "OPENAI_API_KEY", "my_secret", "aws_secret_access_key", "db.password", "X-Auth")) {
            for (sep in listOf("=", ": ", " = ")) assertRedacted("$name$sep$value", value)
        }
        assertEquals("export DB_PASSWORD=<redacted> && run", Redactor.STANDARD.redact("export DB_PASSWORD=$value && run"))
    }

    /** Похожие, но не секретные имена — как есть (иначе журнал расхода и статусы теряли бы числа). */
    @Test
    fun lookAlikeNamesUntouched() {
        for (text in listOf("max_tokens: 100", "input_tokens=5", "token_count: 3", "author: dpsmpx", "password_hash_algo=argon2", "tokenizer: cl100k")) {
            assertEquals(text, Redactor.STANDARD.redact(text), text)
        }
    }

    /** Аудит C-02: поле JSON с секретным именем — и в тексте, и в строке JSON вывода. */
    @Test
    fun jsonSecretFields() {
        val value = "pw" + "-y5".repeat(3)
        assertRedacted("""{"password": "$value", "user": "a"}""", value)
        assertRedacted("""{"db_password":"$value"}""", value)
        val line = """{"type":"result","env":{"GITHUB_TOKEN":"$value","HOME":"/root"}}"""
        val out = Redactor.STANDARD.redactLine(line)
        assertTrue(value !in out, out)
        assertTrue(out.contains("\"HOME\":\"/root\""), "прочие поля целы: $out")
        assertTrue(com.dpsmpx.dpai.core.serialization.JsonParser().parse(out) is com.dpsmpx.dpai.core.result.Outcome.Success, "строка разбирается")
    }

    /** Длинное имя из многих слов не даёт квадратичного перебора. */
    @Test
    fun longNamesAreLinear() {
        // Без цифры: длинное слово с цифрой STANDARD намеренно считает возможным токеном.
        val text = "a_".repeat(50_000) + "x=yes " + "b-".repeat(50_000) + "y"
        val started = Clock.SYSTEM.monotonicNanos()
        assertEquals(text, Redactor.STANDARD.redact(text))
        val name = "a_".repeat(50_000) + "token"
        assertEquals("$name=${Secret.REDACTED}", Redactor.STANDARD.redact("$name=" + listOf("short", "value").joinToString("-")))
        assertTrue((Clock.SYSTEM.monotonicNanos() - started) / 1_000_000 < 2_000)
    }
}

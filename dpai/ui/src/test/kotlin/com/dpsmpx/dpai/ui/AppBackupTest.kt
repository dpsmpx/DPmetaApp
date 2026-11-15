package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.ui.AppBackup.Pref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppBackupTest {
    private val settings = AppBackup.Section(
        "pult",
        prefs = mapOf("dpmeta.settings" to mapOf(
            "zone_minutes" to Pref.Int32(180), "quiet_days" to Pref.Strings(setOf("sat", "sun")), "alerts_privacy" to Pref.Str("hidden"),
            "big" to Pref.Int64(1L shl 40), "on" to Pref.Bool(true), "scale" to Pref.Real(1.25f),
        )),
        docs = mapOf("pult.settings" to mapOf("alert-rules" to JsonObject.of("text" to JsonString("[[rule]]")))),
        files = mapOf("known_hosts" to "host ssh-ed25519 AAAA\n"),
    )
    private val secrets = AppBackup.Section(AppBackup.SECRETS, secrets = mapOf("api:huggingface" to Secret("hf_" + "x".repeat(20))))

    /** Шифр для теста: обратимый, но не тождественный — видно, что прошли через него. */
    private val seal: (ByteArray) -> Outcome<ByteArray> = { Outcome.success("DPAICC1\n".toByteArray() + it.reversedArray()) }
    private val open: (ByteArray) -> Outcome<ByteArray> = { Outcome.success(it.copyOfRange(8, it.size).reversedArray()) }

    @Test
    fun withoutSecretsIsPlainJsonAndRoundTrips() {
        val a = AppBackup.Archive(1_700_000_000_000, "1.0.0-dev", listOf(settings))
        val bytes = (AppBackup.encode(a, seal = null) as Outcome.Success).value
        assertFalse(AppBackup.sealed(bytes))
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("{"))
        assertEquals(a, (AppBackup.decode(bytes, open = null) as Outcome.Success).value, "все типы настроек — те же")
    }

    /** F-47: секреты — только под паролем; без шифра архив не пишется. */
    @Test
    fun secretsOnlyUnderPassword() {
        val a = AppBackup.Archive(1, null, listOf(settings, secrets))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (AppBackup.encode(a, seal = null) as Outcome.Failure).errors.first().code)
        val sealed = (AppBackup.encode(a, seal) as Outcome.Success).value
        assertTrue(AppBackup.sealed(sealed))
        assertFalse("hf_" in sealed.toString(Charsets.UTF_8), "значение не лежит открытым")
        assertIs<Outcome.Failure>(AppBackup.decode(sealed, open = null), "без пароля не читается")
        val back = (AppBackup.decode(sealed, open) as Outcome.Success).value
        assertEquals(Secret("hf_" + "x".repeat(20)), back.section(AppBackup.SECRETS)!!.secrets["api:huggingface"])
        assertEquals(listOf("pult"), back.select(setOf("pult")).sections.map { it.id }, "восстановление — выбранных разделов")
        assertFalse(back.select(setOf("pult")).hasSecrets)
        // Открытый файл с секретами (дописан в общую папку чужим) — отказ, а не подмена ключей (проверка PR #190).
        val forged = (open(sealed) as Outcome.Success).value
        assertFalse(AppBackup.sealed(forged))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (AppBackup.decode(forged, open = null) as Outcome.Failure).errors.first().code)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, (AppBackup.decode(forged, open) as Outcome.Failure).errors.first().code)
    }

    @Test
    fun foreignOrBrokenFilesAreRefusedWithPlace() {
        assertEquals(DiagCode.FORMAT_UNRECOGNIZED, (AppBackup.read("""{"format":"dpai.app-settings","version":1}""") as Outcome.Failure).errors.first().code)
        assertEquals(DiagCode.FORMAT_VERSION_UNSUPPORTED, (AppBackup.read("""{"format":"dpmeta.app-backup","version":2}""") as Outcome.Failure).errors.first().code)
        val head = """{"format":"dpmeta.app-backup","version":1,"createdAt":1,"sections":"""
        for ((bad, where) in listOf(
            """[{"id":"Pult"}]""" to "id",
            """[{"id":"a"},{"id":"a"}]""" to "повтор",
            """[{"id":"a","prefs":{"f":{"k":{"t":"i","v":99999999999}}}}]""" to "a.prefs.f.k",
            """[{"id":"a","prefs":{"f":{"k":{"t":"x","v":1}}}}]""" to "a.prefs.f.k",
            """[{"id":"a","docs":{"../x":{}}}]""" to "a.docs",
            """[{"id":"a","secrets":{"k":"v"}}]""" to "только в разделе",
        )) {
            val r = AppBackup.read("$head$bad}")
            assertTrue(r is Outcome.Failure && where in r.errors.first().message, "$bad → ${(r as? Outcome.Failure)?.errors?.first()?.message}")
        }
        assertIs<Outcome.Failure>(AppBackup.read("не json"))
    }

    @Test
    fun prefValuesFromAndroidTypes() {
        assertEquals(Pref.Str("x"), Pref.of("x"))
        assertEquals(Pref.Int32(1), Pref.of(1))
        assertEquals(Pref.Int64(1L), Pref.of(1L))
        assertEquals(Pref.Strings(setOf("a")), Pref.of(setOf("a")))
        assertNull(Pref.of(listOf(1)))
        assertNull(Pref.of(setOf(1)))
    }

    @Test
    fun revokePagesAndEraseWord() {
        assertEquals(SecretRevoke.ANTHROPIC_KEYS, SecretRevoke.apiKey("api.anthropic.com"))
        assertEquals(SecretRevoke.OPENAI_KEYS, SecretRevoke.apiKey("API.OPENAI.COM"))
        assertNull(SecretRevoke.apiKey("192.168.1.5"), "своя модель — страницы нет")
        for (u in listOf(SecretRevoke.GITHUB_TOKENS, SecretRevoke.HUGGING_FACE, SecretRevoke.ANTHROPIC_ADMIN, SecretRevoke.GEMINI_KEYS, SecretRevoke.CLAUDE_CODE)) {
            assertTrue(u.startsWith("https://"), u)
        }
        assertTrue(SecretRevoke.eraseConfirmed(" СТЕРЕТЬ "))
        assertFalse(SecretRevoke.eraseConfirmed("стереть"))
        assertFalse(SecretRevoke.eraseConfirmed(""))
    }
}

/** Перенос хранилищ раздела: фильтр ключей, служебное не трогается (`DPmetaApp:D-151`). */
class AppBackupStoresTest {
    private fun store(dir: java.nio.file.Path) = com.dpsmpx.dpai.storage.document.DocumentStore(com.dpsmpx.dpai.storage.vfs.NioVfs(dir), "docs")
    private fun doc(v: String) = JsonObject.of("v" to JsonString(v))

    @Test
    fun documentsRoundTripWithoutServiceKeys(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        val take: (String) -> Boolean = { it != "active" && it != "result" }
        val old = store(tmp.resolve("old"))
        old.write("build-p1", doc("b"))
        old.write("run-p1", doc("r"))
        old.write("active", doc("идёт"))
        val exported = (AppBackupStores.export(old, take) as Outcome.Success).value
        assertEquals(setOf("build-p1", "run-p1"), exported.keys, "идущий профиль — не настройка")
        val fresh = store(tmp.resolve("new"))
        fresh.write("run-p9", doc("чужой"))
        fresh.write("result", doc("итог"))
        assertIs<Outcome.Success<*>>(AppBackupStores.apply(fresh, exported, take))
        assertEquals(setOf("build-p1", "run-p1", "result"), fresh.keys().toSet(), "лишнее раздела удалено, служебное осталось")
        assertEquals(doc("r"), (fresh.read("run-p1") as Outcome.Success).value!!.content)
    }

    @Test
    fun prefsReplaceSectionKeysOnly() {
        val c = AppBackupStores.prefs(setOf("zone", "old", "last"), mapOf("zone" to Pref.Int32(60), "last" to Pref.Str("x")), skip = { it == "last" })
        assertEquals(setOf("old"), c.remove)
        assertEquals(mapOf("zone" to Pref.Int32(60)), c.put, "служебный ключ не переписывается копией")
    }
}

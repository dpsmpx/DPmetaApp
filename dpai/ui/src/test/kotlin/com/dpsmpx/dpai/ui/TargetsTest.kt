package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TargetsTest {
    @TempDir
    lateinit var dir: Path

    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(9))

    private fun entry(host: String = "127.0.0.1", port: String = "8022", windows: Boolean = false) =
        TargetForm.validate(ids.next(IdKinds.TARGET), "телефон", host, port, "u0_a224", windows, "/data/data/com.termux/files/home/dpai/bin/dpai")

    @Test
    fun formRejectsOptionLikeHostAndBadPort() {
        assertEquals(DiagCode.VALUE_INVALID, (entry(host = "-oProxyCommand=x") as Outcome.Failure).diagnostics.first().code)
        assertTrue(entry(port = "0") is Outcome.Failure)
        assertTrue(entry(port = "abc") is Outcome.Failure)
    }

    @Test
    fun typeFollowsHostAndPlatform() {
        assertEquals(TargetType.TERMUX_LOCAL, entry().valueOrNull()!!.type)
        assertEquals(TargetType.LINUX_SSH, entry(host = "build.lan").valueOrNull()!!.type)
        assertEquals(TargetType.WINDOWS_REMOTE, entry(host = "pc.lan", windows = true).valueOrNull()!!.type)
        assertEquals(TargetPlatform.WINDOWS, entry(host = "pc.lan", windows = true).valueOrNull()!!.platform)
    }

    @Test
    fun bookRoundTripsThroughCanonicalJson() {
        val list = listOf(entry().valueOrNull()!!, entry(host = "pc.lan", port = "22", windows = true).valueOrNull()!!)
        val text = CanonicalJson.write(TargetBook.toJson(list))
        assertEquals(list, TargetBook.fromJson(JsonParser().parse(text).valueOrNull()!!).valueOrNull())
    }

    @Test
    fun knownHostsReplacesSameHostAndType() {
        var text = ""
        text = KnownHosts.add(text, "[127.0.0.1]:8022 ecdsa-sha2-nistp256 AAAA1")
        text = KnownHosts.add(text, "pc ecdsa-sha2-nistp256 BBBB")
        text = KnownHosts.add(text, "[127.0.0.1]:8022 ecdsa-sha2-nistp256 AAAA2")
        assertEquals("pc ecdsa-sha2-nistp256 BBBB\n[127.0.0.1]:8022 ecdsa-sha2-nistp256 AAAA2\n", text)
        assertEquals(setOf("pc", "[127.0.0.1]:8022"), KnownHosts.names(text))
    }

    @Test
    fun configDirMustBeAbsoluteOnItsPlatform() {
        val id = ids.next(IdKinds.TARGET)
        assertTrue(TargetForm.validate(id, "x", "h", "22", "u", false, "/a/dpai", "relative/dir") is Outcome.Failure)
        assertTrue(TargetForm.validate(id, "x", "h", "22", "u", true, "C:/dpai.cmd", """C:\Users\u\.claude-1""") is Outcome.Success)
        assertEquals("/root/.claude-1", TargetForm.validate(id, "x", "h", "22", "u", false, "/a/dpai", " /root/.claude-1 ").valueOrNull()!!.configDir)
        // Ревью 8 октября 2026: каталог — как его хранит слот; иначе после перезапуска слот не найти.
        assertEquals("/root/.claude-1", TargetForm.validate(id, "x", "h", "22", "u", false, "/a/dpai", "/root/.claude-1/").valueOrNull()!!.configDir)
    }

    /** Ревью 8 октября 2026: `~` в команде агента на target не раскрывается — форма это объясняет. */
    @Test
    fun agentCommandWithTildeIsRejected() {
        val id = ids.next(IdKinds.TARGET)
        val r = TargetForm.validate(id, "x", "h", "22", "u", false, "~/dpai/bin/dpai")
        assertEquals(DiagCode.VALUE_INVALID, (r as Outcome.Failure).diagnostics.first().code)
    }

    /** Документ v1 (до каталога Claude) читается; у его targets каталога нет. */
    @Test
    fun versionOneBookIsStillRead() {
        val id = ids.next(IdKinds.TARGET).value
        val v1 = """{"format":"dpai.app-targets","version":1,"targets":[{"id":"$id","label":"t","host":"127.0.0.1","port":8022,"user":"u","platform":"posix","agent":"/a/dpai"}]}"""
        val read = TargetBook.fromJson(JsonParser().parse(v1).valueOrNull()!!).valueOrNull()!!
        assertEquals(null, read.single().configDir)
    }

    /** Ревью A-85: known_hosts пишется атомарно — сбой посреди записи оставляет прежний файл целым. */
    @Test
    fun knownHostsSaveIsAtomic() {
        val vfs = NioVfs(dir)
        KnownHosts.save(vfs, "known_hosts", "[a]:22 ssh-ed25519 AAAA1")
        val failing = object : Vfs by vfs {
            override fun rename(from: String, to: String) = throw java.io.IOException("сбой при переименовании")
        }
        assertFailsWith<java.io.IOException> { KnownHosts.save(failing, "known_hosts", "[b]:22 ssh-ed25519 AAAA2") }
        assertEquals("[a]:22 ssh-ed25519 AAAA1\n", vfs.read("known_hosts")!!.toString(Charsets.UTF_8))
        KnownHosts.save(vfs, "known_hosts", "[b]:22 ssh-ed25519 AAAA2")
        assertEquals(setOf("[a]:22", "[b]:22"), KnownHosts.names(vfs.read("known_hosts")!!.toString(Charsets.UTF_8)))
    }

    /** Ревью A-86: команда проверки делится как в оболочке — кавычки дают одно слово; незакрытая — отказ формы. */
    @Test
    fun verifyCommandKeepsQuotedWords() {
        assertEquals(listOf("python", "-c", "import sys; sys.exit(0)"), ProjectForm.words("python -c \"import sys; sys.exit(0)\""))
        assertEquals(listOf("sh", "-c", "make test && echo ok"), ProjectForm.words("  sh  -c 'make test && echo ok' "))
        assertEquals(listOf("C:\\tools\\check.cmd", "a b"), ProjectForm.words("C:\\tools\\check.cmd \"a b\""))
        assertEquals(emptyList(), ProjectForm.words("   "))
        assertEquals(null, ProjectForm.words("make 'test"))
    }
}

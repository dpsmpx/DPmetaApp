package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.result.Outcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Одно хранилище (DPmetaApp:D-111, D-113; D-143): разделы, перенос со ссылками, карта. */
class GuestStorageTest {
    @TempDir
    lateinit var dir: Path

    private fun write(p: Path, text: String = "x") {
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    @Test
    fun legacyPlacesMoveAndLeaveRelativeLinks() {
        val h = "linux/rootfs/root"
        val home = dir.resolve(h)
        val clones = dir.resolve("clones")
        write(clones.resolve("dpsmpx/DPmetaApp/.git/HEAD"), "ref: refs/heads/main\n")
        Files.createDirectories(home.resolve("repos")) // точка прежней привязки — пустая
        write(home.resolve("models/tiny.gguf"), "gguf")
        write(home.resolve(".local/share/dpai/kotlin-lsp/current/product-info.json"), "{}")
        write(home.resolve(".claude-slot-1/settings.json"), "{}")
        write(home.resolve(".codex-slot-2/auth.json"), "secret")
        val moves = listOf(GuestStorage.Move("clones", "$h/${GuestStorage.PROJECTS}")) + GuestStorage.legacyMoves(dir, h)
        val r = assertIs<Outcome.Success<GuestStorage.Migrated>>(GuestStorage.migrate(dir, moves)).value
        assertTrue(r.skipped.isEmpty(), r.skipped.toString())
        assertTrue(Files.isRegularFile(home.resolve("projects/dpsmpx/DPmetaApp/.git/HEAD")))
        assertTrue(Files.isRegularFile(home.resolve("${GuestStorage.MODELS}/tiny.gguf")))
        assertTrue(Files.isRegularFile(home.resolve("${GuestStorage.IDE}/kotlin-lsp/current/product-info.json")))
        assertTrue(Files.isRegularFile(home.resolve("ai/claude-1/settings.json")))
        assertTrue(Files.isRegularFile(home.resolve("ai/codex-2/auth.json")))
        for ((link, target) in listOf("repos" to "projects", "models" to GuestStorage.MODELS, ".claude-slot-1" to "ai/claude-1")) {
            val p = home.resolve(link)
            assertTrue(Files.isSymbolicLink(p), link)
            assertFalse(Files.readSymbolicLink(p).isAbsolute, "$link — относительная ссылка: верна и из гостя")
            assertEquals(home.resolve(target).toRealPath(), p.toRealPath())
        }
        assertEquals("../../tools/ide", Files.readSymbolicLink(home.resolve(".local/share/dpai")).toString())
        assertTrue(Files.isRegularFile(clones.resolve("dpsmpx/DPmetaApp/.git/HEAD")), "старое место клонов — ссылкой")
        val again = assertIs<Outcome.Success<GuestStorage.Migrated>>(GuestStorage.migrate(dir, moves)).value
        assertTrue(again.moved.isEmpty() && again.linked.isEmpty(), "второй раз — ничего: ссылки уже стоят")
    }

    @Test
    fun occupiedTargetMergesWithoutOverwriting() {
        val home = dir.resolve("root")
        val models = home.resolve(GuestStorage.MODELS)
        write(home.resolve("models/a.gguf"), "old a")
        write(home.resolve("models/b.gguf"), "b")
        write(models.resolve("a.gguf"), "new a")
        val r = assertIs<Outcome.Success<GuestStorage.Migrated>>(GuestStorage.migrate(dir, GuestStorage.legacyMoves(dir, "root"))).value
        assertEquals("new a", Files.readString(models.resolve("a.gguf")), "занятое не перезаписано")
        assertTrue(Files.isRegularFile(models.resolve("b.gguf")))
        assertEquals(1, r.skipped.size)
        assertFalse(Files.isSymbolicLink(home.resolve("models")), "на старом месте осталось своё — ссылки нет")
    }

    @Test
    fun mapListsSectionsProjectsToolsAndSecretNamesOnly() {
        val home = dir.resolve("linux/rootfs/root")
        val rootfs = dir.resolve("linux/rootfs")
        assertIs<Outcome.Success<Unit>>(GuestStorage.ensure(dir, "linux/rootfs/root"))
        write(home.resolve("projects/dpsmpx/DPmetaApp/.git/HEAD"), "ref: refs/heads/codex/ide-zen-mode\n")
        Files.createDirectories(home.resolve("projects/local/sandbox"))
        write(home.resolve("${GuestStorage.BIN}/kotlin-lsp"), "#!/bin/sh")
        write(home.resolve("${GuestStorage.MODELS}/tiny.gguf"), "g".repeat(2048))
        Files.createDirectories(home.resolve("${GuestStorage.IDE}/android-sdk/platforms/android-36"))
        Files.createDirectories(home.resolve("ai/claude-1"))
        write(rootfs.resolve("lib/apk/db/installed"), "P:git\nV:1\n\nP:bash\n")
        val facts = GuestStorage.scan(home, rootfs, "12.11.2026 14:30", listOf(GuestStorage.SecretLine("Токен GitHub", "GH_TOKEN", false)), 41L shl 30, 0)
        val md = GuestStorage.render(facts)
        assertTrue("`~/projects/dpsmpx/DPmetaApp` — ветка `codex/ide-zen-mode`" in md, md)
        assertTrue("`~/projects/local/sandbox` — без Git" in md, md)
        assertTrue("в `PATH` (`~/tools/bin`): kotlin-lsp" in md)
        assertTrue("платформы Android SDK: android-36" in md)
        assertTrue("tiny.gguf (1 МБ)" in md)
        assertTrue("пакеты Alpine: 2" in md)
        assertTrue("- claude-1" in md)
        assertTrue("Токен GitHub — `GH_TOKEN` — ИИ не выдаётся" in md)
        assertTrue("всё свободное место телефона" in md && "41,0 ГБ" in md)
        assertEquals("/root/ai/claude-3", GuestStorage.slotDir("claude", 3))
    }

    @Test
    fun slotInstructionsGetOneManagedPointerBlock() {
        assertEquals("CLAUDE.md", GuestStorage.instructionFile("claude-1"))
        assertEquals("AGENTS.md", GuestStorage.instructionFile("codex-2"))
        assertEquals(".gemini/GEMINI.md", GuestStorage.instructionFile("gemini-1"))
        assertEquals(null, GuestStorage.instructionFile("notes"))
        val fresh = GuestStorage.withPointer(null)
        assertTrue(fresh.startsWith("<!-- dpmetaapp:storage -->") && "~/STORAGE.md" in fresh)
        val mine = "# Мои правила\nОтвечай кратко.\n"
        val once = GuestStorage.withPointer(mine)
        assertTrue(once.startsWith(mine.trimEnd()), "своё — не тронуто")
        assertEquals(once, GuestStorage.withPointer(once), "повтор — без второго блока")
        assertEquals(1, Regex("<!-- dpmetaapp:storage -->").findAll(GuestStorage.withPointer(once)).count(), "один блок")
        assertTrue("/root/tools/bin" in GuestStorage.PROFILE_SCRIPT && "case" in GuestStorage.PROFILE_SCRIPT)
    }
}

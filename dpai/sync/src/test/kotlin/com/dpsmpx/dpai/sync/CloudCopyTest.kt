package com.dpsmpx.dpai.sync

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Облачная копия (D-107, T20): шифр не открывается чужим паролем и не
 * терпит подмены; архив не кладёт файлы вне каталогов контура и не берёт
 * политику прогона; восстановление не удаляет прежние данные и
 * довершается после прерывания.
 */
class CloudCopyTest {
    @Test
    fun oversizedFileIsRefusedBeforeReadingAndWireLimitIncludesManifest() {
        val disk = NioVfs(dir)
        disk.mkdirs("live")
        disk.write("live/large", ByteArray(4096))
        val guarded = object : com.dpsmpx.dpai.storage.vfs.Vfs by disk {
            override fun readRange(path: String, offset: Long, length: Int): ByteArray? = error("слишком большой файл нельзя читать")
        }
        assertIs<Outcome.Failure>(CloudArchive.collect(guarded, sequenceOf("live/large"), 1024))
        assertIs<Outcome.Failure>(CloudArchive.pack(listOf(CopyEntry("live/one", byteArrayOf(1))), clock.now(), "device", 32))
    }

    @TempDir
    lateinit var dir: Path

    /** Каталог документов приложения; строкой «docs/…» его читала бы проверка ссылок документов. */
    private val D = "docs"

    private val clock = ManualClock()
    private val random = RandomSource.seeded(107)
    private val pass = Secret("длинный пароль копии")

    /** Повторов мало — тесты быстрые; чтение пускает их явно. */
    private fun seal(b: ByteArray) = (CloudCrypto.seal(b, pass, random, iterations = 1000) as Outcome.Success).value

    private fun open(b: ByteArray, p: Secret = pass) = CloudCrypto.open(b, p, minIterations = 1000)

    private fun entries() = listOf(
        CopyEntry("live/live.journal", "события".toByteArray()),
        CopyEntry("live/snapshots/tasks", "{\"t\":1}".toByteArray()),
        CopyEntry("$D/targets", "{}".toByteArray()),
        CopyEntry("known_hosts", "host ssh-ed25519 AAAA\n".toByteArray()),
    )

    @Test
    fun sealedCopyOpensOnlyWithItsPassphrase() {
        val plain = "журнал контура".toByteArray()
        val sealed = seal(plain)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("журнал"), "на диске и в сети — только шифр")
        assertContentEquals(plain, (open(sealed) as Outcome.Success).value)
        assertEquals(DiagCode.BACKUP_INVALID, (open(sealed, Secret("другой пароль копии")) as Outcome.Failure).diagnostics.first().code)
        assertTrue(CloudCrypto.open(sealed, pass) is Outcome.Failure, "по умолчанию слабый вывод ключа не принимается")
        assertEquals(DiagCode.VALUE_INVALID, (CloudCrypto.seal(plain, Secret("короткий"), random) as Outcome.Failure).diagnostics.first().code)
        assertEquals(DiagCode.FORMAT_UNRECOGNIZED, (open("не копия".toByteArray()) as Outcome.Failure).diagnostics.first().code)
    }

    @Test
    fun tamperedHeaderOrBodyIsRefused() {
        val sealed = seal("данные".toByteArray())
        val text = String(sealed, Charsets.ISO_8859_1)
        // Соль в заголовке — связанные данные шифра: подмена одного знака не расшифруется.
        val saltAt = text.indexOf("\"salt\":\"") + 8
        val badHeader = sealed.copyOf().also { it[saltAt] = (if (it[saltAt] == 'A'.code.toByte()) 'B' else 'A').code.toByte() }
        assertIs<Outcome.Failure>(open(badHeader))
        val badBody = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(DiagCode.BACKUP_INVALID, (open(badBody) as Outcome.Failure).diagnostics.first().code)
    }

    @Test
    fun archiveRoundTripChecksEveryFile() {
        val packed = (CloudArchive.pack(entries(), clock.now(), "redmi-13") as Outcome.Success).value
        val (info, files) = (CloudArchive.unpack(packed) as Outcome.Success).value
        assertEquals("redmi-13", info.device)
        assertEquals(4, info.files)
        assertEquals(entries().map { it.path }.sorted(), files.map { it.path })
        assertContentEquals("события".toByteArray(), files.single { it.path == "live/live.journal" }.bytes)
        // Подмена байта файла — расхождение с оглавлением, отказ всей копии.
        val tampered = packed.copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 1).toByte() }
        assertEquals(DiagCode.HASH_MISMATCH, (CloudArchive.unpack(tampered) as Outcome.Failure).diagnostics.first().code)
        assertIs<Outcome.Failure>(CloudArchive.unpack(packed + byteArrayOf(1)), "лишние байты — отказ")
        assertIs<Outcome.Failure>(CloudArchive.unpack(packed.copyOf(packed.size - 1)), "обрезанная копия — отказ")
    }

    @Test
    fun archiveKeepsOnlyAllowedPaths() {
        for (bad in listOf("../etc/passwd", "/live/x", "live/../../x", "linux/rootfs/root/.bashrc", "app_key.pub", "$D/auto-policy", "$D/auto-policy.prev", "live")) {
            assertTrue(CloudArchive.pathProblem(bad) != null, bad)
            assertIs<Outcome.Failure>(CloudArchive.pack(listOf(CopyEntry(bad, byteArrayOf(1))), clock.now(), "d"), bad)
        }
        assertFalse(CloudArchive.wanted("$D/targets.tmp"))
        assertFalse(CloudArchive.wanted("live.replaced-1/x"))
        assertTrue(CloudArchive.wanted("$D/project"))
        assertFalse(CloudArchive.wanted("live/journal-copies/live.journal.3"), "копии сегментов журнала — не данные")
        assertTrue(CloudArchive.pathProblem("live/quarantine-confirmed") != null, "одноразовое «да» карантину — не в копию")
        assertTrue(CloudArchive.pathProblem("live/a\u0000b") != null, "управляющие знаки — отказ")
        assertIs<Outcome.Failure>(CloudArchive.pack(entries(), clock.now(), "имя"), "имя устройства — латиницей")
    }

    @Test
    fun restoreReplacesRootsKeepingTheOldOnesAndResumes() {
        val root = Files.createDirectories(dir.resolve("controller"))
        val vfs = NioVfs(root)
        vfs.mkdirs("live")
        vfs.write("live/live.journal", "старое".toByteArray())
        vfs.write("known_hosts", "old\n".toByteArray())
        vfs.mkdirs("docs")
        vfs.write("$D/targets", "old".toByteArray())
        val restore = CloudRestore(vfs, clock)
        assertEquals(false, (restore.applyPending() as Outcome.Success).value, "без отметки — ничего")
        val staged = (restore.stage(entries()) as Outcome.Success).value
        assertTrue(restore.pending())
        assertContentEquals("старое".toByteArray(), vfs.read("live/live.journal"), "до запуска данные не тронуты")
        // Прерванное применение: первый корень уже перенесён.
        val stamp = staged.removePrefix(CloudRestore.STAGE_PREFIX)
        vfs.rename("docs", "docs${CloudRestore.REPLACED}$stamp")
        vfs.rename("$staged/docs", "docs")
        clock.advance(1.minutes)
        assertEquals(true, (restore.applyPending() as Outcome.Success).value)
        assertFalse(restore.pending())
        assertContentEquals("события".toByteArray(), vfs.read("live/live.journal"))
        assertContentEquals("{}".toByteArray(), vfs.read("$D/targets"))
        assertContentEquals("host ssh-ed25519 AAAA\n".toByteArray(), vfs.read("known_hosts"))
        assertContentEquals("старое".toByteArray(), vfs.read("live${CloudRestore.REPLACED}$stamp/live.journal"), "прежнее — рядом")
        assertContentEquals("old".toByteArray(), vfs.read("docs${CloudRestore.REPLACED}$stamp/targets"))
        assertContentEquals("old\n".toByteArray(), vfs.read("known_hosts${CloudRestore.REPLACED}$stamp"))
        assertEquals(false, (restore.applyPending() as Outcome.Success).value, "второй раз — ничего")
    }

    @Test
    fun busyReplacedNameIsNotOverwrittenAndStagingCanBeCancelled() {
        val vfs = NioVfs(Files.createDirectories(dir.resolve("c2")))
        vfs.mkdirs("live")
        vfs.write("live/x", "сейчас".toByteArray())
        val restore = CloudRestore(vfs, clock)
        val staged = (restore.stage(entries()) as Outcome.Success).value
        val stamp = staged.removePrefix(CloudRestore.STAGE_PREFIX)
        // Прерванное применение уже заняло имя прежнего каталога, а контур создал новый пустой.
        vfs.mkdirs("live${CloudRestore.REPLACED}$stamp")
        vfs.write("live${CloudRestore.REPLACED}$stamp/x", "раньше".toByteArray())
        assertEquals(true, (restore.applyPending() as Outcome.Success).value)
        assertContentEquals("раньше".toByteArray(), vfs.read("live${CloudRestore.REPLACED}$stamp/x"), "занятое имя не затёрто")
        assertContentEquals("сейчас".toByteArray(), vfs.read("live${CloudRestore.REPLACED}$stamp-1/x"))

        clock.advance(1.minutes)
        restore.stage(entries())
        assertTrue(restore.pending())
        assertTrue(restore.cancel() is Outcome.Success)
        assertEquals(false, (restore.applyPending() as Outcome.Success).value, "отменённое не применяется")
    }

    @Test
    fun brokenMarkerIsRefused() {
        val vfs = NioVfs(Files.createDirectories(dir.resolve("c")))
        vfs.write(CloudRestore.MARKER, "../evil\n".toByteArray())
        assertEquals(DiagCode.BACKUP_INVALID, (CloudRestore(vfs, clock).applyPending() as Outcome.Failure).diagnostics.first().code)
    }
}

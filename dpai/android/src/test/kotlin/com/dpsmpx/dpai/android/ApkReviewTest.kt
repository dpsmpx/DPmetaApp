package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Окно перед установкой и подписью (D-157, T22): копия, сведения словами, строка журнала. */
class ApkReviewTest {
    private val dir: File = Files.createTempDirectory("apkreview").toFile()

    private val meta = ApkInstaller.Meta("com.example.app", "1.2", 12, self = false, installed = "1.1")
    private val sig = ApkSign.Signature(verified = true, schemes = listOf("v2", "v3"), certSha256 = "ab".repeat(32), subject = "CN=Автор", problems = emptyList())

    @Test
    fun copyIsTakenAndLaterChangesOfTheTreeFileDoNotReachIt() {
        val source = File(File(dir, "tree").apply { mkdirs() }, "app-debug.apk").apply { writeText("первый") }
        val stage = File(dir, "stage")
        File(stage.apply { mkdirs() }, "старое.apk").writeText("прошлая проверка")
        val copy = assertIs<Outcome.Success<File>>(ApkReview.stage(source, stage)).value
        assertEquals(listOf("app-debug.apk"), stage.list()!!.toList(), "прежние копии убраны")
        source.writeText("подмена")
        assertEquals("первый", copy.readText())
    }

    @Test
    fun missingFileIsRefused() {
        val r = ApkReview.stage(File(dir, "нет.apk"), File(dir, "stage"))
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(r).diagnostics.first().code)
    }

    @Test
    fun descriptionShowsPackageVersionSignatureAndSum() {
        val text = ApkReview.describe(Outcome.success(meta), Outcome.success(sig), "cd".repeat(32), keySha256 = "ab".repeat(32))
        assertTrue("Пакет: com.example.app" in text, text)
        assertTrue("Версия: 1.2 (12)" in text, text)
        assertTrue("Установлено: 1.1" in text, text)
        assertTrue("Подпись: v2, v3" in text, text)
        assertTrue("Кем: CN=Автор" in text, text)
        assertTrue("Сертификат SHA-256: ${"ab".repeat(32)}" in text, text)
        assertTrue("Подписан вашим ключом." in text, text)
        assertTrue(text.endsWith("SHA-256 файла: ${"cd".repeat(32)}"), text)
        assertFalse("самого DPmetaApp" in text, text)
    }

    @Test
    fun ownApkIsNamedAndForeignKeyIsNotCalledYours() {
        val text = ApkReview.describe(Outcome.success(meta.copy(self = true)), Outcome.success(sig), "00", keySha256 = "ef".repeat(32))
        assertTrue("Это APK самого DPmetaApp." in text, text)
        assertFalse("вашим ключом" in text, text)
    }

    @Test
    fun logLineCarriesSubjectFingerprintAndSum() {
        val line = ApkReview.logLine(Outcome.success(meta), Outcome.success(sig), "cd".repeat(32))
        assertEquals("com.example.app 1.2 (12); подпись CN=Автор, сертификат SHA-256 ${"ab".repeat(32)}; файл SHA-256 ${"cd".repeat(32)}", line)
        val unsigned = ApkReview.logLine(Outcome.failure(DiagCode.VALUE_INVALID, "не APK"), Outcome.success(sig.copy(verified = false)), "00")
        assertEquals("пакет не прочитан; подписи нет или она неверна; файл SHA-256 00", unsigned)
    }
}

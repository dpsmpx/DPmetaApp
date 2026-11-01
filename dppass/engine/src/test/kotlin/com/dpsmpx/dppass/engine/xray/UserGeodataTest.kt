package com.dpsmpx.dppass.engine.xray

import com.dpsmpx.dppass.core.Checksums
import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonArray
import com.dpsmpx.dppass.core.JsonString
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Outcome
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Свой набор геоданных пользователя из zip (D-109): та же проверка, что у
 * набора из APK, плюс пределы размера. Данные синтетические.
 */
class UserGeodataTest {
    private val files = linkedMapOf(
        "geoip.dat" to ByteArray(2000) { (it % 251).toByte() },
        "geosite.dat" to "synthetic geosite".toByteArray(),
        "NOTICE.txt" to "IP Geolocation by DB-IP".toByteArray(),
    )

    /** Оглавление набора; [sizeOf] позволяет объявить размер, отличный от настоящего (для предела). */
    private fun manifest(set: Map<String, ByteArray> = files, sizeOf: (String, ByteArray) -> Long = { _, b -> b.size.toLong() }): String = Json.write(
        Json.obj(
            "format" to JsonString("dppass.geodata"),
            "format_version" to Json.num(2),
            "sources" to Json.obj(
                "dlc" to Json.obj(
                    "module" to JsonString("dlc"),
                    "version" to JsonString("v0.0.0-20260101000000-0123456789ab"),
                    "commit" to JsonString("0123456789ab"),
                ),
                "dbip" to Json.obj("release" to JsonString("2026-01"), "sha256" to JsonString("0".repeat(64))),
            ),
            "files" to JsonArray(
                set.map { (name, bytes) ->
                    Json.obj("name" to JsonString(name), "sha256" to JsonString(Checksums.sha256(bytes)), "size" to Json.num(sizeOf(name, bytes)))
                },
            ),
            "geoip" to JsonArray(listOf(JsonString("aa"), JsonString("private"))),
            "geosite" to Json.obj("example-a" to JsonArray(listOf(JsonArray(listOf(JsonString("ads")))))),
        ),
    )

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zo ->
            for ((name, bytes) in entries) {
                zo.putNextEntry(ZipEntry(name))
                zo.write(bytes)
                zo.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun bundle(manifest: String = manifest(), set: Map<String, ByteArray> = files): ByteArray =
        zip(set + ("manifest.json" to manifest.toByteArray()))

    private val tmp: File get() = Files.createTempDirectory("dppass-usergeo").toFile()

    private fun install(zipBytes: ByteArray): Outcome<InstalledGeodata> {
        val dir = tmp
        return UserGeodata.install(ByteArrayInputStream(zipBytes), File(dir, "user"), dir)
    }

    @Test
    fun validBundleInstalls() {
        val r = assertIs<Outcome.Success<InstalledGeodata>>(install(bundle()))
        assertEquals("2026-01", r.value.manifest.dbipRelease)
        assertEquals(files.keys, r.value.dir.list()!!.filter { it != "manifest.json" }.toSet())
        assertContains(r.value.manifest.geoIp, "aa")
    }

    @Test
    fun fileThatDiffersFromManifestIsRejected() {
        val tampered = files.toMutableMap().apply { this["geoip.dat"] = ByteArray(2000) { 7 } }
        // Оглавление с суммой исходного файла, а в zip — другой: сверка SHA-256 не сойдётся.
        val r = assertIs<Outcome.Failure>(install(bundle(manifest(files), tampered)))
        assertEquals(DiagCode.GEODATA_UNAVAILABLE, r.errors.first().code)
    }

    @Test
    fun setLargerThanLimitByManifestIsRejected() {
        val huge = manifest(sizeOf = { name, b -> if (name == "geoip.dat") UserGeodata.MAX_SET_BYTES + 1 else b.size.toLong() })
        val r = assertIs<Outcome.Failure>(install(bundle(huge)))
        assertContains(r.errors.first().message, "больше")
    }

    @Test
    fun bundleWithoutManifestIsRejected() {
        val r = assertIs<Outcome.Failure>(install(zip(files)))
        assertContains(r.errors.first().message, "manifest.json")
    }

    @Test
    fun notAZipIsRejected() {
        val r = assertIs<Outcome.Failure>(install("не zip, просто байты".toByteArray()))
        assertContains(r.errors.first().message, "zip")
    }
}

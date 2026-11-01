package com.dpsmpx.dppass.engine.xray

import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Свой набор геоданных пользователя (D-109, уточняет D-27): пользователь
 * подкладывает набор, собранный **тем же** инструментом DPpass
 * (`tools/xray/geodata`) из первичных источников, — тот же формат и то же
 * оглавление, что у набора из APK. Это обновление геоданных без выпуска
 * приложения, без загрузки по сети: файл пользователь приносит сам.
 *
 * Набор — один zip: `manifest.json` в корне и перечисленные в нём файлы
 * (`geoip.dat`, `geosite.dat`, `NOTICE.txt`). Проверка — та же, что у
 * набора из APK: оглавление разбирается ([GeodataManifest.parse]:
 * формат и версия), каждый файл сверяется по SHA-256 и размеру
 * ([GeodataInstaller]), есть все обязательные файлы. Сверх этого —
 * пределы размера: zip не больше [MAX_BUNDLE_BYTES], сумма объявленных
 * размеров файлов не больше [MAX_SET_BYTES] (защита от «архивной бомбы»
 * до распаковки). Коды маршрутизации берутся из оглавления набора, как и
 * у встроенного.
 *
 * Готовый `geoip.dat`/`geosite.dat` чужого проекта так не принять: без
 * согласованного оглавления DPpass его не разберёт (D-20: состав и
 * происхождение каждого байта известны — оглавление несёт источники).
 */
object UserGeodata {
    /** Сжатый zip набора — не больше 64 МиБ (встроенный `geoip` ~4,3 МиБ сжат, D-27). */
    const val MAX_BUNDLE_BYTES: Long = 64L * 1024 * 1024

    /** Сумма распакованных размеров по оглавлению — не больше 128 МиБ (встроенный ~20 МиБ). */
    const val MAX_SET_BYTES: Long = 128L * 1024 * 1024

    /**
     * Перенести набор пользователя из [zip] в [userRoot] со всей проверкой.
     * [tmpDir] — куда класть временный файл zip (своё хранилище приложения).
     * Успех — установленный набор; иначе — отказ с причиной.
     */
    fun install(zip: InputStream, userRoot: File, tmpDir: File): Outcome<InstalledGeodata> {
        val temp = File(tmpDir, "user-geodata-${System.nanoTime()}.zip")
        try {
            when (val copied = copyBounded(zip, temp)) {
                is Outcome.Failure -> return copied
                is Outcome.Success -> Unit
            }
            return open(temp, userRoot)
        } catch (e: IOException) {
            return fail("набор геоданных не прочитан", e)
        } finally {
            temp.delete()
        }
    }

    /** zip во временный файл, не длиннее предела; превышение — отказ, а не молчаливая обрезка. */
    private fun copyBounded(zip: InputStream, temp: File): Outcome<Unit> {
        var total = 0L
        temp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = zip.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BUNDLE_BYTES) {
                    return Outcome.failure(DiagCode.GEODATA_UNAVAILABLE, "файл набора больше ${MAX_BUNDLE_BYTES / (1024 * 1024)} МиБ")
                }
                out.write(buf, 0, n)
            }
        }
        return Outcome.Success(Unit)
    }

    private fun open(temp: File, userRoot: File): Outcome<InstalledGeodata> {
        val zf = try {
            ZipFile(temp)
        } catch (e: ZipException) {
            return fail("набор — не zip", e)
        }
        zf.use {
            val manifestEntry = zf.getEntry(GeodataManifest.FILE)
                ?: return Outcome.failure(DiagCode.GEODATA_UNAVAILABLE, "в наборе нет ${GeodataManifest.FILE}")
            val manifestBytes = zf.getInputStream(manifestEntry).use { it.readBytes() }
            val manifest = when (val m = GeodataManifest.parse(manifestBytes.toString(Charsets.UTF_8))) {
                is Outcome.Failure -> return m
                is Outcome.Success -> m.value
            }
            val declared = manifest.files.sumOf { it.size }
            if (declared > MAX_SET_BYTES) {
                return Outcome.failure(DiagCode.GEODATA_UNAVAILABLE, "набор по оглавлению больше ${MAX_SET_BYTES / (1024 * 1024)} МиБ")
            }
            // Источник поверх zip: открываются только имена из разобранного оглавления
            // (без «/» по правилу имени, GeodataManifest.NAME), поэтому выйти за zip нельзя.
            val source = GeodataSource { n ->
                val entry = zf.getEntry(n) ?: throw IOException("в наборе нет файла $n")
                zf.getInputStream(entry)
            }
            return GeodataInstaller(source, userRoot, assetPrefix = "").install()
        }
    }

    private fun fail(message: String, e: Exception) = Outcome.failure(Diagnostic.fromThrowable(DiagCode.GEODATA_UNAVAILABLE, message, e))
}

package com.dpsmpx.dppass.app

import android.content.Context
import android.os.Build
import com.dpsmpx.dppass.connection.DPpassCore
import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Safe
import com.dpsmpx.dppass.engine.xray.GeodataCodes
import com.dpsmpx.dppass.engine.xray.GeodataInstaller
import com.dpsmpx.dppass.engine.xray.JniXrayRuntime
import com.dpsmpx.dppass.engine.xray.XrayBackend
import com.dpsmpx.dppass.engine.xray.XrayCompiler
import com.dpsmpx.dppass.engine.xray.XrayProxyEngine
import com.dpsmpx.dppass.storage.EncryptingBlobStore
import com.dpsmpx.dppass.storage.FileBlobStore
import com.dpsmpx.dppass.storage.StorageReset
import com.dpsmpx.dppass.storage.WrappedKeyProvider
import com.dpsmpx.dppass.tunnel.PlatformTraits
import java.io.File

/**
 * Сборка зависимостей приложения — единственное место, где Android
 * встречается с ядром DPpass. Ядро прокси — Xray в процессе приложения
 * (D-25): библиотека `libdppassxray` из APK; не загрузилась или не той
 * версии — каждый вызов ядра отвечает `BACKEND_NOT_AVAILABLE` с причиной.
 * Набор геоданных ядра (D-27) переносится из assets APK до загрузки ядра.
 */
object AppGraph {
    @Volatile
    private var instance: DPpassCore? = null

    fun core(context: Context): DPpassCore = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(context: Context): DPpassCore {
        val data = FileBlobStore(File(context.noBackupFilesDir, "state"))
        // Ключ данных — конвертом в своём каталоге (D-19); поверх существующих записей новый не создаётся.
        val keys = WrappedKeyProvider(
            FileBlobStore(File(context.noBackupFilesDir, "keys")),
            KeystoreKeyWrapper(),
            dataPresent = { StorageReset.hasData(data) },
        )
        // Набор геоданных (D-27): из assets APK в noBackupFilesDir со сверкой SHA-256.
        // Не перенёсся — ядро работает, а правила с geoip/geosite компилятор отвергает
        // с причиной (GEODATA_UNAVAILABLE), а не молча.
        val geodataRoot = File(context.noBackupFilesDir, "geodata")
        val (assetDir, geoCodes, geodataSummary) = when (val g = GeodataInstaller({ context.assets.open(it) }, geodataRoot).install()) {
            is Outcome.Success -> {
                DeviceLog.log.info("geodata_ready", "set" to Safe(g.value.manifest.summary), "installed_now" to g.value.installedNow)
                Triple(g.value.dir, GeodataCodes.Installed(g.value.manifest), g.value.manifest.summary)
            }
            is Outcome.Failure -> {
                g.errors.forEach(DeviceLog.log::diagnostic)
                Triple(geodataRoot.apply { mkdirs() }, GeodataCodes.Missing(g.errors.first().message), "none")
            }
        }
        val runtime = JniXrayRuntime.load({ System.loadLibrary(JniXrayRuntime.LIBRARY) }, assetDir.path)
        when (runtime) {
            is Outcome.Success -> DeviceLog.log.info("engine_loaded", "backend" to Safe(XrayBackend.ID), "version" to Safe(runtime.value.version()))
            is Outcome.Failure -> runtime.errors.forEach(DeviceLog.log::diagnostic)
        }
        return DPpassCore(
            compiler = XrayCompiler(geodata = geoCodes),
            engine = XrayProxyEngine(runtime),
            store = EncryptingBlobStore(data, keys),
            clock = Clock.SYSTEM,
            selfPackage = context.packageName,
            platform = PlatformTraits(supportsExcludeRoute = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
            storageRecovery = StorageReset(data, keys, Clock.SYSTEM),
            // Журнал устройства (D-26): кольцо для отчёта и файл в фоне.
            logSink = DeviceLog.sink,
            engineDetails = listOf("geodata" to geodataSummary),
        )
    }
}

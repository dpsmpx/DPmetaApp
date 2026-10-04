package com.dpsmpx.dppass.app

import android.content.Context
import android.os.Build
import com.dpsmpx.dppass.connection.DPpassCore
import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.engine.xray.JniXrayRuntime
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
        // Каталог геоданных ядра пуст, пока нет решения о геоданных на устройстве
        // (TODO.md): конфигурации с geoip/geosite ядро отвергает при проверке.
        val geodata = File(context.noBackupFilesDir, "geodata").apply { mkdirs() }
        val runtime = JniXrayRuntime.load({ System.loadLibrary(JniXrayRuntime.LIBRARY) }, geodata.path)
        return DPpassCore(
            compiler = XrayCompiler(),
            engine = XrayProxyEngine(runtime),
            store = EncryptingBlobStore(data, keys),
            clock = Clock.SYSTEM,
            selfPackage = context.packageName,
            platform = PlatformTraits(supportsExcludeRoute = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
            storageRecovery = StorageReset(data, keys, Clock.SYSTEM),
        )
    }
}

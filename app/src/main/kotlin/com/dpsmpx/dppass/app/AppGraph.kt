package com.dpsmpx.dppass.app

import android.content.Context
import android.os.Build
import com.dpsmpx.dppass.connection.DPpassCore
import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.engine.mock.MockBackend
import com.dpsmpx.dppass.engine.mock.MockBackendCompiler
import com.dpsmpx.dppass.engine.mock.MockProxyEngine
import com.dpsmpx.dppass.storage.EncryptingBlobStore
import com.dpsmpx.dppass.storage.FileBlobStore
import com.dpsmpx.dppass.tunnel.PlatformTraits
import java.io.File

/**
 * Сборка зависимостей приложения — единственное место, где Android
 * встречается с ядром DPpass. Ядро прокси пока фиктивное (D-03:
 * реальный Xray — этап 2 `ROADMAP`).
 */
object AppGraph {
    @Volatile
    private var instance: DPpassCore? = null

    fun core(context: Context): DPpassCore = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(context: Context): DPpassCore {
        val dir = File(context.noBackupFilesDir, "state")
        val keys = KeystoreKeyProvider(File(context.noBackupFilesDir, "keys"))
        val store = EncryptingBlobStore(FileBlobStore(dir), keys)
        val engine = MockProxyEngine(MockBackend.FULL)
        return DPpassCore(
            compiler = MockBackendCompiler(engine.capabilities),
            engine = engine,
            store = store,
            clock = Clock.SYSTEM,
            selfPackage = context.packageName,
            platform = PlatformTraits(supportsExcludeRoute = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
        )
    }
}

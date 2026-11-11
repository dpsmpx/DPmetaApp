package com.dpsmpx.dpmeta.app.data

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs

/** Уже подключённый аккаунт; срок доступа Keystore и необходимость входа сохраняются. */
object ConnectedGitHub {
    fun load(context: Context): Outcome<Secret?> =
        SecretStore(NioVfs(context.noBackupFilesDir.toPath().resolve("dpmeta"))).load()
}

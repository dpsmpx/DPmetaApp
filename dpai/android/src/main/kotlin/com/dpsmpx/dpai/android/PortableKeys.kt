package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Ключ SSH из копии под паролем (D-184, `DPmetaApp:D-157`): значение раскрывается только
 * здесь — в разбор ключа перед шифром Keystore (правило `secret-reveal`).
 */
internal object PortableKeys {
    fun importSsh(value: Secret): Outcome<Unit> = KeystoreKey.importValue(value.reveal())
}

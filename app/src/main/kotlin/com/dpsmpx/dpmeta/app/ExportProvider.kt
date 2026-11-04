package com.dpsmpx.dpmeta.app

import android.content.Context
import androidx.core.content.FileProvider

/**
 * Провайдер «Поделиться» экспортом пульта (D-67): свой подкласс, чтобы при
 * слиянии манифестов не столкнуться с `FileProvider` подсистемы или
 * библиотеки. Не экспортируется, видит только каталог экспорта в кэше
 * (`res/xml/pult_export_paths.xml`), права — получателю одной отправки.
 */
class ExportProvider : FileProvider() {
    companion object {
        fun authority(context: Context): String = "${context.packageName}.pult.export"
    }
}

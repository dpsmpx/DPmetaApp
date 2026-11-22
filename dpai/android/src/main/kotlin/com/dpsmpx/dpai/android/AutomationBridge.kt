package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Мост оболочки: подсистема не читает форматы пульта и не зависит от его модулей (D-215). */
object AutomationBridge {
    @Volatile var pult: ((root: String, inApp: Boolean) -> Outcome<List<AutomationDefinition>>)? = null
    @Volatile var openPult: ((Context) -> Unit)? = null

    fun catalog(root: String, inApp: Boolean): Outcome<List<AutomationDefinition>> = pult?.invoke(root, inApp)
        ?: Outcome.failure(DiagCode.UNSUPPORTED, "источник команд пульта не подключён оболочкой")
}

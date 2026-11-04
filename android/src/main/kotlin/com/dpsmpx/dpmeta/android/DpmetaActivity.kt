package com.dpsmpx.dpmeta.android

import android.content.Intent
import com.dpsmpx.dpai.android.MainActivity
import com.dpsmpx.dpmeta.app.PultActivity

/**
 * Первый экран приложения (D-53): пульт со вкладкой «AI». Пульт и оболочка
 * подсистемы друг о друге не знают — связывает их только этот модуль.
 */
class DpmetaActivity : PultActivity() {
    override fun aiIntent(): Intent = Intent(this, MainActivity::class.java)
}

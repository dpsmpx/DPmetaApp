package com.dpsmpx.dpmeta.shell

import android.content.Intent
import android.os.Bundle
import com.dpsmpx.dpai.android.AiBackground
import com.dpsmpx.dpai.android.MainActivity
import com.dpsmpx.dpmeta.app.PultActivity

/**
 * Первый экран приложения (D-53): пульт со вкладкой «AI». Пульт и оболочка
 * подсистемы друг о друге не знают — связывает их только этот модуль.
 */
class DpmetaActivity : PultActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Служба «AI» и разрешение на уведомления — уже с пульта, а не только из вкладки «AI» (UX-4).
        AiBackground.attach(this)
    }

    // Окно «AI» — одно: уже открытое выходит вперёд, второго экземпляра нет (UX-1).
    override fun aiIntent(): Intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
}

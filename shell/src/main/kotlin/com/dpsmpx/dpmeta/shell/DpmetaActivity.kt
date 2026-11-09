package com.dpsmpx.dpmeta.shell

import android.content.Intent
import android.os.Bundle
import com.dpsmpx.dpai.android.AiBackground
import com.dpsmpx.dpai.android.AiSummary
import com.dpsmpx.dpai.android.MainActivity
import com.dpsmpx.dpmeta.app.PultActivity
import com.dpsmpx.dpmeta.app.screens.Screens

/**
 * Первый экран приложения (D-53): пульт с пунктом «AI». Пульт и оболочка
 * подсистемы друг о друге не знают — связывает их только этот модуль.
 */
class DpmetaActivity : PultActivity() {
    /** Последние числа «AI» — ссылка на ждущий запрос для перехода со строки сводки (D-76). */
    @Volatile
    private var lastAi: AiSummary.Counts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Служба «AI» и разрешение на уведомления — уже с пульта, а не только из вкладки «AI» (UX-4).
        AiBackground.attach(this)
    }

    // Окно «AI» — одно: уже открытое выходит вперёд, второго экземпляра нет (UX-1).
    override fun aiIntent(): Intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    // Со строки «AI»: ждут ответа — сразу на «Запросы» (D-76).
    override fun aiIntent(requests: Boolean): Intent = lastAi?.request?.takeIf { requests }?.let { AiSummary.open(this, it) } ?: aiIntent()

    override fun aiVersion(): String = com.dpsmpx.dpai.android.BuildConfig.DPAI_VERSION

    /** Строки пульта — в журнал приложения подсистемы «AI» (D-39: один журнал отладки на приложение; там же Redactor). */
    override fun debugLog(tag: String, line: String) = com.dpsmpx.dpai.android.AppLogs.info(applicationContext, tag, line)

    override fun aiCounts(): Screens.AiCounts = AiSummary.read(this).also { lastAi = it }.let { Screens.AiCounts(it.configured, it.waiting, it.running, it.nearLimit) }
}

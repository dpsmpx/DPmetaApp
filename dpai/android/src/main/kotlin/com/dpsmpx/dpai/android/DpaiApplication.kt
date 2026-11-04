package com.dpsmpx.dpai.android

import android.app.Application
import com.dpsmpx.dpai.core.diagnostics.Redactor

/**
 * Процесс приложения: запись падений ставится здесь, один раз на процесс
 * (ревью 8 октября 2026, A-36). Из `Activity.onCreate` она не ловила
 * падения службы контроллера и приёмника запросов, запущенных без окна, а
 * каждое пересоздание окна добавляло ещё один обработчик в цепочку.
 * Открыт для наследования: приложение DPmetaApp добавляет вход на все окна
 * (DPmetaApp:D-59, D-103), отдельная сборка «AI» — без него.
 */
open class DpaiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecord.install(filesDir)
        CrashRecord.onFatal = { text -> AppLogs.get(this).fatal("падение", Redactor.STANDARD.redact(text)) }
        // Диск открыт и после перезапуска процесса (D-96): уведомление «открыт» — снова в шторке.
        runCatching { AppDiskNotice.sync(this) }
    }
}

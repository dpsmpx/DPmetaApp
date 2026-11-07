package com.dpsmpx.dpai.android

import android.app.Application
import android.content.Intent
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

    /**
     * Нужен ли сейчас вход приложения (D-104): окно поверх приложений
     * ([RequestOverlay]) отдаёт опасный ответ экрану приложения, а не даёт
     * его само. Подсистема входа не держит — «нет»; приложение DPmetaApp
     * отвечает по своему входу (`DPmetaApp:D-61`). Вызывается из главного потока.
     */
    open fun loginNeeded(): Boolean = false

    /**
     * Окно, над которым открывается «AI» (`DPmetaApp:D-73`; аудит UI/UX
     * 5 ноября 2026, UX-1): в приложении DPmetaApp — пульт, и «назад» из
     * «AI» ведёт в него. В подсистеме — нет: «AI» сама первое окно.
     */
    open fun parentIntent(): Intent? = null
}

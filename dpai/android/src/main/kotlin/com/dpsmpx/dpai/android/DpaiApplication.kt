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
        Drafts.install(this)
        IdeState.install(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: android.app.Activity) { Drafts.flushNow(); IdeState.flush() }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
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

    /**
     * Манифест экосистемы из реестра приложения (D-114): его собирает
     * оболочка DPmetaApp из реестра и клонов пульта; подсистема от модулей
     * DPmetaApp не зависит. `null` — источника нет (отдельная сборка «AI»):
     * только файл. Блокирующий вызов — с фонового потока.
     */
    open fun ecosystemManifest(): com.dpsmpx.dpai.core.result.Outcome<String>? = null

    /** Есть ли [ecosystemManifest] у этой сборки — без вызова (он читает клоны). */
    open val ecosystemRegistry: Boolean get() = false

    /** Аккаунт предоставляет оболочка; подсистема не знает ни хранилища, ни токена пульта (D-125). */
    open val sharedToolsAccountAvailable: Boolean get() = false

    /** Право для общей папки выдаёт оболочка после явного входа (DPmetaApp:D-95). */
    open fun sharedToolsAccount(): com.dpsmpx.dpai.core.result.Outcome<com.dpsmpx.dpai.core.diagnostics.Secret?> =
        com.dpsmpx.dpai.core.result.Outcome.success(null)

    /** Явный запрос экрана; отмена при уходе/повороте принадлежит экрану. Из фона не вызывается. */
    open fun authorizeSharedTools(activity: android.app.Activity, signal: android.os.CancellationSignal, done: (com.dpsmpx.dpai.core.result.Outcome<Unit>) -> Unit) =
        done(com.dpsmpx.dpai.core.result.Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.UNSUPPORTED, "Источник GitHub-аккаунта недоступен"))

    /** Служба остановлена: отозвать право, в том числе у незавершённого окна входа. */
    open fun sharedToolsSessionClosed() = Unit

    /** Служба контроллера остановлена: оболочка отзывает права, выданные «до остановки службы» (`DPmetaApp:D-114`). */
    open fun controllerStopped() = Unit

    /** Раздел приложения вне окна «AI» (`DPmetaApp:D-112`): окно пульта на нём; `null` — пульта нет (отдельная сборка). */
    open fun sectionIntent(section: com.dpsmpx.dpai.ui.AppSection): android.content.Intent? = null
}

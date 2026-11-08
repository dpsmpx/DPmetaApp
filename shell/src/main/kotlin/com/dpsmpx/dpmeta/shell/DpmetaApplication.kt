package com.dpsmpx.dpmeta.shell

import com.dpsmpx.dpai.android.DpaiApplication
import com.dpsmpx.dpmeta.app.AppLock

/**
 * Процесс приложения DPmetaApp: всё, что делает процесс «AI» (запись падений,
 * диск), и вход по PIN на все окна (D-59) — один раз за запуск и после часа в
 * фоне; опасный ответ окна запроса поверх приложений — тоже после входа
 * (D-61). Службы и уведомления «AI» он не останавливает.
 */
class DpmetaApplication : DpaiApplication() {
    override fun onCreate() {
        super.onCreate()
        AppLock.install(this)
        // Пояс показа — всему приложению с запуска процесса: «AI» строит экраны и без открытого пульта (UX-22).
        com.dpsmpx.dpmeta.app.PultActivity.applyZone(this)
        // Клоны репозиториев пульта (D-70) — в Linux-среде «AI» как /root/repos: модели работают в них.
        com.dpsmpx.dpai.android.linux.LinuxSetup.share(java.io.File(filesDir, com.dpsmpx.dpmeta.app.PultActivity.CLONES_DIR), "/root/repos")
    }

    /** Опасный ответ окна поверх приложений — только после входа (D-61). */
    override fun loginNeeded(): Boolean = AppLock.loginNeeded

    /** Манифест экосистемы для «AI» — из реестра и клонов пульта (DP_AI_Orcestrator:D-114). */
    override fun ecosystemManifest(): com.dpsmpx.dpai.core.result.Outcome<String> = com.dpsmpx.dpmeta.app.PultActivity.ecosystemManifest(this)

    override val ecosystemRegistry: Boolean get() = true

    /** «AI» открывается над пультом, «назад» из неё — в пульт (D-73, UX-1). */
    override fun parentIntent(): android.content.Intent = android.content.Intent(this, DpmetaActivity::class.java)
}

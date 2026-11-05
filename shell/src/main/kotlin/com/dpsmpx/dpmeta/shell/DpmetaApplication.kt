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
        // Клоны репозиториев пульта (D-70) — в Linux-среде «AI» как /root/repos: модели работают в них.
        com.dpsmpx.dpai.android.linux.LinuxSetup.share(java.io.File(filesDir, com.dpsmpx.dpmeta.app.PultActivity.CLONES_DIR), "/root/repos")
    }

    /** Опасный ответ окна поверх приложений — только после входа (D-61). */
    override fun loginNeeded(): Boolean = AppLock.loginNeeded
}

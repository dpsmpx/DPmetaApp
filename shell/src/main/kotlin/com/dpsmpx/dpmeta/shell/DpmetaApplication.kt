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
        // Раздел пульта и токен GitHub в резервной копии (D-151) — до super: восстановленное
        // раскладывается в его начале и должно знать раздел пульта.
        registerBackup()
        super.onCreate()
        AppLock.install(this)
        // Пояс показа — всему приложению с запуска процесса: «AI» строит экраны и без открытого пульта (UX-22).
        com.dpsmpx.dpmeta.app.PultActivity.applyZone(this)
        // Одно хранилище (D-111, D-113): клоны пульта живут в ~/projects гостя — их видят терминал, IDE и «AI»;
        // прежний каталог клонов переезжает туда один раз, до первого окна. Карта ~/STORAGE.md — в фоне.
        com.dpsmpx.dpai.android.StorageMap.adopt(java.io.File(filesDir, com.dpsmpx.dpmeta.app.PultActivity.LEGACY_CLONES_DIR), "projects")
        // Токен GitHub пульта — в общем списке секретов; ИИ — по разрешению и после входа раз за запуск (D-114).
        com.dpsmpx.dpai.android.SecretGrants.register(
            com.dpsmpx.dpai.android.SecretGrants.Source(
                id = "github", name = "Токен GitHub", variable = "GH_TOKEN",
                present = { com.dpsmpx.dpmeta.app.PultActivity.hasGithubToken(it) },
                value = { com.dpsmpx.dpmeta.app.data.AiGitHub.account() },
                unlocked = { com.dpsmpx.dpmeta.app.data.AiGitHub.active() },
                unlock = { activity, done -> unlockGithubForAi(activity, done) },
            ),
        )
        com.dpsmpx.dpai.android.StorageMap.prepare(this)
        com.dpsmpx.dpai.android.StorageMap.refresh(this)
        // Мгновенный канал (D-152): включённый поднимается с любым окном — из фона Android 12+ службу не запустить.
        registerActivityLifecycleCallbacks(PushOnResume)
        // Виджет по такту (D-153): синхронизация пульта перечитывает его сразу.
        com.dpsmpx.dpmeta.app.data.WidgetCache.onChanged = { StudioWidget.refresh(it) }
    }

    /** Ручной запуск кончился, числа «AI» или проверки сменились (D-153). */
    override fun studioChanged() = StudioWidget.refresh(this)

    private object PushOnResume : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) = com.dpsmpx.dpmeta.app.PushChannelService.ensure(activity)
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityStarted(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivityStopped(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    private fun registerBackup() {
        val dpmeta = { c: android.content.Context -> c.filesDir.resolve("dpmeta") }
        com.dpsmpx.dpai.android.AppBackups.register(
            com.dpsmpx.dpai.android.AppBackups.Section(
                "pult", "Пульт", "часовой пояс, тихие часы и приватность, отмеченные клоны, фоновое обновление, свои правила тревог, сводка и «Отложить», сервер и тема мгновенного канала, белый список уведомлений",
                prefs = listOf(
                    com.dpsmpx.dpai.android.AppBackups.PrefsSpec("dpmeta.settings"),
                    com.dpsmpx.dpai.android.AppBackups.PrefsSpec("dpmeta.clones") { it == "flat-removed" },
                    com.dpsmpx.dpai.android.AppBackups.PrefsSpec("dpmeta.background") { it == "last" },
                    // Мгновенный канал (D-152): сервер и тема; ключ — секретом ниже. Включённость не переносится:
                    // на новом телефоне канал включают сами, когда хук получит ключ.
                    com.dpsmpx.dpai.android.AppBackups.PrefsSpec(com.dpsmpx.dpmeta.app.data.PushPrefs.PREFS) { it == com.dpsmpx.dpmeta.app.data.PushPrefs.ENABLED },
                    // Слушатель (D-155): включённость и белый список; доступ Android даётся заново на новом телефоне.
                    com.dpsmpx.dpai.android.AppBackups.PrefsSpec(com.dpsmpx.dpmeta.app.data.ListenerPrefs.PREFS),
                ),
                docs = listOf(
                    com.dpsmpx.dpai.android.AppBackups.DocsSpec("pult.settings", {
                        com.dpsmpx.dpai.storage.document.DocumentStore(com.dpsmpx.dpai.storage.vfs.NioVfs(dpmeta(it).apply { mkdirs() }.toPath()), "settings")
                    }) { it == "alert-rules" },
                ),
            ),
        )
        val store = { c: android.content.Context -> com.dpsmpx.dpmeta.app.data.SecretStore(com.dpsmpx.dpai.storage.vfs.NioVfs(c.noBackupFilesDir.toPath().resolve("dpmeta"))) }
        com.dpsmpx.dpai.android.AppSecrets.register(
            com.dpsmpx.dpai.android.AppSecrets.External(
                id = "github", name = "Токен GitHub", revoke = com.dpsmpx.dpai.ui.SecretRevoke.GITHUB_TOKENS,
                present = { com.dpsmpx.dpmeta.app.PultActivity.hasGithubToken(it) },
                read = { store(it).load() },
                write = { c, s -> store(c).save(s) },
                erase = { c -> store(c).forget(); com.dpsmpx.dpai.core.result.Outcome.success(Unit) },
                unlock = { activity, done ->
                    com.dpsmpx.dpmeta.app.Lock.ask(activity, "Токен GitHub для резервной копии", pinOnly = false,
                        onOpen = { if (!activity.isDestroyed) done(com.dpsmpx.dpai.core.result.Outcome.success(Unit)) },
                        onDenied = { _, _ -> if (!activity.isDestroyed) done(com.dpsmpx.dpai.core.result.Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "Вход не подтверждён")) })
                },
            ),
        )
        // Ключ подписи мгновенного канала (D-152): без входа — служба проверяет подписи при запертом телефоне (T30).
        com.dpsmpx.dpai.android.AppSecrets.register(
            com.dpsmpx.dpai.android.AppSecrets.External(
                id = "push", name = "Ключ мгновенного канала", revoke = null,
                hint = "сотрите или смените ключ — старый перестанет проходить; новый — в окружение хука",
                present = { com.dpsmpx.dpmeta.app.data.PushPrefs.hasKey(it) },
                read = { com.dpsmpx.dpmeta.app.data.PushPrefs.key(it) },
                write = { c, s -> com.dpsmpx.dpmeta.app.data.SecretStore.push(c).save(s) },
                erase = { c ->
                    com.dpsmpx.dpmeta.app.PushChannelService.stop(c)
                    com.dpsmpx.dpmeta.app.data.SecretStore.push(c).forget()
                    com.dpsmpx.dpai.core.result.Outcome.success(Unit)
                },
            ),
        )
    }

    /** Опасный ответ окна поверх приложений — только после входа (D-61). */
    override fun loginNeeded(): Boolean = AppLock.loginNeeded

    override fun presentNotice(notice: com.dpsmpx.dpai.notifications.Notification, render: (com.dpsmpx.dpai.ui.NoticePresentation) -> Boolean) =
        com.dpsmpx.dpmeta.app.data.PultAlerts.presentAi(this, notice, render)

    override val sharedToolsAccountAvailable: Boolean get() = true

    override fun sharedToolsAccount(): com.dpsmpx.dpai.core.result.Outcome<com.dpsmpx.dpai.core.diagnostics.Secret?> =
        com.dpsmpx.dpmeta.app.data.ConnectedGitHub.account()

    override fun authorizeSharedTools(activity: android.app.Activity, signal: android.os.CancellationSignal, done: (com.dpsmpx.dpai.core.result.Outcome<Unit>) -> Unit) {
        if (com.dpsmpx.dpmeta.app.data.ConnectedGitHub.active()) {
            done(com.dpsmpx.dpai.core.result.Outcome.success(Unit))
            return
        }
        val request = com.dpsmpx.dpmeta.app.data.ConnectedGitHub.request()
        com.dpsmpx.dpmeta.app.Lock.ask(activity, "GitHub для общей папки DP-Verify", pinOnly = false, signal = signal,
            onOpen = { if (!signal.isCanceled && !activity.isDestroyed) done(com.dpsmpx.dpmeta.app.data.ConnectedGitHub.authorize(this, request)) },
            onDenied = { _, _ -> if (!signal.isCanceled && !activity.isDestroyed) done(com.dpsmpx.dpai.core.result.Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "Вход не подтверждён")) })
    }

    override fun sharedToolsSessionClosed() = com.dpsmpx.dpmeta.app.data.ConnectedGitHub.close()

    /** Служба остановлена: право ИИ на токен GitHub отзывается (D-114). */
    override fun controllerStopped() {
        com.dpsmpx.dpmeta.app.data.AiGitHub.close()
        // Служба остановлена — числа «AI» на виджете становятся «неизвестно» (D-13, D-153).
        StudioWidget.refresh(this)
    }

    /** Вход для токена GitHub ИИ (D-114): PIN или биометрия, право — до остановки процесса или службы. */
    private fun unlockGithubForAi(activity: android.app.Activity, done: (com.dpsmpx.dpai.core.result.Outcome<Unit>) -> Unit) {
        if (com.dpsmpx.dpmeta.app.data.AiGitHub.active()) return done(com.dpsmpx.dpai.core.result.Outcome.success(Unit))
        val request = com.dpsmpx.dpmeta.app.data.AiGitHub.request()
        com.dpsmpx.dpmeta.app.Lock.ask(activity, "Токен GitHub для ИИ", pinOnly = false,
            onOpen = { if (!activity.isDestroyed) done(com.dpsmpx.dpmeta.app.data.AiGitHub.authorize(this, request)) },
            onDenied = { _, _ -> if (!activity.isDestroyed) done(com.dpsmpx.dpai.core.result.Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "Вход не подтверждён")) })
    }

    /** Раздел пульта с левой панели окна «AI» (D-112): пульт под «AI» выходит вперёд, «AI» над ним закрывается. */
    override fun sectionIntent(section: com.dpsmpx.dpai.ui.AppSection): android.content.Intent =
        android.content.Intent(this, DpmetaActivity::class.java).putExtra(com.dpsmpx.dpmeta.app.PultActivity.EXTRA_SECTION, section.name)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** «AI» открывается над пультом, «назад» из неё — в пульт (D-73, UX-1). */
    override fun parentIntent(): android.content.Intent = android.content.Intent(this, DpmetaActivity::class.java)
}

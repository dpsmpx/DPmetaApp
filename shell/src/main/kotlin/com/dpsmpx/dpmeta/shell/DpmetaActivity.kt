package com.dpsmpx.dpmeta.shell

import android.content.Intent
import android.os.Bundle
import com.dpsmpx.dpai.android.AiBackground
import com.dpsmpx.dpai.android.AiSummary
import com.dpsmpx.dpai.android.MainActivity
import com.dpsmpx.dpai.android.PultAsk
import com.dpsmpx.dpai.android.PultCommands
import com.dpsmpx.dpai.android.PultRoutes
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.PultActivity
import com.dpsmpx.dpmeta.app.screens.AskActions
import com.dpsmpx.dpmeta.app.screens.CommandActions
import com.dpsmpx.dpmeta.app.screens.RouteActions
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
        // Новый клон пульта — под той же защитой места, что загрузки и установки «AI» (D-113).
        val app = applicationContext
        com.dpsmpx.dpmeta.app.data.PultRepository.spaceCheck = { repository, need ->
            com.dpsmpx.dpai.android.StorageSpace.check(app, com.dpsmpx.dpai.storage.space.SpaceGuard.Kind.CLONE, need, repository)
        }
    }

    // Окно «AI» — одно: уже открытое выходит вперёд, второго экземпляра нет (UX-1).
    override fun aiIntent(): Intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    // Со строки «AI»: ждут ответа — сразу на «Запросы» (D-76); иначе есть проблемы — на «Проверку» (D-123).
    override fun aiIntent(requests: Boolean): Intent = lastAi?.request?.takeIf { requests }?.let { AiSummary.open(this, it) }
        ?: lastAi?.takeIf { it.problems > 0 }?.let { MainActivity.check(this) }
        ?: aiIntent()

    // Настройки — одним документом (D-112): «Оформление», «Хранилище и секреты», «AI» — подсистемы, блок пульта — слотом.
    override val settingsDocument: @androidx.compose.runtime.Composable (query: String, projects: @androidx.compose.runtime.Composable () -> Unit) -> Unit =
        { query, projects -> com.dpsmpx.dpai.android.SettingsDocument(projects, query) { sub -> startActivity(MainActivity.settings(this, sub)) } }

    // Пункт «IDE» левой панели (D-112): окно «AI» на «Файлах» — дерево, редактор, терминал проекта.
    override fun ideIntent(): Intent = MainActivity.ide(this)

    // Проекты без GitHub (D-150): рабочие проекты контроллера и мастер нового — в окне «AI».
    override fun localProjects(): List<com.dpsmpx.dpmeta.app.screens.Screens.LocalProject> {
        val controller = com.dpsmpx.dpai.android.Controller.get(filesDir)
        val targets = controller.targets().associateBy { it.id }
        val book = controller.projects()
        return book.projects.map { p ->
            com.dpsmpx.dpmeta.app.screens.Screens.LocalProject(
                p.project.value, p.root.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { p.root },
                targets[p.target]?.let { if (it.inApp) "телефон" else it.label } ?: "target удалён", p.root, p.project == book.current?.project,
            )
        }
    }

    override fun newProjectIntent(): Intent = MainActivity.newProject(this)

    override fun linuxIntent(): Intent = MainActivity.settings(this, "linux")

    override fun publishPush(projectId: String, url: String, token: com.dpsmpx.dpai.core.diagnostics.Secret): Outcome<String> =
        com.dpsmpx.dpai.android.PultProjects.publish(applicationContext, projectId, url, token)

    override fun openProjectIntent(id: String): Intent? {
        val pid = com.dpsmpx.dpai.core.ids.IdKinds.PROJECT.parse(id).valueOrNull() ?: return null
        if (com.dpsmpx.dpai.android.Controller.get(filesDir).selectProject(pid) is Outcome.Failure) return null
        return MainActivity.workspace(this)
    }

    // Документ проекта пульта в IDE (DPmetaApp:D-177): клон пульта — рабочий проект подсистемы «AI».
    override fun workingProjectAt(guestRoot: String): String? = com.dpsmpx.dpai.android.PultProjects.find(applicationContext, guestRoot)

    override fun adoptClone(guestRoot: String, branch: String): Outcome<String> =
        com.dpsmpx.dpai.android.PultProjects.adopt(applicationContext, guestRoot, branch)

    override fun openFileIntent(id: String, path: String): Intent? {
        val pid = com.dpsmpx.dpai.core.ids.IdKinds.PROJECT.parse(id).valueOrNull() ?: return null
        if (com.dpsmpx.dpai.android.Controller.get(filesDir).selectProject(pid) is Outcome.Failure) return null
        return MainActivity.openFile(this, id, path)
    }

    override fun aiVersion(): String = com.dpsmpx.dpai.android.BuildConfig.DPAI_VERSION

    /** Строки пульта — в журнал приложения подсистемы «AI» (D-39: один журнал отладки на приложение; там же Redactor). */
    override fun debugLog(tag: String, line: String) = com.dpsmpx.dpai.android.AppLogs.info(applicationContext, tag, line)

    override fun aiCounts(): Screens.AiCounts = AiSummary.read(this).also { lastAi = it }.let { Screens.AiCounts(it.configured, it.waiting, it.running, it.nearLimit, it.problems) }

    /** Маршруты Claude Code (F-22) — из подсистемы «AI» (`DP_AI_Orcestrator:D-116`): токен остаётся там. */
    override fun routes(): List<RouteActions.Route> =
        PultRoutes.list(applicationContext).map { RouteActions.Route(it.id, it.name, it.project, it.brief, it.hasToken, it.subscription, it.color) }

    /** Запуск маршрута: цену и журнал действия уже прошёл пульт (`:core:actions`); здесь — только вызов. */
    override fun fireRoute(id: String, text: String): Outcome<RouteActions.Fired> =
        when (val r = PultRoutes.fire(applicationContext, id, text)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(RouteActions.Fired(r.value.first, r.value.second))
        }

    /** Команды реестра (`DPmetaApp:D-82`): Linux-среда — подсистемы «AI»; что запускать и уровень решает пульт. */
    override fun commandLinux(): CommandActions.Linux = when (PultCommands.linux(applicationContext)) {
        "ready" -> CommandActions.Linux.READY
        "absent" -> CommandActions.Linux.NOT_INSTALLED
        else -> CommandActions.Linux.UNSUPPORTED
    }

    override fun commandMissing(packages: List<String>): List<String> = PultCommands.missing(applicationContext, packages)

    override fun commandInstall(packages: List<String>): Outcome<List<String>> = PultCommands.install(applicationContext, packages)

    override fun commandRun(
        dir: String,
        argv: List<String>,
        minutes: Int,
        onLine: (String) -> Unit,
        onStop: (() -> Unit) -> Unit,
    ): Outcome<CommandActions.Exit> = when (val r = PultCommands.run(applicationContext, dir, argv, minutes, onLine, onStop)) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(CommandActions.Exit(r.value.code, r.value.stopped, r.value.timedOut))
    }

    /** «Спросить Claude» (F-21): профили Anthropic и ход с инструментами — у подсистемы «AI» (`DP_AI_Orcestrator:D-118`). */
    override fun askProfiles(): List<AskActions.Profile> =
        PultAsk.profiles(applicationContext).map { AskActions.Profile(it.id, it.name, it.model, it.hasKey, it.color) }

    override fun askModels(profile: String): Outcome<List<AskActions.Model>> =
        when (val r = PultAsk.models(applicationContext, profile)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.map { AskActions.Model(it.first, it.second) })
        }

    override fun ask(
        request: AskActions.Request,
        onCancel: (() -> Unit) -> Unit,
        onText: (String) -> Unit,
        tool: (String, JsonObject) -> Pair<String, Boolean>,
    ): Outcome<AskActions.Answer> {
        val r = PultAsk.ask(
            applicationContext, request.profile, request.model, request.effort, request.system,
            request.turns.map { PultAsk.Turn(it.user, it.text) },
            request.tools.map { PultAsk.Tool(it.name, it.description, it.schema) },
            onCancel, onText, tool,
        )
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(
                AskActions.Answer(
                    r.value.text,
                    when (r.value.stop) {
                        "end" -> AskActions.Stop.END
                        "length" -> AskActions.Stop.LENGTH
                        "refusal" -> AskActions.Stop.REFUSAL
                        else -> AskActions.Stop.OTHER
                    },
                    r.value.stopReason, r.value.inputTokens, r.value.outputTokens,
                ),
            )
        }
    }
}

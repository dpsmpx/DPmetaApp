package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.files.SubTree
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.ToolCall
import com.dpsmpx.dpai.providers.ToolResult
import com.dpsmpx.dpai.providers.ToolSpec
import com.dpsmpx.dpai.ui.ApiProjectFiles
import com.dpsmpx.dpai.ui.ApiQuickStart
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ManualRunView
import com.dpsmpx.dpai.ui.ManualStep
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.WorkspacePaths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Связь API-чата с существующей файловой системой и защитой проекта, без второго планировщика (D-214). */
internal object ApiProjectRuntime {
    val changed = kotlinx.coroutines.flow.MutableStateFlow(0)
    data class Choice(val key: String, val title: String, val root: String, val branch: String, val project: ProjectId? = null)

    fun choices(context: Context): Outcome<List<Choice>> {
        val controller = Controller.get(context.filesDir)
        val registered = controller.projects().projects
        val rows = registered.map { Choice(it.project.value, it.root.substringAfterLast('/').substringAfterLast('\\'), it.root, it.integrationBranch, it.project) }.toMutableList()
        val projects = GuestPaths.home(context).resolve("projects")
        if (projects.isDirectory) {
            val clones = when (val r = ApiQuickStart.clones(LocalTree(projects.toPath()))) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            val localIds = controller.targets().filter { it.inApp }.map { it.id }.toSet()
            for (c in clones) if (registered.none { it.target in localIds && it.root.trimEnd('/') == c.root }) rows += Choice("clone:${c.relative}", c.relative, c.root, c.branch)
        }
        return Outcome.success(rows)
    }

    /** Служебные поля выводятся из выбранного клона; существующая привязка не дублируется. */
    fun prepare(context: Context, choice: Choice): Outcome<ProjectEntry> {
        val controller = Controller.get(context.filesDir)
        val current = choices(context).valueOrNull()?.firstOrNull { it.key == choice.key && it.root == choice.root && it.branch == choice.branch }
            ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "список проектов изменился; обновите мастер")
        val old = current.project?.let { id -> controller.projects().projects.firstOrNull { it.project == id } }
        val needsLinux = old == null || controller.targets().firstOrNull { it.id == old.target }?.inApp == true
        if (needsLinux && LinuxSetup.environment(context).installed() == null) {
            StorageMap.prepare(context)
            when (val r = LinuxSetup.install(context)) { is Outcome.Failure -> return r; is Outcome.Success -> Unit }
            StorageMap.prepare(context)
        }
        if (old != null) return when (val r = controller.selectProject(old.project)) { is Outcome.Failure -> r; is Outcome.Success -> Outcome.success(old) }
        val target = controller.targets().firstOrNull { it.inApp && it.configDir != null } ?: when (val r = controller.addInApp(IdGenerator(Clock.SYSTEM, RandomSource.secure()).next(IdKinds.TARGET))) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val root = current.root.trimEnd('/')
        val worktrees = root.substringBeforeLast('/') + "/.trees/" + root.substringAfterLast('/')
        val project = when (val r = controller.projectForm(target, root, worktrees, current.branch, "", claudeOn = target)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        return when (val r = controller.putProject(project)) { is Outcome.Failure -> r; is Outcome.Success -> Outcome.success(project) }
    }

    fun open(context: Context, id: ProjectId): Outcome<Session> {
        val controller = Controller.get(context.filesDir)
        val project = controller.projects().projects.firstOrNull { it.project == id }
            ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "проект разговора удалён; выберите проект в мастере")
        val model = DemoModel.get(context)
        val view = when (val r = model.apiWorkspace(project)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val target = view.target ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "место проекта удалено")
        val tree: FileTree = if (target.inApp) {
            val relative = WorkspacePaths.homeRelative(target, project.root) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "каталог проекта вне домашней папки")
            SubTree(GuestPaths.homeTree(context), relative)
        } else when (val r = model.pcFiles(target, project.root, writable = true)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        return Outcome.success(Session(context.applicationContext, model, project, view.main, tree))
    }

    class Session(private val context: Context, private val model: DemoModel, val project: ProjectEntry,
        private val location: com.dpsmpx.dpai.ui.WorkspaceLocation, private val tree: FileTree) {
        private val files = ApiProjectFiles(tree,
            { path, sha, text -> model.workspaceChange(project, location) { it.write(project.project.value, location.key, tree, path, sha, text) } },
            { path, sha -> model.workspaceChange(project, location) { it.delete(project.project.value, location.key, tree, path, sha) } })
        val tools: List<ToolSpec> = ApiProjectFiles.tools().map { ToolSpec(it.first, it.second, it.third) } + ToolSpec("run_profile",
            "Запустить CHECK или BUILD выбранного проекта через штатные профили; получить настоящий итог и вывод. Не публикует и не выполняет произвольный shell.",
            JsonObject.of("type" to JsonString("object"), "properties" to JsonObject.of("kind" to JsonObject.of("type" to JsonString("string"), "enum" to JsonArray(listOf(JsonString("CHECK"), JsonString("BUILD"))))), "required" to JsonArray(listOf(JsonString("kind")))))

        fun system(): String = buildString {
            append("Ты работаешь над выбранным проектом ${project.root}, ветка ${project.integrationBranch}. Используй инструменты для чтения и изменения его файлов. ")
            append("Перед записью прочитай актуальный файл и его sha256. Не выдавай текст ответа за изменение файла, запуск тестов или успешную сборку: опирайся на результаты инструментов. ")
            append("Служебные каталоги и секреты недоступны. Создание, замена и удаление сохраняют копию для отмены. После правок запусти подходящий профиль и сообщи результат.\n")
            for (path in listOf("AGENTS.md", "CLAUDE.md")) {
                val text = tree.read(path, 64 * 1024).valueOrNull()?.let { com.dpsmpx.dpai.files.TextFile.decode(it).valueOrNull() } ?: continue
                append("\nИнструкции проекта из $path:\n"); append(text.take(12_000))
                if (text.length > 12_000) append("\nПродолжение прочитай read_file.\n")
            }
        }

        fun run(call: ToolCall, cancel: Cancel): ToolResult {
            if (cancel.cancelled) return ToolResult("ход прерван", true)
            val result = if (call.name == "run_profile") profile(call.input, cancel) else model.workspaceAccess(project, location, write = false) { files.run(call.name, call.input) }
            return when (result) {
                is Outcome.Failure -> ToolResult(result.diagnostics.joinToString("; ") { it.message }.take(20_000), true)
                is Outcome.Success -> {
                    if (call.name in setOf("write_file", "replace_text", "delete_file")) {
                        val path = (call.input["path"] as? JsonString)?.value.orEmpty()
                        val absolute = project.root.trimEnd('/') + "/" + path
                        val text = if (call.name == "delete_file") null else tree.read(path, com.dpsmpx.dpai.files.TextFile.MAX_EDIT_BYTES).valueOrNull()?.let { com.dpsmpx.dpai.files.TextFile.decode(it).valueOrNull() }
                        if (call.name == "delete_file" || text != null) CodeService.projectFilesChanged(absolute, text, call.name == "write_file" && call.input["sha256"] !is JsonString,
                            context.getString(R.string.api_work_file_deleted))
                        else CodeService.dependenciesChanged(project.root, context.getString(R.string.api_work_lsp_refresh_failed))
                        Drafts.reloadWorkspace(location, tree, model.workspace()?.target?.inApp == true)
                        changed.value = changed.value + 1
                    }
                    ToolResult(result.value)
                }
            }
        }

        private fun profile(input: JsonObject, cancel: Cancel): Outcome<String> {
            val kind = when ((input["kind"] as? JsonString)?.value) { "CHECK" -> ManualProfileKind.CHECK; "BUILD" -> ManualProfileKind.BUILD; else -> return Outcome.failure(DiagCode.VALUE_INVALID, "профиль — CHECK или BUILD") }
            val existing = when (val r = model.manualProfile(project, kind)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            val chosen = existing ?: defaultProfile(kind) ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "для этого стека нет готового профиля; выберите шаблон проверки/сборки на рабочем экране")
            val previous = model.manualProfileView().finished
            val done = CompletableFuture<ManualRunView>()
            if (!model.startManualProfile(project, location, chosen, onFinished = { _, r -> done.complete(r) })) return Outcome.failure(DiagCode.WORKTREE_OWNED, "контур занят; дождитесь завершения команды")
            ControllerService.resume(context)
            val deadline = Clock.SYSTEM.now().epochMillis + chosen.steps.sumOf { it.timeoutSeconds } * 1000 + 60_000
            while (Clock.SYSTEM.now().epochMillis < deadline) {
                if (cancel.cancelled) { model.stopManualProfile(); return Outcome.failure(DiagCode.CANCELLED_BY_USER, "запрошена остановка команды; пауза сохраняется до подтверждения её конца") }
                val view = try { done.get(1, TimeUnit.SECONDS) } catch (_: TimeoutException) { continue }
                if (view.finished == null || view.finished == previous) return Outcome.failure(DiagCode.STATE_CONFLICT, view.note)
                val output = CanonicalJson.write(JsonObject.of("complete" to JsonBool(view.complete), "note" to JsonString(view.note), "output" to JsonString(view.output.takeLast(16_000))))
                return if (view.complete) Outcome.success(output) else Outcome.failure(DiagCode.STATE_CONFLICT, output)
            }
            model.stopManualProfile()
            return Outcome.failure(DiagCode.PROCESS_TIMEOUT, "ожидание профиля истекло; остановка запрошена, результат ещё не подтверждён")
        }

        private fun defaultProfile(kind: ManualProfileKind): ManualProfile? {
            val root = tree.list("").valueOrNull()?.map { it.name }?.toSet() ?: return null
            val argv = when {
                "gradlew" in root -> listOf("sh", "gradlew", if (kind == ManualProfileKind.CHECK) "check" else "assemble")
                "gradlew.bat" in root -> listOf("cmd.exe", "/c", "gradlew.bat", if (kind == ManualProfileKind.CHECK) "check" else "assemble")
                "pyproject.toml" in root || "requirements.txt" in root || root.any { it.endsWith(".py") } -> if (kind == ManualProfileKind.CHECK) listOf("python3", "-m", "unittest", "discover") else listOf("python3", "-m", "compileall", "-q", ".")
                else -> return null
            }
            return ManualProfile(kind, listOf(ManualStep(if (kind == ManualProfileKind.CHECK) "Проверка проекта" else "Сборка проекта", argv, 1800)))
        }
    }
}

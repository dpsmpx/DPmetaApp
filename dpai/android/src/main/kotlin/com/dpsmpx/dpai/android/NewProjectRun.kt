package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.KotlinRuntime
import com.dpsmpx.dpai.ui.NewProject
import com.dpsmpx.dpai.ui.PcPreview
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.TargetEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.minutes

/** Мост пульта к рабочим проектам (`DPmetaApp:D-150`): отправка ветки после «Опубликовать». */
object PultProjects {
    fun publish(context: Context, projectId: String, url: String, token: com.dpsmpx.dpai.core.diagnostics.Secret): Outcome<String> =
        NewProjectRun.publish(context, DemoModel.get(context), projectId, url, token)
}

/** Запрос открыть мастер нового проекта — из любой вкладки, пульта и окна знакомства (`DPmetaApp:D-150`). */
internal object NewProjectRequests {
    val open = MutableStateFlow(false)

    fun request() {
        open.value = true
    }
}

/**
 * Исполнение мастера нового проекта (`DPmetaApp:D-150`; D-181): место, проверка
 * инструментов, установка в Linux приложения, файлы, первый коммит и рабочий
 * проект. Звать не из главного потока.
 */
internal object NewProjectRun {
    /** Место проекта: Linux приложения (target — его слот, если есть) или target по SSH. */
    sealed interface Place {
        val key: String
        val title: String
        val platform: TargetPlatform

        data class Phone(val slot: TargetEntry?) : Place {
            override val key = PHONE
            override val title = "Телефон — Linux приложения"
            override val platform = TargetPlatform.POSIX
        }

        data class Remote(val target: TargetEntry) : Place {
            override val key: String get() = target.id.value
            override val title: String get() = target.label
            override val platform: TargetPlatform get() = target.platform
            val termux: Boolean get() = target.type == TargetType.TERMUX_LOCAL
        }
    }

    /** Место и причина, по которой оно сейчас не годится (`null` — годится). */
    data class Option(val place: Place, val problem: String?)

    const val PHONE = "phone"

    /** Где можно создать проект: телефон — первым, затем Termux и ПК. */
    fun places(context: Context, targets: List<TargetEntry>): List<Option> {
        val linux = when {
            !LinuxSetup.available(context) -> "Linux приложения на этом устройстве не работает (нужен arm64) — выберите Termux или ПК"
            LinuxSetup.environment(context).installed() == null -> "сначала установите Linux приложения"
            else -> null
        }
        val slot = targets.filter { it.inApp }.let { s -> s.firstOrNull { it.executor == com.dpsmpx.dpai.core.ids.Executor.CLAUDE } ?: s.firstOrNull() }
        val remote = targets.filter { !it.inApp && it.type in REMOTE }.map { t ->
            Option(Place.Remote(t), if (t.configDir == null) "у target нет каталога ИИ — задайте его на вкладке «Targets»" else null)
        }
        return listOf(Option(Place.Phone(slot), linux)) + remote
    }

    private val REMOTE = setOf(TargetType.TERMUX_LOCAL, TargetType.LINUX_SSH, TargetType.WINDOWS_REMOTE)

    /** Причина, по которой шаблон нельзя создать в [place]; `null` — можно. */
    fun unavailable(template: NewProject.Template, place: Place): String? =
        NewProject.unavailable(template, inApp = place is Place.Phone, termux = (place as? Place.Remote)?.termux == true)

    /** Gradle wrapper из APK; нет (сборка без него) — `null`. */
    fun wrapper(context: Context): NewProject.GradleWrapper? = try {
        fun read(name: String) = context.assets.open("newproject/$name").use { it.readBytes() }
        NewProject.GradleWrapper(read("gradlew"), read("gradlew.bat"), read("gradle-wrapper.jar"), read("gradle-wrapper.properties"))
    } catch (_: java.io.IOException) {
        null
    }

    private fun host(context: Context, model: DemoModel, place: Place): Outcome<ProcessHost> = when (place) {
        is Place.Phone -> LinuxSetup.guestHost(context)?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "Linux приложения не установлен")
        is Place.Remote -> model.pcHost(place.target)
    }

    /** Недостающие инструменты шаблона в [place]. */
    fun probe(context: Context, model: DemoModel, place: Place, template: NewProject.Template): Outcome<List<String>> {
        val h = when (val r = host(context, model, place)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val argv = NewProject.probe(template, place.platform, inApp = place is Place.Phone)
        return when (val r = h.run(ProcessSpec(argv), 2.minutes, maxStdoutChars = OUTPUT)) {
            is Outcome.Failure -> r
            is Outcome.Success -> NewProject.missing(r.value.stdout)?.let { Outcome.success(it) }
                ?: Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "проверка инструментов не завершилась: " + (r.value.stdout + r.value.stderr).takeLast(5).joinToString(" ").take(300))
        }
    }

    /**
     * Поставить инструменты шаблона в Linux приложения: пакеты Alpine (под защитой
     * места, D-113) и kotlinc приложения для консольного Kotlin.
     */
    fun install(context: Context, template: NewProject.Template): LongJobs.Done {
        when (val r = LinuxSetup.apk(context, LinuxEnvironment.Apk.ADD, NewProject.alpinePackages(template))) {
            is Outcome.Failure -> return LongJobs.Done(r.errors.first().message, failed = true)
            is Outcome.Success -> if (!r.value.ok) return LongJobs.Done("Пакеты не поставлены: код ${r.value.exitCode}", (r.value.stdout + r.value.stderr).takeLast(30), failed = true)
        }
        if (NewProject.needsKotlinc(template)) {
            val spec = ProcessSpec(KotlinRuntime.installCompiler("/root", "/root/Main.kt", force = false))
            when (val r = LinuxSetup.runSteps(context, listOf(spec))) {
                is Outcome.Failure -> return LongJobs.Done(r.errors.first().message, failed = true)
                is Outcome.Success -> if (!r.value.ok) return LongJobs.Done("kotlinc не поставлен: код ${r.value.exitCode}", (r.value.stdout + r.value.stderr).takeLast(30), failed = true)
            }
        }
        return LongJobs.Done("Инструменты для шаблона «${template.title}» поставлены.")
    }

    /** Итог создания: [entry] — рабочий проект; [root] — его папка на target. */
    data class Created(val entry: ProjectEntry, val root: String)

    /**
     * Создать проект: файлы (если [resume] — уже записаны прошлой попыткой), первый
     * коммит, рабочий проект с профилями. [step] — что идёт, для экрана.
     */
    fun create(
        context: Context,
        model: DemoModel,
        place: Place,
        spec: NewProject.Spec,
        parent: String,
        identity: NewProject.GitIdentity?,
        resume: Boolean,
        step: (String) -> Unit,
        written: () -> Unit,
    ): Outcome<Created> {
        val controller = Controller.get(context.filesDir)
        val target = when (place) {
            is Place.Remote -> place.target
            is Place.Phone -> place.slot ?: run {
                step("Добавляю слот в Linux приложения…")
                val id = IdGenerator(Clock.SYSTEM, RandomSource.secure()).next(IdKinds.TARGET)
                when (val r = controller.addInApp(id)) {
                    is Outcome.Failure -> return r
                    is Outcome.Success -> r.value
                }
            }
        }
        val files = when (val f = NewProject.files(spec, wrapper(context))) {
            is Outcome.Failure -> return f
            is Outcome.Success -> f.value
        }
        val rel = NewProject.normalizeParent(parent) + "/" + spec.name
        // Дерево файлов: в Linux приложения — домашняя папка гостя, иначе SFTP от домашней target.
        val home: String = when (place) {
            is Place.Phone -> {
                if (!resume) writeFiles(GuestPaths.homeTree(context), rel, files, step).let { if (it is Outcome.Failure) return it }
                GUEST_HOME
            }
            is Place.Remote -> {
                step("Подключаюсь к ${target.label}…")
                val sftp = when (val s = model.pcFiles(target, "", writable = true)) {
                    is Outcome.Failure -> return s
                    is Outcome.Success -> s.value
                }
                sftp.use { tree ->
                    val raw = when (val h = tree.home()) {
                        is Outcome.Failure -> return h
                        is Outcome.Success -> h.value
                    }
                    if (!resume) writeFiles(tree, rel, files, step).let { if (it is Outcome.Failure) return it }
                    PcPreview.localPath(target.platform, raw).trimEnd('/')
                }
            }
        }
        written()
        val root = NewProject.root(home, parent, spec.name)
        step("Первый коммит Git…")
        val h = when (val r = host(context, model, place)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        when (val r = h.run(ProcessSpec(NewProject.gitInit(target.platform, root, identity)), 2.minutes, maxStdoutChars = OUTPUT)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> if (!r.value.ok || r.value.stdout.none { it.trim() == NewProject.OK_MARKER }) {
                return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, NewProject.gitProblem((r.value.stdout + r.value.stderr).joinToString("\n")), root)
            }
        }
        step("Добавляю рабочий проект…")
        val commands = NewProject.commands(spec, target.platform, inApp = target.inApp)
        val check = NewProject.checkText(commands.verify).orEmpty()
        val entry = when (val f = controller.projectForm(target, root, NewProject.worktreeRoot(home, parent, spec.name), NewProject.BRANCH, check, claudeOn = target)) {
            is Outcome.Failure -> return f
            is Outcome.Success -> f.value
        }
        controller.putProject(entry).let { if (it is Outcome.Failure) return it }
        // Профили — не обязательны для самого проекта: отказ виден, проект остаётся.
        val profiles = controller.manualProfiles
        listOfNotNull(commands.build, commands.check).forEach { p ->
            (profiles.save(entry, p) as? Outcome.Failure)?.let { return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "проект добавлен, но профиль «${p.kind.name}» не сохранён: ${it.errors.first().message}") }
        }
        commands.run?.let { r ->
            (profiles.saveRun(entry, r) as? Outcome.Failure)?.let { return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "проект добавлен, но профиль запуска не сохранён: ${it.errors.first().message}") }
        }
        return Outcome.success(Created(entry, root))
    }

    /**
     * «Опубликовать на GitHub» (`DPmetaApp:D-150`): после того как пульт создал репозиторий —
     * remote `origin` = [url] и отправка ветки проекта [projectId]. В Linux приложения токен
     * [token] получает только этот процесс Git (окружение гостя, как у выданных секретов,
     * D-144); на Termux и ПК — вход Git самого target. Итог — строка для экрана.
     */
    fun publish(context: Context, model: DemoModel, projectId: String, url: String, token: com.dpsmpx.dpai.core.diagnostics.Secret): Outcome<String> {
        val controller = Controller.get(context.filesDir)
        val project = controller.projects().projects.firstOrNull { it.project.value == projectId }
            ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "рабочий проект не найден", projectId)
        val target = controller.targets().firstOrNull { it.id == project.target }
            ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "target проекта удалён", project.root)
        val argv = NewProject.publish(target.platform, project.root, url)
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "адрес репозитория — не GitHub", url)
        val place: Place = if (target.inApp) Place.Phone(target) else Place.Remote(target)
        val host = when (val h = host(context, model, place)) {
            is Outcome.Failure -> return h
            is Outcome.Success -> h.value
        }
        val env = if (target.inApp) {
            com.dpsmpx.dpai.execution.linux.ProotHost.GUEST_ENVIRONMENT +
                com.dpsmpx.dpai.orchestrator.linux.GuestSecrets.environment(listOf(com.dpsmpx.dpai.orchestrator.linux.GuestSecrets.Issued("GH_TOKEN", token)))
        } else null
        return when (val r = host.run(ProcessSpec(argv, environment = env), 10.minutes, maxStdoutChars = OUTPUT)) {
            is Outcome.Failure -> r
            is Outcome.Success -> NewProject.publishProblem(r.value.stdout + r.value.stderr)?.let { Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, it, project.root) }
                ?: Outcome.success("ветка ${NewProject.BRANCH} отправлена в $url")
        }
    }

    /** Записать файлы в [rel]; непустая папка — отказ до записи. */
    private fun writeFiles(tree: FileTree, rel: String, files: List<NewProject.TemplateFile>, step: (String) -> Unit): Outcome<Unit> {
        val existing = tree.list(rel)
        if (existing is Outcome.Success && existing.value.isNotEmpty()) {
            return Outcome.failure(DiagCode.STATE_CONFLICT, "папка ~/$rel уже есть и не пуста — выберите другое имя или папку", rel)
        }
        step("Записываю файлы (${files.size})…")
        for (f in files) {
            (tree.write("$rel/${f.path}", f.bytes) as? Outcome.Failure)?.let { return it }
        }
        return Outcome.success(Unit)
    }

    private const val GUEST_HOME = "/root"
    private const val OUTPUT = 64 * 1024
}

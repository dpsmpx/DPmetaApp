package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.automation.AutomationEvent
import com.dpsmpx.dpai.automation.AutomationTrigger
import com.dpsmpx.dpai.automation.Workflow
import com.dpsmpx.dpai.automation.WorkflowEngine
import com.dpsmpx.dpai.automation.WorkflowStore
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.storage.vfs.NioVfs

/** Приватные сценарии используют тот же API-сеанс и исполнители; фон без гранта чата (D-222). */
internal object AutomationWorkflows {
    /** В занятом дереве задачи доступны проверки его владельца, без ручного запуска или смены target. */
    fun gate(context: Context, project: com.dpsmpx.dpai.ui.ProjectEntry): com.dpsmpx.dpai.automation.AutomationGate {
        AutomationEvents.reload(context, project.project)
        return com.dpsmpx.dpai.automation.AutomationGate { request, verify ->
        val s = store(context, project.project)
        val workflows = when (val r = s.list()) { is Outcome.Failure -> return@AutomationGate r; is Outcome.Success -> r.value }
        val wanted = workflows.filter { it.enabled && it.triggers.any { t -> t.type == request.trigger } }
        if (wanted.isEmpty()) return@AutomationGate Outcome.success(Unit)
        val controller = Controller.get(context.filesDir)
        val target = controller.targets().firstOrNull { it.id == project.target }
            ?: return@AutomationGate Outcome.failure(DiagCode.STATE_CONFLICT, "target сценария удалён")
        val revision = com.dpsmpx.dpai.ui.AutomationContext.revision(project, target)
        fun catalog(): Outcome<com.dpsmpx.dpai.automation.AutomationCatalog> {
            val disabled = when (val r = s.disabled()) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            val settings = when (val r = ProjectContexts.store(context, project, target).load()) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            return com.dpsmpx.dpai.ui.AutomationContext.bind(com.dpsmpx.dpai.ui.ContextAccess.bind(
                com.dpsmpx.dpai.automation.VerificationAutomations.describe(project.profile(), project.root), settings, disabled), project, target)
        }
        for (w in wanted) {
            if (w.context != revision) return@AutomationGate Outcome.failure(DiagCode.STATE_CONFLICT, "сценарий устарел: " + w.id)
            val available = when (val r = catalog()) { is Outcome.Failure -> return@AutomationGate r; is Outcome.Success -> r.value }
            val maximum = w.steps.map { step -> available.byId(step.id)?.timeoutSeconds }
            if (maximum.any { it == null } || maximum.filterNotNull().sum() > w.timeoutSeconds)
                return@AutomationGate Outcome.failure(DiagCode.VALUE_INVALID,
                    "для занятого дерева используйте штатные verification.* и бюджет не меньше суммы их таймаутов")
            val result = WorkflowEngine(s, Clock.SYSTEM).run(w.id, w.revision,
                AutomationEvent(request.event, request.trigger, revision, request.fingerprint), { false }, ::catalog,
                { _, _ ->
                    if (request.project != project.project || controller.projects().projects.none { it == project } ||
                        controller.targets().firstOrNull { it.id == target.id } != target)
                        Outcome.failure(DiagCode.STATE_CONFLICT, "контекст задачи изменился") else Outcome.success(Unit)
                },
                { step, stop, _ ->
                    if (stop()) Outcome.failure(DiagCode.CANCELLED_BY_USER, "проверка отменена")
                    else {
                        val level = com.dpsmpx.dpai.verification.VerifyLevel.entries.firstOrNull { "verification." + it.name.lowercase() == step.id }
                        if (level == null) Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "занятое дерево допускает только штатные проверки владельца")
                        else {
                            val report = verify(level)
                            Outcome.success(JsonObject.of("status" to JsonString(report.verdict.name),
                                "data" to report.toJson(), "root" to JsonString(request.root),
                                "task" to JsonString(request.task.value), "target" to JsonString(target.id.value)))
                        }
                    }
                })
            when (result) {
                is Outcome.Failure -> return@AutomationGate result
                is Outcome.Success -> if (result.value["status"] != JsonString("PASSED"))
                    return@AutomationGate Outcome.failure(DiagCode.VERIFICATION_FAILED, "сценарий перед действием не прошёл: " + w.title)
            }
        }
        Outcome.success(Unit)
    } }

    fun store(context: Context, project: ProjectId) = WorkflowStore(NioVfs(
        context.noBackupFilesDir.resolve("automation").resolve(project.value).toPath()))

    fun eventId(): String = IdGenerator(Clock.SYSTEM, RandomSource.secure()).next(IdKinds.OPERATION).value

    fun run(context: Context, project: ProjectId, id: String, revision: String, cancel: Cancel,
        event: AutomationEvent? = null, actor: ActorKind = ActorKind.USER, cli: Boolean = false, parent: LongJobs.Permit? = null,
        approvalOwner: String? = null,
        progress: (Int, Int) -> Unit = { _, _ -> }): Outcome<JsonObject> {
        val s = store(context, project)
        val opened = ApiProjectRuntime.open(context, project, approvalOwner ?: AutomationUiCalls.key(project), parent)
        val session = when (opened) { is Outcome.Failure -> return opened; is Outcome.Success -> opened.value }
        return session.use {
            val actual = event ?: AutomationEvent(eventId(), AutomationTrigger.MANUAL, session.contextRevision,
                Hashing.sha256(session.contextRevision).hex)
            if (actual.context != session.contextRevision) return@use Outcome.failure(DiagCode.STATE_CONFLICT, "контекст сценария изменился")
            WorkflowEngine(s, Clock.SYSTEM).run(id, revision, actual, { cancel.cancelled },
                catalog = { when (val r = session.catalog()) { is Outcome.Failure -> r; is Outcome.Success -> Outcome.success(r.value.catalog) } },
                authorize = { _, _ -> when (val r = session.catalog()) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> if (session.contextRevision != actual.context)
                        Outcome.failure(DiagCode.STATE_CONFLICT, "проект или target изменился") else Outcome.success(Unit)
                } },
                execute = { step, stopped, _ -> session.invokeAutomation(step.id, step.revision, step.parameters, Cancel(stopped), actor,
                    confirmProfiles = cli, workflow = true) },
                progress = progress)
        }
    }

    /** Сохранение из UI — CAS; импорт с диска/CLI принудительно выключен. */
    fun save(context: Context, project: ProjectId, input: JsonObject, expected: String?, imported: Boolean): Outcome<Unit> {
        val value = when (val r = Workflow.parse(input)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val old = store(context, project).workflow(value.id).valueOrNull()
        if (!value.enabled && old != null && old.copy(enabled = false) == value) {
            val result = store(context, project).save(value, expected)
            if (result.isSuccess) AutomationEvents.reload(context, project)
            return result
        }
        val opened = ApiProjectRuntime.open(context, project)
        val session = when (opened) { is Outcome.Failure -> return opened; is Outcome.Success -> opened.value }
        return session.use {
            if (value.context != session.contextRevision) return@use Outcome.failure(DiagCode.STATE_CONFLICT, "сценарий относится к другому проекту или target")
            val catalog = when (val r = session.catalog()) { is Outcome.Failure -> return@use r; is Outcome.Success -> r.value.catalog }
            if (value.steps.any { s -> catalog.byId(s.id)?.revision != s.revision })
                return@use Outcome.failure(DiagCode.STATE_CONFLICT, "инструмент сценария изменился или отсутствует")
            if (!imported && value.enabled) for (step in value.steps) {
                val entry = catalog.byId(step.id) ?: return@use Outcome.failure(DiagCode.STATE_CONFLICT, "инструмент отсутствует")
                if (!entry.available || entry.invocation != com.dpsmpx.dpai.automation.AutomationInvocation.DIRECT)
                    return@use Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, entry.reason ?: "нужен интерактивный экран инструмента")
                if (step.parameters.fields.keys.any { k -> entry.parameters.none { it.name == k } } ||
                    entry.parameters.any { p -> (p.required && p.name !in step.parameters.fields) ||
                        (step.parameters[p.name]?.let { it !is JsonString || !p.accepts(it.value) } == true) })
                    return@use Outcome.failure(DiagCode.VALUE_INVALID, "параметры сценария не соответствуют схеме: " + step.id)
            }
            val result = store(context, project).save(if (imported) value.copy(enabled = false) else value, expected)
            if (result.isSuccess) {
                AutomationEvents.reload(context, project)
                if (!imported && value.enabled && value.triggers.any { t ->
                    t.type in setOf(AutomationTrigger.SCHEDULE, AutomationTrigger.EVENT, AutomationTrigger.FILES_CHANGED)
                }) ControllerService.resume(context)
            }
            result
        }
    }

    /** Существующая служба вызывает без своего таймера; только явно включённые сценарии. */
    fun tick(context: Context) {
        AutomationEvents.tick(context)
    }

    /** Событие не вызывает команду само: выбирает сохранённые триггеры одного проекта. */
    fun dispatch(context: Context, project: ProjectId, type: AutomationTrigger, id: String, fingerprint: String,
        name: String = "", cancelled: () -> Boolean = { false }, parent: LongJobs.Permit? = null,
        approvalOwner: String? = null): Outcome<Unit> {
        val s = store(context, project)
        val workflows = when (val r = s.list()) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        for (w in workflows.filter { it.enabled && it.triggers.any { t -> t.type == type && (type != AutomationTrigger.EVENT || t.event == name) } }) {
            val event = AutomationEvent(id, type, w.context, fingerprint, name)
            val result = if (parent == null) AutomationUiCalls.awaitWorkflow(context, project, w.id, w.revision, event,
                Cancel(cancelled), approvalOwner, ActorKind.SYSTEM)
            else run(context, project, w.id, w.revision, Cancel(cancelled), event, parent = parent,
                approvalOwner = approvalOwner, actor = ActorKind.SYSTEM,
                progress = { at, total -> parent.let {
                    LongJobs.get(context).report(it.key, it.runId, context.getString(R.string.workflow_progress, at, total))
                } })
            when (result) {
                is Outcome.Failure -> return result
                is Outcome.Success -> if (result.value["status"] != JsonString("PASSED"))
                    return Outcome.failure(DiagCode.VERIFICATION_FAILED, "сценарий не подтвердил успех: " + w.title, w.id)
            }
        }
        return Outcome.success(Unit)
    }
}

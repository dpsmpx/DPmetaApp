package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.RepositoryId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep

/**
 * Проект контроллера (D-35): репозиторий на одном target, каталог рабочих
 * деревьев задач там же, ветка интеграции и шаги проверки. Задачи
 * проекта идут на его target: рабочие деревья — на его диске. Id проекта и
 * репозитория постоянны — по ним журнал узнаёт деревья после перезапуска.
 */
data class ProjectEntry(
    val project: ProjectId,
    val repository: RepositoryId,
    val target: ExecutionTargetId,
    val root: String,
    val worktreeRoot: String,
    val integrationBranch: String,
    val checks: List<VerifyStep>,
) {
    fun repository(): Repository = Repository(repository, target, root)

    /** Профиль проверки: шаги уровня NORMAL; без шагов — проверки нет, итог честно `COMPLETION_UNVERIFIED` (D-23). */
    fun profile(): VerificationProfile? = checks.takeIf { it.isNotEmpty() }?.let { VerificationProfile(project, mapOf(VerifyLevel.NORMAL to it)) }
}

/** Форма проекта: пути — абсолютные на target, ветка — без пробелов и `..`, проверка — команда строкой. */
object ProjectForm {
    private val BRANCH = Regex("[A-Za-z0-9._/-]{1,100}")

    fun validate(ids: IdGenerator, target: TargetEntry, root: String, worktreeRoot: String, branch: String, check: String): Outcome<ProjectEntry> {
        fun absolute(p: String) = if (target.platform == com.dpsmpx.dpai.execution.target.TargetPlatform.WINDOWS) Regex("""[A-Za-z]:[\\/].*""").matches(p) else p.startsWith("/")
        val r = root.trim()
        val w = worktreeRoot.trim()
        if (!absolute(r) || r.any { it.isISOControl() }) return Outcome.failure(DiagCode.VALUE_INVALID, "каталог репозитория — абсолютный путь на target", "root")
        if (!absolute(w) || w.any { it.isISOControl() } || w == r) return Outcome.failure(DiagCode.VALUE_INVALID, "каталог рабочих деревьев — абсолютный путь на target, не сам репозиторий", "worktreeRoot")
        val b = branch.trim()
        if (!BRANCH.matches(b) || ".." in b || b.startsWith("-")) return Outcome.failure(DiagCode.VALUE_INVALID, "ветка интеграции недопустима", "branch")
        val argv = check.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (argv.firstOrNull()?.startsWith("-") == true) return Outcome.failure(DiagCode.VALUE_INVALID, "команда проверки не начинается с «-»", "check")
        val steps = if (argv.isEmpty()) emptyList() else listOf(VerifyStep("проверка", argv))
        return Outcome.success(ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, r, w, b, steps))
    }
}

/** Проект приложения — документ `dpai.app-project` v1 (`docs/DATA_MODEL.md`). */
object ProjectBook {
    const val FORMAT = "dpai.app-project"
    const val VERSION = 1

    fun toJson(p: ProjectEntry): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            *FormatHeader.write(FORMAT, VERSION),
            "project" to JsonString(p.project.value), "repository" to JsonString(p.repository.value), "target" to JsonString(p.target.value),
            "root" to JsonString(p.root), "worktreeRoot" to JsonString(p.worktreeRoot), "integrationBranch" to JsonString(p.integrationBranch),
            "checks" to JsonArray(p.checks.map { s -> JsonObject(linkedMapOf("name" to JsonString(s.name), "command" to JsonArray(s.command.map(::JsonString)))) }),
        ),
    )

    fun fromJson(value: JsonValue): Outcome<ProjectEntry> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val project = f.string("project")?.let { IdKinds.PROJECT.parse(it).valueOrNull() }
        val repository = f.string("repository")?.let { IdKinds.REPOSITORY.parse(it).valueOrNull() }
        val target = f.string("target")?.let { IdKinds.TARGET.parse(it).valueOrNull() }
        val root = f.string("root")
        val worktreeRoot = f.string("worktreeRoot")
        val branch = f.string("integrationBranch")
        val checks = f.array("checks")?.items?.mapIndexedNotNull { i, v ->
            val o = v as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.checks[$i]") }
            val s = JsonFields(o, "$.checks[$i]", d)
            val name = s.string("name")
            val command = s.array("command")?.items?.map { (it as? JsonString)?.value }
            s.finish()
            if (name != null && command != null && command.isNotEmpty() && command.none { it == null }) VerifyStep(name, command.filterNotNull()) else null
        }
        f.finish()
        if (d.hasErrors || project == null || repository == null || target == null || root == null || worktreeRoot == null || branch == null || checks == null) {
            return Outcome.Failure(d.all.ifEmpty { listOf(com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.VALUE_INVALID, "проект не читается", "$")) })
        }
        return Outcome.success(ProjectEntry(project, repository, target, root, worktreeRoot, branch, checks))
    }
}

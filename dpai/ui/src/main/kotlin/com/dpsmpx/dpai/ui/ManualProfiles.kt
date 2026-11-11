package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.storage.document.DocumentStore
import kotlin.time.Duration.Companion.seconds

enum class ManualProfileKind { CHECK, BUILD }

data class ManualProfile(val kind: ManualProfileKind, val command: List<String>, val timeoutSeconds: Long) {
    fun toJson(): JsonObject = JsonObject.of("format" to JsonString("dpai.manual-profile"), "version" to JsonNumber.of(1),
        "kind" to JsonString(kind.name), "command" to JsonArray(command.map(::JsonString)), "timeout" to JsonNumber.of(timeoutSeconds))

    companion object {
        fun form(kind: ManualProfileKind, command: String, timeout: String): Outcome<ManualProfile> {
            val argv = ProjectForm.words(command)
            val seconds = timeout.toLongOrNull()
            if (argv == null || argv.isEmpty() || argv.size > 128 || argv.any { it.length > 8192 || '\u0000' in it } || command.length > 16384 || seconds == null || seconds !in 1..86400) {
                return Outcome.failure(DiagCode.VALUE_INVALID, "нужна команда с закрытыми кавычками и таймаут 1…86400 секунд")
            }
            return Outcome.success(ManualProfile(kind, argv, seconds))
        }

        fun read(o: JsonObject): ManualProfile? {
            if (o.text("format") != "dpai.manual-profile" || o.number("version") != 1L) return null
            val kind = ManualProfileKind.entries.firstOrNull { it.name == o.text("kind") } ?: return null
            val argv = (o["command"] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: return null } ?: return null
            val timeout = o.number("timeout") ?: return null
            return ManualProfile(kind, argv, timeout).takeIf { argv.isNotEmpty() && argv.size <= 128 && argv.sumOf { it.length } <= 16384 && argv.none { '\u0000' in it } && timeout in 1..86400 }
        }
    }
}

data class ManualRun(val id: SessionId, val project: ProjectEntry, val target: TargetEntry, val path: String, val branch: String, val profile: ManualProfile)
data class ManualRunView(val active: ManualRun?, val blocked: Boolean, val note: String, val output: String)

/**
 * Единственный ручной запуск и долговечная пауза всего контура (D-134).
 * Якорь неизменяем: восстановление никогда не повторяет start. Сетевые вызовы —
 * вне замка модели, один worker; короткие операции store синхронизированы.
 */
class ManualProfiles(private val docs: DocumentStore, private val ids: IdGenerator) {
    @Volatile private var view = load()
    private var sequence = 0L
    private var tail = ""
    val blocked: Boolean get() = view.blocked
    fun snapshot(): ManualRunView = view

    private fun load(): ManualRunView = when (val r = docs.read(ACTIVE)) {
        is Outcome.Failure -> ManualRunView(null, true, "не удалось прочитать активный профиль; контур на паузе", "")
        is Outcome.Success -> if (r.value == null) {
            val result = docs.read(RESULT).valueOrNull()?.content
            ManualRunView(null, false, result?.text("note").orEmpty(), result?.text("output").orEmpty())
        } else {
            val run = readRun(r.value.content)
            ManualRunView(run, true, if (run == null) "повреждён якорь профиля; контур на паузе" else "восстановлен запуск; нужна сверка с агентом", "")
        }
    }

    @Synchronized
    fun profile(project: ProjectEntry, kind: ManualProfileKind): Outcome<ManualProfile?> = when (val r = docs.read(key(project, kind))) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.value == null) Outcome.success(null) else ManualProfile.read(r.value.content)?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.STORAGE_CORRUPTED, "повреждён профиль ${kind.name}")
    }

    @Synchronized
    fun <T> edit(action: () -> Outcome<T>): Outcome<T> = if (blocked) Outcome.failure(DiagCode.WORKTREE_OWNED, "проект на паузе: выполняется ручной профиль") else action()

    @Synchronized
    fun save(project: ProjectEntry, profile: ManualProfile): Outcome<Unit> {
        if (blocked) return Outcome.failure(DiagCode.WORKTREE_OWNED, "профиль выполняется; настройки пока заблокированы")
        return when (val r = docs.write(key(project, profile.kind), profile.toJson())) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    /** Вызывается под замком контура после проверки свободного проекта и черновиков. */
    @Synchronized
    fun prepare(project: ProjectEntry, target: TargetEntry, location: WorkspaceLocation, profile: ManualProfile): Outcome<ManualRun> {
        if (blocked) return Outcome.failure(DiagCode.WORKTREE_OWNED, "ручной запуск уже держит контур на паузе")
        if (ManualProfile.read(profile.toJson()) == null || target.id != location.target || project.target != target.id) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный профиль или target")
        val run = ManualRun(ids.next(IdKinds.SESSION), project, target, location.path, location.branch, profile)
        val r = docs.write(ACTIVE, JsonObject.of("format" to JsonString("dpai.manual-run"), "version" to JsonNumber.of(1),
            "id" to JsonString(run.id.value), "project" to ProjectBook.toJson(project), "target" to TargetBook.toJson(listOf(target)),
            "path" to JsonString(run.path), "branch" to JsonString(run.branch), "profile" to profile.toJson()))
        // Даже частичная запись могла оставить якорь. При ошибке перечитать и блокировать при неопределённости.
        if (r is Outcome.Failure) { view = load(); return r }
        sequence = 0; tail = ""
        view = ManualRunView(run, true, "проект на паузе; запуск команды", "")
        return Outcome.success(run)
    }

    /** Только один раз, непосредственно после успешного prepare. После рестарта — только poll/stop. */
    fun launch(run: ManualRun, client: AgentClient) {
        when (val r = client.start(run.id, RunSpec(run.profile.command, run.path, timeout = run.profile.timeoutSeconds.seconds))) {
            is Outcome.Failure -> note("ответ на запуск потерян или получен отказ; нужна сверка: ${r.errors.first().message}")
            is Outcome.Success -> note("команда выполняется; проект на паузе")
        }
    }

    /** false: завершено; true: пауза остаётся. LOST/нет связи не равны завершению. */
    fun poll(client: AgentClient): Boolean {
        val run = view.active ?: return blocked
        when (val r = client.events(run.id, sequence, 100)) {
            is Outcome.Failure -> note("нужна сверка: ${r.errors.first().message}")
            is Outcome.Success -> {
                for (e in r.value.events) {
                    val text = (e.payload["text"] as? JsonString)?.value
                    if (text != null) tail = (tail + Redactor.STANDARD.redact(text).takeLast(MAX_OUTPUT) + "\n").takeLast(MAX_OUTPUT)
                    sequence = maxOf(sequence, e.sequence)
                }
                view = view.copy(output = tail)
            }
        }
        when (val r = client.status(run.id)) {
            is Outcome.Failure -> note("связь с запуском не подтверждена; доступна безопасная остановка: ${r.errors.first().message}")
            is Outcome.Success -> if (r.value.state == RunState.EXITED) {
                val why = when (r.value.reason) {
                    ExitReason.EXITED -> "код выхода ${r.value.exitCode ?: "неизвестен"}"
                    ExitReason.TIMEOUT -> "истёк таймаут"
                    ExitReason.STOPPED -> "остановлено"
                    else -> "запуск завершён: ${r.value.reason}"
                }
                return finish(client, run, why)
            } else if (r.value.state == RunState.LOST) note("агент потерял супервизор; требуется безопасная остановка")
        }
        return true
    }

    fun stop(client: AgentClient): Boolean {
        val run = view.active ?: return blocked
        return finish(client, run, "остановлено пользователем")
    }

    private fun finish(client: AgentClient, run: ManualRun, result: String): Boolean {
        when (val r = client.fenceStop(run.id)) {
            is Outcome.Failure -> { note("пауза сохраняется: ${r.errors.first().message}"); return true }
            is Outcome.Success -> Unit
        }
        synchronized(this) {
            val note = Redactor.STANDARD.redact("${run.profile.kind}: $result")
            val written = docs.write(RESULT, JsonObject.of("format" to JsonString("dpai.manual-result"), "version" to JsonNumber.of(1), "note" to JsonString(note), "output" to JsonString(tail)))
            if (written is Outcome.Failure) { note("не удалось записать итог; пауза сохраняется"); return true }
            val deleted = docs.delete(ACTIVE)
            if (deleted is Outcome.Failure) { note("не удалось снять паузу на диске"); return true }
            view = ManualRunView(null, false, note, tail)
        }
        return false
    }

    private fun note(text: String) { view = view.copy(note = Redactor.STANDARD.redact(text).take(1000)) }
    private fun key(p: ProjectEntry, kind: ManualProfileKind) = "${kind.name.lowercase()}-${p.project.value}"

    private fun readRun(o: JsonObject): ManualRun? {
        if (o.text("format") != "dpai.manual-run" || o.number("version") != 1L) return null
        val id = o.text("id")?.let { IdKinds.SESSION.parse(it).valueOrNull() } ?: return null
        val project = (o["project"] as? JsonObject)?.let { ProjectBook.fromJson(it).valueOrNull() } ?: return null
        val target = (o["target"] as? JsonObject)?.let { TargetBook.fromJson(it).valueOrNull()?.singleOrNull() } ?: return null
        val profile = (o["profile"] as? JsonObject)?.let(ManualProfile::read) ?: return null
        val path = o.text("path") ?: return null
        val branch = o.text("branch") ?: return null
        if (target.id != project.target || path.isEmpty() || path.any { it.code < 32 }) return null
        return ManualRun(id, project, target, path, branch, profile)
    }

    companion object {
        private const val ACTIVE = "active"
        private const val RESULT = "result"
        private const val MAX_OUTPUT = 65536
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonString)?.value
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonNumber)?.toLongExact()

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

/** Шаг профиля: имя для человека, argv без shell и таймаут шага в секундах. */
data class ManualStep(val name: String, val command: List<String>, val timeoutSeconds: Long) {
    val valid: Boolean
        get() = name.isNotBlank() && name.length <= MAX_NAME && name.none { it.isISOControl() } &&
            command.isNotEmpty() && command.size <= 128 && command.sumOf { it.length } <= 16384 && command.none { '\u0000' in it } &&
            timeoutSeconds in 1..86400

    companion object {
        const val MAX_NAME = 60
        const val DEFAULT_NAME = "Команда"
    }
}

/**
 * Ручной профиль CHECK/BUILD (D-134, D-154): шаги выполняются по порядку в одном
 * дереве, следующий — только после кода 0 предыдущего. Формат
 * `dpai.manual-profile` v2; v1 (одна команда) читается как один шаг.
 */
data class ManualProfile(val kind: ManualProfileKind, val steps: List<ManualStep>) {
    val valid: Boolean get() = steps.size in 1..MAX_STEPS && steps.all { it.valid }

    fun toJson(): JsonObject = JsonObject.of("format" to JsonString(FORMAT), "version" to JsonNumber.of(2),
        "kind" to JsonString(kind.name),
        "steps" to JsonArray(steps.map { JsonObject.of("name" to JsonString(it.name), "command" to JsonArray(it.command.map(::JsonString)), "timeout" to JsonNumber.of(it.timeoutSeconds)) }))

    /** Готовый набор шагов для частых сборок; человек правит и сохраняет сам. */
    data class Template(val title: String, val steps: List<Triple<String, String, String>>)

    companion object {
        const val FORMAT = "dpai.manual-profile"
        const val MAX_STEPS = 20

        /** Один шаг из строки команды — прежняя форма и тесты. */
        fun form(kind: ManualProfileKind, command: String, timeout: String): Outcome<ManualProfile> = form(kind, listOf(Triple(ManualStep.DEFAULT_NAME, command, timeout)))

        /** Шаги из полей экрана: (имя, команда, таймаут). Ошибка называет номер шага. */
        fun form(kind: ManualProfileKind, rows: List<Triple<String, String, String>>): Outcome<ManualProfile> {
            if (rows.isEmpty()) return Outcome.failure(DiagCode.FIELD_MISSING, "добавьте хотя бы один шаг")
            if (rows.size > MAX_STEPS) return Outcome.failure(DiagCode.VALUE_INVALID, "шагов — не больше $MAX_STEPS")
            val steps = rows.mapIndexed { i, (name, command, timeout) ->
                val argv = ProjectForm.words(command)
                val seconds = timeout.trim().toLongOrNull()
                val step = ManualStep(name.trim().ifEmpty { "Шаг ${i + 1}" }, argv.orEmpty(), seconds ?: 0)
                if (argv == null || argv.isEmpty() || command.length > 16384 || seconds == null || !step.valid) {
                    return Outcome.failure(DiagCode.VALUE_INVALID, "шаг ${i + 1}: нужна команда с закрытыми кавычками, имя до ${ManualStep.MAX_NAME} знаков и таймаут 1…86400 секунд")
                }
                step
            }
            return Outcome.success(ManualProfile(kind, steps))
        }

        fun read(o: JsonObject): ManualProfile? {
            if (o.text("format") != FORMAT) return null
            val kind = ManualProfileKind.entries.firstOrNull { it.name == o.text("kind") } ?: return null
            val steps = when (o.number("version")) {
                1L -> {
                    val argv = (o["command"] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: return null } ?: return null
                    listOf(ManualStep(ManualStep.DEFAULT_NAME, argv, o.number("timeout") ?: return null))
                }
                2L -> ((o["steps"] as? JsonArray)?.items ?: return null).map { v ->
                    val so = v as? JsonObject ?: return null
                    val argv = (so["command"] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: return null } ?: return null
                    ManualStep(so.text("name") ?: return null, argv, so.number("timeout") ?: return null)
                }
                else -> return null
            }
            return ManualProfile(kind, steps).takeIf { it.valid }
        }

        /** argv обратно в строку для поля: каждое слово в одинарных кавычках, если в нём есть что экранировать. */
        fun quote(argv: List<String>): String = argv.joinToString(" ") { arg ->
            if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in SAFE }) arg else arg.split('\'').joinToString("\"'\"") { "'$it'" }
        }

        private const val SAFE = "-_./=:,+@%"

        fun templates(kind: ManualProfileKind): List<Template> = when (kind) {
            ManualProfileKind.BUILD -> listOf(
                Template("CMake", listOf(
                    Triple("Настройка", "cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug -DCMAKE_EXPORT_COMPILE_COMMANDS=ON", "600"),
                    Triple("Сборка", "cmake --build build -j 2", "3600"),
                )),
                Template("Gradle", listOf(Triple("Сборка", "./gradlew assembleDebug", "3600"))),
                Template("Make", listOf(Triple("Сборка", "make -j2", "3600"))),
            )
            ManualProfileKind.CHECK -> listOf(
                Template("CTest", listOf(
                    Triple("Сборка", "cmake --build build -j 2", "3600"),
                    Triple("Тесты", "ctest --test-dir build --output-on-failure", "1800"),
                )),
                Template("Python", listOf(
                    Triple("Стиль", "ruff check .", "300"),
                    Triple("Тесты", "python3 -m pytest -q", "1800"),
                )),
                Template("Gradle", listOf(Triple("Тесты", "./gradlew test", "3600"))),
            )
        }
    }
}

/**
 * Запуск программы проекта (D-155): команда для терминала (с shell — человек
 * вводит её сам, как у ▶ редактора, D-138), папка от корня дерева и
 * «сначала собрать» — профилем «Сборка». Формат `dpai.run-profile` v1.
 */
data class RunProfile(val command: String, val dir: String, val buildFirst: Boolean) {
    val valid: Boolean get() = command.isNotBlank() && command.length <= MAX_COMMAND && '\u0000' !in command && dirProblem(dir) == null

    fun toJson(): JsonObject = JsonObject.of("format" to JsonString(FORMAT), "version" to JsonNumber.of(1),
        "command" to JsonString(command), "dir" to JsonString(dir), "buildFirst" to com.dpsmpx.dpai.core.serialization.JsonBool(buildFirst))

    /** Папка запуска на машине дерева: корень [root] и [dir] через `/`. */
    fun workDir(root: String): String = if (dir.isEmpty()) root else root.trimEnd('/') + "/" + dir

    companion object {
        const val FORMAT = "dpai.run-profile"
        const val MAX_COMMAND = 4096

        fun form(command: String, dir: String, buildFirst: Boolean): Outcome<RunProfile> {
            val d = dir.trim().trim('/')
            dirProblem(d)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it) }
            if (command.isBlank()) return Outcome.failure(DiagCode.FIELD_MISSING, "введите команду запуска: например, ./build/app")
            if (command.length > MAX_COMMAND || '\u0000' in command) return Outcome.failure(DiagCode.VALUE_INVALID, "команда — до $MAX_COMMAND знаков")
            return Outcome.success(RunProfile(command.trim(), d, buildFirst))
        }

        fun read(o: JsonObject): RunProfile? {
            if (o.text("format") != FORMAT || o.number("version") != 1L) return null
            val buildFirst = (o["buildFirst"] as? com.dpsmpx.dpai.core.serialization.JsonBool)?.value ?: return null
            return RunProfile(o.text("command") ?: return null, o.text("dir") ?: return null, buildFirst).takeIf { it.valid }
        }

        /** Папка — путь от корня дерева: без `..`, `.`, пустых частей, `\\` и управляющих знаков. */
        fun dirProblem(dir: String): String? = when {
            dir.isEmpty() -> null
            dir.length > 1024 -> "папка — до 1024 знаков"
            dir.startsWith("/") -> "папка — от корня дерева, без «/» в начале"
            dir.split('/').any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.isISOControl() || c == '\\' } } -> "папка — от корня дерева, без «..», «.» и пустых частей"
            else -> null
        }
    }
}

/** Итог шага: номер с единицы, код выхода (нет — не дождались) и причина словами. */
data class ManualStepResult(val step: Int, val exitCode: Int?, val reason: String) {
    val ok: Boolean get() = exitCode == 0
}

data class ManualRun(val id: SessionId, val project: ProjectEntry, val target: TargetEntry, val path: String, val branch: String, val profile: ManualProfile)

/**
 * [step] — какой шаг запрошен последним (`null` — неизвестно, см. D-154), [results] —
 * итоги завершённых шагов этого запуска.
 */
data class ManualRunView(
    val active: ManualRun?,
    val blocked: Boolean,
    val note: String,
    val output: String,
    val step: Int? = null,
    val results: List<ManualStepResult> = emptyList(),
    /** Последний завершённый в этом процессе запуск и прошли ли все его шаги с кодом 0 (D-155: «собрать и запустить»). */
    val finished: SessionId? = null,
    val complete: Boolean = false,
)

/**
 * Единственный ручной запуск и долговечная пауза всего контура (D-134).
 * Якорь неизменяем: восстановление никогда не повторяет start. Сетевые вызовы —
 * вне замка модели, один worker; короткие операции store синхронизированы.
 *
 * Шаги (D-154) — ходы одного запуска агента: шаг k — ход k. Перед start шага k
 * записывается намерение `intent = k` (`dpai.manual-progress`); start шага k
 * посылается, только если до этого намерения ещё не было. После перезапуска
 * намерение известно, и решение однозначно: ход k завершился с кодом 0 —
 * записать k+1 и запустить следующий шаг; агент не знает хода k, хотя
 * намерение записано, — неизвестно, дошёл ли start: сборка безопасно
 * останавливается (`fence-stop` закрывает запуск навсегда, опоздавший start не
 * пройдёт). Намерение потеряно или повреждено — новые шаги не запускаются.
 */
class ManualProfiles(private val docs: DocumentStore, private val ids: IdGenerator) {
    @Volatile private var view = load()
    private var sequence = 0L
    private var tail = ""
    val blocked: Boolean get() = view.blocked
    fun snapshot(): ManualRunView = view

    /** Намерение и итоги шагов запуска [run]; чужой, повреждённый или отсутствующий документ — намерение неизвестно. */
    private data class Progress(val intent: Int?, val results: List<ManualStepResult>)

    private fun progress(run: SessionId): Progress {
        val o = (docs.read(PROGRESS) as? Outcome.Success)?.value?.content ?: return Progress(null, emptyList())
        if (o.text("format") != "dpai.manual-progress" || o.number("version") != 1L || o.text("run") != run.value) return Progress(null, emptyList())
        val intent = o.number("intent")?.toInt()
        val results = (o["results"] as? JsonArray)?.items?.mapNotNull { v ->
            val r = v as? JsonObject ?: return@mapNotNull null
            ManualStepResult(r.number("step")?.toInt() ?: return@mapNotNull null, r.number("code")?.toInt(), r.text("reason").orEmpty())
        }.orEmpty()
        return Progress(intent?.takeIf { it >= 1 }, results)
    }

    private fun writeProgress(run: SessionId, intent: Int, results: List<ManualStepResult>): Outcome<Unit> {
        val doc = JsonObject.of("format" to JsonString("dpai.manual-progress"), "version" to JsonNumber.of(1), "run" to JsonString(run.value),
            "intent" to JsonNumber.of(intent),
            "results" to JsonArray(results.map { r ->
                val m = linkedMapOf<String, com.dpsmpx.dpai.core.serialization.JsonValue>("step" to JsonNumber.of(r.step), "reason" to JsonString(r.reason))
                r.exitCode?.let { m["code"] = JsonNumber.of(it) }
                JsonObject(m)
            }))
        return when (val w = docs.write(PROGRESS, doc)) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    private fun load(): ManualRunView = when (val r = docs.read(ACTIVE)) {
        is Outcome.Failure -> ManualRunView(null, true, "не удалось прочитать активный профиль; контур на паузе", "")
        is Outcome.Success -> if (r.value == null) {
            val result = docs.read(RESULT).valueOrNull()?.content
            ManualRunView(null, false, result?.text("note").orEmpty(), result?.text("output").orEmpty())
        } else {
            val run = readRun(r.value!!.content)
            val p = run?.let { progress(it.id) }
            ManualRunView(run, true, if (run == null) "повреждён якорь профиля; контур на паузе" else "восстановлен запуск; нужна сверка с агентом", "", p?.intent, p?.results.orEmpty())
        }
    }

    @Synchronized
    fun profile(project: ProjectEntry, kind: ManualProfileKind): Outcome<ManualProfile?> = when (val r = docs.read(key(project, kind))) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.value == null) Outcome.success(null) else ManualProfile.read(r.value!!.content)?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.STORAGE_CORRUPTED, "повреждён профиль ${kind.name}")
    }

    @Synchronized
    fun <T> edit(action: () -> Outcome<T>): Outcome<T> = if (blocked) Outcome.failure(DiagCode.WORKTREE_OWNED, "проект на паузе: выполняется ручной профиль") else action()

    @Synchronized
    fun save(project: ProjectEntry, profile: ManualProfile): Outcome<Unit> {
        if (blocked) return Outcome.failure(DiagCode.WORKTREE_OWNED, "профиль выполняется; настройки пока заблокированы")
        if (!profile.valid) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный профиль")
        return when (val r = docs.write(key(project, profile.kind), profile.toJson())) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    @Synchronized
    fun runProfile(project: ProjectEntry): Outcome<RunProfile?> = when (val r = docs.read(runKey(project))) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.value == null) Outcome.success(null) else RunProfile.read(r.value!!.content)?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.STORAGE_CORRUPTED, "повреждён профиль запуска")
    }

    @Synchronized
    fun saveRun(project: ProjectEntry, profile: RunProfile): Outcome<Unit> {
        if (!profile.valid) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный профиль запуска")
        return when (val r = docs.write(runKey(project), profile.toJson())) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    /** Вызывается под замком контура после проверки свободного проекта и черновиков. */
    @Synchronized
    fun prepare(project: ProjectEntry, target: TargetEntry, location: WorkspaceLocation, profile: ManualProfile): Outcome<ManualRun> {
        if (blocked) return Outcome.failure(DiagCode.WORKTREE_OWNED, "ручной запуск уже держит контур на паузе")
        if (ManualProfile.read(profile.toJson()) == null || target.id != location.target || project.target != target.id || location.project != project.project || location.repository != project.repository) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный профиль или target")
        val run = ManualRun(ids.next(IdKinds.SESSION), project, target, location.path, location.branch, profile)
        val anchor = JsonObject.of("format" to JsonString("dpai.manual-run"), "version" to JsonNumber.of(2),
            "id" to JsonString(run.id.value), "project" to ProjectBook.toJson(project), "target" to TargetBook.toJson(listOf(target)),
            "path" to JsonString(run.path), "branch" to JsonString(run.branch), "profile" to profile.toJson())
        if (readRun(anchor) == null) return Outcome.failure(DiagCode.VALUE_INVALID, "снимок проекта/target не прошёл проверку")
        val r = docs.write(ACTIVE, anchor)
        // Даже частичная запись могла оставить якорь. При ошибке перечитать и блокировать при неопределённости.
        if (r is Outcome.Failure) { view = load(); return r }
        sequence = 0; tail = ""
        view = ManualRunView(run, true, "проект на паузе; запуск команды", "")
        return Outcome.success(run)
    }

    /** Только один раз, непосредственно после успешного prepare. После рестарта — только poll/stop. */
    fun launch(run: ManualRun, client: AgentClient) {
        startStep(run, client, 1, emptyList())
    }

    /**
     * Записать намерение «шаг [step]» и только потом послать start. Не записалось —
     * start не посылается: без записи перезапуск не узнал бы, что шаг мог начаться.
     */
    private fun startStep(run: ManualRun, client: AgentClient, step: Int, results: List<ManualStepResult>): Boolean {
        val s = run.profile.steps[step - 1]
        if (writeProgress(run.id, step, results) is Outcome.Failure) {
            note("не удалось записать ход сборки — шаг $step не запущен; остановите сборку")
            return false
        }
        view = view.copy(step = step, results = results)
        val total = run.profile.steps.size
        if (total > 1) tail = (tail + "── Шаг $step из $total: ${s.name} ──\n").takeLast(MAX_OUTPUT)
        view = view.copy(output = tail)
        when (val r = client.start(run.id, RunSpec(s.command, run.path, timeout = s.timeoutSeconds.seconds))) {
            is Outcome.Failure -> note("шаг $step: ответ на запуск потерян или получен отказ; нужна сверка: ${r.errors.first().message}")
            is Outcome.Success -> note(if (total > 1) "шаг $step из $total «${s.name}» выполняется; проект на паузе" else "команда выполняется; проект на паузе")
        }
        return true
    }

    /** false: завершено; true: пауза остаётся. LOST/нет связи не равны завершению. */
    fun poll(client: AgentClient): Boolean {
        val run = view.active ?: return blocked
        val status = when (val r = client.status(run.id)) {
            is Outcome.Failure -> { note("связь с запуском не подтверждена; доступна безопасная остановка: ${r.errors.first().message}"); return true }
            is Outcome.Success -> r.value
        }
        val after = maxOf(sequence, status.lastSequence - 100)
        when (val r = client.events(run.id, after, 100)) {
            is Outcome.Failure -> note("вывод не обновлён: ${r.errors.first().message}")
            is Outcome.Success -> {
                if (after > sequence) tail = "…показан хвост вывода…\n"
                for (e in r.value.events) {
                    val text = (e.payload["text"] as? JsonString)?.value
                    if (text != null) tail = (tail + Redactor.STANDARD.redact(text).takeLast(MAX_OUTPUT) + "\n").takeLast(MAX_OUTPUT)
                    sequence = maxOf(sequence, e.sequence)
                }
                view = view.copy(output = tail)
            }
        }
        if (status.state == RunState.LOST) {
            note("агент потерял супервизор; требуется безопасная остановка")
            return true
        }
        if (status.state != RunState.EXITED) return true
        val total = run.profile.steps.size
        val turn = status.turn
        val why = when (status.reason) {
            ExitReason.EXITED -> "код выхода ${status.exitCode ?: "неизвестен"}"
            ExitReason.TIMEOUT -> "истёк таймаут"
            ExitReason.STOPPED -> "остановлено"
            else -> "запуск завершён: ${status.reason}"
        }
        val known = progress(run.id)
        // У одного шага продолжения нет: неизвестное намерение (якорь прежней версии) ничего не меняет.
        val intent = known.intent ?: 1.takeIf { total == 1 }
        when {
            intent == null -> return finish(client, run, known.results, "ход сборки не записан или повреждён — после шага $turn ничего не запускалось ($why)")
            turn < intent -> return finish(client, run, known.results, "запуск шага $intent не подтверждён агентом — сборка остановлена, повторно шаг не запускался; запустите профиль снова")
            turn > intent -> return finish(client, run, known.results, "агент сообщил шаг $turn, ожидался $intent — сборка остановлена")
        }
        val result = ManualStepResult(turn, status.exitCode.takeIf { status.reason == ExitReason.EXITED }, why)
        val results = known.results.filter { it.step < turn } + result
        view = view.copy(results = results, step = turn)
        if (result.ok && turn < total && !stopRequested) {
            // Не записалось намерение — следующий шаг не запускается, сборка завершается с итогом.
            if (!startStep(run, client, turn + 1, results)) return finish(client, run, results, "шаг ${turn + 1} не запущен: ход сборки не записан")
            return true
        }
        return finish(client, run, results, if (total > 1 && result.ok && turn < total) "остановлено после шага $turn" else why)
    }

    @Volatile private var stopRequested = false

    fun stop(client: AgentClient): Boolean {
        val run = view.active ?: return blocked
        stopRequested = true
        return finish(client, run, progress(run.id).results, "остановлено пользователем")
    }

    private fun finish(client: AgentClient, run: ManualRun, results: List<ManualStepResult>, result: String): Boolean {
        when (val r = client.fenceStop(run.id)) {
            is Outcome.Failure -> { note("пауза сохраняется: ${r.errors.first().message}"); return true }
            is Outcome.Success -> Unit
        }
        synchronized(this) {
            val note = Redactor.STANDARD.redact(summary(run, results, result)).take(MAX_NOTE)
            val written = docs.write(RESULT, JsonObject.of("format" to JsonString("dpai.manual-result"), "version" to JsonNumber.of(1), "note" to JsonString(note), "output" to JsonString(tail)))
            if (written is Outcome.Failure) { note("не удалось записать итог; пауза сохраняется"); return true }
            // Документ хода — от этого запуска; не удалился — следующий запуск его не примет (другой run).
            docs.delete(PROGRESS)
            val deleted = docs.delete(ACTIVE)
            if (deleted is Outcome.Failure) { note("не удалось снять паузу на диске"); return true }
            val complete = results.size == run.profile.steps.size && results.all { it.ok }
            view = ManualRunView(null, false, note, tail, results = results, finished = run.id, complete = complete)
            stopRequested = false
        }
        return false
    }

    /** Итог для человека: у одного шага — как раньше, у нескольких — строка на каждый шаг. */
    private fun summary(run: ManualRun, results: List<ManualStepResult>, result: String): String {
        val steps = run.profile.steps
        if (steps.size == 1) return "${run.profile.kind}: $result"
        return buildString {
            append("${run.profile.kind}: $result")
            steps.forEachIndexed { i, s ->
                val r = results.firstOrNull { it.step == i + 1 }
                append("\n").append(i + 1).append(". ").append(s.name).append(" — ")
                append(when {
                    r == null -> "не запускался"
                    r.ok -> "успешно"
                    else -> r.reason
                })
            }
        }
    }

    private fun note(text: String) { view = view.copy(note = Redactor.STANDARD.redact(text).take(1000)) }
    private fun key(p: ProjectEntry, kind: ManualProfileKind) = "${kind.name.lowercase()}-${p.project.value.lowercase()}"
    private fun runKey(p: ProjectEntry) = "run-${p.project.value.lowercase()}"

    private fun readRun(o: JsonObject): ManualRun? {
        if (o.text("format") != "dpai.manual-run" || o.number("version") !in setOf(1L, 2L)) return null
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
        private const val PROGRESS = "progress"
        private const val MAX_OUTPUT = 65536
        private const val MAX_NOTE = 4000
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonString)?.value
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonNumber)?.toLongExact()

package com.dpsmpx.dpai.orchestrator.github

import com.dpsmpx.dpai.connectors.GhPr
import com.dpsmpx.dpai.connectors.GhPrDetail
import com.dpsmpx.dpai.connectors.GitHubCommands
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpai.verification.StepRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * PR проекта на GitHub из «AI» (D-108): всё — командами официального `gh` на target проекта,
 * его входом; токена GitHub у приложения нет (D-14). Команды идут через агента target
 * ([runner], как шаги проверки — рабочий каталог задаёт он), отправка ветки — `git push`
 * ([git]). Вывод `gh` — данные со стороны (T3): разбирает [GitHubCommands] строго.
 *
 * Уровни действий — `DPmetaApp:D-07`, `DPmetaApp:D-36`, `DPmetaApp:D-80`: список и карточка —
 * 0 (чтение); отправка ветки задачи с PR — 1; комментарий — 2 (экран «что уйдёт» и PIN
 * устройства — у оболочки); «Слить» — 3 (красный экран и ввод `владелец/имя`; имя
 * сверяется и здесь). Каждое действие с уровнем — строкой в [journal], итог и отказ — тоже.
 */
class GitHubDesk(
    private val runner: StepRunner,
    private val git: GitService,
    /** Каталог репозитория проекта на target. */
    val repository: String,
    /** Ветка интеграции проекта: база PR задачи. */
    val base: String,
    private val journal: ActionJournal,
    private val clock: Clock,
    private val timeout: Duration = 2.minutes,
) {
    /** Открытые PR репозитория проекта (уровень 0). */
    fun list(): Outcome<List<GhPr>> = gh("gh pr list", GitHubCommands.prList()).then(GitHubCommands::parsePrList)

    /** Карточка PR: описание, проверки, отзывы и комментарии (уровень 0). */
    fun view(number: Int): Outcome<GhPrDetail> = when (val argv = GitHubCommands.prView(number)) {
        is Outcome.Failure -> argv
        is Outcome.Success -> gh("gh pr view", argv.value).then(GitHubCommands::parsePrView)
    }

    /** Что сделает [send] — для экрана подтверждения уровня 1: ветка, удалённый, база. */
    fun plan(task: TaskId): SendPlan = SendPlan(branch(task), REMOTE, base, repository)

    /**
     * Отправить ветку задачи на GitHub и открыть PR в [base] (уровень 1, `DPmetaApp:D-36`):
     * `git push -- origin task/<id>`, затем `gh pr create`. Итог — адрес PR. Ветка уже на
     * GitHub и PR открыт — повтор отвечает тем же адресом, а не вторым PR.
     */
    fun send(task: TaskId, title: String, body: String): Outcome<String> {
        val branch = branch(task)
        val create = GitHubCommands.createPr(base, branch, title, body)
        if (create is Outcome.Failure) return record(1, "pr.create", branch, create)
        val pushed = git.run(GitOp.PUSH, repository, listOf(REMOTE, branch))
        if (pushed is Outcome.Failure) return record(1, "branch.push", branch, pushed)
        record(1, "branch.push", branch, Outcome.success("$REMOTE/$branch"))
        val made = gh("gh pr create", (create as Outcome.Success).value).then { GitHubCommands.prUrl(it) }
        if (made is Outcome.Failure) {
            // PR этой ветки уже есть — его адрес, а не отказ: повтор отправки не плодит PR.
            existing(branch)?.let { return record(1, "pr.create", branch, Outcome.success(it)) }
        }
        return record(1, "pr.create", branch, made)
    }

    /** Комментарий к PR (уровень 2, `DPmetaApp:D-80`): подтверждение PIN — у оболочки, до вызова. */
    fun comment(number: Int, body: String): Outcome<Unit> {
        val argv = GitHubCommands.prComment(number, body)
        if (argv is Outcome.Failure) return record(2, "pr.comment", "#$number", argv)
        return record(2, "pr.comment", "#$number", gh("gh pr comment", (argv as Outcome.Success).value).then { Outcome.success(Unit) })
    }

    /**
     * Слить PR (уровень 3, `DPmetaApp:D-80`): merge-коммитом и только коммита [shown] — того,
     * что был на экране. [typed] — имя `владелец/имя`, введённое человеком; не совпало с
     * репозиторием PR — отказ без вызова `gh`.
     */
    fun merge(shown: GhPrDetail, typed: String): Outcome<Unit> {
        val subject = "#${shown.pr.number}"
        val repo = GitHubCommands.repoOf(shown.pr.url)
        if (repo == null || typed.trim() != repo) {
            return record(3, "pr.merge", subject, Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.OPERATION_NOT_ALLOWED, "имя репозитория не совпало — слияние не выполнено", "github"))
        }
        val argv = GitHubCommands.prMerge(shown.pr.number, shown.headSha)
        if (argv is Outcome.Failure) return record(3, "pr.merge", subject, argv)
        return record(3, "pr.merge", subject, gh("gh pr merge", (argv as Outcome.Success).value).then { Outcome.success(Unit) })
    }

    private fun existing(branch: String): String? =
        GitHubCommands.prState(branch).valueOrNull()?.let { argv -> gh("gh pr view", argv).valueOrNull() }
            ?.let { com.dpsmpx.dpai.core.serialization.JsonParser().parse(it.joinToString("\n")).valueOrNull() as? JsonObject }
            ?.let { (it["url"] as? JsonString)?.value }?.takeIf { it.startsWith("https://") && (it.length < 300) }

    /** `gh …` в каталоге репозитория через агента; ненулевой код — понятная причина ([GitHubCommands.failure]). */
    private fun gh(what: String, argv: List<String>): Outcome<List<String>> = when (val r = runner.run(argv, repository, timeout)) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.value.ok) Outcome.success(r.value.stdout) else Outcome.Failure(listOf(GitHubCommands.failure(what, r.value.exitCode, r.value.stderr, r.value.stdout)))
    }

    private fun <T> record(level: Int, action: String, subject: String, r: Outcome<T>): Outcome<T> {
        journal.append(ActionRecord(clock.now().epochMillis, level, action, repository, subject, r is Outcome.Success, when (r) {
            is Outcome.Success -> (if (r.value == Unit) "готово" else r.value.toString()).take(300)
            is Outcome.Failure -> r.errors.first().let { "${it.code}: ${it.message}" }.take(300)
        }))
        return r
    }

    private fun branch(task: TaskId) = TASK_BRANCH + task.value

    /** Что уходит на GitHub при [send]. */
    data class SendPlan(val branch: String, val remote: String, val base: String, val repository: String)

    companion object {
        /** Ветки задач контура — как у оркестратора (`Orchestrator`, `task/<id>`). */
        const val TASK_BRANCH: String = "task/"
        const val REMOTE: String = "origin"
    }
}

/** Строка журнала действий (`DPmetaApp:D-07`): когда, уровень, что, где, над чем, итог. */
data class ActionRecord(val at: Long, val level: Int, val action: String, val repository: String, val subject: String, val ok: Boolean, val detail: String) {
    fun toJson(): JsonObject = JsonObject.of(
        "format" to JsonString(ActionJournal.FORMAT), "version" to JsonNumber.of(1), "at" to JsonNumber.of(at), "level" to JsonNumber.of(level),
        "action" to JsonString(action), "repository" to JsonString(repository), "subject" to JsonString(subject), "ok" to JsonBool(ok), "detail" to JsonString(detail),
    )
}

/**
 * Журнал действий «AI» вне приложения (`DPmetaApp:D-07`): JSON-строки только добавлением,
 * с `fsync` — запись переживает смерть процесса. Пока у DPmetaApp нет `:core:actions`
 * (этап 5) — здесь; потом строки переезжают туда тем же форматом.
 */
class ActionJournal(private val vfs: Vfs, private val path: String = PATH) {
    @Synchronized
    fun append(r: ActionRecord) {
        Vfs.parentOf(path).takeIf { it.isNotEmpty() }?.let(vfs::mkdirs)
        vfs.appendDurably(path, (CanonicalJson.write(r.toJson()) + "\n").toByteArray(Charsets.UTF_8))
    }

    /** Последние [n] строк — для экрана; разбор строгий, чужая строка пропускается. */
    @Synchronized
    fun last(n: Int = 50): List<ActionRecord> {
        val text = vfs.read(path)?.toString(Charsets.UTF_8) ?: return emptyList()
        return text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(n).mapNotNull(::parse)
    }

    private fun parse(line: String): ActionRecord? {
        val o = com.dpsmpx.dpai.core.serialization.JsonParser().parse(line).valueOrNull() as? JsonObject ?: return null
        fun s(k: String) = (o[k] as? JsonString)?.value
        fun l(k: String) = (o[k] as? JsonNumber)?.toLongExact()
        if (s("format") != FORMAT) return null
        return ActionRecord(l("at") ?: return null, l("level")?.toInt() ?: return null, s("action") ?: return null, s("repository") ?: return null,
            s("subject") ?: return null, (o["ok"] as? JsonBool)?.value ?: return null, s("detail") ?: return null)
    }

    companion object {
        const val FORMAT: String = "dpai.actions"
        const val PATH: String = "actions/github.jsonl"
    }
}

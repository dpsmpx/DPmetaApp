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
 *
 * [claude] — ход Claude идёт на другом target (D-50): там слот и вход
 * Claude, а дерево остаётся здесь и доступно ходу по MCP через SSH.
 */
data class ProjectEntry(
    val project: ProjectId,
    val repository: RepositoryId,
    val target: ExecutionTargetId,
    val root: String,
    val worktreeRoot: String,
    val integrationBranch: String,
    val checks: List<VerifyStep>,
    val claude: ClaudeRoute? = null,
) {
    fun repository(): Repository = Repository(repository, target, root)

    /** Профиль проверки: шаги уровня NORMAL; без шагов — проверки нет, итог честно `COMPLETION_UNVERIFIED` (D-23). */
    fun profile(): VerificationProfile? = checks.takeIf { it.isNotEmpty() }?.let { VerificationProfile(project, mapOf(VerifyLevel.NORMAL to it)) }
}

/**
 * Ход Claude проекта — на другом target (D-50): [target] — где Claude и
 * слот; [identityFile], [knownHostsFile] — ключ SSH и known_hosts на нём (на
 * телефоне — пути внутри proot, где идёт Claude), которыми он ходит на
 * target проекта.
 */
data class ClaudeRoute(val target: ExecutionTargetId, val identityFile: String, val knownHostsFile: String? = null)

/** Форма проекта: пути — абсолютные на target, ветка — без пробелов и `..`, проверка — команда строкой. */
object ProjectForm {
    private val BRANCH = Regex("[A-Za-z0-9._/-]{1,100}")

    /** Ключ на телефоне по умолчанию: внутри proot Alpine, где идёт Claude (`docs/SETUP_PC_WINDOWS.md`, §7). */
    const val DEFAULT_IDENTITY = "/root/.ssh/dpai_workspace"

    fun validate(
        ids: IdGenerator,
        target: TargetEntry,
        root: String,
        worktreeRoot: String,
        branch: String,
        check: String,
        claudeOn: TargetEntry? = null,
        identityFile: String = "",
        knownHostsFile: String = "",
    ): Outcome<ProjectEntry> {
        fun absolute(p: String) = if (target.platform == com.dpsmpx.dpai.execution.target.TargetPlatform.WINDOWS) Regex("""[A-Za-z]:[\\/].*""").matches(p) else p.startsWith("/")
        val r = root.trim()
        val w = worktreeRoot.trim()
        if (!absolute(r) || r.any { it.isISOControl() }) return Outcome.failure(DiagCode.VALUE_INVALID, "каталог репозитория — абсолютный путь на target", "root")
        if (!absolute(w) || w.any { it.isISOControl() } || w == r) return Outcome.failure(DiagCode.VALUE_INVALID, "каталог рабочих деревьев — абсолютный путь на target, не сам репозиторий", "worktreeRoot")
        val b = branch.trim()
        if (!BRANCH.matches(b) || ".." in b || b.startsWith("-")) return Outcome.failure(DiagCode.VALUE_INVALID, "ветка интеграции недопустима", "branch")
        val argv = words(check) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "в команде проверки незакрытая кавычка", "check")
        if (argv.firstOrNull()?.startsWith("-") == true) return Outcome.failure(DiagCode.VALUE_INVALID, "команда проверки не начинается с «-»", "check")
        val steps = if (argv.isEmpty()) emptyList() else listOf(VerifyStep("проверка", argv))
        val route = when {
            claudeOn == null || claudeOn.id == target.id -> {
                if (target.configDir == null) return Outcome.failure(DiagCode.VALUE_INVALID, "у target проекта нет каталога Claude: выберите, где работает Claude", "claude")
                null
            }
            else -> {
                if (claudeOn.configDir == null) return Outcome.failure(DiagCode.VALUE_INVALID, "у ${claudeOn.label} нет каталога Claude", "claude")
                // Дерево по SSH (D-50) — не для Linux в приложении: к нему снаружи по SSH не попасть, а из него к ПК —
                // нужен свой ключ в госте. «В приложении» — Claude и дерево в одном Linux (D-75, шаг 5).
                if (target.inApp || claudeOn.inApp) {
                    return Outcome.failure(DiagCode.UNSUPPORTED, "«в приложении» — Claude и дерево в одном Linux: выберите тот же target", "claude")
                }
                val key = identityFile.trim()
                val known = knownHostsFile.trim().takeIf { it.isNotEmpty() }
                fun bad(p: String) = !p.startsWith("/") || p.any { it.isISOControl() || it == '\'' } || p.startsWith("-")
                if (bad(key)) return Outcome.failure(DiagCode.VALUE_INVALID, "ключ SSH — абсолютный путь на ${claudeOn.label}", "identityFile")
                if (known != null && bad(known)) return Outcome.failure(DiagCode.VALUE_INVALID, "known_hosts — абсолютный путь на ${claudeOn.label}", "knownHostsFile")
                ClaudeRoute(claudeOn.id, key, known)
            }
        }
        return Outcome.success(ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, r, w, b, steps, route))
    }

    /**
     * Слова команды проверки из поля ввода (ревью A-86): через пробелы, а
     * `'…'` и `"…"` — одно слово, как в оболочке. Деление по пробелам
     * ломало кавычки (`python -c "import x"`), и проверка падала всегда.
     * `\` — обычный знак: на target с Windows это разделитель пути.
     * Незакрытая кавычка — `null`.
     */
    internal fun words(text: String): List<String>? {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var quote: Char? = null
        for (c in text) {
            when {
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '\'' || c == '"' -> {
                    quote = c
                    inWord = true
                }
                c.isWhitespace() -> if (inWord) {
                    out += word.toString()
                    word.clear()
                    inWord = false
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
        }
        if (quote != null) return null
        if (inWord) out += word.toString()
        return out
    }
}

/** Проект приложения — документ `dpai.app-project` (`docs/DATA_MODEL.md`). */
object ProjectBook {
    const val FORMAT = "dpai.app-project"
    /** v2 — ход Claude на другом target (`claude`, D-50); v1 читается, у его проекта ход — на target проекта. */
    const val VERSION = 2

    fun toJson(p: ProjectEntry): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            *FormatHeader.write(FORMAT, VERSION),
            "project" to JsonString(p.project.value), "repository" to JsonString(p.repository.value), "target" to JsonString(p.target.value),
            "root" to JsonString(p.root), "worktreeRoot" to JsonString(p.worktreeRoot), "integrationBranch" to JsonString(p.integrationBranch),
            "checks" to JsonArray(p.checks.map { s -> JsonObject(linkedMapOf("name" to JsonString(s.name), "command" to JsonArray(s.command.map(::JsonString)))) }),
        ).also { m ->
            p.claude?.let { c ->
                m["claude"] = JsonObject(
                    linkedMapOf<String, JsonValue>("target" to JsonString(c.target.value), "identityFile" to JsonString(c.identityFile))
                        .also { o -> c.knownHostsFile?.let { o["knownHostsFile"] = JsonString(it) } },
                )
            }
        },
    )

    fun fromJson(value: JsonValue): Outcome<ProjectEntry> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        val version = FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
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
        val claude = if (version >= 2) {
            f.optObj("claude")?.let { c ->
                val t = c.string("target")?.let { IdKinds.TARGET.parse(it).valueOrNull() }
                val key = c.string("identityFile")
                val known = c.optString("knownHostsFile")
                c.finish()
                if (t == null || key == null) null.also { d.error(DiagCode.VALUE_INVALID, "маршрут Claude не читается", "$.claude") } else ClaudeRoute(t, key, known)
            }
        } else {
            null
        }
        f.finish()
        if (d.hasErrors || project == null || repository == null || target == null || root == null || worktreeRoot == null || branch == null || checks == null) {
            return Outcome.Failure(d.all.ifEmpty { listOf(com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.VALUE_INVALID, "проект не читается", "$")) })
        }
        return Outcome.success(ProjectEntry(project, repository, target, root, worktreeRoot, branch, checks, claude))
    }
}

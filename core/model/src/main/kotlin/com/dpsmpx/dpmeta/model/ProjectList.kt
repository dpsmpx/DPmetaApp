package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.git.RepoRef

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Проект пульта из списка пользователя (D-176): репозиторий GitHub, который он выбрал из
 * своего аккаунта или добавил ссылкой. [docs] — ключевые документы карточки, [checklists] —
 * файлы с чекбоксами для прогресса; оба — пути в репозитории, их выбирает пользователь.
 */
data class UserProject(val repo: RepoRef, val docs: List<String> = emptyList(), val checklists: List<String> = emptyList())

/**
 * Список проектов пульта (D-176) — вместо реестра DPmeta (D-05). Порядок — пользователя;
 * повторов нет (без учёта регистра: GitHub так же). Рабочие проекты IDE (D-147) — свой список
 * подсистемы «AI»: пульт показывает оба.
 */
data class ProjectList(val projects: List<UserProject> = emptyList()) {
    fun contains(repo: RepoRef): Boolean = projects.any { same(it.repo, repo) }

    /** Добавить в конец; уже есть — список прежний. Больше [MAX] — отказ. */
    fun add(repo: RepoRef): Outcome<ProjectList> = when {
        contains(repo) -> Outcome.success(this)
        projects.size >= MAX -> Outcome.failure(DiagCode.VALUE_INVALID, "проектов в пульте — не больше $MAX", repo.toString())
        else -> Outcome.success(copy(projects = projects + UserProject(repo)))
    }

    fun remove(repo: RepoRef): ProjectList = copy(projects = projects.filterNot { same(it.repo, repo) })

    /**
     * Документы и чек-листы проекта (D-176): пути проверяются ([validPath]), повторы убираются,
     * не больше [MAX_PATHS] каждого вида. Проекта нет в списке — отказ.
     */
    fun withPaths(repo: RepoRef, docs: List<String>, checklists: List<String>): Outcome<ProjectList> {
        val i = projects.indexOfFirst { same(it.repo, repo) }
        if (i < 0) return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "проекта нет в списке пульта", repo.toString())
        val d = docs.map(String::trim).filter(String::isNotEmpty).distinct()
        val c = checklists.map(String::trim).filter(String::isNotEmpty).distinct()
        (d + c).firstOrNull { !validPath(it) }?.let {
            return Outcome.failure(DiagCode.VALUE_INVALID, "путь — относительный, внутри репозитория, без «..»", it)
        }
        if (d.size > MAX_PATHS || c.size > MAX_PATHS) return Outcome.failure(DiagCode.VALUE_INVALID, "путей одного вида — не больше $MAX_PATHS", repo.toString())
        return Outcome.success(copy(projects = projects.toMutableList().also { it[i] = it[i].copy(docs = d, checklists = c) }))
    }

    /** Сдвинуть на [delta] мест; за краями — к краю. */
    fun move(repo: RepoRef, delta: Int): ProjectList {
        val from = projects.indexOfFirst { same(it.repo, repo) }
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, projects.lastIndex)
        val list = projects.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(projects = list)
    }

    /** Имена субъектов истории сохраняются; каталог не добавляет роли, алиасы и перенаправления. */
    fun forSync(): ProjectCatalog {
        val clash = projects.groupingBy { it.repo.name.lowercase() }.eachCount()
        return ProjectCatalog(projects.map { p ->
            val name = if ((clash[p.repo.name.lowercase()] ?: 0) > 1) p.repo.toString() else p.repo.name
            SyncProject(name, p.repo, p.docs, p.checklists)
        })
    }

    fun toJson(): JsonObject = JsonObject.of(
        *FormatHeader.write(FORMAT, VERSION),
        "projects" to JsonArray(
            projects.map { p ->
                JsonObject(
                    linkedMapOf<String, JsonValue>("repo" to JsonString(p.repo.toString())).apply {
                        if (p.docs.isNotEmpty()) put("docs", JsonArray(p.docs.map(::JsonString)))
                        if (p.checklists.isNotEmpty()) put("checklists", JsonArray(p.checklists.map(::JsonString)))
                    },
                )
            },
        ),
    )

    companion object {
        const val FORMAT = "dpmeta.projects"
        const val VERSION = 1

        /** Предел списка: экран и синхронизация рассчитаны на обозримое число проектов. */
        const val MAX = 200

        /** Предел путей документов и чек-листов у одного проекта. */
        const val MAX_PATHS = 20

        private fun same(a: RepoRef, b: RepoRef) = a.toString().equals(b.toString(), ignoreCase = true)

        /**
         * Путь в репозитории: относительный, без `..`, `.` и пустых частей, без управляющих знаков —
         * иначе он не подставится в запрос GitHub и не выйдет за пределы клона.
         */
        fun validPath(path: String): Boolean =
            path.length in 1..300 && !path.startsWith('/') && path.none { it.isISOControl() || it == '\\' } &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        fun parse(value: JsonValue): Outcome<ProjectList> {
            val diagnostics = DiagnosticCollector()
            val fields = JsonFields.root(value, diagnostics) ?: return Outcome.Failure(diagnostics.all)
            FormatHeader.read(fields, FORMAT, 1..VERSION, diagnostics) ?: return Outcome.Failure(diagnostics.all)
            val items = fields.array("projects")
            fields.finish()
            if (diagnostics.hasErrors || items == null) return Outcome.Failure(diagnostics.all)
            if (items.items.size > MAX) return Outcome.failure(DiagCode.VALUE_INVALID, "проектов в пульте — не больше $MAX", "projects")
            var list = ProjectList()
            for ((i, item) in items.items.withIndex()) {
                val f = JsonFields.root(item, diagnostics) ?: return Outcome.Failure(diagnostics.all)
                val repoText = f.string("repo")
                val docs = paths(f.raw("docs", required = false)) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "пути документов — массив путей в репозитории", "projects[$i].docs")
                val checklists = paths(f.raw("checklists", required = false)) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "пути чек-листов — массив путей в репозитории", "projects[$i].checklists")
                f.finish()
                if (diagnostics.hasErrors || repoText == null) return Outcome.Failure(diagnostics.all)
                val repo = when (val r = RepoRef.parse(repoText)) {
                    is Outcome.Failure -> return r
                    is Outcome.Success -> r.value
                }
                if (list.contains(repo)) return Outcome.failure(DiagCode.VALUE_INVALID, "проект в списке дважды", repo.toString())
                list = list.copy(projects = list.projects + UserProject(repo, docs, checklists))
            }
            return Outcome.success(list)
        }

        /** `null` — не массив путей; нет поля — пустой список. */
        private fun paths(raw: JsonValue?): List<String>? {
            raw ?: return emptyList()
            val items = (raw as? JsonArray)?.items ?: return null
            if (items.size > MAX_PATHS) return null
            val out = items.map { (it as? JsonString)?.value ?: return null }
            return if (out.all(::validPath) && out.distinct().size == out.size) out else null
        }
    }
}

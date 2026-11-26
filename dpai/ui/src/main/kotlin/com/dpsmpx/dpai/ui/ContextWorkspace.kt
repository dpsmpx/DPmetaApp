package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.contexts.ContextBinding
import com.dpsmpx.dpai.contexts.ContextMode
import com.dpsmpx.dpai.contexts.ContextPacket
import com.dpsmpx.dpai.contexts.ContextProfile
import com.dpsmpx.dpai.contexts.ContextReference
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.files.FileEntry
import com.dpsmpx.dpai.files.FileMeta
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity

/** Один фильтр для чтения, обзора и записи. Чужая область не маскируется пустым успешным файлом. */
class ContextTree(private val source: FileTree, val profile: ContextProfile, private val reports: Boolean = false) : FileTree {
    private fun safe(path: String) = FileNames.pathValid(path) && if (reports) DevelopmentTools.reportPath(path) else !ApiProjectFiles.hidden(path)
    override fun list(dir: String): Outcome<List<FileEntry>> {
        if (dir.isNotEmpty() && (!safe(dir) || !profile.canList(dir))) return denied()
        return when (val r = source.list(dir)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.filter { e ->
                val path = FileNames.join(dir, e.name)
                safe(path) && if (e.dir) profile.canList(path) else profile.canRead(path)
            })
        }
    }
    override fun read(path: String, maxBytes: Long): Outcome<ByteArray> =
        if (safe(path) && profile.canRead(path)) source.read(path, maxBytes) else denied()
    override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> =
        if (safe(path) && !ApiProjectFiles.hidden(path) && profile.allowed(path)) source.write(path, bytes) else denied()
    override fun delete(path: String): Outcome<Unit> = if (safe(path) && !ApiProjectFiles.hidden(path) && profile.allowed(path)) source.delete(path) else denied()
    private fun <T> denied(): Outcome<T> = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "путь вне профиля контекста или относится к секретным/служебным файлам")
}

/** Подбор без исполнения конфигурации. FULL_ACCESS разрешает запросы, но не отправляет весь репозиторий. */
class ContextWorkspace(private val source: FileTree, val profile: ContextProfile,
    private val binding: ContextBinding, private val cancelled: () -> Boolean = { false },
    private val progress: (String, Int) -> Unit = { _, _ -> },
    private val clock: com.dpsmpx.dpai.core.clock.Clock = com.dpsmpx.dpai.core.clock.Clock.SYSTEM) {
    val tree: FileTree = ContextTree(source, profile)
    // Только производные данные этой области. Хеш считается заново перед каждым обращением к кешу.
    companion object { private val decoded = com.dpsmpx.dpai.contexts.ContextTextCache() }

    fun packet(objective: String, paths: List<String> = emptyList(), reason: String? = null,
        acceptance: String = "Критерии не указаны; уточнить до изменения поведения.",
        related: String = "Связанные задачи не указаны.", evidence: String = "Свежие проверки и известные ошибки не представлены.",
        commit: String? = null, requestOrigin: Origin = Origin.USER, budget: com.dpsmpx.dpai.contexts.ContextPolicy? = null): Outcome<ContextPacket> {
        if (objective.isBlank() || objective.length > 200_000 || paths.size > 20 || paths.distinct().size != paths.size ||
            paths.any { !profile.canRead(it) || !DevelopmentTools.safePath(it) }) return invalid()
        if (reason != null && (!profile.allowExpansion || reason.isBlank() || reason.length > 1000))
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "расширение контекста отключено или не объяснена его необходимость")
        val begun = clock.monotonicNanos()
        fun stop() = cancelled() || clock.monotonicNanos() - begun >= 60_000_000_000L
        if (stop()) return interrupted()
        progress("scan", 0)
        val survey = ProjectSurvey(tree, ::stop) { progress("scan", it) }
        val scanned = when (val r = survey.scan()) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val relevant = ((survey.relevant(scanned, objective)["matches"] as? JsonArray)?.items.orEmpty()).mapNotNull {
            ((it as? JsonObject)?.get("path") as? JsonString)?.value
        }
        val selected = (profile.pinned + paths + if (profile.mode == ContextMode.MINIMAL) profile.roots else relevant.take(6)).distinct()
        val rules = profile.rulePaths(selected).filter { path -> profile.roots.none { it in scanned.files && path.startsWith("$it/") } }
        if ((rules + selected).distinct().size > 128) return Outcome.failure(DiagCode.CONTEXT_BUDGET_EXCEEDED,
            "больше 128 источников и правил родителей; сократите выборку, обязательные правила не усекаются")
        val refs = arrayListOf<ContextReference>()
        val sources = arrayListOf<ContextSource>()
        val incomplete = scanned.incomplete.toMutableList()
        fun add(key: String, level: SourceLevel, text: String, origin: Origin = Origin.REPOSITORY) {
            sources += ContextSource(key, level, binding.project, Sensitivity.INTERNAL, origin, text)
        }
        add("request:objective", SourceLevel.TASK, objective, requestOrigin)
        add("request:acceptance", SourceLevel.TASK, acceptance, requestOrigin)
        add("request:related", SourceLevel.TASK, related, requestOrigin)
        add("evidence:summary", SourceLevel.TOOL, evidence, Origin.ORCHESTRATOR)
        for (path in (rules + selected).distinct()) {
            if (stop()) return interrupted()
            val rule = path in rules
            if (!DevelopmentTools.safePath(path) || !profile.canRead(path)) return invalid()
            // Порядок важности внутри CODE: закреплённое → явно запрошенное → ранжированные кандидаты.
            val key = "file:" + (if (rule) "" else selected.indexOf(path).toString().padStart(3, '0') + ":") +
                if (path.length <= 285) path else Hashing.sha256(path).hex
            if (path !in scanned.files) {
                // Отсутствие проверяем по родителю: неполный обход не превращает неизвестное в отсутствие.
                val entries = tree.list(FileNames.parent(path))
                if (entries is Outcome.Success && entries.value.none { it.name == FileNames.name(path) }) {
                    refs += ContextReference(path, null, key)
                    if (!rule) incomplete += "missing:$path"
                    continue
                }
                if (rule) return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "не удалось установить наличие обязательных правил", path)
            }
            progress("read", refs.size)
            val bytes = when (val r = tree.read(path, 256 * 1024)) {
                is Outcome.Failure -> if (rule || path in profile.pinned || path in paths) return r else { incomplete += "unread:$path"; continue }
                is Outcome.Success -> r.value
            }
            val hash = Hashing.sha256(bytes).hex
            val text = decoded.get(binding, hash) ?: when (val r = TextFile.decode(bytes)) {
                is Outcome.Failure -> if (rule || path in profile.pinned || path in paths) return r else { incomplete += "non_text:$path"; continue }
                is Outcome.Success -> com.dpsmpx.dpai.contexts.PromptRenderer.neutralize(com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(r.value))
                    .also { decoded.put(binding, hash, it) }
            }
            refs += ContextReference(path, hash, key)
            // Файл целиком либо явно пропущен бюджетом; середина Unicode и правил не обрезается.
            add(key, if (rule) SourceLevel.PROJECT else SourceLevel.CODE, text)
        }
        progress("assemble", refs.size)
        return ContextPacket.build(binding, profile, sources, refs, incomplete, commit, budget)
    }

    fun hashes(paths: Collection<String>): Outcome<Map<String, String?>> {
        val out = linkedMapOf<String, String?>()
        val begun = clock.monotonicNanos()
        for (path in paths) {
            if (cancelled() || clock.monotonicNanos() - begun >= 60_000_000_000L) return interrupted()
            // Автовыборка уже, чем штатное постраничное чтение: его прежний предел 2 МиБ сохраняется.
            val bytes = tree.read(path, TextFile.MAX_EDIT_BYTES)
            if (bytes is Outcome.Success) out[path] = Hashing.sha256(bytes.value).hex else {
                val entries = tree.list(FileNames.parent(path))
                if (entries is Outcome.Success && entries.value.none { it.name == FileNames.name(path) }) out[path] = null
                else return Outcome.failure(DiagCode.STATE_CONFLICT, "актуальность источника не подтверждена", path)
            }
        }
        return Outcome.success(out)
    }
    private fun <T> invalid(): Outcome<T> = Outcome.failure(DiagCode.VALUE_INVALID, "неверные параметры или путь контекста вне разрешённой области")
    private fun <T> interrupted(): Outcome<T> = Outcome.failure(DiagCode.CANCELLED_BY_USER, "подготовка контекста отменена")
}

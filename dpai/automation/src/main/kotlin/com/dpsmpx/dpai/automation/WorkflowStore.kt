package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.Closeable
import java.io.IOException

/** Приватный каталог одного проекта; не лежит в дереве, доступном модели (D-222). */
class WorkflowStore(private val vfs: Vfs, private val acquire: () -> Closeable?) {
    constructor(vfs: NioVfs) : this(vfs, { vfs.mkdirs(""); vfs.tryLease("operation.lock") })
    // Отзыв разрешения не ждёт конца сценария. CAS настроек имеет отдельную короткую аренду.
    private fun <T> configure(block: () -> Outcome<T>): Outcome<T> = try {
        vfs.mkdirs("")
        val lease = if (vfs is NioVfs) vfs.tryLease("configuration.lock") else acquire()
        if (lease == null) Outcome.failure(DiagCode.WORKTREE_OWNED, "настройки сценариев заняты") else lease.use { block() }
    } catch (_: IOException) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "настройки сценариев недоступны") }
    private val definitions = DocumentStore(vfs, "definitions")
    private val runs = DocumentStore(vfs, "runs")
    private val settings = DocumentStore(vfs, "settings")
    private val last = DocumentStore(vfs, "latest")
    private fun definitionKey(id: String) = com.dpsmpx.dpai.core.hashing.Hashing.sha256(id).hex

    fun <T> locked(block: () -> Outcome<T>): Outcome<T> { return try {
        val lease = acquire() ?: return Outcome.failure(DiagCode.WORKTREE_OWNED, "сценарий проекта уже выполняется")
        lease.use { block() }
    } catch (_: IOException) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "хранилище сценариев недоступно") } }

    fun list(): Outcome<List<Workflow>> { return try {
        // DocumentStore.keys не различает пустую папку и отказ list; здесь отказ не скрывается.
        vfs.list("definitions")
        val values = arrayListOf<Workflow>()
        for (key in definitions.keys()) when (val r = readDefinition(key)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value?.let(values::add)
        }
        Outcome.success(values)
    } catch (_: IOException) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "список сценариев не прочитан") } }

    fun workflow(id: String): Outcome<Workflow?> {
        if (!Workflow.storedId(id)) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный id сценария")
        return readDefinition(definitionKey(id))
    }

    private fun readDefinition(key: String): Outcome<Workflow?> {
        return when (val r = read(definitions, key)) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value?.let { content -> when (val value = Workflow.parse(content)) {
                    is Outcome.Failure -> value
                    is Outcome.Success -> if (definitionKey(value.value.id) == key) value
                        else Outcome.failure(DiagCode.STORAGE_CORRUPTED, "сценарий записан под чужим ключом")
                } } ?: Outcome.success(null)
        }
    }

    /** expected=null только при создании; изменение поверх другого снимка отвергается. */
    fun save(value: Workflow, expected: String?): Outcome<Unit> = configure {
        when (val valid = Workflow.parse(value.json())) { is Outcome.Failure -> return@configure valid; is Outcome.Success -> Unit }
        val old = when (val r = workflow(value.id)) { is Outcome.Failure -> return@configure r; is Outcome.Success -> r.value }
        if (old?.revision != expected) return@configure Outcome.failure(DiagCode.STATE_CONFLICT, "сценарий изменился; перечитайте его")
        val all = when (val r = list()) { is Outcome.Failure -> return@configure r; is Outcome.Success -> r.value }
        if (old == null && all.size >= 100) return@configure Outcome.failure(DiagCode.VALUE_INVALID, "предел: 100 сценариев проекта")
        definitions.write(definitionKey(value.id), value.json()).map { }
    }

    fun disabled(): Outcome<Set<String>> = when (val r = read(settings, "disabled")) {
        is Outcome.Failure -> r
        is Outcome.Success -> {
            val fields = r.value?.fields.orEmpty()
            if (fields.any { (k, v) -> !Workflow.ID.matches(k) || v != com.dpsmpx.dpai.core.serialization.JsonBool(true) })
                Outcome.failure(DiagCode.STORAGE_CORRUPTED, "повреждён список отключённых инструментов")
            else Outcome.success(fields.keys)
        }
    }

    fun disable(id: String, disabled: Boolean): Outcome<Unit> = configure {
        if (!Workflow.storedId(id)) return@configure Outcome.failure(DiagCode.VALUE_INVALID, "неверный id инструмента")
        val old = when (val r = disabled()) { is Outcome.Failure -> return@configure r; is Outcome.Success -> r.value }
        val next = if (disabled) old + id else old - id
        if (next.size > 500) return@configure Outcome.failure(DiagCode.VALUE_INVALID, "слишком много отключённых инструментов")
        settings.write("disabled", JsonObject(next.associateWith { com.dpsmpx.dpai.core.serialization.JsonBool(true) })).map { }
    }

    internal fun run(key: String): Outcome<JsonObject?> = when (val r = read(runs, key)) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.value?.let { validRun(key, it) } != false) r
            else Outcome.failure(DiagCode.STORAGE_CORRUPTED, "неверная версия или запись результата сценария")
    }
    fun result(workflow: String, event: String): Outcome<JsonObject?> {
        if (!Workflow.storedId(workflow) || event.length !in 1..200 || '\u0000' in event)
            return Outcome.failure(DiagCode.VALUE_INVALID, "неверный запрос результата сценария")
        return observedRun(com.dpsmpx.dpai.core.hashing.Hashing.sha256(workflow + ":" + event).hex)
    }

    /** Свободная аренда доказывает отсутствие исполнителя: чтение не оставляет фантомный RUNNING. */
    private fun observedRun(key: String): Outcome<JsonObject?> {
        val original = when (val r = run(key)) { is Outcome.Failure -> return r; is Outcome.Success -> r }
        if (original.value?.get("status") != JsonString("RUNNING")) return original
        val reconciled = locked {
            val current = when (val r = run(key)) { is Outcome.Failure -> return@locked r; is Outcome.Success -> r.value }
            if (current?.get("status") != JsonString("RUNNING")) return@locked Outcome.success(current)
            val interrupted = JsonObject(current.fields + mapOf("status" to JsonString("INCOMPLETE"),
                "note" to JsonString("исполнение прервано; сверьте историю инструмента, начатый шаг сам не повторяется")))
            record(key, interrupted).map { interrupted }
        }
        // Аренда занята настоящим worker, в том числе проверкой владельца дерева задачи.
        return if (reconciled is Outcome.Failure && reconciled.errors.any { it.code == DiagCode.WORKTREE_OWNED }) original else reconciled
    }

    internal fun record(key: String, value: JsonObject): Outcome<Unit> {
        if (!validRun(key, value)) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный результат сценария")
        return when (val written = runs.write(key, value)) {
        is Outcome.Failure -> written
        is Outcome.Success -> {
            val workflow = (value["workflow"] as? com.dpsmpx.dpai.core.serialization.JsonString)?.value
                ?: return Outcome.failure(DiagCode.VALUE_INVALID, "нет id сценария в результате")
            last.write(definitionKey(workflow), JsonObject.of("key" to JsonString(key),
                "summary" to JsonObject(value.fields - "results"))).map { }
        }
    } }

    /** Отказ до открытия исполнителя также завершает доставку, не создавая бесконечные повторные уведомления. */
    fun refuse(workflow: Workflow, event: AutomationEvent, note: String, at: Long): Outcome<Unit> = locked {
        if (!event.valid()) return@locked Outcome.failure(DiagCode.VALUE_INVALID, "неверное событие отказа")
        val key = com.dpsmpx.dpai.core.hashing.Hashing.sha256(workflow.id + ":" + event.id).hex
        when (val r = run(key)) {
            is Outcome.Failure -> return@locked r
            is Outcome.Success -> r.value?.let { previous ->
                return@locked if (previous["status"] == JsonString("RUNNING")) record(key, JsonObject(previous.fields + mapOf(
                    "status" to JsonString("INCOMPLETE"), "note" to JsonString("процесс прерван; начатый шаг не повторяется, сверьте его историю"))))
                else Outcome.success(Unit)
            }
        }
        record(key, JsonObject.of("format" to JsonString("dpai.workflow-run"), "version" to JsonNumber.of(1),
            "workflow" to JsonString(workflow.id), "revision" to JsonString(workflow.revision), "event" to JsonString(event.id),
            "run" to JsonString(key), "context" to JsonString(event.context), "trigger" to JsonString(event.type.name),
            "fingerprint" to JsonString(event.fingerprint), "status" to JsonString("BLOCKED"), "timestamp" to JsonNumber.of(at),
            "step" to JsonNumber.of(-1), "total" to JsonNumber.of(workflow.steps.size), "completed" to JsonNumber.of(0),
            "results" to JsonArray(emptyList()), "note" to JsonString(com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(note).take(2000))))
    }

    /** Частый UI-опрос читает только короткую сводку, не все доказательства каждого шага. */
    fun latestSummary(workflow: String): Outcome<JsonObject?> {
        if (!Workflow.storedId(workflow)) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный id сценария")
        return when (val r = read(last, definitionKey(workflow))) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val summary = r.value?.get("summary") as? JsonObject
                if (summary?.get("status") == JsonString("RUNNING")) latest(workflow).map { it?.let { value -> JsonObject(value.fields - "results") } }
                else Outcome.success(summary)
            }
        }
    }

    fun latest(workflow: String): Outcome<JsonObject?> {
        if (!Workflow.storedId(workflow)) return Outcome.failure(DiagCode.VALUE_INVALID, "неверный id сценария")
        val pointer = when (val r = read(last, definitionKey(workflow))) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value ?: return Outcome.success(null)
        }
        val key = (pointer["key"] as? com.dpsmpx.dpai.core.serialization.JsonString)?.value
            ?.takeIf { Workflow.HASH.matches(it) } ?: return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "повреждена ссылка результата")
        return observedRun(key)
    }

    private fun validRun(key: String, o: JsonObject): Boolean {
        fun text(k: String) = (o[k] as? JsonString)?.value
        fun number(k: String) = (o[k] as? JsonNumber)?.toLongExact()
        val id = text("workflow") ?: return false
        val event = text("event") ?: return false
        val status = text("status") ?: return false
        val total = number("total") ?: return false
        val completed = number("completed") ?: return false
        val step = number("step") ?: return false
        return Workflow.storedId(id) && event.length in 1..200 && '\u0000' !in event &&
            text("format") == "dpai.workflow-run" && number("version") == 1L &&
            text("run") == key && com.dpsmpx.dpai.core.hashing.Hashing.sha256(id + ":" + event).hex == key &&
            text("revision")?.let(Workflow.HASH::matches) == true &&
            text("context")?.let(Workflow.HASH::matches) == true && text("fingerprint")?.let(Workflow.HASH::matches) == true &&
            AutomationTrigger.entries.any { it.name == text("trigger") } && WorkflowStatus.entries.any { it.name == status } &&
            number("timestamp") != null && total in 1..20 && completed in 0..total && step in -1 until total &&
            (o["results"] as? JsonArray)?.items?.size?.toLong() == completed &&
            (status != "PASSED" || completed == total) && text("note") != null
    }

    /** Прежний снимок не даёт разрешения повторить эффект, состояние которого неизвестно. */
    private fun read(store: DocumentStore, key: String): Outcome<JsonObject?> = when (val r = store.read(key)) {
        is Outcome.Failure -> r
        is Outcome.Success -> if (r.warnings.isNotEmpty() || r.value?.fromLastKnownGood == true)
            Outcome.failure(DiagCode.STORAGE_CORRUPTED, "нужна ручная сверка восстановленного документа сценария", key)
        else Outcome.success(r.value?.content)
    }
}

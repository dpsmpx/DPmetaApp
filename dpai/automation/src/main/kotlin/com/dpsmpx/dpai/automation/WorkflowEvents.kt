package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.Closeable
import java.io.IOException

data class QueuedAutomation(val workflow: String, val revision: String, val event: AutomationEvent, val at: Long) {
    val key: String get() = Hashing.sha256(workflow + ":" + event.id).hex
    fun json() = JsonObject.of("format" to JsonString("dpai.automation-event"), "version" to JsonNumber.of(1),
        "workflow" to JsonString(workflow), "revision" to JsonString(revision), "at" to JsonNumber.of(at),
        "event" to JsonObject.of("id" to JsonString(event.id), "type" to JsonString(event.type.name),
            "context" to JsonString(event.context), "fingerprint" to JsonString(event.fingerprint), "name" to JsonString(event.name),
            "lineage" to JsonArray(event.lineage.sorted().map(::JsonString))))
}

/** Долговечная доставка в существующий worker; отдельный короткий lease не держится во время команды. */
class WorkflowEvents(private val vfs: Vfs, private val clock: Clock, private val acquire: () -> Closeable?) {
    constructor(vfs: NioVfs, clock: Clock) : this(vfs, clock, { vfs.mkdirs(""); vfs.tryLease("events.lock") })
    private val docs = DocumentStore(vfs, "pending-events")

    fun enqueue(workflow: Workflow, event: AutomationEvent): Outcome<Unit> = locked {
        if (!workflow.enabled || workflow.context != event.context || !event.valid() || workflow.triggers.none(event::accepts))
            return@locked Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "событие не подходит включённому сценарию")
        val next = QueuedAutomation(workflow.id, workflow.revision, event, clock.now().epochMillis)
        val old = when (val r = read(next.key)) { is Outcome.Failure -> return@locked r; is Outcome.Success -> r.value }
        if (old != null) return@locked if (old.workflow == next.workflow && old.revision == next.revision && old.event == next.event)
            Outcome.success(Unit) else Outcome.failure(DiagCode.STATE_CONFLICT, "событие уже доставлено с другим содержимым")
        try { vfs.list("pending-events") } catch (_: IOException) {
            return@locked Outcome.failure(DiagCode.STORAGE_IO_FAILED, "очередь событий не прочитана")
        }
        if (docs.keys().size >= 1000) return@locked Outcome.failure(DiagCode.VALUE_INVALID, "очередь содержит 1000 событий; разберите их перед новыми")
        docs.write(next.key, next.json()).map { }
    }

    fun pending(): Outcome<List<QueuedAutomation>> = locked {
        try { vfs.list("pending-events") } catch (_: IOException) {
            return@locked Outcome.failure(DiagCode.STORAGE_IO_FAILED, "очередь событий не прочитана")
        }
        val result = arrayListOf<QueuedAutomation>()
        for (key in docs.keys()) when (val r = read(key)) {
            is Outcome.Failure -> return@locked r
            is Outcome.Success -> r.value?.let(result::add)
        }
        Outcome.success(result.sortedWith(compareBy<QueuedAutomation> { it.at }.thenBy { it.key }))
    }

    fun acknowledge(key: String): Outcome<Unit> = locked {
        if (!Workflow.HASH.matches(key)) Outcome.failure(DiagCode.VALUE_INVALID, "неверный id доставки") else docs.delete(key)
    }

    private fun read(key: String): Outcome<QueuedAutomation?> {
        val stored = when (val r = docs.read(key)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> {
                if (r.warnings.isNotEmpty()) return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "нужна сверка восстановленного события")
                r.value?.content ?: return Outcome.success(null)
            }
        }
        return try {
            require(stored.fields.keys == setOf("format", "version", "workflow", "revision", "at", "event"))
            require((stored["format"] as? JsonString)?.value == "dpai.automation-event" && (stored["version"] as? JsonNumber)?.toLongExact() == 1L)
            val event = stored["event"] as? JsonObject ?: error("event")
            require(event.fields.keys == setOf("id", "type", "context", "fingerprint", "name", "lineage"))
            fun text(o: JsonObject, name: String) = (o[name] as? JsonString)?.value ?: error(name)
            val lineage = (event["lineage"] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: error("lineage") }?.toSet() ?: error("lineage")
            val value = QueuedAutomation(text(stored, "workflow"), text(stored, "revision"),
                AutomationEvent(text(event, "id"), AutomationTrigger.valueOf(text(event, "type")), text(event, "context"),
                    text(event, "fingerprint"), text(event, "name"), lineage),
                (stored["at"] as? JsonNumber)?.toLongExact() ?: error("at"))
            require(Workflow.storedId(value.workflow) && Workflow.HASH.matches(value.revision) && value.event.valid() && value.key == key)
            Outcome.success(value)
        } catch (_: IllegalArgumentException) {
            Outcome.failure(DiagCode.STORAGE_CORRUPTED, "неверное событие автоматизации")
        } catch (_: IllegalStateException) {
            Outcome.failure(DiagCode.STORAGE_CORRUPTED, "неверное событие автоматизации")
        }
    }

    private fun <T> locked(block: () -> Outcome<T>): Outcome<T> { return try {
        val lease = acquire() ?: return Outcome.failure(DiagCode.WORKTREE_OWNED, "очередь событий занята")
        lease.use { block() }
    } catch (_: IOException) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "очередь событий недоступна") } }
}

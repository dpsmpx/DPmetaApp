package com.dpsmpx.dpai.ecosystem

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonPath
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Предложение задачи от экосистемы (ТЗ, §44): что сделать и почему. В
 * V1 задача из предложения создаётся только с подтверждения
 * (`docs/direction/V1_SCOPE.md`, §3); [operation] — вид работы, по нему
 * глобальный режим решает, разрешена ли она (ТЗ, §46).
 */
data class SuggestedTask(val key: String, val title: String, val objective: String, val priority: String, val operation: String)

/**
 * Проект экосистемы (ТЗ, §43): имя, репозиторий, состояние (ветки, PR,
 * проверка, риски — как их сообщает источник), документы для контекста и
 * предложения задач.
 */
data class EcosystemProject(
    val key: String,
    val name: String,
    val repository: String?,
    val state: Map<String, String>,
    val documents: List<String>,
    val suggestions: List<SuggestedTask>,
)

/**
 * Источник сведений об экосистеме (ТЗ, §5.3, §43). Необязателен: без него
 * оркестратор работает (ТЗ, §148) — [None].
 */
interface EcosystemProvider {
    /** Проекты; источник недоступен — отказ с диагностикой, а не пустой список. */
    fun projects(): Outcome<List<EcosystemProject>>

    /** Источник не подключён: проектов нет, и это не ошибка. */
    object None : EcosystemProvider {
        override fun projects(): Outcome<List<EcosystemProject>> =
            Outcome.success(emptyList(), listOf(Diagnostic.info(DiagCode.ECOSYSTEM_DISABLED, "экосистема не подключена: работа без неё")))
    }
}

/**
 * Манифест экосистемы `dpai.ecosystem` v1 — контракт, который выгружает
 * DPmeta или пишет пользователь (`docs/ECOSYSTEM.md`). Код DPmeta сюда не
 * переносится (D-16): адаптер читает только этот контракт. Чтение строгое
 * (D-09). [read] — текст манифеста; `null` — манифеста нет.
 */
class ManifestEcosystem(private val read: () -> String?) : EcosystemProvider {
    override fun projects(): Outcome<List<EcosystemProject>> {
        val text = read() ?: return Outcome.failure(DiagCode.ECOSYSTEM_UNAVAILABLE, "манифест экосистемы не найден")
        val value = when (val p = JsonParser().parse(text)) {
            is Outcome.Failure -> return Outcome.Failure(listOf(Diagnostic.of(DiagCode.ECOSYSTEM_UNAVAILABLE, "манифест не JSON", causes = p.errors)))
            is Outcome.Success -> p.value
        }
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val projects = f.array("projects")?.items.orEmpty().mapIndexedNotNull { i, item -> project(item, JsonPath.index("$.projects", i), d) }
        f.finish()
        val dup = projects.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) d.error(DiagCode.VALUE_INVALID, "проекты с одинаковым ключом: ${dup.joinToString()}", "$.projects")
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(projects)
    }

    private fun project(v: com.dpsmpx.dpai.core.serialization.JsonValue, path: String, d: DiagnosticCollector): EcosystemProject? {
        val f = (v as? JsonObject)?.let { JsonFields(it, path, d) } ?: return null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", path) }
        val key = f.string("key")
        val name = f.string("name")
        val repo = f.optString("repository")
        // Состояние — свободные пары строк: какие поля, решает источник, а не оркестратор.
        val state = (f.raw("state", required = false) as? JsonObject)?.fields.orEmpty().mapNotNull { (k, value) ->
            (value as? JsonString)?.let { k to it.value } ?: null.also { d.error(DiagCode.FIELD_TYPE, "ожидалась строка", JsonPath.field(JsonPath.field(path, "state"), k)) }
        }.toMap()
        val docs = (f.raw("documents", required = false) as? JsonArray)?.items?.mapNotNull { (it as? JsonString)?.value }.orEmpty()
        val suggestions = (f.raw("suggestions", required = false) as? JsonArray)?.items.orEmpty().mapIndexedNotNull { i, s ->
            val sp = JsonPath.index(JsonPath.field(path, "suggestions"), i)
            val sf = (s as? JsonObject)?.let { JsonFields(it, sp, d) } ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", sp) }
            val r = listOf(sf.string("key"), sf.string("title"), sf.string("objective"), sf.string("priority"), sf.string("operation"))
            sf.finish()
            if (r.any { it == null }) null else SuggestedTask(r[0]!!, r[1]!!, r[2]!!, r[3]!!, r[4]!!)
        }
        f.finish()
        if (key == null || name == null) return null
        return EcosystemProject(key, name, repo, state, docs, suggestions)
    }

    companion object {
        const val FORMAT = "dpai.ecosystem"
        const val VERSION = 1
    }
}

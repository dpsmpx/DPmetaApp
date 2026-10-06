package com.dpsmpx.dpai.storage.migration

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject

/** Шаг миграции между соседними версиями формата: [from] → [from] + 1 (D-09). */
class Migration(val from: Int, val description: String, val transform: (JsonObject) -> Outcome<JsonObject>) {
    val to: Int get() = from + 1
}

/**
 * Миграции документа формата [format] до версии [current] (ТЗ, §62):
 * проверить → перевести по цепочке соседних версий → проверить каждый шаг.
 * Чистая функция: вход не меняется, отказ любого шага — `MIGRATION_FAILED`
 * с причиной, и документ остаётся прежним. Атомарную замену на диске
 * делает `DocumentStore.readMigrated`.
 */
class Migrator(val format: String, val current: Int, migrations: List<Migration>) {
    private val steps: Map<Int, Migration> = migrations.associateBy { it.from }
    val oldest: Int = current - migrations.size

    init {
        require(steps.size == migrations.size) { "две миграции из одной версии" }
        require(oldest >= 1 && (oldest until current).all { it in steps }) { "цепочка миграций $format не сплошная" }
    }

    data class Migrated(val document: JsonObject, val fromVersion: Int, val applied: List<Int>)

    fun migrate(document: JsonObject): Outcome<Migrated> {
        val diags = DiagnosticCollector()
        val version = FormatHeader.read(JsonFields(document, "$", diags), format, oldest..current, diags)
            ?: return Outcome.Failure(diags.all.filter { it.isError })
        var doc = document
        val applied = mutableListOf<Int>()
        val notes = mutableListOf<Diagnostic>()
        for (v in version until current) {
            val step = steps.getValue(v)
            val out = try {
                step.transform(doc)
            } catch (e: RuntimeException) {
                Outcome.failure(Diagnostic.fromThrowable(DiagCode.INTERNAL_ERROR, "миграция бросила исключение", e))
            }
            val next = (out as? Outcome.Success)?.value
            val check = DiagnosticCollector()
            val nextVersion = next?.let { FormatHeader.read(JsonFields(it, "$", check), format, v + 1..v + 1, check) }
            if (next == null || nextVersion == null) {
                return Outcome.failure(
                    Diagnostic.of(
                        DiagCode.MIGRATION_FAILED,
                        "$format: миграция $v → ${v + 1} (${step.description}) не удалась; документ не изменён",
                        causes = out.errors + check.all.filter { it.isError },
                    ),
                )
            }
            doc = next
            applied += v
            notes += Diagnostic.info(DiagCode.MIGRATION_APPLIED, "$format: $v → ${v + 1} (${step.description})")
        }
        return Outcome.success(Migrated(doc, version, applied), notes)
    }
}

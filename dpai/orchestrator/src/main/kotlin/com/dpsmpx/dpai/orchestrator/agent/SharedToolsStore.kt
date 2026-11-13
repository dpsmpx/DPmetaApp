package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.execution.tools.SharedTools
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.Vfs

/** Настройка target, общая для слотов и исполнителей (D-122); атомарно, с проверкой хеша. */
class SharedToolsStore(vfs: Vfs) {
    private val documents = DocumentStore(vfs, "config")

    fun read(): Outcome<SharedTools?> = documents.read(KEY).then { document ->
        document?.let { SharedTools.parse(it.content) } ?: Outcome.success(null)
    }

    fun write(value: SharedTools): Outcome<Unit> = documents.write(KEY, value.toJson()).map { }

    fun disable(): Outcome<Unit> = documents.delete(KEY)

    companion object {
        const val KEY = "shared-tools"
    }
}

package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Наблюдаемое завершение очереди clangd; процент 100 сам по себе не подтверждает готовность. */
class ProjectIndex {
    private var revision = 0L
    private var running = false
    private var ready = false

    class Lease internal constructor(private val owner: ProjectIndex, private val revision: Long) {
        fun valid(): Boolean = owner.matches(revision)
    }

    @Synchronized fun requested(token: String?) {
        if (token != PROGRESS_ID) return
        revision++; running = true; ready = false
    }

    @Synchronized fun progress(token: String?, kind: String?) {
        if (token != PROGRESS_ID) return
        when (kind) {
            "begin" -> { revision++; running = true; ready = false }
            "end" -> if (running) { revision++; running = false; ready = true }
        }
    }

    @Synchronized fun stopped() { revision++; running = false; ready = false }

    @Synchronized fun lease(): Outcome<Lease> = if (ready && !running) Outcome.success(Lease(this, revision))
        else Outcome.failure(DiagCode.STATE_CONFLICT, "индекс C++ ещё не готов; дождитесь окончания индексации и повторите")

    @Synchronized private fun matches(expected: Long): Boolean = ready && !running && revision == expected

    companion object { const val PROGRESS_ID = "backgroundIndexProgress" }
}

package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.accounts.AiAdmission
import com.dpsmpx.dpai.accounts.AiRoutingState
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs

/** Приватное состояние допуска: без ключей, промптов и доступа через диск приложения. */
internal object ModelRoutingStore {
    fun install(context: Context) {
        val docs = DocumentStore(NioVfs(context.getDir("models", Context.MODE_PRIVATE).toPath()), "docs")
        val state = when (val read = docs.read("model-routing")) {
            is Outcome.Failure -> read
            is Outcome.Success -> read.value?.let {
                if (it.fromLastKnownGood) Outcome.failure(DiagCode.STORAGE_CORRUPTED, "восстановлена прежняя настройка ИИ: требуется сверка незавершённых запусков")
                else AiRoutingState.read(it.content)
            } ?: Outcome.success(AiRoutingState())
        }
        AiAdmission.global.install(state) { next -> docs.write("model-routing", next.json()).map { } }
    }
}

package com.dpsmpx.dpmeta.app.data

import android.content.Context
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.metrics.Store

/** Только существующий вычисленный срез пульта; без аккаунта, синхронизации и построения индекса. */
object WidgetCache {
    /** Обновить виджет (D-153): задаёт оболочка — пульт от неё не зависит. */
    @Volatile
    var onChanged: ((android.content.Context) -> Unit)? = null

    /** Срез пульта сменился (синхронизация) — виджету перечитать. */
    fun changed(context: Context) {
        runCatching { onChanged?.invoke(context.applicationContext) }
    }

    fun projects(context: Context) = Store(
        NioVfs(context.filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM, RandomSource.secure(),
    ).readState().map { state -> state?.let(Screens::summary) }
}

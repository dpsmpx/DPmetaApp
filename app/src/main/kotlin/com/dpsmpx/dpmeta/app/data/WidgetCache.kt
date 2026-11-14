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
    fun projects(context: Context) = Store(
        NioVfs(context.filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM, RandomSource.secure(),
    ).readState().map { state -> state?.let(Screens::summary) }
}

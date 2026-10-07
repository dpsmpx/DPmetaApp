package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.DashboardScreen
import com.dpsmpx.dpai.ui.DemoSystem
import com.dpsmpx.dpai.ui.SessionScreen
import com.dpsmpx.dpai.ui.TaskBoard
import java.io.File

/** Снимок для отрисовки: всё, что показывает приложение в один кадр. */
data class Snapshot(val dashboard: DashboardScreen, val board: TaskBoard, val session: SessionScreen?)

/**
 * Демо-система приложения (D-34): `DemoSystem` из `ui` с журналом в
 * кеше приложения. Журнал демо не хранится между запусками — каждый
 * запуск начинается с чистого каталога. Шаг и снимок — под одним
 * замком: их зовут с фонового потока.
 */
class DemoModel(dir: File) {
    private val demo: DemoSystem

    init {
        dir.deleteRecursively()
        dir.mkdirs()
        demo = DemoSystem(Clock.SYSTEM, NioVfs(dir.toPath()))
    }

    @Synchronized
    fun step(selected: String?): Snapshot {
        demo.step()
        val session = selected?.let { IdKinds.SESSION.parse(it).valueOrNull() }
        return Snapshot(demo.dashboard(), demo.board(), session?.let(demo::session))
    }

    companion object {
        @Volatile
        private var instance: DemoModel? = null

        /** Одна система на процесс: поворот экрана не перезапускает демо. */
        fun get(cacheDir: File): DemoModel = instance ?: synchronized(this) {
            instance ?: DemoModel(File(cacheDir, "demo")).also { instance = it }
        }
    }
}

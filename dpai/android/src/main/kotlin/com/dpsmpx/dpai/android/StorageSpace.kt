package com.dpsmpx.dpai.android

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.content.edit
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.space.SpaceGuard
import java.io.IOException

/**
 * Место для защиты `SpaceGuard` (`DPmetaApp:D-113`): свободно на томе данных
 * приложения, сколько приложение занимает целиком, предел автора. Предел —
 * настройка «Предел хранилища, ГБ» (`0` — всё свободное место телефона); общая
 * для пульта и «AI»: одно приложение — одно хранилище.
 */
object StorageSpace {
    private const val PREFS = "dpmeta.storage"
    private const val LIMIT_GB = "limit_gb"

    fun limitGb(context: Context): Int = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(LIMIT_GB, 0)

    fun setLimitGb(context: Context, gb: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(LIMIT_GB, gb.coerceIn(0, 4096)) }
    }

    /**
     * Свободно — с учётом кэша, который система очистит сама (`StorageManager`);
     * не ответила — `StatFs`. Занято — статистика своего приложения (своё — без
     * особого разрешения); не ответила — `null`: неизвестное — не ноль.
     */
    fun facts(context: Context): SpaceGuard.Facts {
        val app = context.applicationContext
        val dir = app.filesDir
        val sm = app.getSystemService(StorageManager::class.java)
        val uuid = runCatching { sm.getUuidForPath(dir) }.getOrNull()
        val free = try {
            if (uuid != null) sm.getAllocatableBytes(uuid) else StatFs(dir.path).availableBytes
        } catch (_: IOException) {
            StatFs(dir.path).availableBytes
        }
        val used = runCatching {
            val stats = app.getSystemService(StorageStatsManager::class.java).queryStatsForUid(uuid ?: StorageManager.UUID_DEFAULT, Process.myUid())
            stats.appBytes + stats.dataBytes + stats.cacheBytes
        }.getOrNull()
        return SpaceGuard.Facts(free, used, SpaceGuard.limitOf(limitGb(app)))
    }

    /** Можно ли начать [kind] размером [need] (`null` — оценка вида): отказ — с числами (`STORAGE_SPACE_LOW`). */
    fun check(context: Context, kind: SpaceGuard.Kind, need: Long?, what: String): Outcome<Unit> = SpaceGuard.check(facts(context), kind, need, what)
}

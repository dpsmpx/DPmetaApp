package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdKinds

/**
 * Автообновление лимитов слота (слово автора 11 октября 2026): включено —
 * `/usage` сразу и дальше раз в минуту, в фоне службы контроллера, а не
 * только при открытой сводке (D-48). Выбор — настройка этого телефона,
 * не журнала: на поведение контура не влияет.
 */
object UsageAuto {
    private const val PREFS = "usage-auto"
    private const val KEY = "targets"
    const val EVERY_MS = 60_000L

    fun enabled(context: Context): Set<ExecutionTargetId> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { IdKinds.TARGET.parse(it).valueOrNull() }.toSet()

    fun set(context: Context, target: ExecutionTargetId, on: Boolean) {
        val now = enabled(context).map { it.value }.toMutableSet()
        if (on) now += target.value else now -= target.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, now).apply()
    }
}

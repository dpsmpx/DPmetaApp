package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.ids.TaskId

/**
 * Имя сессии, которое задаёт человек (D-61): нажатие на название в
 * заголовке окна сессии. Одно на задачу — все её сессии в одном чате
 * (D-59). Настройка телефона: журнал контура и название задачи не
 * меняются; пустое имя — снова название задачи.
 */
object SessionNames {
    private const val PREFS = "session-names"

    fun name(context: Context, task: TaskId?): String? =
        task?.let { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(it.value, null) }

    fun set(context: Context, task: TaskId, name: String) {
        val clean = name.trim().take(80)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (clean.isEmpty()) remove(task.value) else putString(task.value, clean)
        }.apply()
    }
}

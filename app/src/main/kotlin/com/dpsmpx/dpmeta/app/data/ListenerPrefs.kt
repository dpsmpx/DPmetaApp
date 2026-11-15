package com.dpsmpx.dpmeta.app.data

import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpmeta.notify.AppNotifications

/**
 * Слушатель уведомлений других приложений (F-57; D-155): включён ли в приложении (по
 * умолчанию нет — и без системного доступа он ничего не видит) и белый список пакетов
 * (по умолчанию — приложение Claude). Хранится в `dpmeta.listener`.
 */
object ListenerPrefs {
    const val PREFS = "dpmeta.listener"
    private const val ENABLED = "enabled"
    private const val PACKAGES = "packages"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(ENABLED, on) }

    fun packages(context: Context): List<String> =
        prefs(context).getString(PACKAGES, null)?.let { AppNotifications.packages(it).ok } ?: AppNotifications.DEFAULT_PACKAGES

    fun setPackages(context: Context, list: List<String>) = prefs(context).edit { putString(PACKAGES, list.joinToString("\n")) }
}

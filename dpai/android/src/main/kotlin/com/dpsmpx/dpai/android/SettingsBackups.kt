package com.dpsmpx.dpai.android

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.SettingsBackup
import com.dpsmpx.dpai.ui.SettingsSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Копии настроек приложения в «Загрузки/dpai-backup» (D-51): файлы вне
 * приложения переживают его удаление и переустановку с другой подписью
 * (так 10 октября 2026 пропали настройки автора). Своё приложение пишет
 * туда через MediaStore без разрешений; загрузка — системным выбором
 * файла, поэтому читается и копия прежней установки.
 *
 * Сразу после изменения настроек и раз в час — новая копия; хранятся
 * последние [KEEP] своих. Пустые настройки (нет targets) не копируются:
 * свежая установка не плодит пустых копий.
 */
object SettingsBackups {
    private const val DIR = "dpai-backup"
    private const val PREFIX = "dpai-settings-"
    private const val KEEP = 24
    private const val EVERY_MS = 60L * 60 * 1000
    private const val CHECK_MS = 30L * 1000
    private const val PREFS = "settings-backup"

    @Volatile
    private var lastCheck = 0L

    /** Последняя копия: имя файла и когда — для экрана. */
    fun last(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val name = p.getString("lastName", null) ?: return null
        return name to p.getLong("lastAt", 0)
    }

    /** Такт службы и экрана: изменились настройки — копия сейчас; не менялись — раз в час. */
    fun tick(context: Context, controller: Controller) {
        val now = Clock.SYSTEM.now().epochMillis
        if (now - lastCheck < CHECK_MS) return
        lastCheck = now
        val s = controller.snapshot()
        if (s.targets.isEmpty()) return
        val p = prefs(context)
        val changed = p.getString("lastDigest", null) != SettingsBackup.digest(s)
        if (changed || now - p.getLong("lastAt", 0) >= EVERY_MS) save(context, s, controller)
    }

    /** Копия сейчас (кнопка «Сохранить»); имя файла. */
    @Synchronized
    fun save(context: Context, s: SettingsSnapshot, controller: Controller): Outcome<String> {
        val now = Clock.SYSTEM.now().epochMillis
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".json"
        val bytes = SettingsBackup.write(s, now, controller.version).toByteArray(Charsets.UTF_8)
        Downloads.write(context, DIR, name, "application/json", bytes, "копия настроек").let { if (it is Outcome.Failure) return it }
        prefs(context).edit { putString("lastDigest", SettingsBackup.digest(s)).putLong("lastAt", now).putString("lastName", name) }
        Downloads.prune(context, DIR, PREFIX, KEEP)
        return Outcome.success(name)
    }

    /**
     * Настройки из выбранного файла копии. Сначала — копия нынешних (если
     * есть что сохранить): восстановление не должно ничего терять.
     */
    fun restore(context: Context, uri: Uri, controller: Controller): Outcome<SettingsSnapshot> {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл копии не открылся", uri.toString())
        } catch (e: Exception) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл копии не прочитан", e, uri.toString()))
        }
        val s = when (val r = SettingsBackup.read(text)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val current = controller.snapshot()
        if (current.targets.isNotEmpty()) save(context, current, controller).let { if (it is Outcome.Failure) return it }
        return when (val r = controller.restore(s)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(s).also {
                prefs(context).edit { putString("lastDigest", SettingsBackup.digest(s)) }
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

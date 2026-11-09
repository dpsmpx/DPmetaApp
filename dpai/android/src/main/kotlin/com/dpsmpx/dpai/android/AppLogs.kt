package com.dpsmpx.dpai.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.orchestrator.log.AppLog
import com.dpsmpx.dpai.orchestrator.log.DeviceProbe
import com.dpsmpx.dpai.orchestrator.log.EventLog
import com.dpsmpx.dpai.orchestrator.log.FileLogSink
import com.dpsmpx.dpai.orchestrator.log.LogLevel
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File

/**
 * Журнал приложения на телефоне (D-43): один на процесс, файл
 * `files/logs/dpai.log` (до 1 МиБ, затем `.1`). Сюда — этапы запуска,
 * события контура (через `EventLog`: без текста модели и цели задачи),
 * сбои такта службы, сводка раз в минуту со сведениями устройства и
 * падения. Наружу ничего не уходит: выгрузка — только кнопкой человека.
 */
object AppLogs {
    @Volatile
    private var instance: AppLog? = null

    /**
     * Строка журнала от оболочки DPmetaApp (пульт, `DPmetaApp:D-39`): один журнал отладки на
     * приложение, без типов оркестратора у вызывающего; текст проходит Redactor журнала.
     */
    fun info(context: Context, tag: String, text: String) {
        get(context).info(tag, text)
    }

    fun get(context: Context): AppLog = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val dir = app.filesDir
            // Версия «AI» — своя; APK приложения DPmetaApp — со своей версией (DPmetaApp:D-54): обе в заголовке.
            val apk = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
            val version = BuildConfig.DPAI_VERSION + if (apk != BuildConfig.DPAI_VERSION) " · приложение $apk" else ""
            val sink = FileLogSink(
                NioVfs(dir.toPath()), header = {
                    listOf(
                        "AI $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
                    )
                },
                location = File(dir, FileLogSink.DEFAULT_PATH).path,
            )
            AppLog(Clock.SYSTEM, sink).also { instance = it }
        }
    }

    /** События журнала контура — строками журнала приложения. */
    fun events(context: Context): (List<EventEnvelope>) -> Unit {
        val log = get(context)
        return { es ->
            for (e in es) {
                val line = EventLog.line(e) ?: continue
                when (line.level) {
                    LogLevel.DEBUG -> log.debug(line.tag, line.text)
                    LogLevel.WARN -> log.warn(line.tag, line.text)
                    LogLevel.ERROR -> log.error(line.tag, line.text)
                    else -> log.info(line.tag, line.text)
                }
            }
        }
    }

    /**
     * Проблема контура — строкой журнала: код, сообщение и коренные причины
     * цепочки (D-05) — их не видно на сводке, а по ним чинят.
     */
    fun problems(context: Context): (com.dpsmpx.dpai.core.diagnostics.Diagnostic) -> Unit {
        val log = get(context)
        return { d ->
            val causes = d.rootCauses().filter { it !== d }.joinToString(" ← ") { "${it.code}: ${it.message}" }
            val text = Redactor.STANDARD.redact("${d.code}: ${d.message}" + (d.subject?.let { " [$it]" } ?: "") + if (causes.isNotEmpty()) " ← $causes" else "")
            if (d.isError) log.error("контур", text) else log.warn("контур", text)
        }
    }

    /** Сбой как строка журнала: тип и сообщение через редактор, без стека (D-05, D-14). */
    fun describe(e: Throwable): String = Redactor.STANDARD.redact("${e.javaClass.simpleName}: ${e.message}")

    /**
     * Сведения устройства для сводки (D-43): батарея и зарядка, экран,
     * Doze, экономия энергии — то, что подтверждает фоновую работу на
     * проверке (ТЗ, §96; D-28, D-39).
     */
    fun device(context: Context, wakeHeld: () -> Boolean?): DeviceProbe {
        val app = context.applicationContext
        return DeviceProbe {
            val power = app.getSystemService(PowerManager::class.java)
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let { b ->
                val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && s > 0) "${l * 100 / s}%" else "?"
            } ?: "?"
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.let { if (it > 0) "да" else "нет" } ?: "?"
            DeviceProbe.JVM.sample() + listOf(
                "батарея" to level,
                "зарядка" to plugged,
                "экран" to if (power.isInteractive) "вкл" else "выкл",
                "doze" to if (power.isDeviceIdleMode) "да" else "нет",
                "экономия" to if (power.isPowerSaveMode) "да" else "нет",
                "исключение_батареи" to if (power.isIgnoringBatteryOptimizations(app.packageName)) "да" else "нет",
                "wakelock" to (wakeHeld()?.let { if (it) "держится" else "нет" } ?: "—"),
            )
        }
    }
}

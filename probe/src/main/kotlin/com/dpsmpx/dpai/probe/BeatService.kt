package com.dpsmpx.dpai.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * П. 4 исследования: живёт ли процесс под proot, пока приложение в фоне
 * и экран выключен (ограничение фантомных процессов Android 12+, HyperOS).
 * Служба переднего плана держит процесс приложения — как служба
 * контроллера в продукте (D-36); под proot идёт цикл, который раз в 10 с
 * дописывает время в `/tmp/beat`. По файлу видно, когда процесс умер:
 * последняя отметка и наибольший разрыв между отметками.
 */
class BeatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Проба фона", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Пробник B2: проба фона")
            .setContentText("процесс под proot отмечается раз в 10 с")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
        // Тип specialUse — с Android 14; раньше служба переднего плана без типа.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        synchronized(Companion) {
            if (process?.isAlive != true) {
                val probe = Probe(this) {}
                File(probe.rootfs, "tmp/beat").delete()
                startedAt = System.currentTimeMillis()
                process = ProcessBuilder(
                    probe.prootArgv("while true; do date +%s >> /tmp/beat; sleep 10; done"),
                ).apply { environment().putAll(probe.prootEnvironment()) }.redirectErrorStream(true).redirectOutput(File(cacheDir, "beat.log")).start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(Companion) {
            process?.destroyForcibly()
            process = null
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "probe-beat"
        private const val ID = 1

        @Volatile
        private var process: Process? = null

        @Volatile
        private var startedAt: Long = 0

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BeatService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BeatService::class.java))
        }

        /** Итог пробы фона: жив ли процесс, отметки, последняя и наибольший разрыв. */
        fun check(context: Context): String {
            val beats = File(Probe(context) {}.rootfs, "tmp/beat").takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
            val now = System.currentTimeMillis() / 1000
            val alive = process?.isAlive == true
            if (beats.isEmpty()) return "фон: отметок нет; процесс ${if (alive) "жив" else "не запущен или умер"}"
            val gap = beats.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0
            val since = if (startedAt > 0) (now - startedAt / 1000) / 60 else 0
            val verdict = if (alive && now - beats.last() <= 30) "ДА — процесс жив" else "НЕТ — процесс умер или стоит"
            return "ИТОГ п. 4 фон: $verdict; отметок ${beats.size} за $since мин, последняя ${now - beats.last()} с назад, наибольший разрыв $gap с"
        }
    }
}

package com.dpsmpx.dpai.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.AppComponents
import com.dpsmpx.dpai.ui.EnvUpdates
import java.io.File

/**
 * Проверка обновлений окружения раз в сутки (D-197; `DPmetaApp:D-171`, шаг 7): задача
 * JobScheduler при Wi-Fi (без оплаты трафика) и зарядке. В госте — только `apk update` и
 * `apk version -l '<'` (адресат — прежнее зеркало Alpine), компоненты — сверкой с версиями
 * этой сборки, без сети. Есть новое и набор не тот, о котором уже говорили, — уведомление;
 * ставит только человек. Идёт работа гостя — пропуск до следующего раза.
 */
class EnvUpdatesJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        kotlin.concurrent.thread(name = "dpai-env-updates") {
            runCatching { check(app, notify = true) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 0x0E4F_0D97
        private const val PREFS = "dpai.environment"
        private const val ON = "updatesDaily"
        private const val LAST = "updatesLast"
        private const val NOTIFIED = "updatesNotified"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun on(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, true)

        fun setOn(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(ON, on) }
            schedule(context)
        }

        fun last(context: Context): EnvUpdates.Result? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null)?.let { (EnvUpdates.decode(it) as? Outcome.Success)?.value }

        /** Поставить задачу по настройке; уже стоит — не трогать (не сбивать расписание). */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!on(context)) { scheduler.cancel(JOB_ID); return }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, EnvUpdatesJob::class.java))
                .setPeriodic(DAY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresCharging(true)
                // Переживает перезагрузку (RECEIVE_BOOT_COMPLETED в манифесте): при загрузке ничего другого не запускается.
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        /**
         * Проверить сейчас: итог — запомнить; [notify] — уведомить, если набор новый. Linux не
         * установлен или идёт работа гостя — `null` (ничего не меняется).
         */
        fun check(context: Context, notify: Boolean): EnvUpdates.Result? {
            val app = context.applicationContext
            val env = LinuxSetup.environment(app)
            if (env.installed() == null) return null
            if (notify && LongJobs.get(app).busyGuest()) return null
            val vfs = NioVfs(app.filesDir.toPath())
            val inv = (AlpinePackages.read(vfs, env.rootfs) as? Outcome.Success)?.value ?: return null
            val packages = when (LinuxSetup.apkReadOnly(app, LinuxEnvironment.Apk.UPDATE)) {
                is Outcome.Failure -> null
                is Outcome.Success -> (LinuxSetup.apkReadOnly(app, LinuxEnvironment.Apk.LIST_UPGRADES) as? Outcome.Success)?.value?.let { EnvUpdates.parse(it.stdout, inv) }
            } ?: return null
            val arch = inv.byName["musl"]?.arch
            val components = EnvUpdates.components(runCatching { AppComponents.scan(File(app.filesDir, env.rootfs).toPath(), arch) }.getOrDefault(emptyList()))
            val now = Clock.SYSTEM.now()
            val result = EnvUpdates.Result(now.epochMillis, packages, components)
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val notified = prefs.getString(NOTIFIED, null)?.let { (EnvUpdates.decode(it) as? Outcome.Success)?.value }
            prefs.edit { putString(LAST, EnvUpdates.encode(result)) }
            if (notify && EnvUpdates.changed(result, notified)) {
                EnvUpdates.notice(result)?.let { body ->
                    Notifier(app).show(Notification("env-updates", NotificationCategory.INFO, "Окружение: есть обновления", body, null, 1, now, now))
                    prefs.edit { putString(NOTIFIED, EnvUpdates.encode(result)) }
                }
            }
            AppLogs.info(app, "окружение", "проверка обновлений: пакетов ${packages.size}, компонентов ${components.size}")
            return result
        }
    }
}

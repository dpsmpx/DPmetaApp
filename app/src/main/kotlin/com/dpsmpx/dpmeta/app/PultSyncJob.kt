package com.dpsmpx.dpmeta.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.data.BackgroundSync
import com.dpsmpx.dpmeta.app.data.PultPool
import com.dpsmpx.dpmeta.app.data.PultRepository
import com.dpsmpx.dpmeta.app.data.SecretStore
import java.io.File

/**
 * Фоновое обновление пульта (D-149): периодическая задача JobScheduler тем же путём,
 * что «Обновить». Токен — только если он открыт после недавней разблокировки или
 * входа (T-01 не ослабляется); иначе запуск тихо пропускается. Новые клоны не
 * создаются (D-78). Итог — строкой в настройках «Ещё».
 */
class PultSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        // Свой поток: синхронизация сама раздаёт запросы в PultPool.work и ждёт их — не занимать его же.
        kotlin.concurrent.thread(name = "dpmeta-background-sync") {
            val result = runCatching { run(app) }.getOrElse { BackgroundSync.Result.Failed(it.javaClass.simpleName) }
            remember(app, result)
            jobFinished(params, false)
        }
        return true
    }

    /** Система прерывает задачу (нет сети, предел времени): синхронизация отменяется, следующая — по расписанию. */
    override fun onStopJob(params: JobParameters): Boolean {
        PultRepository.cancelRunningClones()
        return false
    }

    companion object {
        private const val JOB_ID = 0x0D9E_7A11
        private const val PREFS = "dpmeta.background"
        private const val INTERVAL = "interval"
        private const val LAST = "last"

        fun interval(context: Context): Int = BackgroundSync.interval(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p -> if (p.contains(INTERVAL)) p.getInt(INTERVAL, BackgroundSync.DEFAULT) else null })

        fun lastLine(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null)

        /** Сменить интервал (0 — выключить) и поставить или снять задачу. */
        fun setInterval(context: Context, minutes: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(INTERVAL, BackgroundSync.interval(minutes)) }
            schedule(context)
        }

        /** Поставить задачу по настройке; уже стоит с тем же интервалом — не трогать (не сбивать расписание). */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val minutes = interval(context)
            if (minutes == 0) { scheduler.cancel(JOB_ID); return }
            val period = minutes * 60_000L
            if (scheduler.getPendingJob(JOB_ID)?.intervalMillis == period) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, PultSyncJob::class.java))
                .setPeriodic(period)
                // ТЗ, N-04: «есть сеть» и «не низкий заряд».
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                // Переживает перезагрузку (RECEIVE_BOOT_COMPLETED, D-149): при загрузке ничего другого не запускается.
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        private fun run(app: Context): BackgroundSync.Result {
            val secrets = SecretStore(NioVfs(app.noBackupFilesDir.toPath().resolve("dpmeta")))
            val token = when (val t = secrets.load()) {
                is Outcome.Failure -> return if (t.diagnostics.first().code == DiagCode.DEVICE_AUTH_REQUIRED) BackgroundSync.Result.TokenLocked else BackgroundSync.Result.Failed(t.diagnostics.first().message)
                is Outcome.Success -> t.value ?: return BackgroundSync.Result.NoToken
            }
            val repo = PultRepository(NioVfs(app.filesDir.toPath().resolve("dpmeta")), Clock.SYSTEM, PultPool.work, File(app.filesDir, PultActivity.CLONES_DIR), PultPool.clones)
            if (repo.syncing()) return BackgroundSync.Result.Busy
            val selected = PultActivity.selectedClones(app)
            val clones = BackgroundSync.clones(selected, runCatching { repo.missingClones(selected) }.getOrDefault(true))
            return when (val r = repo.sync(token, clones)) {
                is Outcome.Failure -> if (r.diagnostics.first().code == DiagCode.STATE_CONFLICT) BackgroundSync.Result.Busy else BackgroundSync.Result.Failed(r.diagnostics.first().message)
                is Outcome.Success -> {
                    // Тревоги (D-85): новые события — через правила в уведомления, как у «Обновить».
                    if (r.value.events.isNotEmpty()) runCatching { com.dpsmpx.dpmeta.app.data.PultAlerts.post(app, r.value.events, Clock.SYSTEM.now()) }
                    BackgroundSync.Result.Done(r.value.line)
                }
            }
        }

        private fun remember(app: Context, result: BackgroundSync.Result) {
            PultActivity.applyZone(app)
            val at = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(com.dpsmpx.dpai.core.clock.DisplayZone.current)
                .format(java.time.Instant.ofEpochMilli(Clock.SYSTEM.now().epochMillis))
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(LAST, BackgroundSync.line(at, result.text)) }
        }
    }
}

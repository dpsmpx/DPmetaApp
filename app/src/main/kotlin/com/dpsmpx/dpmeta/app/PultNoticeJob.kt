package com.dpsmpx.dpmeta.app

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.DisplayZone
import com.dpsmpx.dpmeta.app.data.NoticeCard
import com.dpsmpx.dpmeta.app.data.PultAlerts
import com.dpsmpx.dpmeta.app.data.PultNotices

/**
 * Сроки уведомлений пульта (D-152): отложенная карточка приходит снова, сводка — по
 * расписанию. Срок держит JobScheduler (`setPersisted` — переживает перезагрузку;
 * `RECEIVE_BOOT_COMPLETED` уже есть, D-149); точность — минуты, как у системы.
 */
class PultNoticeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val extras = params.extras
        when (extras.getString(KIND)) {
            REPOST -> NoticeCard.from(extras)?.let { PultAlerts.present(app, it, silent = PultAlerts.quietNow(app)) }
            DIGEST -> PultAlerts.postDigest(app)
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val KIND = "kind"
        private const val REPOST = "repost"
        private const val DIGEST = "digest"
        private const val DIGEST_ID = 0x0D9E_D16E
        private const val REPOST_BASE = 0x0D9E_5000

        /** Окно запуска после срока: система может задержать (Doze), но не сильно. */
        private const val WINDOW_MS = 10L * 60 * 1000

        private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

        private fun job(context: Context, id: Int, atMillis: Long, extras: PersistableBundle): JobInfo {
            val delay = (atMillis - Clock.SYSTEM.now().epochMillis).coerceAtLeast(0)
            return JobInfo.Builder(id, ComponentName(context, PultNoticeJob::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + WINDOW_MS)
                .setPersisted(true)
                .setExtras(extras)
                .build()
        }

        /** Показать [card] снова в [atMillis] (кнопка «Отложить»). */
        fun scheduleRepost(context: Context, card: NoticeCard, atMillis: Long) {
            val extras = card.toBundle().apply { putString(KIND, REPOST) }
            scheduler(context)?.schedule(job(context, REPOST_BASE + (card.tag.hashCode() and 0xFFF), atMillis, extras))
        }

        /**
         * Сводка — к ближайшему сроку расписания, если есть что показать; уже назначенная на
         * этот срок не переназначается. Смена расписания — `force`.
         */
        fun scheduleDigest(context: Context, force: Boolean = false) {
            val app = context.applicationContext
            val s = scheduler(app) ?: return
            if (PultNotices.pending(app).events.isEmpty()) { s.cancel(DIGEST_ID); return }
            val now = Clock.SYSTEM.now()
            val at = PultNotices.digest(app).next(now, DisplayZone.current).epochMillis
            val existing = s.getPendingJob(DIGEST_ID)
            if (!force && existing != null && existing.extras.getLong(AT) == at) return
            s.schedule(job(app, DIGEST_ID, at, PersistableBundle().apply { putString(KIND, DIGEST); putLong(AT, at) }))
        }

        private const val AT = "at"
    }
}

/** Кнопки уведомления пульта (D-152): «Отложить» — убрать и назначить повтор. Только изнутри. */
class PultNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SNOOZE) return
        val card = intent.extras?.let(NoticeCard::from) ?: return
        val index = intent.getIntExtra(INDEX, 0)
        val option = PultNotices.snoozes(context).getOrNull(index) ?: return
        val until = option.until(Clock.SYSTEM.now(), DisplayZone.current).epochMillis
        context.getSystemService(NotificationManager::class.java)?.cancel(card.tag, card.tag.hashCode())
        PultNoticeJob.scheduleRepost(context, card, until)
    }

    companion object {
        const val SNOOZE = "com.dpsmpx.dpmeta.SNOOZE"
        const val INDEX = "snooze"
    }
}

package com.dpsmpx.dpmeta.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.dpsmpx.dpai.core.clock.DisplayZone
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.R
import com.dpsmpx.dpmeta.app.screens.AlertActions
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.model.AlertChannel
import com.dpsmpx.dpmeta.model.AlertImportance
import com.dpsmpx.dpmeta.model.AlertRules
import com.dpsmpx.dpmeta.model.EventCatalog
import com.dpsmpx.dpmeta.notify.Alerts
import com.dpsmpx.dpmeta.notify.Decision
import com.dpsmpx.dpmeta.notify.QuietHours
import com.dpsmpx.dpmeta.notify.Shown
import java.io.IOException

/**
 * Тревоги пульта (ТЗ, §6.7, F-37, F-38; D-85): события синхронизации — через движок
 * `:core:notify` — в уведомления Android. Каналы — по видам (F-38) и важности; на
 * экране блокировки — без проектов и текстов (приватность по умолчанию, T-08); тихие
 * часы — без звука, кроме срочных правил. История показа для антидребезга — файлом
 * `alerts-shown.json` (`dpmeta.alerts-shown` v1) в данных пульта.
 */
object PultAlerts {
    const val EVENTS_ASSET = "events.toml"
    const val ALERTS_ASSET = "alerts.toml"
    const val SHOWN_FORMAT = "dpmeta.alerts-shown"
    private const val SHOWN = "alerts-shown.json"
    private const val PREFS = "dpmeta.settings"
    private const val QUIET_FROM = "quiet_from"
    private const val QUIET_TO = "quiet_to"
    private const val PRIVACY = "alerts_privacy"

    /** Каталог и правила из APK. */
    data class Config(val catalog: EventCatalog, val rules: AlertRules)

    @Volatile
    private var config: Outcome<Config>? = null

    /** Каталог и правила (`config/events.toml`, `config/alerts.toml` в APK) — разбор один раз на процесс. */
    fun config(context: Context): Outcome<Config> {
        config?.let { return it }
        val assets = context.applicationContext.assets
        fun read(name: String): String? = try {
            assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: IOException) {
            null
        }
        val events = read(EVENTS_ASSET) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "каталога событий нет в сборке", EVENTS_ASSET)
        val alerts = read(ALERTS_ASSET) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "правил тревог нет в сборке", ALERTS_ASSET)
        val r = when (val c = EventCatalogFormat.parse(events, EVENTS_ASSET)) {
            is Outcome.Failure -> c
            is Outcome.Success -> when (val a = AlertFormat.parse(alerts, c.value, ALERTS_ASSET)) {
                is Outcome.Failure -> a
                is Outcome.Success -> Outcome.success(Config(c.value, a.value))
            }
        }
        config = r
        return r
    }

    /** Настройки уведомлений: тихие часы (`null` — выкл) и приватность экрана блокировки. */
    data class Settings(val quiet: QuietHours?, val privacy: Boolean)

    fun settings(context: Context): Settings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val from = p.getInt(QUIET_FROM, AlertActions.DEFAULT_QUIET.fromMinute)
        val to = p.getInt(QUIET_TO, AlertActions.DEFAULT_QUIET.toMinute)
        val quiet = if (from in 0 until QuietHours.MINUTES_IN_DAY && to in 0 until QuietHours.MINUTES_IN_DAY && from != to) QuietHours(from, to) else null
        return Settings(quiet, p.getBoolean(PRIVACY, true))
    }

    fun setQuiet(context: Context, quiet: QuietHours?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(QUIET_FROM, quiet?.fromMinute ?: -1)
            putInt(QUIET_TO, quiet?.toMinute ?: -1)
        }
    }

    fun setPrivacy(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(PRIVACY, on) }
    }

    private fun vfs(context: Context): NioVfs = NioVfs(context.applicationContext.filesDir.toPath().resolve("dpmeta")).also { it.mkdirs("") }

    /** История показа: сломанный или чужой файл — пустая (антидребезг начнётся заново, не падение). */
    private fun readShown(context: Context): List<Shown> {
        val text = try {
            vfs(context).read(SHOWN)?.toString(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        } ?: return emptyList()
        val o = JsonParser().parse(text).valueOrNull() as? JsonObject ?: return emptyList()
        if ((o["format"] as? JsonString)?.value != SHOWN_FORMAT) return emptyList()
        return (o["shown"] as? JsonArray)?.items.orEmpty().mapNotNull { v ->
            val e = v as? JsonObject ?: return@mapNotNull null
            val rule = (e["rule"] as? JsonString)?.value ?: return@mapNotNull null
            val at = (e["at"] as? JsonNumber)?.toLongExact() ?: return@mapNotNull null
            Shown(rule, (e["project"] as? JsonString)?.value, Timestamp(at))
        }
    }

    private fun writeShown(context: Context, shown: List<Shown>) {
        val doc = JsonObject(
            linkedMapOf<String, JsonValue>(
                *FormatHeader.write(SHOWN_FORMAT, 1),
                "shown" to JsonArray(
                    shown.map { s ->
                        JsonObject(
                            linkedMapOf<String, JsonValue>("rule" to JsonString(s.rule), "at" to JsonNumber.of(s.at.epochMillis)).also { m ->
                                s.project?.let { m["project"] = JsonString(it) }
                            },
                        )
                    },
                ),
            ),
        )
        runCatching {
            val v = vfs(context)
            v.write("$SHOWN.tmp", CanonicalJson.write(doc).toByteArray(Charsets.UTF_8))
            v.rename("$SHOWN.tmp", SHOWN)
        }
    }

    /**
     * Решить о событиях синхронизации [events] и показать уведомления. Итог — строка
     * для журнала отладки: сколько событий, сколько показано, сколько отброшено; текстов
     * событий в ней нет (D-39).
     */
    @Synchronized
    fun post(context: Context, events: List<FeedEvent>, now: Timestamp): String {
        if (events.isEmpty()) return "событий нет"
        val app = context.applicationContext
        val cfg = when (val c = config(app)) {
            is Outcome.Failure -> return "правила не прочитаны: ${c.errors.first().code}"
            is Outcome.Success -> c.value
        }
        val s = settings(app)
        val decided = Alerts.decide(events.mapNotNull(Feed::event), cfg.catalog, cfg.rules, now, DisplayZone.current, s.quiet, readShown(app))
        writeShown(app, decided.shown)
        val notify = decided.decisions.filterIsInstance<Decision.Notify>()
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        var posted = 0
        if (allowed) {
            for (d in notify) if (show(app, d, cfg.catalog, s.privacy)) posted++
        }
        val dropped = decided.decisions.count { it is Decision.Dropped }
        return "событий ${events.size}, к показу ${notify.size}, показано $posted" +
            (if (!allowed) " (нет разрешения на уведомления)" else "") + (if (dropped > 0) ", отброшено $dropped" else "")
    }

    /** Канал Android вида [channel] и важности [importance]: создаётся при первом показе. */
    private fun channel(manager: NotificationManager, channel: AlertChannel, importance: AlertImportance): String {
        val id = "dpmeta.alert.${channel.code}" + when (importance) {
            AlertImportance.DEFAULT -> ""
            AlertImportance.HIGH -> ".high"
            AlertImportance.LOW -> ".low"
        }
        if (manager.getNotificationChannel(id) == null) {
            val (level, suffix) = when (importance) {
                AlertImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT to ""
                AlertImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH to " — срочное"
                AlertImportance.LOW -> NotificationManager.IMPORTANCE_LOW to " — тихое"
            }
            manager.createNotificationChannel(
                NotificationChannel(id, "Пульт: ${channel.title}$suffix", level).apply {
                    description = "Тревоги пульта DPmetaApp по правилам: ${channel.title.lowercase()}."
                    // Приватность (T-08): на экране блокировки — только общая строка.
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
        return id
    }

    private fun show(context: Context, d: Decision.Notify, catalog: EventCatalog, privacy: Boolean): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val id = channel(manager, d.channel, d.importance)
        val typeTitle = catalog.type(d.event.type)?.title ?: d.event.type
        val title = listOfNotNull(d.event.project, typeTitle).joinToString(": ")
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val pending = open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        // Экран блокировки (T-08): только вид события — без проекта и текста.
        val public = NotificationCompat.Builder(context, id)
            .setSmallIcon(R.drawable.ic_pult_summary)
            .setContentTitle("DPmetaApp")
            .setContentText(typeTitle)
            .build()
        val n = NotificationCompat.Builder(context, id)
            .setSmallIcon(R.drawable.ic_pult_summary)
            .setContentTitle(title)
            .setContentText(d.event.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(d.event.text))
            .setWhen(d.event.at.epochMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup("dpmeta.alert.${d.rule.id}")
            .setSilent(d.silent)
            .setVisibility(if (privacy) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(public)
            .apply { pending?.let(::setContentIntent) }
            .build()
        return try {
            manager.notify(d.tag, d.tag.hashCode(), n)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}

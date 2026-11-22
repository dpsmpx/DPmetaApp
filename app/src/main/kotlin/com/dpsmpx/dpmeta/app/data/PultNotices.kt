package com.dpsmpx.dpmeta.app.data

import android.content.Context
import android.os.PersistableBundle
import androidx.core.content.edit
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.model.Event
import com.dpsmpx.dpmeta.notify.Digest
import com.dpsmpx.dpmeta.notify.DigestSchedule
import com.dpsmpx.dpmeta.notify.SnoozeOption

/**
 * Карточка уведомления пульта (D-152): всё, чтобы показать её снова после «Отложить» —
 * та же метка, канал, текст и кнопки. [pullProject]/[pullNumber] — для «Открыть PR»,
 * [url] — для «Открыть сессию» события; [ask] — касание открывает чат
 * «Спросить Claude» (F-58; D-161).
 */
data class NoticeCard(
    val tag: String,
    val channel: String,
    val title: String,
    val text: String,
    val typeTitle: String,
    val whenMs: Long,
    val group: String,
    val pullProject: String? = null,
    val pullNumber: Int? = null,
    val url: String? = null,
    val ask: Boolean = false,
) {
    fun toBundle(): PersistableBundle = PersistableBundle().apply {
        putString("tag", tag); putString("channel", channel); putString("title", title); putString("text", text)
        putString("typeTitle", typeTitle); putLong("when", whenMs); putString("group", group)
        pullProject?.let { putString("pullProject", it) }
        pullNumber?.let { putInt("pullNumber", it) }
        url?.let { putString("url", it) }
        if (ask) putBoolean("ask", true)
    }

    companion object {
        fun from(b: PersistableBundle): NoticeCard? = NoticeCard(
            b.getString("tag") ?: return null, b.getString("channel") ?: return null, b.getString("title") ?: return null,
            b.getString("text") ?: return null, b.getString("typeTitle") ?: return null, b.getLong("when"), b.getString("group") ?: return null,
            b.getString("pullProject"), b.getInt("pullNumber", -1).takeIf { it > 0 }, b.getString("url"), b.getBoolean("ask", false),
        )

        fun from(b: android.os.Bundle): NoticeCard? = NoticeCard(
            b.getString("tag") ?: return null, b.getString("channel") ?: return null, b.getString("title") ?: return null,
            b.getString("text") ?: return null, b.getString("typeTitle") ?: return null, b.getLong("when"), b.getString("group") ?: return null,
            b.getString("pullProject"), b.getInt("pullNumber", -1).takeIf { it > 0 }, b.getString("url"), b.getBoolean("ask", false),
        )

        fun toBundle(c: NoticeCard): android.os.Bundle = android.os.Bundle().apply {
            putString("tag", c.tag); putString("channel", c.channel); putString("title", c.title); putString("text", c.text)
            putString("typeTitle", c.typeTitle); putLong("when", c.whenMs); putString("group", c.group)
            c.pullProject?.let { putString("pullProject", it) }
            c.pullNumber?.let { putInt("pullNumber", it) }
            c.url?.let { putString("url", it) }
            if (c.ask) putBoolean("ask", true)
        }
    }
}

/**
 * Настройки и накопитель дайджеста пульта (D-152): расписание сводки, две кнопки
 * «Отложить»; события «в сводку» — в `digest.json` (не больше [Digest.MAX_PENDING],
 * старые выпадают — их число помнится).
 */
object PultNotices {
    private const val PREFS = "dpmeta.settings"
    private const val DIGEST = "digest_schedule"
    private const val SNOOZE = "snooze_"
    private const val FILE = "digest.json"
    private const val FORMAT = "dpmeta.digest"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun digest(context: Context): DigestSchedule = DigestSchedule.parse(prefs(context).getString(DIGEST, null))

    fun setDigest(context: Context, s: DigestSchedule) = prefs(context).edit { putString(DIGEST, s.code) }

    fun snoozes(context: Context): List<SnoozeOption> = (0..1).map { SnoozeOption.parse(prefs(context).getString("$SNOOZE${it + 1}", null), it) }

    fun setSnooze(context: Context, index: Int, option: SnoozeOption) = prefs(context).edit { putString("$SNOOZE${index + 1}", option.code) }

    private fun vfs(context: Context) = NioVfs(context.applicationContext.filesDir.toPath().resolve("dpmeta")).also { it.mkdirs("") }

    /** Накопленное: события и сколько выпало из-за предела. */
    data class Pending(val events: List<Event>, val dropped: Int)

    @Synchronized
    fun pending(context: Context): Pending {
        val text = runCatching { vfs(context).read(FILE)?.toString(Charsets.UTF_8) }.getOrNull() ?: return Pending(emptyList(), 0)
        val o = JsonParser().parse(text).valueOrNull() as? JsonObject ?: return Pending(emptyList(), 0)
        if ((o["format"] as? JsonString)?.value != FORMAT) return Pending(emptyList(), 0)
        val events = (o["events"] as? JsonArray)?.items.orEmpty().mapNotNull { v ->
            val e = v as? JsonObject ?: return@mapNotNull null
            Event(
                (e["type"] as? JsonString)?.value ?: return@mapNotNull null,
                Timestamp((e["at"] as? JsonNumber)?.toLongExact() ?: return@mapNotNull null),
                (e["project"] as? JsonString)?.value,
                (e["text"] as? JsonString)?.value.orEmpty(),
            )
        }
        return Pending(events, ((o["dropped"] as? JsonNumber)?.toLongExact() ?: 0L).toInt())
    }

    /** Добавить события «в сводку»; итог — сколько теперь ждёт. */
    @Synchronized
    fun add(context: Context, events: List<Event>): Int {
        if (events.isEmpty()) return pending(context).events.size
        val before = pending(context)
        val all = before.events + events
        val keep = all.takeLast(Digest.MAX_PENDING)
        write(context, Pending(keep, before.dropped + (all.size - keep.size)))
        return keep.size
    }

    /** Забрать накопленное (для показа сводки) и очистить. */
    @Synchronized
    fun take(context: Context): Pending = pending(context).also { write(context, Pending(emptyList(), 0)) }

    private fun write(context: Context, p: Pending) {
        val doc = JsonObject(
            linkedMapOf(
                *FormatHeader.write(FORMAT, 1),
                "dropped" to JsonNumber.of(p.dropped),
                "events" to JsonArray(p.events.map { e ->
                    JsonObject(linkedMapOf<String, JsonValue>("type" to JsonString(e.type), "at" to JsonNumber.of(e.at.epochMillis), "text" to JsonString(e.text.take(500)))
                        .also { m -> e.project?.let { m["project"] = JsonString(it) } })
                }),
            ),
        )
        val v = vfs(context)
        runCatching {
            v.write("$FILE.tmp", CanonicalJson.write(doc).toByteArray(Charsets.UTF_8))
            v.rename("$FILE.tmp", FILE)
        }
    }
}

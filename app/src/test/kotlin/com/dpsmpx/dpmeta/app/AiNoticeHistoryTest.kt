package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.app.data.AiNoticeHistory
import com.dpsmpx.dpmeta.app.data.RecordedNotice
import com.dpsmpx.dpmeta.app.screens.AiNotices
import com.dpsmpx.dpmeta.app.screens.AlertActions
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import com.dpsmpx.dpmeta.model.Event
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiNoticeHistoryTest {
    @TempDir lateinit var dir: Path
    private fun store() = AiNoticeHistory(DocumentStore(NioVfs(dir), "settings"))
    private fun notice() = Notification("task-key", NotificationCategory.INFO, "ProjectX: задача", "ответ", null, 1, Timestamp(10), Timestamp(11))

    @Test
    fun requiredMessagesCannotEnterUserFilterAndProjectIsNotGuessed() {
        val n = notice()
        assertNull(AiNotices.event(n.copy(category = NotificationCategory.SECURITY)))
        assertNull(AiNotices.event(n.copy(category = NotificationCategory.APPROVAL)))
        assertNull(AiNotices.event(n.copy(request = IdGenerator(ManualClock(), RandomSource.seeded(7)).next(IdKinds.REQUEST))))
        val event = AiNotices.event(n)!!
        assertNull(event.project)
        assertEquals("ai.info", event.type)
        assertEquals(mapOf("count" to 1.0), event.numbers)
        val synthetic = listOf("gh", "p_").joinToString("") + "A".repeat(36)
        assertFalse(AiNotices.event(n.copy(body = "token $synthetic"))!!.text.contains(synthetic))
    }

    @Test
    fun updatedAggregationReplacesRecordAndSilentIsExcludedOnlyFromVisibleFeed() {
        val store = store()
        val first = AiNotices.event(notice())!!
        assertIs<Outcome.Success<*>>(store.add(RecordedNotice(first, true), Timestamp(12)))
        val updated = AiNotices.event(notice().copy(count = 4, lastAt = Timestamp(20)))!!
        assertEquals(first.key, updated.key)
        assertIs<Outcome.Success<*>>(store.add(RecordedNotice(updated, false), Timestamp(21)))
        val records = (store.read(Timestamp(22)) as Outcome.Success).value
        assertEquals(1, records.size)
        assertEquals(4.0, records.single().event.numbers["count"])
        assertEquals(Timestamp(20), records.single().event.at)
        assertTrue(AiNotices.feed(records).isEmpty())
    }

    @Test
    fun cacheBoundsAndWeekRetentionApplyAtReadAndWrite() {
        val store = store()
        val record = RecordedNotice(Event("ai.info", Timestamp(1), null, "old", mapOf("title" to "old"), mapOf("count" to 1.0), "old"), true)
        assertIs<Outcome.Success<*>>(store.add(record, Timestamp(2)))
        val now = Timestamp(8 * 24 * 60 * 60 * 1000L)
        assertTrue((store.read(now) as Outcome.Success).value.isEmpty())
        repeat(AiNoticeHistory.MAX_ITEMS + 2) { index ->
            assertIs<Outcome.Success<*>>(store.add(record.copy(event = record.event.copy(at = now, key = "new-$index")), now))
        }
        val records = (store.read(now) as Outcome.Success).value
        assertEquals(AiNoticeHistory.MAX_ITEMS, records.size)
        assertTrue(records.none { it.event.key in setOf("old", "new-0", "new-1") })
    }

    @Test
    fun textCutDoesNotSplitSurrogatePairOrIntroduceFilterFields() {
        val prefix = notice().title.length + 1
        val before = AiNoticeHistory.MAX_TEXT - prefix - 1
        val source = "word ".repeat(before / 5 + 1).take(before) + "😀tail"
        val event = AiNotices.event(notice().copy(body = source))!!
        val saved = (store().add(RecordedNotice(event, true), Timestamp(20)) as Outcome.Success).value.single()
        val back = (store().read(Timestamp(20)) as Outcome.Success).value.single()
        assertTrue(saved.truncated && back.truncated)
        assertTrue(back.event.text.length <= AiNoticeHistory.MAX_TEXT)
        assertFalse(back.event.text.last().isHighSurrogate())
        assertFalse("truncated" in back.event.fields, "служебная метка не должна влиять на contains/regex")
    }

    @Test
    fun previewDoesNotCountAiFeedProjectionTwice() {
        val catalog = EventCatalogFormat.parse("""
            schema = 1
            [[event]]
            type = "ai.info"
            title = "Сведения ИИ"
            source = ["app"]
            channel = "claude"
        """.trimIndent()).valueOrNull()!!
        val rules = AlertFormat.parse("""
            schema = 1
            [[rule]]
            id = "ai"
            title = "ИИ"
            on = "ai.*"
            action = "notify"
        """.trimIndent(), catalog).valueOrNull()!!
        val event = AiNotices.event(notice())!!
        val records = listOf(RecordedNotice(event, true), RecordedNotice(event.copy(key = "other"), false))
        val rows = AlertActions.rows(rules, catalog, AiNotices.feed(records), Timestamp(30), ZoneOffset.UTC, null, records.map { it.event })
        assertEquals(2, rows.single().tried)
    }
}

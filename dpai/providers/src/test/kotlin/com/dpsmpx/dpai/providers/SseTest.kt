package com.dpsmpx.dpai.providers

import kotlin.test.Test
import kotlin.test.assertEquals

/** Разбор SSE: события, многострочные данные, комментарии, событие без пустой строки в конце. */
class SseTest {
    private fun parse(text: String, stopAfter: Int = Int.MAX_VALUE): List<SseEvent> {
        val lines = text.split("\n").iterator()
        val out = mutableListOf<SseEvent>()
        Sse.read({ if (lines.hasNext()) lines.next() else null }) {
            out += it
            out.size < stopAfter
        }
        return out
    }

    @Test
    fun eventsAndData() {
        val text = ": комментарий\nevent: a\ndata: {\"x\":1}\n\ndata:без пробела\ndata: вторая\n\n\n\nevent: пустое\n\ndata: [DONE]"
        assertEquals(
            listOf(SseEvent("a", "{\"x\":1}"), SseEvent(null, "без пробела\nвторая"), SseEvent(null, "[DONE]")),
            parse(text),
        )
    }

    @Test
    fun stopsWhenAsked() {
        assertEquals(1, parse("data: 1\n\ndata: 2\n\n", stopAfter = 1).size)
    }
    @Test fun accumulatedEventAndEntireResponseAreBoundedWithoutReportingPartialSuccess() {
        val lines = listOf("data: 12345", "data: 67890", "").iterator()
        kotlin.test.assertFailsWith<java.io.IOException> {
            Sse.read({ if (lines.hasNext()) lines.next() else null }, 10, 100) { error("oversized event must not dispatch") }
        }
        var read = 0
        kotlin.test.assertFailsWith<java.io.IOException> {
            Sse.read({ read++; ": keepalive" }, 10, 30) { true }
        }
        assertEquals(3, read)
    }

}

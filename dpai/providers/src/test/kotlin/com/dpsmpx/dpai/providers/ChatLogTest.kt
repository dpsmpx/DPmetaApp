package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Переписка чата `dpai.api-chats` v1 (D-98): чтение строгое, в запрос — только целые ответы. */
class ChatLogTest {
    @Test fun projectBindingSurvivesV2AndOldV1RemainsAnOrdinaryChat() {
        val project = IdKinds.PROJECT.parse("prj_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!
        val fixture = javaClass.getResource("/api-chats/project-v2.json")!!.readText()
        assertEquals(project, ChatBook.fromJson(JsonParser().parse(fixture).valueOrNull()!!).valueOrNull()!!.single().project)
        assertIs<Outcome.Failure>(ChatBook.fromJson(JsonParser().parse(fixture.replace("\"version\":2", "\"version\":1")).valueOrNull()!!))
        val current = conversation.copy(project = project)
        assertEquals(project, ChatBook.fromJson(ChatBook.toJson(listOf(current))).valueOrNull()!!.single().project)
        val text = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(ChatBook.toJson(listOf(conversation))).replace("\"version\":${ChatBook.VERSION}", "\"version\":1")
        assertEquals(null, ChatBook.fromJson(JsonParser().parse(text).valueOrNull()!!).valueOrNull()!!.single().project)
    }
    private val id = IdKinds.CORRELATION.parse("cor_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!
    private val acc = IdKinds.ACCOUNT.parse("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!

    private val conversation = Conversation(
        id, acc, Conversation.title("  Привет,\n как   дела?  "),
        listOf(
            ChatEntry(ChatRole.USER, "Привет, как дела?"),
            ChatEntry(ChatRole.ASSISTANT, "Хорошо", "m", EntryStatus.OK, null, 10, 2),
            ChatEntry(ChatRole.USER, "А ещё?"),
            ChatEntry(ChatRole.ASSISTANT, "Оборв", "m", EntryStatus.CANCELLED, "прервано"),
            ChatEntry(ChatRole.USER, "Ну?"),
            ChatEntry(ChatRole.ASSISTANT, "", "m", EntryStatus.FAILED, "провайдер недоступен"),
            ChatEntry(ChatRole.ASSISTANT, "Не могу", "m", EntryStatus.REFUSED),
            ChatEntry(ChatRole.ASSISTANT, "Длинный", "m", EntryStatus.LENGTH),
        ),
    )

    @Test
    fun roundTrip() {
        assertEquals("Привет, как дела?", conversation.title)
        val back = ChatBook.fromJson(ChatBook.toJson(listOf(conversation))).valueOrNull()!!
        assertEquals(listOf(conversation), back)
    }

    @Test
    fun contextSkipsBrokenReplies() {
        assertEquals(
            listOf(
                ChatMessage(ChatRole.USER, "Привет, как дела?"),
                ChatMessage(ChatRole.ASSISTANT, "Хорошо"),
                ChatMessage(ChatRole.USER, "А ещё?"),
                ChatMessage(ChatRole.USER, "Ну?"),
                ChatMessage(ChatRole.ASSISTANT, "Длинный"),
            ),
            conversation.context(),
        )
    }

    @Test
    fun strictReading() {
        val good = ChatBook.toJson(listOf(conversation)).toString()
        for (bad in listOf(
            good.replace("\"title\":", "\"extra\":1,\"title\":"),
            good.replace("\"status\":\"ok\"", "\"status\":\"maybe\""),
            good.replace("\"profile\":\"acc_", "\"profile\":\"tgt_"),
            good.replace("\"version\":${ChatBook.VERSION}", "\"version\":${ChatBook.VERSION + 1}"),
        )) {
            assertIs<Outcome.Failure>(ChatBook.fromJson(JsonParser().parse(bad).valueOrNull()!!), bad)
        }
    }

    @Test
    fun limits() {
        val long = conversation.copy(entries = List(ChatBook.MAX_ENTRIES + 5) { ChatEntry(ChatRole.USER, "x$it") })
        assertEquals(ChatBook.MAX_ENTRIES, long.plus(ChatEntry(ChatRole.USER, "y")).entries.size)
        val many = List(ChatBook.MAX_CONVERSATIONS + 3) { conversation }
        val back = ChatBook.fromJson(ChatBook.toJson(many)).valueOrNull()!!
        assertEquals(ChatBook.MAX_CONVERSATIONS, back.size)
        val huge = conversation.copy(entries = listOf(ChatEntry(ChatRole.USER, "я".repeat(ChatBook.MAX_TEXT + 10))))
        assertEquals(ChatBook.MAX_TEXT, ChatBook.fromJson(ChatBook.toJson(listOf(huge))).valueOrNull()!!.single().entries.single().text.length)
    }

    @Test fun contextV3CorpusKeepsProvenanceAndRejectsExternalPaths() {
        fun read(name: String) = ChatBook.fromJson(JsonParser().parse(javaClass.getResource("/api-chats/$name.json")!!.readText()).valueOrNull()!!)
        val entry = read("project-context-v3").valueOrNull()!!.single().entries.single()
        assertEquals("a".repeat(64), entry.contextScope)
        assertEquals(mapOf("src/A.kt" to "b".repeat(64), "AGENTS.md" to null), entry.contextSources)
        assertEquals("c".repeat(40), entry.contextCommit)
        assertIs<Outcome.Failure>(read("invalid-context-path-v3"))
    }

    @Test fun v3KeepsSourceStampsButNeverResendsOldScopeOrChangedFileAnswers() {
        val scope = "a".repeat(64)
        val hash = "b".repeat(64)
        val stamped = conversation.copy(entries = listOf(
            ChatEntry(ChatRole.USER, "исходная цель", contextScope = scope),
            ChatEntry(ChatRole.ASSISTANT, "актуальный ответ", contextScope = scope, contextSources = mapOf("src/A.kt" to hash)),
            ChatEntry(ChatRole.ASSISTANT, "старая область", contextScope = "c".repeat(64)),
            ChatEntry(ChatRole.ASSISTANT, "непроверенная старая история"),
            ChatEntry(ChatRole.USER, "продолжай")))
        val back = ChatBook.fromJson(ChatBook.toJson(listOf(stamped))).valueOrNull()!!.single()
        assertEquals(stamped, back)
        val messages = back.projectContext(scope, mapOf("src/A.kt" to hash), false, 20, 10_000, 5000).valueOrNull()!!
        assertEquals(listOf("исходная цель", "актуальный ответ", "продолжай"), messages.messages.map { it.text })
        val changed = back.projectContext(scope, mapOf("src/A.kt" to "d".repeat(64)), false, 20, 10_000, 5000).valueOrNull()!!
        assertEquals(listOf("исходная цель", "продолжай"), changed.messages.map { it.text })
        assertEquals(5, back.entries.size, "видимая история сохранена")
        val oldVersion = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(ChatBook.toJson(listOf(stamped))).replace("\"version\":3", "\"version\":2")
        assertIs<Outcome.Failure>(ChatBook.fromJson(JsonParser().parse(oldVersion).valueOrNull()!!))
    }

    @Test fun projectHistoryKeepsCurrentRequestAndExplainsBudgetOmissions() {
        val scope = "a".repeat(64)
        val c = conversation.copy(entries = listOf(ChatEntry(ChatRole.USER, "первая цель", contextScope = scope)) +
            List(20) { ChatEntry(ChatRole.ASSISTANT, "прошлый ответ $it", contextScope = scope) } + ChatEntry(ChatRole.USER, "текущий запрос"))
        val result = c.projectContext(scope, emptyMap(), false, 2, 1000, 500).valueOrNull()!!
        assertEquals(listOf("первая цель", "прошлый ответ 19", "текущий запрос"), result.messages.map { it.text })
        assertEquals(19, result.omitted)
        assertEquals(listOf("текущий запрос"), c.projectContext(scope, emptyMap(), false, 0, 1000, 500).valueOrNull()!!.messages.map { it.text })
        assertEquals(DiagCode.CONTEXT_BUDGET_EXCEEDED, c.projectContext(scope, emptyMap(), false, 2, 2, 500).errors.single().code)
    }

    @Test
    fun mockProvider() {
        val p = MockProvider(chunks = { listOf("a", "b") })
        val got = mutableListOf<String>()
        assertEquals("ab", p.chat(ChatRequest("m", conversation.context()), Cancel()) { got += it }.valueOrNull()!!.text)
        assertEquals(listOf("a", "b"), got)
        val c = Cancel().also { it.cancel() }
        assertEquals(DiagCode.CANCELLED_BY_USER, assertIs<Outcome.Failure>(p.chat(ChatRequest("m", emptyList()), c) {}).errors.single().code)
        assertTrue(p.requests.size == 2)
    }
}

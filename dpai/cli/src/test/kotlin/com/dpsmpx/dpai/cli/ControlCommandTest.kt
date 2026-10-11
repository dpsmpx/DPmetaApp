package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `dpai` как пульт (D-47): сопряжение, разбор аргументов в команды, сокращённые id, --yes, ошибки канала. */
class ControlCommandTest {
    @TempDir
    lateinit var dir: Path

    private val state = """{"tasks":[{"id":"tsk_01M4EWP74S6EWVHWSM33QD5YK0","title":"Калькулятор","state":"READY","priority":"NORMAL","held":false,"taskClass":"NORMAL_ENGINEERING",
        |"usage":{"turns":2,"unknownTurns":0,"inputTokens":1200,"cacheReadTokens":600,"outputTokens":80,"costUsd":"0.0421","costEstimated":true}}],
        |"sessions":[{"id":"ses_01M4EY7BAXY3ZGE5VT0K6SF4QD","task":"tsk_01M4EWP74S6EWVHWSM33QD5YK0","state":"COMPLETED","target":"tgt_01M4EPJW9FKC2Q78Y8QHVN8P2Y","turn":1}],
        |"requests":[{"id":"req_01M4EY8H60MYC7N5W7XF7STMSB","kind":"USER_RESOLUTION","title":"Задача без проверки","body":"…","options":[
        |{"id":"verify","label":"Проверить снова","role":"ACCEPT","needsText":false,"uri":"dpai://answer/req_01M4EY8H60MYC7N5W7XF7STMSB/verify?hash=sha256:aa"},
        |{"id":"waive","label":"Принять","role":"DESTRUCTIVE","needsText":false,"uri":"dpai://answer/req_01M4EY8H60MYC7N5W7XF7STMSB/waive?hash=sha256:aa"}]}],
        |"subscriptions":[{"id":"sub_01M4EVC6NCPMK72R25GZV67HSW","label":"подписка pro","nearPercent":80,"near":true,"windows":[{"window":"SESSION","level":"OK","percent":82}]}],
        |"slots":[],"control":{"disabledTargets":[]}}""".trimMargin()

    private var changes = """{"branch":"task/tsk_01M4EWP74S6EWVHWSM33QD5YK0","base":"main","committed":[" calc.py | 2 +-","+print(2)"],
        |"uncommitted":[],"untracked":["notes.txt"],"truncated":true,"diagnostics":[]}""".trimMargin()

    private val sent = mutableListOf<Pair<String, JsonObject?>>()

    private fun cli(out: MutableList<String>, err: MutableList<String>) = ControlCommand(out::add, err::add, NioVfs(dir)) { _ ->
        ControlTransport { method, path, token, body ->
            sent += "$method $path" to body
            when {
                path == "/v1/pair" -> HttpReply(200, obj("""{"token":"${"a".repeat(64)}"}"""))
                token == null -> HttpReply(401, obj("""{"status":"FAILED","diagnostics":[]}"""))
                path == "/v1/state" -> HttpReply(200, obj(state))
                path.endsWith("/changes") -> HttpReply(200, obj(changes))
                else -> HttpReply(200, obj("""{"operation":"x","operationId":"op_1","status":"SUCCEEDED","diagnostics":[]}"""))
            }
        }
    }

    private fun obj(s: String) = JsonParser().parse(s).valueOrNull() as JsonObject

    private fun field(k: String) = (sent.last().second!![k] as? com.dpsmpx.dpai.core.serialization.JsonString)?.value

    @Test
    fun pairThenCommandsWithShortIdsAndConfirmation() {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        assertEquals(1, cli(out, err).run("status", emptyList()), "без сопряжения — отказ")
        assertTrue(err.single().contains("dpai control pair"))
        assertEquals(0, cli(out, err).run("control", listOf("pair", "123456")))
        assertEquals(0, cli(out, err).run("status", emptyList()))
        assertTrue(out.any { "Калькулятор" in it } && out.any { "5 ч 82 %" in it }, out.toString())
        assertTrue(out.any { "ходов 2, 0.04 \$ (оценка)" in it }, "расход задачи (D-42, п. 4): $out")

        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "hold")))
        assertEquals("task.hold", field("command"))
        assertEquals("tsk_01M4EWP74S6EWVHWSM33QD5YK0", field("task"), "сокращённый id раскрыт")
        assertTrue(field("key")!!.startsWith("op_"), "ключ нажатия")
        cli(out, err).run("task", listOf("tsk_01M4", "cancel", "--yes"))
        assertTrue(CanonicalJson.write(sent.last().second!!).contains(""""confirmed":true"""))
        cli(out, err).run("task", listOf("add", "Новая", "Сделай", "--priority", "high", "--class", "trivial", "--budget", "0.25"))
        val add = CanonicalJson.write(sent.last().second!!)
        assertTrue(""""budgetCents":25""" in add && """"priority":"HIGH"""" in add && """"taskClass":"TRIVIAL"""" in add, add)
        assertEquals(0, cli(out, err).run("answer", listOf("STMSB", "verify")))
        assertTrue(CanonicalJson.write(sent.last().second!!).contains("verify?hash"))
        assertEquals(1, cli(out, err).run("answer", listOf("STMSB", "нет-такого")))
        assertEquals(2, cli(out, err).run("task", listOf("5YK0", "плясать")))
        assertEquals(0, cli(out, err).run("sessions-limit", listOf("off")))
        assertEquals("control.session-limit", field("command"))
        assertTrue(sent.last().second!!["limit"] == null, "off — без предела")
    }

    @Test
    fun taskDiffShowsWhatTheMergeWouldBring() {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        assertEquals(0, cli(out, err).run("control", listOf("pair", "123456")))
        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "diff")))
        assertEquals("GET /v1/tasks/tsk_01M4EWP74S6EWVHWSM33QD5YK0/changes", sent.last().first, "сокращённый id раскрыт")
        assertTrue(out.any { "main...task/tsk_01M4EWP74S6EWVHWSM33QD5YK0" in it } && "+print(2)" in out, out.toString())
        assertTrue(out.none { "не закоммичено" in it }, "пустая часть не печатается")
        assertTrue("  notes.txt" in out && out.any { "обрезан" in it }, out.toString())

        out.clear()
        changes = """{"branch":"task/x","base":"main","untracked":[],"truncated":false,"diagnostics":[]}"""
        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "diff")))
        assertTrue(out.single().contains("ещё нет"), "ни ветки, ни дерева — задача не начиналась: $out")
        out.clear()
        changes = """{"branch":"task/x","base":"main","committed":[],"uncommitted":[],"untracked":[],"truncated":false,"diagnostics":[]}"""
        cli(out, err).run("task", listOf("5YK0", "diff"))
        assertTrue(out.single().startsWith("изменений нет"), out.toString())
    }
}

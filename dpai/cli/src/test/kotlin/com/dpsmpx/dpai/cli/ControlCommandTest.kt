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

    private val sent = mutableListOf<Pair<String, JsonObject?>>()

    private fun cli(out: MutableList<String>, err: MutableList<String>) = ControlCommand(out::add, err::add, NioVfs(dir)) { _ ->
        ControlTransport { method, path, token, body ->
            sent += "$method $path" to body
            when {
                path == "/v1/pair" -> HttpReply(200, obj("""{"token":"${"a".repeat(64)}"}"""))
                token == null -> HttpReply(401, obj("""{"status":"FAILED","diagnostics":[]}"""))
                path == "/v1/state" -> HttpReply(200, obj(state))
                path.endsWith("/diff") -> HttpReply(
                    200,
                    obj("""{"base":"main","mergeBase":"0123456789abcdef","head":"рабочее дерево","files":[{"path":"calc.py","added":2,"removed":1},{"path":"logo.png","added":null,"removed":null}],"untracked":["notes.txt"],"patch":"+print(2)","truncated":false}"""),
                )
                path == "/v1/search" -> HttpReply(200, obj("""{"hits":[{"kind":"TASK","title":"Калькулятор","detail":"READY","link":"dpai://task/tsk_01M4EWP74S6EWVHWSM33QD5YK0"}],"more":{"TASK":3},"notes":["пометка поиска"]}"""))
                path.endsWith("/reviews") -> HttpReply(
                    200,
                    obj("""{"roles":["CODE","SECURITY"],"reviews":[{"role":"CODE","verdict":"BLOCKED","attempt":1,"findings":[{"severity":"BLOCKING","title":"деление на ноль","file":"a.kt","line":3,"detail":"пустой список"}],"omitted":2}]}"""),
                )
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
        // Слова, а не идентификаторы (D-89): «готова к запуску · обычный», а не «READY  NORMAL».
        val line = out.first { "Калькулятор" in it }
        assertTrue("готова к запуску" in line && "обычный" in line && "READY" !in line && "NORMAL" !in line, line)
        val reqs = mutableListOf<String>()
        assertEquals(0, cli(reqs, err).run("requests", emptyList()))
        assertTrue(reqs.any { "решение" in it } && reqs.none { "USER_RESOLUTION" in it }, reqs.toString())
        assertTrue(out.any { "ходов 2 · вход 1200 · выход 80 · \$0.04 (оценка)" in it }, "расход задачи (D-42, п. 4): $out")

        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "hold")))
        assertEquals("task.hold", field("command"))
        assertEquals("tsk_01M4EWP74S6EWVHWSM33QD5YK0", field("task"), "сокращённый id раскрыт")
        assertTrue(field("key")!!.startsWith("op_"), "ключ нажатия")
        cli(out, err).run("task", listOf("tsk_01M4", "cancel", "--yes"))
        assertTrue(CanonicalJson.write(sent.last().second!!).contains(""""confirmed":true"""))
        cli(out, err).run("task", listOf("add", "Новая", "Сделай", "--priority", "high", "--class", "trivial", "--budget", "0.25"))
        val add = CanonicalJson.write(sent.last().second!!)
        assertTrue(""""budgetCents":25""" in add && """"priority":"HIGH"""" in add && """"taskClass":"TRIVIAL"""" in add, add)
        assertEquals(0, cli(out, err).run("task", listOf("add", "После", "Сделай", "--depends-on", "5YK0", "--scope", "src/a.kt, docs")))
        val after = CanonicalJson.write(sent.last().second!!)
        assertTrue(""""dependsOn":["tsk_01M4EWP74S6EWVHWSM33QD5YK0"]""" in after && """"scope":["src/a.kt","docs"]""" in after, after)
        assertEquals(1, cli(out, err).run("task", listOf("add", "Ещё", "Сделай", "--depends-on", "нет-такой")), "неизвестная зависимость — отказ до отправки")
        assertEquals(0, cli(out, err).run("answer", listOf("STMSB", "verify")))
        assertTrue(CanonicalJson.write(sent.last().second!!).contains("verify?hash"))
        assertEquals(1, cli(out, err).run("answer", listOf("STMSB", "нет-такого")))
        assertEquals(2, cli(out, err).run("task", listOf("5YK0", "плясать")))
        // «Запустить первой» и «Перенести» (D-77).
        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "first")))
        assertEquals("task.run-first", field("command"))
        assertEquals(0, cli(out, err).run("session", listOf("SF4QD", "reassign")))
        assertEquals("session.reassign", field("command"))
        assertEquals("ses_01M4EY7BAXY3ZGE5VT0K6SF4QD", field("session"))
        assertEquals(0, cli(out, err).run("sessions-limit", listOf("off")))
        assertEquals("control.session-limit", field("command"))
        assertTrue(sent.last().second!!["limit"] == null, "off — без предела")
    }

    /** Изменения задачи (D-42, п. 3): по файлам, новые файлы и текст; `--stat` — без текста. */
    @Test
    fun taskDiffByFilesAndText() {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        assertEquals(0, cli(out, err).run("control", listOf("pair", "123456")))
        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "diff")))
        assertEquals("GET /v1/tasks/tsk_01M4EWP74S6EWVHWSM33QD5YK0/diff", sent.last().first, "сокращённый id раскрыт")
        assertTrue("  +2 −1  calc.py" in out && "  +? −?  logo.png" in out && "  новый, вне индекса: notes.txt" in out, out.toString())
        assertTrue("+print(2)" in out, "текст изменений: $out")
        out.clear()
        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "diff", "--stat")))
        assertTrue("+print(2)" !in out && "  +2 −1  calc.py" in out, out.toString())
    }

    /** Поиск (ТЗ, §75) и ревью задачи (D-73) из `dpai`. */
    @Test
    fun searchAndReviews() {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        assertEquals(0, cli(out, err).run("control", listOf("pair", "123456")))
        assertEquals(0, cli(out, err).run("search", listOf("кальк", "ул")))
        assertEquals("POST /v1/search", sent.last().first)
        assertEquals("кальк ул", field("query"))
        assertTrue(out.containsAll(listOf("TASK:", "ещё TASK: 3", "· пометка поиска")) && out.any { "Калькулятор" in it && "dpai://task/" in it }, out.toString())
        assertEquals(2, cli(out, err).run("search", emptyList()), "без запроса — подсказка")

        cli(out, err).run("task", listOf("add", "С ревью", "Сделай", "--review", "code,security"))
        assertTrue(""""reviews":["CODE","SECURITY"]""" in CanonicalJson.write(sent.last().second!!), CanonicalJson.write(sent.last().second!!))
        cli(out, err).run("task", listOf("5YK0", "policy", "--class", "architecture", "--review", "none"))
        assertTrue(""""reviews":[]""" in CanonicalJson.write(sent.last().second!!), "none — снять роли")
        cli(out, err).run("task", listOf("5YK0", "policy", "--class", "architecture"))
        assertTrue("reviews" !in CanonicalJson.write(sent.last().second!!), "без --review роли не трогаются")

        out.clear()
        assertEquals(0, cli(out, err).run("task", listOf("5YK0", "reviews")))
        assertEquals("GET /v1/tasks/tsk_01M4EWP74S6EWVHWSM33QD5YK0/reviews", sent.last().first)
        assertTrue(out.any { it.startsWith("── CODE: BLOCKED") } && out.any { "BLOCKING  деление на ноль  (a.kt:3)" in it }, out.toString())
        assertTrue("── SECURITY: ещё нет итога" in out && out.any { "не показано находок: 2" in it }, out.toString())
    }
}

package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * MCP-сервер рабочего дерева (D-50): протокол JSON-RPC строками, шесть
 * инструментов, граница дерева — путь за него не читается и не пишется,
 * команды останавливаются по тайм-ауту.
 */
class WorkspaceMcpTest {
    @TempDir
    lateinit var dir: Path

    private val tree: Path by lazy { dir.resolve("tree").also { Files.createDirectories(it) } }
    private val posix = File.separatorChar == '/'
    private val mcp by lazy { WorkspaceMcp(tree, WorkspaceMcp.defaultShell(!posix), "test") }
    private var next = 0

    private fun call(method: String, params: String = "{}"): JsonObject {
        val line = """{"jsonrpc":"2.0","id":${++next},"method":"$method","params":$params}"""
        return JsonParser().parse(mcp.handle(line)!!).valueOrNull() as JsonObject
    }

    /** Вызов инструмента: текст результата и признак ошибки. */
    private fun tool(name: String, args: String): Pair<String, Boolean> {
        val r = call("tools/call", """{"name":"$name","arguments":$args}""")["result"] as JsonObject
        val text = ((r["content"] as JsonArray).items.single() as JsonObject)["text"] as JsonString
        return text.value to (r["isError"] as JsonBool).value
    }

    private fun q(s: String) = com.dpsmpx.dpai.core.serialization.CanonicalJson.write(JsonString(s))

    @Test
    fun handshakeAndToolList() {
        val init = call("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"claude-code","version":"2"}}""")["result"] as JsonObject
        assertEquals(JsonString("2025-06-18"), init["protocolVersion"])
        assertTrue((init["capabilities"] as JsonObject).fields.containsKey("tools"))
        val unknown = call("initialize", """{"protocolVersion":"1999-01-01"}""")["result"] as JsonObject
        assertEquals(JsonString(WorkspaceMcp.PROTOCOL_VERSIONS.last()), unknown["protocolVersion"])
        assertNull(mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), "на уведомление не отвечают")
        assertEquals(JsonObject(emptyMap()), call("ping")["result"])
        val tools = ((call("tools/list")["result"] as JsonObject)["tools"] as JsonArray).items.map { it as JsonObject }
        assertEquals(listOf("run", "read", "write", "edit", "glob", "grep"), tools.map { (it["name"] as JsonString).value })
        tools.forEach { t -> assertEquals(JsonString("object"), (t["inputSchema"] as JsonObject)["type"]) }
    }

    @Test
    fun protocolErrors() {
        fun code(line: String) = ((JsonParser().parse(mcp.handle(line)!!).valueOrNull() as JsonObject)["error"] as JsonObject)["code"]
        assertEquals(JsonNumber.of(WorkspaceMcp.PARSE_ERROR), code("{не json"))
        assertEquals(JsonNumber.of(WorkspaceMcp.METHOD_NOT_FOUND), code("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}"""))
        assertEquals(JsonNumber.of(WorkspaceMcp.INVALID_PARAMS), code("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"rm"}}"""))
    }

    @Test
    fun writeReadEditInsideTree() {
        assertFalse(tool("write", """{"path":"src/a.txt","content":${q("один\nдва\nдва\n")}}""").second)
        assertEquals("один\nдва\nдва\n", tree.resolve("src/a.txt").toFile().readText())
        val (read, readErr) = tool("read", """{"path":"src/a.txt","offset":2,"limit":1}""")
        assertFalse(readErr)
        assertEquals("     2\tдва\n", read)
        val (twice, twiceErr) = tool("edit", """{"path":"src/a.txt","old_string":"два","new_string":"три"}""")
        assertTrue(twiceErr, "неоднозначная замена — отказ")
        assertTrue("2 раз" in twice, twice)
        assertFalse(tool("edit", """{"path":"src/a.txt","old_string":"два","new_string":"три","replace_all":true}""").second)
        assertFalse(tool("edit", """{"path":"src/a.txt","old_string":"один","new_string":"ноль"}""").second)
        assertEquals("ноль\nтри\nтри\n", tree.resolve("src/a.txt").toFile().readText())
        assertTrue(tool("edit", """{"path":"src/a.txt","old_string":"нет такого","new_string":"x"}""").second)
        assertTrue(tool("read", """{"path":"src/нет.txt"}""").second)
    }

    @Test
    fun globAndGrep() {
        tool("write", """{"path":"a/one.kt","content":"fun main() = Unit\n"}""")
        tool("write", """{"path":"a/two.txt","content":"main\n"}""")
        tool("write", """{"path":".git/config","content":"main\n"}""")
        assertEquals("a/one.kt", tool("glob", """{"pattern":"**/*.kt"}""").first)
        val found = tool("grep", """{"pattern":"main"}""").first.lines().sorted()
        assertEquals(listOf("a/one.kt:1:fun main() = Unit", "a/two.txt:1:main"), found, ".git не ищется")
        assertEquals("a/one.kt:1:fun main() = Unit", tool("grep", """{"pattern":"MAIN","ignore_case":true,"glob":"*.kt"}""").first)
    }

    @Test
    fun pathsOutsideTreeAreRefused() {
        File(dir.toFile(), "secret.txt").writeText("за деревом")
        val outside = dir.resolve("secret.txt").toString()
        for (args in listOf("""{"path":"../secret.txt"}""", """{"path":${q(outside)}}""", """{"path":"a/../../secret.txt"}""")) {
            val (text, err) = tool("read", args)
            assertTrue(err, args)
            assertTrue("вне рабочего дерева" in text, text)
        }
        assertTrue(tool("write", """{"path":"../x.txt","content":"x"}""").second)
        assertFalse(dir.resolve("x.txt").toFile().exists(), "за деревом ничего не записано")
        assertTrue(tool("glob", """{"pattern":"*","path":".."}""").second)
    }

    @Test
    fun symlinkOutOfTreeIsRefused() {
        val link = tree.resolve("out")
        val made = runCatching { Files.createSymbolicLink(link, dir) }.isSuccess
        assumeTrue(made, "символьные ссылки недоступны")
        File(dir.toFile(), "secret.txt").writeText("за деревом")
        val (text, err) = tool("read", """{"path":"out/secret.txt"}""")
        assertTrue(err)
        assertTrue("за рабочее дерево" in text, text)
        assertTrue(tool("write", """{"path":"out/new.txt","content":"x"}""").second)
        assertFalse(dir.resolve("new.txt").toFile().exists())
        assertEquals("[ничего]", tool("grep", """{"pattern":"за деревом"}""").first, "поиск не идёт по ссылке за дерево")
    }

    @Test
    fun runInTreeWithExitCodeAndTimeout() {
        assumeTrue(posix, "оболочка POSIX")
        val (ok, okErr) = tool("run", """{"command":"pwd; echo готово"}""")
        assertFalse(okErr, ok)
        assertTrue(ok.lines().first().endsWith("tree"), ok)
        assertTrue("готово" in ok && ok.endsWith("[код выхода 0]"), ok)
        val (bad, badErr) = tool("run", """{"command":"echo нет >&2; exit 3"}""")
        assertTrue(badErr)
        assertTrue("нет" in bad && bad.endsWith("[код выхода 3]"), bad)
        val (slow, slowErr) = tool("run", """{"command":"sleep 30","timeout_seconds":1}""")
        assertTrue(slowErr)
        assertTrue("тайм-ауту" in slow, slow)
        assertTrue(tool("run", """{"command":"true","timeout_seconds":0}""").second, "предел вне 1…600 — отказ")
    }

    @Test
    fun serveAnswersLineByLine() {
        val input = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            "",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
        ).joinToString("\n")
        val out = mutableListOf<String>()
        mcp.serve(input.reader().buffered()) { out += it }
        assertEquals(2, out.size)
        assertTrue(out.all { '\n' !in it }, "ответ — одна строка")
        assertEquals(listOf(JsonNumber.of(1), JsonNumber.of(2)), out.map { (JsonParser().parse(it).valueOrNull() as JsonObject)["id"] })
    }

    @Test
    fun shellsByName() {
        assertEquals("PowerShell", WorkspaceMcp.shellNamed("powershell", windows = true)!!.name)
        assertEquals(listOf("cmd.exe", "/d", "/c"), WorkspaceMcp.shellNamed("cmd", windows = true)!!.prefix)
        assertNull(WorkspaceMcp.shellNamed("fish", windows = false))
        assertEquals("bash (Git Bash)", WorkspaceMcp.defaultShell(windows = true) { it.endsWith("Git/bin/bash.exe") }.name)
        assertEquals("PowerShell", WorkspaceMcp.defaultShell(windows = true) { false }.name)
        assertEquals("sh", WorkspaceMcp.defaultShell(windows = false) { false }.name)
    }
}

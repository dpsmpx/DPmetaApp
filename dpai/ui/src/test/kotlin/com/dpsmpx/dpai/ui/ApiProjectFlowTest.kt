package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.ApiProfiles
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.ChatMessage
import com.dpsmpx.dpai.providers.ChatRequest
import com.dpsmpx.dpai.providers.ChatRole
import com.dpsmpx.dpai.providers.HttpReply
import com.dpsmpx.dpai.providers.Providers
import com.dpsmpx.dpai.providers.ToolChat
import com.dpsmpx.dpai.providers.ToolResult
import com.dpsmpx.dpai.providers.ToolSpec
import com.dpsmpx.dpai.providers.Transport
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Существующий клон → автоматическая привязка → API read/write → реальная запись/отмена; CLI не нужен. */
class ApiProjectFlowTest {
    @TempDir lateinit var dir: Path
    private val cipher = object : DraftCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(record: String, bytes: ByteArray): Outcome<ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(record.toByteArray()) }
            return Outcome.success(c.iv + c.doFinal(bytes))
        }
        override fun open(record: String, bytes: ByteArray): Outcome<ByteArray> = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)); updateAAD(record.toByteArray()) }
            Outcome.success(c.doFinal(bytes, 12, bytes.size - 12))
        } catch (_: GeneralSecurityException) { Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_CORRUPTED, "копия недоступна") }
    }

    @Test fun apiModelSeesAndEditsAnExistingCloneWithoutCliLogin() = scenario(paused = false)
    @Test fun busyWorkspacePreventsApiWritesEvenWhenTheModelAsksForThem() = scenario(paused = true)

    private fun scenario(paused: Boolean) {
        val projects = dir.resolve("projects").toFile().apply { mkdirs() }
        val source = projects.resolve("owner/repo/main.kt").apply { parentFile.mkdirs(); writeText("fun answer() = 1\n") }
        projects.resolve("owner/repo/.git/HEAD").apply { parentFile.mkdirs(); writeText("ref: refs/heads/main\n") }
        val clone = ApiQuickStart.clones(LocalTree(projects.toPath())).valueOrNull()!!.single()
        val ids = IdGenerator(ManualClock(), RandomSource.seeded(214))
        val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Телефон", "/root/.claude-slot-1").valueOrNull()!!
        val project = ApiQuickStart.adopt(ids, clone, emptyList(), emptyList()) { Outcome.success(target) }.valueOrNull()!!
        val view = ProjectWorkspaceView.of(project, listOf(target), true).copy(paused = paused)
        val gate = WorkspaceWriteGate(ReentrantLock()) { view }
        val store = WorkspaceChangeStore(NioVfs(dir.resolve("copies")), cipher)
        val changes = WorkspaceChanges(store)
        val tree = LocalTree(source.parentFile.toPath())
        val actions = ApiProjectFiles(tree,
            { path, hash, text -> gate.perform(project, view.main) { changes.write(project.project.value, view.main.key, tree, path, hash, text) } },
            { path, hash -> gate.perform(project, view.main) { changes.delete(project.project.value, view.main.key, tree, path, hash) } })
        var round = 0
        val calls = arrayListOf<String>()
        val bodies = arrayListOf<String>()
        fun tool(name: String, args: JsonObject): String {
            val call = JsonObject.of("id" to JsonString("call-$round"), "type" to JsonString("function"), "function" to JsonObject.of("name" to JsonString(name), "arguments" to JsonString(CanonicalJson.write(args))))
            return """{"choices":[{"message":{"role":"assistant","tool_calls":[${CanonicalJson.write(call)}]},"finish_reason":"tool_calls"}]}"""
        }
        val transport = Transport { http, _ ->
            val body = http.body!!.toString(Charsets.UTF_8); bodies += body
            val result = when (round++) {
                0 -> tool("read_file", JsonObject.of("path" to JsonString("main.kt")))
                1 -> {
                    val request = JsonParser().parse(body).valueOrNull() as JsonObject
                    val last = (request["messages"] as JsonArray).items.last() as JsonObject
                    val read = JsonParser().parse((last["content"] as JsonString).value).valueOrNull() as JsonObject
                    assertTrue((read["content"] as JsonString).value.contains("answer() = 1"))
                    tool("write_file", JsonObject.of("path" to JsonString("main.kt"), "sha256" to read["sha256"]!!, "content" to JsonString("fun answer() = 42\n")))
                }
                else -> """{"choices":[{"message":{"role":"assistant","content":"Готово"},"finish_reason":"stop"}]}"""
            }
            Outcome.success(object : HttpReply {
                override val status = 200
                private var read = false
                override fun header(name: String): String? = null
                override fun readLine(): String? = if (read) null else result.also { read = true }
                override fun close() = Unit
            })
        }
        val profile = ApiProfiles.form(ids.next(IdKinds.ACCOUNT), "API", ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", "model", null, false).valueOrNull()!!
        val secret = listOf("sample", "api", "credential").joinToString("-")
        val provider = Providers.of(profile, Secret(secret), transport) as ToolChat
        val reply = provider.chatWithTools(ChatRequest("model", listOf(ChatMessage(ChatRole.USER, "Поменяй ответ на 42"))), ApiProjectFiles.tools().map { ToolSpec(it.first, it.second, it.third) }, Cancel(), {}) { call ->
            calls += call.name
            when (val r = actions.run(call.name, call.input)) { is Outcome.Failure -> ToolResult(r.diagnostics.first().message, true); is Outcome.Success -> ToolResult(r.value) }
        }
        assertIs<Outcome.Success<*>>(reply)
        assertEquals(listOf("read_file", "write_file"), calls)
        assertTrue(bodies.none { secret in it })
        assertEquals(if (paused) "fun answer() = 1\n" else "fun answer() = 42\n", source.readText())
        if (!paused) {
            assertIs<Outcome.Success<*>>(changes.undo(project.project.value, view.main.key, tree))
            assertEquals("fun answer() = 1\n", source.readText())
        }
    }
}

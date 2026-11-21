package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.files.LocalTree
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ApiQuickStartTest {
    @TempDir lateinit var dir: Path
    private fun file(path: String, text: String) = dir.resolve(path).toFile().apply { parentFile.mkdirs(); writeText(text) }
    private fun obj(vararg p: Pair<String, String>) = JsonObject(p.associate { it.first to JsonString(it.second) })
    private fun tools() = ApiProjectFiles(LocalTree(dir),
        { path, _, text -> when (val r = LocalTree(dir).write(path, text.toByteArray())) { is Outcome.Failure -> r; is Outcome.Success -> Outcome.success(Unit) } },
        { path, _ -> LocalTree(dir).delete(path) })

    @Test fun existingClonesAppearWithoutTargetsOrLinuxProcess() {
        file("owner/repo/.git/HEAD", "ref: refs/heads/dev\n")
        file("local/.git/HEAD", "ref: refs/heads/main\n")
        file(".trees/hidden/.git/HEAD", "ref: refs/heads/task\n")
        file("unrelated/source.kt", "code")
        val clones = ApiQuickStart.clones(LocalTree(dir)).valueOrNull()!!
        assertEquals(listOf("local", "owner/repo"), clones.map { it.relative })
        assertEquals("/root/projects/owner/repo", clones.last().root)
        assertEquals("/root/projects/owner/.trees/repo", clones.last().worktrees)
    }

    @Test fun projectReadAndExactReplacementUseObservedHash() {
        val source = file("main.cpp", "int answer() { return 1; }\n")
        val api = tools()
        val read = api.run("read_file", obj("path" to "main.cpp")).valueOrNull()!!
        val hash = ((JsonParser().parse(read).valueOrNull() as JsonObject)["sha256"] as JsonString).value
        assertIs<Outcome.Success<*>>(api.run("replace_text", obj("path" to "main.cpp", "sha256" to hash, "old_text" to "return 1", "new_text" to "return 42")))
        assertEquals("int answer() { return 42; }\n", source.readText())
        assertIs<Outcome.Failure>(api.run("replace_text", obj("path" to "main.cpp", "sha256" to hash, "old_text" to "return 42", "new_text" to "return 0")))
        assertTrue(source.readText().contains("42"))
    }

    @Test fun secretsTraversalAndGitAreNotAvailableToTheModel() {
        file(".env", "private")
        file(".git/config", "private")
        file("src/main.kt", "public")
        val api = tools()
        for (path in listOf(".env", ".git/config", "../outside", "/absolute", "id_ed25519", "x.p12")) {
            assertIs<Outcome.Failure>(api.run("read_file", obj("path" to path)))
            assertIs<Outcome.Failure>(api.run("write_file", obj("path" to path, "content" to "changed")))
        }
        val search = api.run("find_text", obj("query" to "private")).valueOrNull()!!
        assertTrue(search.contains("\"matches\":[]"))
        assertEquals("private", dir.resolve(".env").toFile().readText())
    }

    @Test fun paginationRemainsValidJsonWithinToolResultLimit() {
        val text = "\"\\\t🙂".repeat(7000)
        file("source.txt", text)
        val api = tools()
        var offset = 0L
        val observed = StringBuilder()
        do {
            val result = api.run("read_file", JsonObject.of("path" to JsonString("source.txt"), "offset" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(offset))).valueOrNull()!!
            assertTrue(result.length < 32_768)
            val parsed = JsonParser().parse(result).valueOrNull() as JsonObject
            observed.append((parsed["content"] as JsonString).value)
            val next = (parsed["nextOffset"] as com.dpsmpx.dpai.core.serialization.JsonNumber).toLongExact()!!
            assertTrue(next > offset)
            offset = next
        } while (offset < text.length)
        assertEquals(text, observed.toString())
    }

    @Test fun discoveryNeverFollowsAnOwnerSymlink(@TempDir outside: Path) {
        val head = outside.resolve("repo/.git/HEAD").toFile().apply { parentFile.mkdirs(); writeText("ref: refs/heads/main\n") }
        Files.createSymbolicLink(dir.resolve("owner"), outside)
        assertEquals(emptyList(), ApiQuickStart.clones(LocalTree(dir)).valueOrNull())
        assertTrue(head.exists())
    }
}

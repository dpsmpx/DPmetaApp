package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Модели GGUF с Hugging Face (D-110, D-119; T21) — на транспорте без сети. */
class HuggingFaceTest {
    private val repo = "acme/tiny-GGUF"
    private val rev = "9217f5db79a29953eb74d5343926648285ec7e67"
    private val model = ByteArray(300_000) { (it % 251).toByte() }
    private val small = "маленькая модель".toByteArray()

    private fun hex(algo: String, vararg parts: ByteArray): String =
        MessageDigest.getInstance(algo).apply { parts.forEach { update(it) } }.digest().joinToString("") { "%02x".format(it) }

    private val listJson = """
        {"id":"$repo","sha":"$rev","gated":false,"siblings":[
          {"rfilename":"README.md","size":10,"blobId":"${"a".repeat(40)}"},
          {"rfilename":"tiny-q4_k_m.gguf","size":${model.size},"blobId":"${"b".repeat(40)}","lfs":{"sha256":"${hex("SHA-256", model)}","size":${model.size},"pointerSize":134}},
          {"rfilename":"sub/small.gguf","size":${small.size},"blobId":"${hex("SHA-1", "blob ${small.size}\u0000".toByteArray(), small)}"},
          {"rfilename":"../evil.gguf","size":1,"blobId":"${"c".repeat(40)}"}
        ]}
    """.trimIndent().replace("\n", "")

    /** Ответ байтами: [failAfter] — обрыв после стольких байт, [hook] — перед каждым чтением. */
    private class Bytes(
        override val status: Int,
        private val body: ByteArray = ByteArray(0),
        private val headers: Map<String, String> = emptyMap(),
        private val failAfter: Int? = null,
        private val hook: (Int) -> Unit = {},
    ) : HttpReply {
        private var at = 0

        override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        override fun readLine(): String? = throw IOException("байты")

        override fun read(buf: ByteArray): Int {
            hook(at)
            if (failAfter != null && at >= failAfter) throw IOException("обрыв")
            if (at >= body.size) return -1
            val n = minOf(buf.size, body.size - at, (failAfter ?: Int.MAX_VALUE) - at)
            body.copyInto(buf, 0, at, at + n)
            at += n
            return n
        }

        override fun close() = Unit
    }

    private class Net(private val respond: (HttpCall, Cancel) -> HttpReply) : Transport {
        val calls = mutableListOf<HttpCall>()

        override fun open(call: HttpCall, cancel: Cancel): Outcome<HttpReply> {
            calls += call
            return Outcome.success(respond(call, cancel).also { if (it is FakeReply) it.cancel = cancel })
        }
    }

    private class Memory(start: ByteArray = ByteArray(0)) : DownloadSink {
        val out = ByteArrayOutputStream().apply { write(start) }
        override val size: Long get() = out.size().toLong()

        override fun read(offset: Long, length: Int): ByteArray = out.toByteArray().let { it.copyOfRange(offset.toInt(), minOf(it.size, offset.toInt() + length)) }

        override fun append(bytes: ByteArray) = out.write(bytes)

        override fun reset() = out.reset()
    }

    private val token = Secret("hf_" + "abcdefghij".repeat(3))

    private fun listing(): HfListing {
        val net = Net { _, _ -> FakeReply(200, listJson) }
        return HuggingFace(net).files(repo, null).valueOrNull()!!
    }

    private val cdn = "https://us.aws.cdn.hf.co/xet-bridge-us/abc/def?Expires=1&Signature=x"

    @Test
    fun repoIsOwnerAndNameOrTheModelAddress() {
        assertEquals("Qwen/Qwen2.5-0.5B-Instruct-GGUF", HuggingFace.repoOf(" Qwen/Qwen2.5-0.5B-Instruct-GGUF "))
        assertEquals("Qwen/Qwen2.5-0.5B-Instruct-GGUF", HuggingFace.repoOf("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main"))
        for (bad in listOf("qwen", "a/../b", "../a/b", "a/b/c", "https://evil.example/a/b", "a b/c")) assertNull(HuggingFace.repoOf(bad), bad)
    }

    @Test
    fun hostsAreHuggingFaceOnly() {
        assertTrue(HuggingFaceHosts.api(URI("https://huggingface.co/api/models/a/b")))
        assertTrue(!HuggingFaceHosts.api(URI("https://cdn-lfs.hf.co/x")), "токен — не на хранилище")
        for (ok in listOf("https://us.aws.cdn.hf.co/x", "https://cas-bridge.xethub.hf.co/x", "https://cdn-lfs.huggingface.co/x", "https://huggingface.co/api/resolve-cache/x")) {
            assertTrue(HuggingFaceHosts.storage(URI(ok)), ok)
        }
        for (bad in listOf("http://us.aws.cdn.hf.co/x", "https://evilhf.co/x", "https://hf.co.evil.example/x", "https://u:p@cdn-lfs.hf.co/x", "https://cdn-lfs.hf.co:8443/x")) {
            assertTrue(!HuggingFaceHosts.storage(URI(bad)), bad)
        }
    }

    @Test
    fun listsOnlySafeGgufFilesAtTheCommitWithoutToken() {
        val net = Net { _, _ -> FakeReply(200, listJson) }
        val l = HuggingFace(net).files(repo, null).valueOrNull()!!
        assertEquals("https://huggingface.co/api/models/acme/tiny-GGUF?blobs=true", net.calls.single().url.toString())
        assertNull(net.calls.single().secret, "просмотр — без токена")
        assertEquals(rev, l.revision)
        assertEquals(listOf("sub/small.gguf", "tiny-q4_k_m.gguf"), l.files.map { it.path }, "README и путь с «..» — мимо")
        assertNotNull(l.files.first { it.name == "tiny-q4_k_m.gguf" }.sha256)
        assertNotNull(l.files.first { it.name == "small.gguf" }.gitSha1)
        val gated = HuggingFace(Net { _, _ -> FakeReply(200, listJson.replace("\"gated\":false", "\"gated\":\"manual\"")) }).files(repo, null).valueOrNull()!!
        assertTrue(gated.gated)
    }

    /** Закрытая модель: без токена — просьба токена; токен — заголовком к huggingface.co; отвергнут — свой код. */
    @Test
    fun closedModelAsksForTokenAndTokenGoesOnlyToHuggingFace() {
        val noToken = HuggingFace(Net { _, _ -> FakeReply(401, "{\"error\":\"Invalid username or password.\"}") }).files(repo, null)
        assertEquals(DiagCode.PROVIDER_KEY_MISSING, assertIs<Outcome.Failure>(noToken).errors.single().code)
        val net = Net { _, _ -> FakeReply(403, "{}") }
        val bad = HuggingFace(net).files(repo, token)
        assertEquals(DiagCode.PROVIDER_AUTH_FAILED, assertIs<Outcome.Failure>(bad).errors.single().code)
        assertEquals("Authorization", net.calls.single().secret!!.name)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(HuggingFace(net).files("нет", null)).errors.single().code)
    }

    @Test
    fun downloadsByCommitThroughStorageAndChecksSha256() {
        val l = listing()
        val f = l.files.first { it.name == "tiny-q4_k_m.gguf" }
        val net = Net { call, _ ->
            if (call.url.host == "huggingface.co") Bytes(302, headers = mapOf("Location" to cdn)) else Bytes(200, model)
        }
        val sink = Memory()
        val seen = mutableListOf<Long>()
        val r = HuggingFace(net).download(l, f, token, sink, Cancel()) { have, _ -> seen += have }
        assertTrue(r is Outcome.Success, "$r")
        assertContentEquals(model, sink.out.toByteArray())
        assertEquals("https://huggingface.co/acme/tiny-GGUF/resolve/$rev/tiny-q4_k_m.gguf", net.calls[0].url.toString())
        assertNotNull(net.calls[0].secret, "к huggingface.co — с токеном")
        assertNull(net.calls[1].secret, "на хранилище токен не идёт")
        assertEquals(model.size.toLong(), seen.last())
    }

    @Test
    fun redirectOutsideHuggingFaceIsRefused() {
        val l = listing()
        val f = l.files.first { it.name == "tiny-q4_k_m.gguf" }
        for (where in listOf("https://evil.example/model.gguf", "http://us.aws.cdn.hf.co/x")) {
            val net = Net { call, _ -> if (call.url.host == "huggingface.co") Bytes(302, headers = mapOf("Location" to where)) else Bytes(200, model) }
            val sink = Memory()
            val r = HuggingFace(net).download(l, f, null, sink, Cancel())
            assertEquals(DiagCode.HOST_NOT_ALLOWED, assertIs<Outcome.Failure>(r).errors.single().code, where)
            assertEquals(1, net.calls.size, "на чужой адрес запроса нет")
            assertEquals(0L, sink.size)
        }
    }

    /** Малый файл не в LFS: 307 на относительный адрес того же huggingface.co; сумма — SHA-1 git-объекта. */
    @Test
    fun relativeRedirectAndGitBlobSum() {
        val l = listing()
        val f = l.files.first { it.name == "small.gguf" }
        val net = Net { call, _ ->
            if (call.url.path.startsWith("/api/resolve-cache/")) Bytes(200, small) else Bytes(307, headers = mapOf("Location" to "/api/resolve-cache/models/acme/tiny-GGUF/$rev/sub/small.gguf?etag=x"))
        }
        val sink = Memory()
        assertTrue(HuggingFace(net).download(l, f, token, sink, Cancel()) is Outcome.Success)
        assertContentEquals(small, sink.out.toByteArray())
        assertEquals("huggingface.co", net.calls[1].url.host)
    }

    @Test
    fun resumesWithRangeAndRestartsWhenServerSendsWhole() {
        val l = listing()
        val f = l.files.first { it.name == "tiny-q4_k_m.gguf" }
        val part = model.copyOf(120_000)
        val net = Net { call, _ ->
            if (call.url.host == "huggingface.co") {
                Bytes(302, headers = mapOf("Location" to cdn))
            } else {
                Bytes(206, model.copyOfRange(120_000, model.size), mapOf("Content-Range" to "bytes 120000-${model.size - 1}/${model.size}"))
            }
        }
        val sink = Memory(part)
        assertTrue(HuggingFace(net).download(l, f, null, sink, Cancel()) is Outcome.Success)
        assertEquals("bytes=120000-", net.calls[1].headers["Range"])
        assertContentEquals(model, sink.out.toByteArray())
        val whole = Net { call, _ -> if (call.url.host == "huggingface.co") Bytes(302, headers = mapOf("Location" to cdn)) else Bytes(200, model) }
        val again = Memory(part)
        assertTrue(HuggingFace(whole).download(l, f, null, again, Cancel()) is Outcome.Success)
        assertContentEquals(model, again.out.toByteArray(), "200 на Range — часть сброшена, файл целиком")
    }

    @Test
    fun wrongSumDeletesThePart() {
        val l = listing()
        val f = l.files.first { it.name == "tiny-q4_k_m.gguf" }
        val forged = model.copyOf().also { it[1000] = 0x7f }
        val net = Net { call, _ -> if (call.url.host == "huggingface.co") Bytes(302, headers = mapOf("Location" to cdn)) else Bytes(200, forged) }
        val sink = Memory()
        val r = HuggingFace(net).download(l, f, null, sink, Cancel())
        assertEquals(DiagCode.HASH_MISMATCH, assertIs<Outcome.Failure>(r).errors.single().code)
        assertEquals(0L, sink.size)
    }

    @Test
    fun cancelDeletesThePartAndDropKeepsIt() {
        val l = listing()
        val f = l.files.first { it.name == "tiny-q4_k_m.gguf" }
        val cancel = Cancel()
        val net = Net { call, c ->
            if (call.url.host == "huggingface.co") {
                Bytes(302, headers = mapOf("Location" to cdn))
            } else {
                Bytes(200, model, hook = { at -> if (at >= 131_072) c.cancel() }, failAfter = null).let { b ->
                    object : HttpReply by b {
                        override fun read(buf: ByteArray): Int = if (c.cancelled) throw IOException("соединение закрыто") else b.read(buf)
                    }
                }
            }
        }
        val sink = Memory()
        val r = HuggingFace(net).download(l, f, null, sink, cancel)
        assertEquals(DiagCode.CANCELLED_BY_USER, assertIs<Outcome.Failure>(r).errors.single().code)
        assertEquals(0L, sink.size, "отмена — часть удалена")
        val drop = Net { call, _ -> if (call.url.host == "huggingface.co") Bytes(302, headers = mapOf("Location" to cdn)) else Bytes(200, model, failAfter = 200_000) }
        val kept = Memory()
        val d = HuggingFace(drop).download(l, f, null, kept, Cancel())
        assertEquals(DiagCode.NETWORK_UNREACHABLE, assertIs<Outcome.Failure>(d).errors.single().code)
        assertEquals(200_000L, kept.size, "обрыв — дописанное остаётся для докачки")
    }
}

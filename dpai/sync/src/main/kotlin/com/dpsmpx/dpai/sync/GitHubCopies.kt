package com.dpsmpx.dpai.sync

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.providers.Cancel
import com.dpsmpx.dpai.providers.HttpCall
import com.dpsmpx.dpai.providers.SecretHeader
import com.dpsmpx.dpai.providers.Transport
import java.io.IOException
import java.net.URI
import java.util.Base64

/** Репозиторий копий: приватный ли, ветка по умолчанию, можно ли писать токеном. */
data class CopyRepo(val private: Boolean, val branch: String, val canPush: Boolean)

/** Итог отправки: коммит и блоб копии — по блобу следующая отправка узнаёт свою копию. */
data class Uploaded(val commit: String, val blob: String)

/**
 * Копии контура в приватном репозитории GitHub (D-107, T20): Git Data API
 * на `api.github.com` — блоб, дерево поверх прежнего, коммит, сдвиг ветки
 * без силы; загрузка — блоб по пути. Адрес — постоянный, переадресации
 * транспорт не выполняет (D-98); токен — только в заголовке
 * `Authorization` ([SecretHeader]), в адрес и журнал не попадает.
 * Публичный репозиторий — отказ: копия, хоть и зашифрованная, не ложится
 * в открытый доступ.
 */
class GitHubCopies(private val transport: Transport, private val owner: String, private val repo: String, private val token: Secret) {
    /** Репозиторий годится для копий: есть, приватный, токен может писать. */
    fun check(cancel: Cancel = Cancel()): Outcome<CopyRepo> {
        nameProblem()?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, "github") }
        val o = when (val r = json("GET", "", null, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value as? JsonObject ?: return bad("ответ о репозитории — не объект")
        }
        val private = (o["private"] as? JsonBool)?.value ?: return bad("в ответе нет признака приватности")
        val branch = (o["default_branch"] as? JsonString)?.value ?: return bad("в ответе нет ветки по умолчанию")
        val push = ((o["permissions"] as? JsonObject)?.get("push") as? JsonBool)?.value ?: false
        val repoInfo = CopyRepo(private, branch, push)
        return when {
            !private -> Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "репозиторий $owner/$repo открытый: копии — только в приватный", "github")
            !push -> Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "токен не может писать в $owner/$repo: нужен доступ Contents — Read and write", "github")
            !BRANCH.matches(branch) || ".." in branch -> bad("имя ветки по умолчанию не поддержано: $branch")
            else -> Outcome.success(repoInfo)
        }
    }

    /**
     * Положить [bytes] по [path] новым коммитом ветки по умолчанию. Файл по
     * пути уже есть и это не [expected] (блоб прошлой отправки этой
     * установки) — отказ `STATE_CONFLICT`, пока человек не скажет
     * [overwrite]: новая установка не затрёт молча копию, из которой её
     * ещё не восстановили.
     */
    fun upload(path: String, bytes: ByteArray, message: String, expected: String?, overwrite: Boolean = false, cancel: Cancel = Cancel()): Outcome<Uploaded> {
        pathProblem(path)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, "github") }
        if (bytes.size > MAX_COPY_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "копия ${bytes.size / 1024 / 1024} МиБ — больше ${MAX_COPY_BYTES / 1024 / 1024} МиБ: GitHub её не примет", "github")
        val repoInfo = when (val r = check(cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val head = when (val r = sha("GET", "/git/ref/heads/${repoInfo.branch}", null, cancel, "object")) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val baseTree = when (val r = sha("GET", "/git/commits/$head", null, cancel, "tree")) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val present = when (val r = blobAt(path, repoInfo.branch, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (present != null && present != expected && !overwrite) {
            return Outcome.failure(DiagCode.STATE_CONFLICT, "по пути $path уже лежит копия, отправленная не с этой установки: восстановите её или подтвердите замену", "github")
        }
        val blob = when (val r = sha("POST", "/git/blobs", obj("content" to JsonString(Base64.getEncoder().encodeToString(bytes)), "encoding" to JsonString("base64")), cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val entry = obj("path" to JsonString(path), "mode" to JsonString("100644"), "type" to JsonString("blob"), "sha" to JsonString(blob))
        val tree = when (val r = sha("POST", "/git/trees", obj("base_tree" to JsonString(baseTree), "tree" to JsonArray(listOf(entry))), cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val commit = when (val r = sha("POST", "/git/commits", obj("message" to JsonString(message), "tree" to JsonString(tree), "parents" to JsonArray(listOf(JsonString(head)))), cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return when (val r = json("PATCH", "/git/refs/heads/${repoInfo.branch}", obj("sha" to JsonString(commit), "force" to JsonBool(false)), cancel)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Uploaded(commit, blob))
        }
    }

    /** Блоб файла [path] ветки [branch]; файла нет — `null`. */
    private fun blobAt(path: String, branch: String, cancel: Cancel): Outcome<String?> =
        when (val r = sha("GET", "/contents/$path?ref=$branch", null, cancel, accept = OBJECT)) {
            is Outcome.Success -> r
            is Outcome.Failure -> if (r.diagnostics.any { it.code == DiagCode.GITHUB_NOT_FOUND }) Outcome.success(null) else r
        }

    /** Файл [path] ветки по умолчанию; нет его — `GITHUB_NOT_FOUND`. */
    fun download(path: String, cancel: Cancel = Cancel()): Outcome<ByteArray> {
        pathProblem(path)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, "github") }
        val repoInfo = when (val r = check(cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        // Оглавление по пути (вид `object` — и для файлов больше 1 МиБ) даёт sha блоба; содержимое — API блобов.
        val blob = when (val r = sha("GET", "/contents/$path?ref=${repoInfo.branch}", null, cancel, accept = OBJECT)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val o = when (val r = json("GET", "/git/blobs/$blob", null, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value as? JsonObject ?: return bad("ответ о блобе — не объект")
        }
        if ((o["encoding"] as? JsonString)?.value != "base64") return bad("блоб не в base64")
        val content = (o["content"] as? JsonString)?.value ?: return bad("в блобе нет содержимого")
        return try {
            Outcome.success(Base64.getMimeDecoder().decode(content))
        } catch (_: IllegalArgumentException) {
            bad("содержимое блоба — не base64")
        }
    }

    /** Имена файлов копий в каталоге [dir] ветки по умолчанию; каталога нет — пусто. */
    fun list(dir: String, cancel: Cancel = Cancel()): Outcome<List<String>> {
        pathProblem(dir)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, "github") }
        val repoInfo = when (val r = check(cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return when (val r = json("GET", "/contents/$dir?ref=${repoInfo.branch}", null, cancel)) {
            is Outcome.Failure -> if (r.diagnostics.any { it.code == DiagCode.GITHUB_NOT_FOUND }) Outcome.success(emptyList()) else r
            is Outcome.Success -> {
                val items = (r.value as? JsonArray)?.items ?: return bad("каталог копий — не список")
                Outcome.success(items.mapNotNull { (it as? JsonObject)?.let { e -> if ((e["type"] as? JsonString)?.value == "file") (e["name"] as? JsonString)?.value else null } }.sorted())
            }
        }
    }

    private fun nameProblem(): String? =
        if (NAME.matches(owner) && NAME.matches(repo) && owner != "." && owner != ".." && repo != "." && repo != "..") null else "владелец и репозиторий — латиница, цифры, «.», «-», «_»"

    private fun sha(method: String, path: String, body: JsonObject?, cancel: Cancel, inner: String? = null, accept: String = JSON): Outcome<String> = when (val r = json(method, path, body, cancel, accept)) {
        is Outcome.Failure -> r
        is Outcome.Success -> {
            val o = (r.value as? JsonObject)?.let { if (inner == null) it else it[inner] as? JsonObject }
            (o?.get("sha") as? JsonString)?.value?.takeIf { SHA.matches(it) }?.let { Outcome.success(it) } ?: bad("в ответе $method $path нет sha")
        }
    }

    private fun json(method: String, path: String, body: JsonObject?, cancel: Cancel, accept: String = JSON): Outcome<JsonValue> {
        nameProblem()?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, "github") }
        val url = URI.create("$API/repos/$owner/$repo$path")
        if (url.scheme != "https" || url.host != HOST) return Outcome.failure(DiagCode.HOST_NOT_ALLOWED, "копии — только на $HOST", url.host)
        val headers = linkedMapOf("Accept" to accept, "X-GitHub-Api-Version" to "2022-11-28", "User-Agent" to "dpai-cloud-sync")
        if (body != null) headers["Content-Type"] = "application/json"
        val call = HttpCall(method, url, headers, body?.let { CanonicalJson.bytes(it) }, SecretHeader("Authorization", "Bearer ", token))
        val reply = when (val r = transport.open(call, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return reply.use { rep ->
            val text = try {
                rep.readText(MAX_REPLY_CHARS)
            } catch (e: IOException) {
                return@use Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "ответ GitHub не дочитан: ${e.javaClass.simpleName}", HOST)
            }
            when (rep.status) {
                // Предел разбора — как предел ответа: блоб копии у предела размера тоже разбирается.
                in 200..299 -> when (val j = JsonParser(JsonParser.Limits(maxChars = MAX_REPLY_CHARS)).parse(text)) {
                    is Outcome.Success -> j
                    is Outcome.Failure -> Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "ответ GitHub не разобран: ${j.errors.firstOrNull()?.message}", HOST)
                }
                401 -> Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "токен не принят: проверьте его срок и доступ к $owner/$repo", HOST)
                403, 429 -> if (rep.header("x-ratelimit-remaining") == "0" || rep.status == 429) {
                    Outcome.failure(DiagCode.GITHUB_RATE_LIMITED, "предел запросов GitHub — позже", HOST)
                } else {
                    Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "нет прав: токену нужен доступ Contents — Read and write к $owner/$repo", HOST)
                }
                404 -> Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "не найдено или скрыто от токена: $owner/$repo$path", HOST)
                409 -> Outcome.failure(DiagCode.VALUE_INVALID, "репозиторий пуст: создайте его с файлом README", HOST)
                422 -> Outcome.failure(DiagCode.STATE_CONFLICT, "GitHub отклонил запрос (ветка ушла вперёд?) — повторите", HOST)
                else -> Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "GitHub ответил ${rep.status}", HOST)
            }
        }
    }

    private fun bad(why: String): Outcome<Nothing> = Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, why, HOST)

    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonObject.of(*pairs)

    companion object {
        const val HOST = "api.github.com"
        const val API = "https://$HOST"

        /**
         * Предел копии: загрузка держит блоб в памяти несколько раз (строка
         * ответа, текст, строка JSON, байты), а телефону нужна память и на
         * остальное; GitHub к тому же предупреждает о файлах больше 50 МиБ.
         */
        const val MAX_COPY_BYTES = 10 * 1024 * 1024

        /**
         * Предел ответа в знаках, разбора JSON и строки транспорта в байтах:
         * блоб 10 МиБ в base64 с экранированными переводами строк — около
         * 14,4 млн знаков, с запасом.
         */
        const val MAX_REPLY_CHARS = 20 * 1024 * 1024
        private const val JSON = "application/vnd.github+json"
        private const val OBJECT = "application/vnd.github.object+json"
        private val NAME = Regex("[A-Za-z0-9_.-]{1,100}")
        private val BRANCH = Regex("[A-Za-z0-9_./-]{1,100}")
        private val SHA = Regex("[0-9a-f]{40}")
        private val PATH = Regex("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")

        fun pathProblem(path: String): String? =
            if (PATH.matches(path) && path.split('/').none { it == "." || it == ".." } && path.length <= 200) null else "путь в репозитории — латиница, цифры, «.», «-», «_» и «/»"
    }
}

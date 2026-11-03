package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Перевод потока исполнителя в нормальную форму хода — строки `stream-json`
 * Claude Code (D-100): дальше их разбирает [ClaudeStreamParser], и итог,
 * восстановление и замер — одни для всех исполнителей. Строка, которую
 * перевести нельзя (не JSON, неизвестное событие), отдаётся как есть —
 * разборщик назовёт её находкой, а не догадкой (D-05).
 */
interface StreamNormalizer {
    /** Строки нормальной формы для строки stdout исполнителя. */
    fun lines(line: String): List<String>

    /** Строка stderr: у части исполнителей отказ входа виден только там. */
    fun stderr(text: String) {}

    /** Код причины, если поток кончился без итога, а stderr или ошибки её назвали. */
    fun failureHint(): DiagCode? = null

    companion object {
        fun of(executor: Executor, session: ProviderSessionId): StreamNormalizer = when (executor) {
            Executor.CLAUDE -> Passthrough
            Executor.CODEX -> CodexNormalizer(session)
            Executor.GEMINI -> GeminiNormalizer(session)
        }
    }

    object Passthrough : StreamNormalizer {
        override fun lines(line: String): List<String> = listOf(line)
    }
}

/** Общее у переводчиков: события нормальной формы и разбор причин отказа по тексту ошибки. */
abstract class NormalizerBase(protected val session: ProviderSessionId) : StreamNormalizer {
    protected var hint: DiagCode? = null

    override fun failureHint(): DiagCode? = hint

    protected fun parse(line: String): JsonObject? =
        if (line.isBlank()) null else (JsonParser().parse(line).valueOrNull() as? JsonObject)

    protected fun out(vararg pairs: Pair<String, JsonValue?>): String =
        CanonicalJson.write(JsonObject(linkedMapOf<String, JsonValue>().also { m -> pairs.forEach { (k, v) -> if (v != null) m[k] = v } }))

    protected fun init(model: String?): String = out(
        "type" to JsonString("system"), "subtype" to JsonString("init"),
        "session_id" to JsonString(session.value), "model" to model?.let(::JsonString),
    )

    /** Сообщение модели: текст, рассуждение, вызовы инструментов, ошибка API. */
    protected fun assistant(texts: List<String> = emptyList(), thinking: List<String> = emptyList(), tools: List<Triple<String, String, JsonObject>> = emptyList(), error: String? = null): String {
        val content = texts.map { JsonObject.of("type" to JsonString("text"), "text" to JsonString(it)) } +
            thinking.map { JsonObject.of("type" to JsonString("thinking"), "thinking" to JsonString(it)) } +
            tools.map { (id, name, input) -> JsonObject.of("type" to JsonString("tool_use"), "id" to JsonString(id), "name" to JsonString(name), "input" to input) }
        return out(
            "type" to JsonString("assistant"), "session_id" to JsonString(session.value),
            "message" to JsonObject.of("content" to JsonArray(content)), "error" to error?.let(::JsonString),
        )
    }

    protected fun toolResult(id: String, text: String, isError: Boolean): String = out(
        "type" to JsonString("user"), "session_id" to JsonString(session.value),
        "message" to JsonObject.of(
            "content" to JsonArray(listOf(JsonObject.of("type" to JsonString("tool_result"), "tool_use_id" to JsonString(id), "content" to JsonString(text), "is_error" to JsonBool(isError)))),
        ),
    )

    protected fun result(success: Boolean, text: String?, usage: JsonObject?): String = out(
        "type" to JsonString("result"), "subtype" to JsonString(if (success) "success" else "error_during_execution"),
        "is_error" to JsonBool(!success), "result" to text?.let(::JsonString), "session_id" to JsonString(session.value), "usage" to usage,
    )

    /** Ошибка API в нормальной форме: `authentication_failed`, `rate_limit` или `null` — прочее. */
    protected fun classify(message: String): String? {
        val m = message.lowercase()
        return when {
            AUTH.any { it in m } -> "authentication_failed"
            LIMIT.any { it in m } -> "rate_limit"
            else -> null
        }
    }

    protected fun remember(message: String) {
        when (classify(message)) {
            "authentication_failed" -> hint = DiagCode.CLAUDE_AUTH_REQUIRED
            "rate_limit" -> if (hint == null) hint = DiagCode.CLAUDE_RATE_LIMITED
        }
    }

    companion object {
        private val AUTH = listOf("401 unauthorized", "unauthenticated", "not logged in", "fatalauthenticationerror", "manual authorization is required", "please set an auth method", "login required")
        private val LIMIT = listOf("usage limit", "rate limit", "rate_limit", "429", "resource_exhausted", "quota exceeded", "quota")

        fun JsonObject.str(key: String): String? = (this[key] as? JsonString)?.value

        fun JsonObject.long(key: String): Long? = (this[key] as? JsonNumber)?.toLongExact()
    }
}

/**
 * Поток `codex exec --json` (`docs/research/CODEX_CLI.md`, §3): элементы
 * хода — сообщения, рассуждения, команды и правки файлов — становятся
 * сообщениями и вызовами инструментов нормальной формы (команда — `Bash`,
 * правка — `Edit`/`Write` с `file_path`, D-41); `turn.completed` — итог с
 * расходом, `turn.failed` — итог с ошибкой. Id сессии Codex (`thread_id`)
 * назначает сам; в нормальной форме — id оркестратора.
 */
class CodexNormalizer(session: ProviderSessionId) : NormalizerBase(session) {
    private var lastText: String? = null

    override fun lines(line: String): List<String> {
        val o = parse(line) ?: return listOf(line)
        return when (o.str("type")) {
            "thread.started" -> listOf(init(null))
            "item.completed" -> item(o["item"] as? JsonObject)
            "turn.completed" -> {
                val u = o["usage"] as? JsonObject
                val input = u?.long("input_tokens")
                val cached = u?.long("cached_input_tokens") ?: 0
                val usage = u?.let {
                    JsonObject(
                        linkedMapOf<String, JsonValue>().also { m ->
                            // У OpenAI кеш — часть входа; в нормальной форме вход — без кеша, как у Claude.
                            input?.let { m["input_tokens"] = JsonNumber.of(maxOf(0, it - cached)) }
                            m["cache_read_input_tokens"] = JsonNumber.of(cached)
                            it.long("output_tokens")?.let { v -> m["output_tokens"] = JsonNumber.of(v) }
                        },
                    )
                }
                listOf(result(true, lastText, usage))
            }
            "turn.failed" -> {
                val msg = (o["error"] as? JsonObject)?.str("message") ?: "ход Codex не удался"
                remember(msg)
                listOf(assistant(error = classify(msg) ?: "api_error"), result(false, msg, null))
            }
            "error" -> {
                o.str("message")?.let(::remember)
                emptyList()
            }
            "turn.started", "item.started", "item.updated" -> emptyList()
            else -> listOf(line)
        }
    }

    override fun stderr(text: String) = remember(text)

    private fun item(i: JsonObject?): List<String> {
        i ?: return emptyList()
        val id = i.str("id") ?: "item"
        return when (i.str("type")) {
            "agent_message" -> i.str("text")?.let {
                lastText = it
                listOf(assistant(texts = listOf(it)))
            }.orEmpty()
            "reasoning" -> i.str("text")?.takeIf { it.isNotBlank() }?.let { listOf(assistant(thinking = listOf(it))) }.orEmpty()
            "command_execution" -> {
                val input = JsonObject.of("command" to JsonString(i.str("command").orEmpty()))
                val failed = (i["exit_code"] as? JsonNumber)?.toLongExact()?.let { it != 0L } ?: (i.str("status") == "failed")
                listOf(assistant(tools = listOf(Triple(id, "Bash", input))), toolResult(id, i.str("aggregated_output").orEmpty(), failed))
            }
            "file_change" -> {
                val changes = (i["changes"] as? JsonArray)?.items.orEmpty().filterIsInstance<JsonObject>()
                val tools = changes.mapIndexed { n, c ->
                    val name = if (c.str("kind") == "add") "Write" else "Edit"
                    Triple("$id-$n", name, JsonObject.of("file_path" to JsonString(c.str("path").orEmpty())))
                }
                if (tools.isEmpty()) emptyList() else listOf(assistant(tools = tools)) + tools.map { toolResult(it.first, "", i.str("status") == "failed") }
            }
            "mcp_tool_call" -> {
                val name = "mcp__${i.str("server") ?: "?"}__${i.str("tool") ?: "?"}"
                listOf(assistant(tools = listOf(Triple(id, name, JsonObject.of()))), toolResult(id, "", i.str("status") == "failed"))
            }
            "web_search" -> listOf(
                assistant(tools = listOf(Triple(id, "WebSearch", JsonObject.of("query" to JsonString(i.str("query").orEmpty()))))),
                toolResult(id, "", false),
            )
            "error" -> {
                i.str("message")?.let(::remember)
                emptyList()
            }
            else -> emptyList()
        }
    }
}

/**
 * Поток `gemini --output-format stream-json` (`docs/research/GEMINI_CLI.md`,
 * §3): куски ответа (`message`, `delta`) копятся и становятся сообщением
 * перед вызовом инструмента и в конце; инструменты Gemini — под именами
 * нормальной формы (`read_file` — `Read`, `run_shell_command` — `Bash`, …);
 * `result` — итог с расходом или с ошибкой. Отказ входа Gemini пишет в
 * stderr, а не в поток.
 */
class GeminiNormalizer(session: ProviderSessionId) : NormalizerBase(session) {
    private val pending = StringBuilder()
    private var lastText: String? = null

    override fun lines(line: String): List<String> {
        val o = parse(line) ?: return listOf(line)
        return when (o.str("type")) {
            "init" -> listOf(init(o.str("model")))
            "message" -> {
                if (o.str("role") == "assistant") o.str("content")?.let { pending.append(it) }
                emptyList()
            }
            "tool_use" -> {
                val id = o.str("tool_id") ?: "tool"
                val (name, input) = tool(o.str("tool_name").orEmpty(), o["parameters"] as? JsonObject ?: JsonObject.of())
                flush() + assistant(tools = listOf(Triple(id, name, input)))
            }
            "tool_result" -> {
                val text = o.str("output") ?: (o["error"] as? JsonObject)?.str("message") ?: ""
                listOf(toolResult(o.str("tool_id") ?: "tool", text, o.str("status") == "error"))
            }
            "error" -> {
                o.str("message")?.let(::remember)
                emptyList()
            }
            "result" -> {
                val head = flush()
                val stats = o["stats"] as? JsonObject
                val usage = stats?.let {
                    JsonObject(
                        linkedMapOf<String, JsonValue>().also { m ->
                            it.long("input_tokens")?.let { v -> m["input_tokens"] = JsonNumber.of(v) }
                            it.long("output_tokens")?.let { v -> m["output_tokens"] = JsonNumber.of(v) }
                        },
                    )
                }
                if (o.str("status") == "success") {
                    head + result(true, lastText, usage)
                } else {
                    val e = o["error"] as? JsonObject
                    val msg = listOfNotNull(e?.str("type"), e?.str("message")).joinToString(": ").ifEmpty { "ход Gemini не удался" }
                    remember(msg)
                    head + assistant(error = classify(msg) ?: "api_error") + result(false, msg, usage)
                }
            }
            else -> listOf(line)
        }
    }

    override fun stderr(text: String) = remember(text)

    private fun flush(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val text = pending.toString()
        pending.setLength(0)
        lastText = text
        return listOf(assistant(texts = listOf(text)))
    }

    /** Имя и вход инструмента Gemini — как у встроенных инструментов нормальной формы (D-41: файлы хода). */
    private fun tool(name: String, p: JsonObject): Pair<String, JsonObject> {
        val path = (p["absolute_path"] ?: p["file_path"] ?: p["path"]) as? JsonString
        fun withPath(n: String) = n to (path?.let { JsonObject.of("file_path" to it) } ?: JsonObject.of())
        return when (name) {
            "read_file", "read_many_files" -> withPath("Read")
            "write_file" -> withPath("Write")
            "replace", "edit" -> withPath("Edit")
            "run_shell_command" -> "Bash" to JsonObject.of("command" to ((p["command"] as? JsonString) ?: JsonString("")))
            "glob" -> "Glob" to p
            "search_file_content", "grep" -> "Grep" to p
            "list_directory", "ls" -> "LS" to p
            "web_fetch" -> "WebFetch" to p
            "google_web_search" -> "WebSearch" to p
            else -> name to p
        }
    }
}

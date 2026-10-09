package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Рабочее дерево задачи на target для хода Claude на другом target — MCP-
 * сервер по stdio (D-50; `docs/EXECUTION_MODEL.md`, §7). Claude Code на
 * телефоне запускает его сам: `ssh … user@pc dpai agent mcp --root <дерево>`
 * в `--mcp-config`, а свои встроенные инструменты отключает (`--tools ""`).
 * Так ход идёт со входом телефона, а файлы и команды — на ПК; вход claude.ai
 * на ПК не нужен и никуда не передаётся (D-27).
 *
 * Протокол — JSON-RPC 2.0 строками (транспорт stdio MCP): `initialize`,
 * `ping`, `tools/list`, `tools/call`; уведомления без ответа. Запросы
 * обрабатываются по одному, по порядку.
 *
 * Инструменты — `run`, `read`, `write`, `edit`, `glob`, `grep`. Пути —
 * относительно [root] или абсолютные внутри него; путь вне дерева (в том
 * числе через символьную ссылку) — отказ инструмента, а не доступ (граница
 * проекта, `docs/SECURITY_MODEL.md`). Команды `run` идут в [root] через
 * [shell] и останавливаются всем деревом по тайм-ауту (ТЗ, §119); за их
 * пределы граница не действует — как и у встроенного Bash.
 */
class WorkspaceMcp(
    root: Path,
    private val shell: Shell,
    private val version: String,
    private val host: ProcessHost = LocalProcessHost(),
) {
    /** Оболочка `run`: как запустить строку команды и как её назвать модели. */
    data class Shell(val name: String, val prefix: List<String>)

    private val root: Path = root.toAbsolutePath().normalize()
    private val realRoot: Path = runCatching { this.root.toRealPath() }.getOrDefault(this.root)
    private val vfs = NioVfs(this.root)
    private val parser = JsonParser()

    /** Читать запросы до конца входа; ответы — строками в [out]. */
    fun serve(input: BufferedReader, out: (String) -> Unit) {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            handle(line)?.let(out)
        }
    }

    /** Одно сообщение JSON-RPC → строка ответа; уведомление — `null`. */
    fun handle(line: String): String? {
        val msg = when (val p = parser.parse(line)) {
            is Outcome.Failure -> return error(JsonNull, PARSE_ERROR, "не JSON: ${p.errors.first().message}")
            is Outcome.Success -> p.value as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "ожидался объект")
        }
        val id = msg["id"]
        val method = (msg["method"] as? JsonString)?.value
        if (id == null) return null
        if (method == null) return error(id, INVALID_REQUEST, "нет method")
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, JsonObject.of("tools" to JsonArray(TOOLS.map { it.describe(shell) })))
            "tools/call" -> {
                val name = (params["name"] as? JsonString)?.value
                val tool = TOOLS.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "нет инструмента $name")
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val r = try {
                    tool.call(this, Args(args))
                } catch (e: ToolError) {
                    ToolResult(e.message.orEmpty(), isError = true)
                } catch (e: IOException) {
                    ToolResult("ошибка ввода-вывода: ${e.message}", isError = true)
                }
                result(id, JsonObject.of("content" to JsonArray(listOf(JsonObject.of("type" to JsonString("text"), "text" to JsonString(r.text)))), "isError" to JsonBool(r.isError)))
            }
            else -> error(id, METHOD_NOT_FOUND, "метод $method не поддерживается")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        // Версия клиента, если мы её знаем; иначе — наша последняя (спецификация MCP, «Lifecycle»).
        val asked = (params["protocolVersion"] as? JsonString)?.value
        val agreed = asked?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.last()
        return JsonObject.of(
            "protocolVersion" to JsonString(agreed),
            "capabilities" to JsonObject.of("tools" to JsonObject(emptyMap())),
            "serverInfo" to JsonObject.of("name" to JsonString("dpai-workspace"), "version" to JsonString(version)),
            "instructions" to JsonString(
                "Рабочее дерево задачи: ${root.toString().replace('\\', '/')}. Пути — относительно него. " +
                    "Команды — инструмент run (${shell.name}).",
            ),
        )
    }

    private fun result(id: JsonValue, value: JsonValue): String =
        CanonicalJson.write(JsonObject.of("jsonrpc" to JsonString("2.0"), "id" to id, "result" to value))

    private fun error(id: JsonValue, code: Int, message: String): String =
        CanonicalJson.write(
            JsonObject.of("jsonrpc" to JsonString("2.0"), "id" to id, "error" to JsonObject.of("code" to JsonNumber.of(code), "message" to JsonString(message))),
        )

    // --- пути ---

    /** Путь внутри дерева; вне его — [ToolError]. */
    internal fun resolve(p: String?): Path {
        val raw = p?.takeIf { it.isNotBlank() } ?: return root
        val path = try {
            Path.of(raw.replace('\\', '/').let { if (FileSystems.getDefault().separator == "\\") it.replace('/', '\\') else it })
        } catch (_: InvalidPathException) {
            throw ToolError("путь недопустим: $raw")
        }
        val full = (if (path.isAbsolute) path else root.resolve(path)).normalize()
        if (!full.startsWith(root)) throw ToolError("путь вне рабочего дерева: $raw")
        // Символьная ссылка внутри дерева не выводит за него: сверка по настоящему пути существующей части.
        var probe: Path? = full
        while (probe != null && !Files.exists(probe)) probe = probe.parent
        if (probe != null && !probe.toRealPath().startsWith(realRoot)) throw ToolError("путь ведёт за рабочее дерево: $raw")
        return full
    }

    private fun shown(p: Path): String = root.relativize(p).toString().replace('\\', '/').ifEmpty { "." }

    // --- инструменты ---

    internal class Args(private val o: JsonObject) {
        fun string(name: String): String = opt(name) ?: throw ToolError("нужен параметр $name")

        fun opt(name: String): String? = when (val v = o[name]) {
            null, JsonNull -> null
            is JsonString -> v.value
            else -> throw ToolError("$name — строка")
        }

        fun long(name: String, default: Long, range: LongRange): Long = when (val v = o[name]) {
            null, JsonNull -> default
            is JsonNumber -> v.toLongExact()?.takeIf { it in range } ?: throw ToolError("$name — целое ${range.first}…${range.last}")
            else -> throw ToolError("$name — число")
        }

        fun bool(name: String): Boolean = when (val v = o[name]) {
            null, JsonNull -> false
            is JsonBool -> v.value
            else -> throw ToolError("$name — true или false")
        }
    }

    internal class ToolError(message: String) : Exception(message)

    internal data class ToolResult(val text: String, val isError: Boolean = false)

    private class Tool(
        val name: String,
        val description: (Shell) -> String,
        val properties: List<Triple<String, String, String>>,
        val required: List<String>,
        val call: WorkspaceMcp.(Args) -> ToolResult,
    ) {
        fun describe(shell: Shell): JsonObject = JsonObject.of(
            "name" to JsonString(name),
            "description" to JsonString(description(shell)),
            "inputSchema" to JsonObject.of(
                "type" to JsonString("object"),
                "properties" to JsonObject(properties.associate { (n, type, d) -> n to JsonObject.of("type" to JsonString(type), "description" to JsonString(d)) }),
                "required" to JsonArray(required.map(::JsonString)),
            ),
        )
    }

    private fun run(a: Args): ToolResult {
        val command = a.string("command")
        val timeout = a.long("timeout_seconds", 120, 1L..600L).seconds
        val lines = ArrayList<String>()
        var chars = 0L
        val lock = Any()
        val started = host.start(ProcessSpec(shell.prefix + command, workDir = root.toString())) { line ->
            synchronized(lock) {
                chars += line.text.length + 1
                lines += line.text
                // Держим только хвост: начало длинного вывода модели обычно не нужно.
                while (lines.size > 1 && chars > MAX_OUTPUT) chars -= lines.removeAt(0).length + 1
            }
        }
        val process = when (started) {
            is Outcome.Failure -> throw ToolError("команда не запустилась: ${started.errors.first().message}")
            is Outcome.Success -> started.value
        }
        val code = process.awaitExit(timeout)
        val tail = if (code == null) {
            val report = process.stop()
            "\n[остановлено по тайм-ауту $timeout" + (if (report.clean) "" else "; живы ${report.survivors.joinToString()}") + "]"
        } else {
            "\n[код выхода $code]"
        }
        val text = synchronized(lock) { lines.joinToString("\n") }
        return ToolResult(text + tail, isError = code != 0)
    }

    private fun read(a: Args): ToolResult {
        val p = resolve(a.string("path"))
        if (!Files.isRegularFile(p)) throw ToolError("нет файла ${shown(p)}")
        val offset = a.long("offset", 1, 1L..Int.MAX_VALUE.toLong()).toInt()
        val limit = a.long("limit", 2000, 1L..10_000L).toInt()
        val text = text(p) ?: throw ToolError("${shown(p)} — не текст UTF-8")
        val all = text.lines()
        val out = StringBuilder()
        for (i in (offset - 1) until minOf(all.size, offset - 1 + limit)) {
            val line = all[i].let { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
            if (out.length + line.length > MAX_OUTPUT * 3) {
                out.append("[дальше обрезано: строки с ${i + 1}]\n")
                break
            }
            out.append((i + 1).toString().padStart(6)).append('\t').append(line).append('\n')
        }
        if (out.isEmpty()) out.append("[в файле ${all.size} строк — с $offset ничего]")
        return ToolResult(out.toString())
    }

    private fun write(a: Args): ToolResult {
        val p = resolve(a.string("path"))
        val content = a.string("content")
        if (Files.isDirectory(p)) throw ToolError("${shown(p)} — каталог")
        store(p, content.toByteArray(Charsets.UTF_8))
        return ToolResult("записано ${shown(p)}: ${content.length} знаков")
    }

    private fun edit(a: Args): ToolResult {
        val p = resolve(a.string("path"))
        val old = a.string("old_string")
        val new = a.string("new_string")
        if (old.isEmpty()) throw ToolError("old_string пуст: новый файл — write")
        if (old == new) throw ToolError("old_string и new_string совпадают")
        if (!Files.isRegularFile(p)) throw ToolError("нет файла ${shown(p)}")
        val text = text(p) ?: throw ToolError("${shown(p)} — не текст UTF-8")
        val count = countOf(text, old)
        if (count == 0) throw ToolError("old_string не найден в ${shown(p)}")
        val all = a.bool("replace_all")
        if (count > 1 && !all) throw ToolError("old_string встречается $count раз: уточните его или replace_all")
        val changed = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        store(p, changed.toByteArray(Charsets.UTF_8))
        return ToolResult("изменено ${shown(p)}: замен ${if (all) count else 1}")
    }

    private fun glob(a: Args): ToolResult {
        val base = resolve(a.opt("path"))
        if (!Files.isDirectory(base)) throw ToolError("нет каталога ${shown(base)}")
        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:" + a.string("pattern"))
        } catch (e: IllegalArgumentException) {
            throw ToolError("шаблон недопустим: ${e.message}")
        }
        val found = files(base).filter { matcher.matches(base.relativize(it)) }
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
        val shownFiles = found.take(MAX_RESULTS).map(::shown)
        val more = if (found.size > MAX_RESULTS) "\n[ещё ${found.size - MAX_RESULTS}]" else ""
        return ToolResult(shownFiles.joinToString("\n").ifEmpty { "[ничего]" } + more)
    }

    private fun grep(a: Args): ToolResult {
        val base = resolve(a.opt("path"))
        val regex = try {
            Regex(a.string("pattern"), if (a.bool("ignore_case")) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: IllegalArgumentException) {
            throw ToolError("выражение недопустимо: ${e.message}")
        }
        val only = a.opt("glob")?.let { g ->
            try {
                FileSystems.getDefault().getPathMatcher("glob:$g")
            } catch (e: IllegalArgumentException) {
                throw ToolError("шаблон недопустим: ${e.message}")
            }
        }
        val limit = a.long("max_results", MAX_RESULTS.toLong(), 1L..2000L).toInt()
        val candidates = if (Files.isRegularFile(base)) listOf(base) else files(base)
        val out = ArrayList<String>()
        for (f in candidates) {
            if (only != null && !only.matches(f.fileName) && !only.matches(root.relativize(f))) continue
            if (runCatching { Files.size(f) }.getOrDefault(0L) > MAX_GREP_FILE) continue
            val text = text(f) ?: continue
            text.lineSequence().forEachIndexed { i, line ->
                if (out.size < limit && regex.containsMatchIn(line)) out += "${shown(f)}:${i + 1}:${line.take(MAX_LINE / 4)}"
            }
            if (out.size >= limit) break
        }
        return ToolResult(out.joinToString("\n").ifEmpty { "[ничего]" } + if (out.size >= limit) "\n[предел $limit]" else "")
    }

    /** Файлы под [base] без `.git` и без ссылок, ведущих за дерево. */
    private fun files(base: Path): List<Path> {
        val out = ArrayList<Path>()
        Files.walk(base).use { s ->
            s.filter { p -> Files.isRegularFile(p) && root.relativize(p).none { it.toString() == ".git" } }
                .filter { p -> runCatching { p.toRealPath().startsWith(realRoot) }.getOrDefault(false) }
                .limit(MAX_WALK.toLong())
                .forEach { out.add(it) }
        }
        return out
    }

    /** Запись в дерево — через `Vfs` хранилища (D-24): путь уже проверен [resolve]. */
    private fun store(p: Path, bytes: ByteArray) {
        val rel = root.relativize(p).joinToString("/")
        if (!Vfs.isSafeRelative(rel)) throw ToolError("путь недопустим для записи: ${shown(p)}")
        Vfs.parentOf(rel).takeIf { it.isNotEmpty() }?.let(vfs::mkdirs)
        vfs.write(rel, bytes)
    }

    /** Содержимое как UTF-8; двоичный или не UTF-8 — `null`. */
    private fun text(p: Path): String? {
        val bytes = Files.readAllBytes(p)
        if (bytes.take(8192).any { it == 0.toByte() }) return null
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    companion object {
        /** Версии протокола MCP, которые сервер понимает; последняя — предлагаемая. */
        val PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

        /** Предел вывода `run` в знаках — хвост; `read` — втрое больше. */
        const val MAX_OUTPUT = 30_000
        const val MAX_LINE = 2_000
        const val MAX_RESULTS = 500
        private const val MAX_WALK = 50_000
        private const val MAX_GREP_FILE = 5L * 1024 * 1024

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        private fun countOf(text: String, part: String): Int {
            var n = 0
            var i = text.indexOf(part)
            while (i >= 0) {
                n++
                i = text.indexOf(part, i + part.length)
            }
            return n
        }

        private val TOOLS = listOf(
            Tool(
                "run", { s -> "Выполнить команду в оболочке ${s.name} в рабочем дереве (сборка, тесты, git). Вход пуст; вывод — хвост до $MAX_OUTPUT знаков и код выхода." },
                listOf(Triple("command", "string", "строка команды"), Triple("timeout_seconds", "integer", "предел, 1…600 с; по умолчанию 120")),
                listOf("command"),
            ) { run(it) },
            Tool(
                "read", { "Прочитать текстовый файл с номерами строк." },
                listOf(Triple("path", "string", "путь в дереве"), Triple("offset", "integer", "первая строка, с 1"), Triple("limit", "integer", "сколько строк, по умолчанию 2000")),
                listOf("path"),
            ) { read(it) },
            Tool(
                "write", { "Записать файл целиком (UTF-8), создав каталоги." },
                listOf(Triple("path", "string", "путь в дереве"), Triple("content", "string", "новое содержимое")),
                listOf("path", "content"),
            ) { write(it) },
            Tool(
                "edit", { "Заменить точный фрагмент в файле: old_string должен встречаться один раз, иначе — replace_all." },
                listOf(
                    Triple("path", "string", "путь в дереве"), Triple("old_string", "string", "что заменить, точно"),
                    Triple("new_string", "string", "на что"), Triple("replace_all", "boolean", "заменить все вхождения"),
                ),
                listOf("path", "old_string", "new_string"),
            ) { edit(it) },
            Tool(
                "glob", { "Файлы по шаблону glob (например **/*.kt) относительно path; новые первыми." },
                listOf(Triple("pattern", "string", "шаблон glob"), Triple("path", "string", "каталог поиска; по умолчанию корень дерева")),
                listOf("pattern"),
            ) { glob(it) },
            Tool(
                "grep", { "Поиск регулярного выражения по файлам: строки «путь:номер:текст»." },
                listOf(
                    Triple("pattern", "string", "регулярное выражение"), Triple("path", "string", "файл или каталог; по умолчанию корень"),
                    Triple("glob", "string", "только файлы по шаблону"), Triple("ignore_case", "boolean", "без учёта регистра"),
                    Triple("max_results", "integer", "предел строк, по умолчанию 500"),
                ),
                listOf("pattern"),
            ) { grep(it) },
        )

        /** Оболочка по имени из `--shell`; `null` — по умолчанию ([defaultShell]); незнакомое имя — `null` результатом. */
        fun shellNamed(name: String?, windows: Boolean): Shell? = when (name) {
            null -> defaultShell(windows)
            "bash" -> if (windows) defaultShell(true).takeIf { it.name.startsWith("bash") } else Shell("bash", listOf("bash", "-c"))
            "sh" -> Shell("sh", listOf("sh", "-c"))
            "powershell" -> Shell("PowerShell", listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"))
            "cmd" -> Shell("cmd", listOf("cmd.exe", "/d", "/c"))
            else -> null
        }

        /**
         * Оболочка `run` по умолчанию: на Windows — Git Bash, если он есть
         * (его же требует Claude Code на Windows), иначе PowerShell; на POSIX
         * — `bash`, если есть, иначе `sh`.
         */
        fun defaultShell(windows: Boolean, exists: (String) -> Boolean = { Files.isExecutable(Path.of(it)) }): Shell =
            if (windows) {
                listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files (x86)/Git/bin/bash.exe").firstOrNull(exists)
                    ?.let { Shell("bash (Git Bash)", listOf(it, "-c")) }
                    ?: Shell("PowerShell", listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"))
            } else {
                listOf("/bin/bash", "/usr/bin/bash").firstOrNull(exists)?.let { Shell("bash", listOf(it, "-c")) } ?: Shell("sh", listOf("/bin/sh", "-c"))
            }
    }
}

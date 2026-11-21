package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.FileTrees
import com.dpsmpx.dpai.files.TextFile

/** Проектные инструменты API без сети и Android (D-214). Записи инъецируются общей защитой проекта. */
class ApiProjectFiles(source: FileTree,
    private val write: (String, String?, String) -> Outcome<Unit>, private val delete: (String, String) -> Outcome<Unit>) {
    private val tree = object : FileTree by source {
        override fun list(dir: String) = when (val r = source.list(dir)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.filter { !hidden(FileNames.join(dir, it.name)) })
        }
    }

    fun run(name: String, input: JsonObject): Outcome<String> {
        val path = (input["path"] as? JsonString)?.value ?: ""
        if (path.isNotEmpty() && (!FileNames.pathValid(path) || hidden(path))) return denied()
        fun text(key: String) = (input[key] as? JsonString)?.value
        fun digest() = text("sha256")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        fun finish(result: Outcome<Unit>): Outcome<String> = when (result) {
            is Outcome.Failure -> result
            is Outcome.Success -> Outcome.success("Файл сохранён: $path")
        }
        return when (name) {
            "list_files" -> when (val r = tree.list(path)) {
                is Outcome.Failure -> r
                is Outcome.Success -> {
                    val all = r.value.filter { !hidden(FileNames.join(path, it.name)) }.sortedBy { it.name }
                    encoded(JsonObject.of("entries" to JsonArray(all.take(40).map { e -> JsonObject.of("name" to JsonString(e.name), "directory" to JsonBool(e.dir)) }), "truncated" to JsonBool(all.size > 40)))
                }
            }
            "read_file" -> {
                if (path.isEmpty()) return invalid()
                val raw = when (val r = tree.read(path, TextFile.MAX_EDIT_BYTES)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
                val content = when (val r = TextFile.decode(raw)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
                val offset = (input["offset"] as? JsonNumber)?.toLongExact() ?: 0
                if (offset !in 0..content.length.toLong()) return invalid()
                var from = offset.toInt()
                if (from > 0 && from < content.length && content[from].isLowSurrogate() && content[from - 1].isHighSurrogate()) from--
                var end = minOf(from + 18_000, content.length)
                if (end < content.length && end > from && content[end - 1].isHighSurrogate()) end--
                var encoded: String
                do {
                    if (end < content.length && end > from && content[end - 1].isHighSurrogate()) end--
                    encoded = CanonicalJson.write(JsonObject.of("sha256" to JsonString(Hashing.sha256(raw).hex), "offset" to JsonNumber.of(from), "nextOffset" to JsonNumber.of(end),
                        "complete" to JsonBool(end == content.length), "content" to JsonString(content.substring(from, end))))
                    if (encoded.length > 30_000) end = from + (end - from) / 2
                } while (encoded.length > 30_000)
                Outcome.success(encoded)
            }
            "write_file" -> {
                if (path.isEmpty()) return invalid()
                val content = text("content") ?: return invalid()
                val expected = digest()
                if (input["sha256"] is JsonString && expected == null) return invalid()
                finish(write(path, expected, content))
            }
            "replace_text" -> {
                if (path.isEmpty()) return invalid()
                val expected = digest() ?: return invalid()
                val before = text("old_text")?.takeIf { it.isNotEmpty() } ?: return invalid()
                val after = text("new_text") ?: return invalid()
                val raw = when (val r = tree.read(path, TextFile.MAX_EDIT_BYTES)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
                if (Hashing.sha256(raw).hex != expected) return Outcome.failure(DiagCode.STATE_CONFLICT, "файл изменился; прочитайте его заново")
                val content = when (val r = TextFile.decode(raw)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
                val at = content.indexOf(before)
                if (at < 0 || content.indexOf(before, at + 1) >= 0) return Outcome.failure(DiagCode.STATE_CONFLICT, "исходный фрагмент должен встречаться ровно один раз")
                finish(write(path, expected, content.replaceRange(at, at + before.length, after)))
            }
            "delete_file" -> {
                if (path.isEmpty()) return invalid()
                val expected = digest() ?: return invalid()
                finish(delete(path, expected))
            }
            "find_text" -> {
                val query = text("query")?.takeIf { it.isNotEmpty() && it.length <= 500 } ?: return invalid()
                val files = when (val r = FileTrees.scan(tree, 5000)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value.files }
                val matches = arrayListOf<JsonValue>()
                var unread = 0
                var truncated = false
                for ((file, _) in files) {
                    if (hidden(file)) continue
                    val raw = tree.read(file, 256 * 1024).valueOrNull()
                    val source = raw?.let { TextFile.decode(it).valueOrNull() }
                    if (source == null) { unread++; continue }
                    for ((line, value) in source.split('\n').withIndex()) if (query in value) {
                        val match = JsonObject.of("path" to JsonString(file), "line" to JsonNumber.of(line + 1), "text" to JsonString(value.take(150)))
                        if (CanonicalJson.write(JsonArray(matches + match)).length > 28_000) { truncated = true; break }
                        matches += match
                        if (matches.size == 80) { truncated = true; break }
                    }
                    if (truncated) break
                }
                encoded(JsonObject.of("matches" to JsonArray(matches), "truncated" to JsonBool(truncated), "unreadFiles" to JsonNumber.of(unread)))
            }
            else -> Outcome.failure(DiagCode.UNSUPPORTED, "нет такого проектного инструмента")
        }
    }

    private fun encoded(value: JsonValue) = Outcome.success(CanonicalJson.write(value))
    private fun invalid() = Outcome.failure(DiagCode.VALUE_INVALID, "неверные параметры инструмента проекта")
    private fun denied() = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "инструменту доступен только код выбранного проекта без секретных и служебных файлов")

    companion object {
        private val DIRECTORIES = setOf(".git", ".ssh", ".gradle", ".dp-ide", "node_modules", ".venv", "venv", "build", ".idea")
        fun hidden(path: String): Boolean = path.split('/').any { segment ->
            val part = segment.lowercase()
            part in DIRECTORIES || part == ".env" || part.startsWith(".env.") && !part.endsWith(".example") ||
                part in setOf(".npmrc", ".netrc", ".git-credentials", "credentials.json", "id_rsa", "id_ed25519") ||
                part.substringAfterLast('.', "") in setOf("jks", "keystore", "p12", "pfx", "key")
        }

        /** Тройки совместимы с ToolSpec, но UI-модель не зависит от HTTP-провайдеров. */
        fun tools(): List<Triple<String, String, JsonObject>> {
            fun schema(vararg fields: String, required: List<String> = emptyList()): JsonObject = JsonObject.of("type" to JsonString("object"),
                "properties" to JsonObject(fields.associateWith { key -> JsonObject.of("type" to JsonString(if (key == "offset") "integer" else "string")) }),
                "required" to JsonArray(required.map(::JsonString)))
            return listOf(
                Triple("list_files", "Файлы выбранного проекта; path пустой для корня, ссылки и секреты скрыты.", schema("path")),
                Triple("read_file", "Прочитать текст и sha256. Если complete=false, продолжить с nextOffset; offset в UTF-16.", schema("path", "offset", required = listOf("path"))),
                Triple("find_text", "Найти буквальный текст в проекте; отчёт явно указывает неполное чтение.", schema("query", required = listOf("query"))),
                Triple("write_file", "Создать файл без sha256 или заменить целиком с sha256 последнего read_file. Есть копия и отмена.", schema("path", "sha256", "content", required = listOf("path", "content"))),
                Triple("replace_text", "Заменить единственный точный фрагмент с sha256 последнего чтения; остальные байты сохраняются.", schema("path", "sha256", "old_text", "new_text", required = listOf("path", "sha256", "old_text", "new_text"))),
                Triple("delete_file", "Удалить один прочитанный файл по sha256, сохранив зашифрованную копию для отмены.", schema("path", "sha256", required = listOf("path", "sha256"))),
            )
        }
    }
}

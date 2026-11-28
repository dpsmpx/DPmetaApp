package com.dpsmpx.dpai.contexts

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.json

/** Область чтения и автоматическая выборка — разные параметры, D-224. Это не грант операций. */
enum class ContextMode { MINIMAL, SCOPED, PROJECT_WIDE, FULL_ACCESS, CUSTOM }

data class ContextProfile(
    val mode: ContextMode = ContextMode.PROJECT_WIDE,
    val roots: List<String> = listOf("."),
    val pinned: List<String> = emptyList(),
    val constraints: List<String> = listOf("AGENTS.md", "CLAUDE.md"),
    val maxChars: Int = 64_000,
    val maxTokens: Long = 16_000,
    val allowExpansion: Boolean = true,
    val historyMessages: Int = 20,
) {
    init {
        require(roots.size <= 64 && roots.distinct().size == roots.size && roots.all { it == "." || path(it) })
        require(pinned.size <= 100 && pinned.distinct().size == pinned.size && pinned.all(::path))
        require(constraints.size <= 16 && constraints.distinct().size == constraints.size && constraints.all(::path))
        require(maxChars in 1000..1_000_000 && maxTokens in 500..250_000 && historyMessages in 0..100)
        require(mode !in setOf(ContextMode.MINIMAL, ContextMode.SCOPED) || roots.isNotEmpty() && "." !in roots)
        require(mode !in setOf(ContextMode.PROJECT_WIDE, ContextMode.FULL_ACCESS) || roots == listOf("."))
        require(rulePaths().size <= 100) { "слишком много родительских правил; сократите область" }
        require(pinned.all { allowed(it) }) { "закреплённый файл вне области" }
    }

    val revision: String get() = Hashing.sha256(json()).hex
    val restricted: Boolean get() = roots != listOf(".") || mode == ContextMode.MINIMAL

    fun allowed(value: String): Boolean = path(value) && roots.any { root -> root == "." || value == root ||
        mode != ContextMode.MINIMAL && value.startsWith("$root/") }

    /** Правила родителей входят явно; прочие соседние файлы MINIMAL не получает. */
    fun rulePaths(selected: Collection<String> = roots.filter { it != "." }): List<String> {
        val directories = linkedSetOf("")
        for (file in selected.filter(::path)) {
            val parts = file.split('/')
            for (n in 1..(if (file in roots && mode != ContextMode.MINIMAL) parts.size else parts.size - 1)) directories += parts.take(n).joinToString("/")
        }
        return (constraints + directories.flatMap { d -> listOf("AGENTS.md", "CLAUDE.md").map { if (d.isEmpty()) it else "$d/$it" } }).distinct()
    }

    fun canRead(path: String): Boolean = allowed(path) || path in rulePaths()
    fun canList(directory: String): Boolean = directory.isEmpty() || allowed(directory) ||
        (roots + rulePaths()).any { it.startsWith("$directory/") }

    fun policy(base: ContextPolicy? = null): ContextPolicy = ContextPolicy(
        allowSecret = base?.allowSecret ?: false,
        maxChars = minOf(maxChars, base?.maxChars ?: maxChars),
        maxTokens = minOf(maxTokens, base?.maxTokens ?: maxTokens),
        priority = ContextPolicy.PRIORITY, critical = ContextPolicy.CRITICAL, dedupe = true,
    )

    fun json(): JsonObject = JsonObject.of("format" to json("dpai.context-profile"), "version" to json(1),
        "mode" to json(mode.name), "roots" to strings(roots), "pinned" to strings(pinned), "constraints" to strings(constraints),
        "maxChars" to json(maxChars), "maxTokens" to json(maxTokens), "allowExpansion" to json(allowExpansion),
        "historyMessages" to json(historyMessages))

    companion object {
        /** Канонический проектный путь; адаптер дополнительно применяет FileNames и фильтр секретов. */
        fun path(value: String): Boolean = value.length in 1..1000 && value.split('/').all { segment ->
            segment.isNotEmpty() && segment !in setOf(".", "..") && segment.none { it.isISOControl() || it in "\\:" } &&
                segment.toByteArray(Charsets.UTF_8).size <= 255
        }

        fun read(o: JsonObject): Outcome<ContextProfile> = try {
            require(o.fields.keys == setOf("format", "version", "mode", "roots", "pinned", "constraints", "maxChars", "maxTokens", "allowExpansion", "historyMessages"))
            require(o["format"] == json("dpai.context-profile") && o["version"] == json(1))
            fun text(key: String) = (o[key] as JsonString).value
            fun paths(key: String) = (o[key] as JsonArray).items.also { require(it.size <= 100) }.map { (it as JsonString).value }
            val chars = (o["maxChars"] as JsonNumber).toLongExact()!!.also { require(it in 1000..1_000_000) }.toInt()
            val history = (o["historyMessages"] as JsonNumber).toLongExact()!!.also { require(it in 0..100) }.toInt()
            Outcome.success(ContextProfile(ContextMode.valueOf(text("mode")), paths("roots"), paths("pinned"), paths("constraints"), chars,
                (o["maxTokens"] as JsonNumber).toLongExact()!!, (o["allowExpansion"] as JsonBool).value, history))
        } catch (_: Exception) { Outcome.failure(DiagCode.VALUE_INVALID, "неверный или неподдержанный профиль контекста") }

        private fun strings(values: List<String>) = JsonArray(values.map(::JsonString))
    }
}

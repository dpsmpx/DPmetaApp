package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.model.Place
import com.dpsmpx.dpmeta.model.Registry

/**
 * Манифест экосистемы `dpai.ecosystem` v1 из реестра и клонов пульта
 * (`DP_AI_Orcestrator:D-114`, `DPmetaApp:D-53`): по умолчанию «AI» берёт его
 * отсюда, а не файлом с ПК. Правила — те же, что у `DPmeta:tools/manifest.py`
 * (M-11, C-13), чтобы оба источника давали одно и то же:
 *
 * - проекты — реестр без архива; ключ — имя строчными;
 * - состояние — место и, если клон есть, основная ветка, последний коммит
 *   (у подсистемы — её каталога) и дата;
 * - документы — точки входа и журнал решений, которые есть в основной
 *   ветке клона; клона нет — пусто;
 * - предложения — открытые пункты `DPmeta:docs/SYNC_QUEUE.md` проекта, не
 *   ждущие автора, с полем «Предложение»; ключ `sq-NN`, приоритет `LOW`,
 *   вид работы `docs`.
 *
 * Пути клонов на устройстве (`repository`) не пишутся. Сбор фактов клонов —
 * у вызывающего ([Clone]); здесь — только чистое построение.
 */
object DpaiManifest {
    const val FORMAT = "dpai.ecosystem"
    const val VERSION = 1

    /** Точки входа проекта (`DPmeta:tools/manifest.py`, `ENTRY_DOCS`). */
    val ENTRY_DOCS = listOf("CLAUDE.md", "README.md", "TODO.md")

    /** Путь очереди согласований в клоне DPmetaApp (подсистема `dpmeta/`). */
    const val QUEUE = "dpmeta/docs/SYNC_QUEUE.md"

    private const val PRIORITY = "LOW"
    private const val OPERATION = "docs"

    /**
     * Что известно о клоне проекта: основная ветка, последний коммит
     * (короткий хеш и дата `ГГГГ-ММ-ДД`) и какие из [wanted] документов
     * есть в основной ветке. [branch] `null` — клон без основной ветки.
     */
    data class Clone(val branch: String?, val head: String? = null, val updated: String? = null, val documents: List<String> = emptyList())

    /** Документы, которые манифест ищет в клоне проекта: точки входа и журнал решений из реестра. */
    fun wanted(registry: Registry, name: String): List<String> = ENTRY_DOCS + listOfNotNull(registry.byName(name)?.decisions?.path)

    /**
     * Манифест одной строкой JSON. [clones] — по имени проекта; нет записи —
     * клона нет. Повтор ключа проекта — `VALUE_INVALID`: адаптер «AI» читает
     * строго и такой манифест отверг бы.
     */
    fun build(registry: Registry, clones: Map<String, Clone>, queueText: String): Outcome<String> {
        val queue = readQueue(queueText)
        val projects = registry.projects.filter { it.place != Place.ARCHIVE }.map { p ->
            val state = linkedMapOf<String, JsonValue>("place" to JsonString(p.place.code))
            val clone = clones[p.name]
            val documents = when {
                clone == null -> {
                    state["clone"] = JsonString("нет")
                    emptyList()
                }
                clone.branch == null -> {
                    state["clone"] = JsonString("без основной ветки")
                    emptyList()
                }
                else -> {
                    state["branch"] = JsonString(clone.branch)
                    if (clone.head != null && clone.updated != null) {
                        state["head"] = JsonString(clone.head)
                        state["updated"] = JsonString(clone.updated)
                    }
                    wanted(registry, p.name).filter { it in clone.documents }
                }
            }
            JsonObject.of(
                "key" to JsonString(key(p.name)),
                "name" to JsonString(p.name),
                "state" to JsonObject(state),
                "documents" to JsonArray(documents.map(::JsonString)),
                "suggestions" to JsonArray(suggestions(queue, p.name)),
            )
        }
        val keys = projects.map { (it.fields["key"] as JsonString).value }
        val dup = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (dup.isNotEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "ключи проектов повторяются: ${dup.joinToString(", ")}")
        val doc = JsonObject.of("format" to JsonString(FORMAT), "version" to JsonNumber.of(VERSION), "projects" to JsonArray(projects))
        return Outcome.success(com.dpsmpx.dpai.core.serialization.CanonicalJson.write(doc))
    }

    /** Ключ проекта: имя строчными, прочее — дефисом (`key_of` DPmeta). */
    fun key(name: String): String = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')

    /** Пункт очереди: репозитории, заголовок, состояние сводки и поля пункта. */
    internal data class QueueItem(val repos: List<String>, val title: String, val state: String, val fields: MutableMap<String, String> = linkedMapOf()) {
        val proposal: String get() = fields["Предложение"].orEmpty()
    }

    private val ROW = Regex("^\\| (SQ-\\d+) \\| ([^|]+) \\| ([^|]+) \\| ([^|]+) \\| ([^|]+) \\|$")
    private val HEAD = Regex("^### (SQ-\\d+) — (.+)$")
    private val FIELD = Regex("^- \\*\\*([^*]+?):?\\*\\*:? ?(.*)$")
    private val BLOCKED = Regex("RISK-\\d+|за автором|(?:правит|решает)(?: только)? автор|только автор", RegexOption.IGNORE_CASE)

    /** Текст Markdown одной строкой: без обратных кавычек и звёздочек. */
    private fun plain(text: String): String = text.replace("`", "").replace("**", "").replace(Regex("\\s+"), " ").trim()

    /** Пункты очереди по номеру — как `read_queue` DPmeta. */
    internal fun readQueue(text: String): Map<String, QueueItem> {
        val items = linkedMapOf<String, QueueItem>()
        val lines = text.lines()
        for (line in lines) {
            val m = ROW.matchEntire(line) ?: continue
            val (num, repos, title, _, state) = m.destructured
            items[num.trim()] = QueueItem(repos.split(",").map { it.trim().trim('`') }, plain(title.trim()), state.trim())
        }
        var current: String? = null
        var field: String? = null
        var buf = mutableListOf<String>()
        fun flush() {
            val item = current?.let { items[it] } ?: return
            val name = field ?: return
            if (buf.isEmpty()) return
            // Строки поля — одной строкой; пункты вложенного списка («- …») — через «;».
            val sb = StringBuilder()
            for (x in buf) {
                if (x.startsWith("- ")) sb.append(if (sb.isNotBlank()) "; " else "").append(x.substring(2)) else sb.append(' ').append(x)
            }
            item.fields.putIfAbsent(name, plain(sb.toString()))
        }
        for (line in lines) {
            val h = HEAD.matchEntire(line)
            if (h != null || line.startsWith("## ")) {
                flush()
                current = h?.groupValues?.get(1)
                field = null
                buf = mutableListOf()
                continue
            }
            val f = FIELD.matchEntire(line)
            when {
                f != null -> {
                    flush()
                    field = f.groupValues[1]
                    buf = mutableListOf(f.groupValues[2])
                }
                field != null && line.startsWith("  ") -> buf.add(line.trim())
                field != null -> {
                    // Пустая строка или другой пункт списка — поле кончилось.
                    flush()
                    field = null
                    buf = mutableListOf()
                }
            }
        }
        flush()
        return items
    }

    /** Открыт ли пункт для сессии проекта: не закрыт, не ждёт автора и не «открытый вопрос». */
    private fun open(item: QueueItem): Boolean {
        // Поле пункта — главное, сводка — запасной источник (`item_status` DPmeta).
        val state = item.fields["Состояние"]?.takeIf { it.isNotEmpty() } ?: item.state
        if (!state.trim().let { it.startsWith("открыт") || it.startsWith("частично") }) return false
        if (BLOCKED.containsMatchIn(state)) return false
        return !item.fields["Вид"].orEmpty().trim().lowercase().startsWith("открытый вопрос")
    }

    private fun suggestions(queue: Map<String, QueueItem>, name: String): List<JsonValue> =
        queue.entries.sortedBy { it.key.substringAfter('-').toIntOrNull() ?: Int.MAX_VALUE }.filter { (_, item) ->
            name in item.repos && item.proposal.isNotEmpty() && open(item)
        }.map { (num, item) ->
            val where = item.fields["Где"] ?: item.fields["Где и что"]
            val parts = mutableListOf<String>()
            where?.let { parts += "Где: $it" }
            parts += "Предложение: ${item.proposal}"
            val others = item.repos.filter { it != name }
            if (others.isNotEmpty()) parts += "Пункт касается и ${others.joinToString(", ")}: здесь — только часть для $name."
            JsonObject.of(
                "key" to JsonString(num.lowercase()),
                "title" to JsonString("$num: ${item.title}"),
                "objective" to JsonString(parts.joinToString(" ") + " (DPmeta:$num)"),
                "priority" to JsonString(PRIORITY),
                "operation" to JsonString(OPERATION),
            )
        }
}

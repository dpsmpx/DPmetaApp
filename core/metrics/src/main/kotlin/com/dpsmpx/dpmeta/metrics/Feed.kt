package com.dpsmpx.dpmeta.metrics

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpmeta.model.Event

/** Событие ленты (ТЗ, F-05): когда, какой проект, вид и текст для показа. */
data class FeedEvent(val at: Timestamp, val project: String, val kind: String, val text: String) {
    fun toJson(): JsonObject = JsonObject.of(
        "at" to JsonNumber.of(at.epochMillis),
        "project" to JsonString(project),
        "kind" to JsonString(kind),
        "text" to JsonString(text),
    )
}

/**
 * Лента событий (ТЗ, F-05; D-34): события выводятся из разницы прежнего и
 * нового состояния, а не опросом отдельного источника. Первый срез — без
 * событий (иначе вся история выглядела бы новой).
 */
object Feed {
    const val FORMAT = "dpmeta.feed"
    const val VERSION = 1

    /** Виды событий: код → подпись фильтра. */
    val KINDS: Map<String, String> = linkedMapOf(
        "ai.notice" to "ИИ",
        "main" to "коммит в main",
        "pr.opened" to "PR открыт",
        "pr.merged" to "PR влит",
        "pr.closed" to "PR закрыт",
        "decision" to "новое решение",
        "roadmap" to "шаг ROADMAP",
    )

    /** Типы каталога событий (§8.1; D-85) для видов ленты: вид ленты — хранимый код, тип — для правил тревог. */
    val TYPES: Map<String, String> = linkedMapOf(
        "main" to "main.commit",
        "pr.opened" to "pr.opened",
        "pr.merged" to "pr.merged",
        "pr.closed" to "pr.closed",
        "decision" to "decision.new",
        "roadmap" to "roadmap.step.done",
    )

    /** Событие ленты — событием каталога для движка тревог; вид без типа — `null`. Предмет — текст события. */
    fun event(e: FeedEvent): Event? = TYPES[e.kind]?.let { Event(it, e.at, e.project, e.text, key = e.text) }

    private fun payload(p: ProjectState?, kind: String): JsonObject? = p?.sections?.get(kind)?.payload as? JsonObject

    private fun objects(o: JsonObject?, key: String): List<JsonObject> = (o?.get(key) as? JsonArray)?.items.orEmpty().mapNotNull { it as? JsonObject }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonString)?.value

    private fun long(o: JsonObject?, k: String) = (o?.get(k) as? JsonNumber)?.toLongExact()

    fun diff(previous: EcosystemState?, next: EcosystemState): List<FeedEvent> {
        if (previous == null) return emptyList()
        val out = mutableListOf<FeedEvent>()
        for (p in next.projects) {
            val before = previous.project(p.name) ?: continue
            fun at(kind: String) = p.sections[kind]?.asOf ?: next.asOf
            // Коммит в main: сменился SHA основной ветки.
            val mainBefore = objects(payload(before, "branches"), "branches").firstOrNull { str(it, "name") == Rules.MAIN_BRANCH }
            val mainNow = objects(payload(p, "branches"), "branches").firstOrNull { str(it, "name") == Rules.MAIN_BRANCH }
            if (mainBefore != null && mainNow != null && str(mainBefore, "sha") != str(mainNow, "sha")) {
                out += FeedEvent(long(mainNow, "date")?.let(::Timestamp) ?: at("branches"), p.name, "main", "коммит в main ${str(mainNow, "sha")?.take(7).orEmpty()}")
            }
            // PR: появился — открыт; ушёл из открытых — влит (есть в последних влитых) или закрыт.
            if (p.sections["pulls"] != null && before.sections["pulls"] != null) {
                val was = objects(payload(before, "pulls"), "pulls").associateBy { long(it, "number") }
                val now = objects(payload(p, "pulls"), "pulls").associateBy { long(it, "number") }
                val merged = objects(payload(p, "merged"), "pulls").associateBy { long(it, "number") }
                for ((n, o) in now) if (n !in was) out += FeedEvent(long(o, "updatedAt")?.let(::Timestamp) ?: at("pulls"), p.name, "pr.opened", "PR #$n открыт: ${str(o, "title").orEmpty()}")
                for ((n, o) in was) {
                    if (n in now) continue
                    val m = merged[n]
                    out += if (m != null) {
                        FeedEvent(long(m, "mergedAt")?.let(::Timestamp) ?: at("merged"), p.name, "pr.merged", "PR #$n влит: ${str(o, "title").orEmpty()}")
                    } else {
                        FeedEvent(at("pulls"), p.name, "pr.closed", "PR #$n закрыт: ${str(o, "title").orEmpty()}")
                    }
                }
            }
            // Новые решения журнала.
            if (before.sections["decisions"] != null) {
                val known = objects(payload(before, "decisions"), "entries").mapNotNull { str(it, "id") }.toSet()
                for (e in objects(payload(p, "decisions"), "entries")) {
                    val id = str(e, "id") ?: continue
                    if (id !in known) out += FeedEvent(at("decisions"), p.name, "decision", "новое решение $id — ${str(e, "title").orEmpty()}")
                }
            }
            // Отмечен шаг ROADMAP: выросло число отмеченных шагов (или этапов, если шагов нет).
            val rb = payload(before, "roadmap")
            val rn = payload(p, "roadmap")
            if (rb != null && rn != null) {
                val key = if ((long(rn, "stepsTotal") ?: 0) > 0) "stepsDone" else "stagesDone"
                val a = long(rb, key) ?: 0
                val b = long(rn, key) ?: 0
                if (b > a) out += FeedEvent(at("roadmap"), p.name, "roadmap", "ROADMAP: отмечено ${if (key == "stepsDone") "шагов" else "этапов"} $a → $b")
            }
        }
        return out.sortedWith(compareByDescending<FeedEvent> { it.at }.thenBy { it.project }.thenBy { it.kind }.thenBy { it.text })
    }

    /** Новые события поверх прежних, последние [Rules.FEED_KEEP], свежие — первыми. */
    fun merge(old: List<FeedEvent>, fresh: List<FeedEvent>): List<FeedEvent> =
        (fresh + old).distinct().sortedWith(compareByDescending<FeedEvent> { it.at }.thenBy { it.project }.thenBy { it.kind }.thenBy { it.text }).take(Rules.FEED_KEEP)

    fun toJson(events: List<FeedEvent>): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, VERSION), "events" to JsonArray(events.map { it.toJson() })),
    )

    fun fromJson(value: JsonValue): Outcome<List<FeedEvent>> {
        val o = value as? JsonObject ?: return Outcome.success(emptyList())
        if ((o["format"] as? JsonString)?.value != FORMAT) return Outcome.success(emptyList())
        return Outcome.success(
            objects(o, "events").mapNotNull { e ->
                FeedEvent(Timestamp(long(e, "at") ?: return@mapNotNull null), str(e, "project") ?: return@mapNotNull null, str(e, "kind") ?: return@mapNotNull null, str(e, "text").orEmpty())
            },
        )
    }
}

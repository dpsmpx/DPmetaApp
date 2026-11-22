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
        "decision" to "новое решение (архив)",
        "roadmap" to "шаг ROADMAP (архив)",
        "routine" to "запуск маршрута",
    )

    /** Типы каталога событий (§8.1; D-85) для видов ленты: вид ленты — хранимый код, тип — для правил тревог. */
    val TYPES: Map<String, String> = linkedMapOf(
        "main" to "main.commit",
        "pr.opened" to "pr.opened",
        "pr.merged" to "pr.merged",
        "pr.closed" to "pr.closed",
        "routine" to "claude.routine.fired",
    )

    /** Событие ленты — событием каталога для движка тревог; вид без типа — `null`. Предмет — текст события. */
    fun event(e: FeedEvent): Event? = TYPES[e.kind]?.let { Event(it, e.at, e.project, e.text, key = e.text) }

    private fun payload(p: ProjectState?, kind: String): JsonObject? = p?.sections?.get(kind)?.payload as? JsonObject

    private fun objects(o: JsonObject?, key: String): List<JsonObject> = (o?.get(key) as? JsonArray)?.items.orEmpty().mapNotNull { it as? JsonObject }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonString)?.value

    private fun long(o: JsonObject?, k: String) = (o?.get(k) as? JsonNumber)?.toLongExact()

    /**
     * [routes] — сессии запусков маршрутов из пульта (id `session_…` → имя маршрута, F-58;
     * D-161): новый PR, в описании которого ссылка на такую сессию, — «PR #N от маршрута».
     */
    fun diff(previous: EcosystemState?, next: EcosystemState, routes: Map<String, String> = emptyMap()): List<FeedEvent> {
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
                for ((n, o) in now) {
                    if (n in was) continue
                    val route = str(o, "session")?.let(routes::get)
                    val head = if (route != null) "PR #$n от маршрута «$route»" else "PR #$n открыт"
                    out += FeedEvent(long(o, "updatedAt")?.let(::Timestamp) ?: at("pulls"), p.name, "pr.opened", "$head: ${str(o, "title").orEmpty()}")
                }
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

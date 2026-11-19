package com.dpsmpx.dpmeta.app.screens

/**
 * Текст экранов — снимок на JVM (этап 3; ТЗ, §9): то же содержимое, что
 * рисует Compose, строками. Снимки лежат в тестах; изменился экран —
 * изменился снимок, это видно в диффе PR.
 */
object ScreenText {
    /** Сводка (UX-6): плитки и куда ведёт каждая; строка «AI» — если её дала оболочка (D-76). */
    fun summary(s: Screens.Summary, ai: Screens.AiCounts? = null): String = buildString {
        appendLine("СВОДКА — срез ${s.asOf}")
        for (t in Screens.tiles(s)) appendLine("${t.title}: ${t.value} → ${t.goes.title}")
        ai?.let { appendLine(Screens.aiLine(it) + if (Screens.aiToRequests(it)) " → Запросы" else " → AI") }
    }

    /** График (F-34) строками: описание для TalkBack и таблица значений (N-07) — то, что рядом с рисунком. */
    fun stats(c: Stats.Chart): String = buildString {
        appendLine("ГРАФИК «${c.metric.title}» — ${c.period.title}")
        appendLine(c.description)
        appendLine(c.table.header.joinToString(" | "))
        for (r in c.table.rows) appendLine(r.joinToString(" | "))
    }

    fun tiles(tiles: List<Stats.Tile>): String = buildString {
        appendLine("ПЛИТКИ")
        for (t in tiles) appendLine("  ${t.metric.title}: ${t.value}${if (t.change.isEmpty()) "" else " (${t.change})"}; дней в спарклайне ${t.spark.size}")
    }

    fun projects(groups: List<Screens.Group>): String = buildString {
        appendLine("ПРОЕКТЫ")
        for (g in groups) {
            appendLine("— ${g.title}")
            // Строка — как на экране (UX-18): имя и состояние, ниже — числа; ошибки — «⚠ N».
            for (r in g.rows) {
                appendLine("  ${r.name} — ${r.working}" + if (r.errors > 0) " · ⚠ ${r.errors}" else "")
                appendLine("    ${Screens.projectLine(r)}")
            }
        }
    }

    /** Карточка (UX-8): то, что рисует экран, — «Внимание», документы, разделы с итогом и их содержимое. */
    fun card(c: Screens.Card): String = buildString {
        appendLine("${c.name} — ${c.working}")
        val v = Screens.cardView(c)
        if (v.attention.isNotEmpty()) {
            appendLine("ВНИМАНИЕ:")
            for (a in v.attention) appendLine("  $a")
            // Сами ошибки — в блоке «Внимание», а не в конце карточки (UX-8, D-65).
            for (e in c.errors) appendLine("    $e")
        }
        if (c.files.isNotEmpty()) appendLine("Файлы: " + c.files.joinToString(", ") { (t, p) -> "$t → $p" })
        for (s in v.sections) {
            appendLine("${s.title} ▸")
            for (l in s.lines) appendLine("  $l")
        }
    }

    fun feed(rows: List<Screens.FeedRow>): String = buildString {
        appendLine("ЛЕНТА: ${rows.size}")
        for (r in rows) appendLine("  ${r.time} ${r.project} [${r.kind}] ${r.text}")
    }

}

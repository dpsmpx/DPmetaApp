package com.dpsmpx.dpmeta.app.screens

/**
 * Текст экранов — снимок на JVM (этап 3; ТЗ, §9): то же содержимое, что
 * рисует Compose, строками. Снимки лежат в тестах; изменился экран —
 * изменился снимок, это видно в диффе PR.
 */
object ScreenText {
    fun summary(s: Screens.Summary): String = buildString {
        appendLine("СВОДКА — срез ${s.asOf}")
        appendLine("Работают: ${s.active}")
        appendLine("Вопросов автору: ${s.questions}")
        appendLine("Открытых PR: ${s.pullsWaiting}")
        appendLine("Проектов: ${s.projects}")
        appendLine("Ошибок источников: ${s.sourceErrors}")
        appendLine("Шлюз готов: ${s.gatesReady}")
        appendLine("Синхронизация: ${s.syncHealth} источников")
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
            for (r in g.rows) {
                append("  ${r.name}: ${r.working}; решений ${r.decisions}, TODO ${r.todo}, ROADMAP ${r.roadmap}, PR ${r.pulls}")
                appendLine(if (r.errors > 0) "; ошибок ${r.errors}" else "")
            }
        }
    }

    fun card(c: Screens.Card): String = buildString {
        appendLine("${c.name} — ${c.place}, ${c.working}")
        if (c.files.isNotEmpty()) appendLine("Файлы: " + c.files.joinToString(", ") { (t, p) -> "$t → $p" })
        appendLine("Решений: ${c.decisions}")
        for (d in c.lastDecisions) appendLine("  $d")
        appendLine("TODO: ${c.todo}, за автором ${c.forAuthor}")
        appendLine("Готовность: ${c.ready.stage}; ${c.ready.verdict}")
        for (l in c.ready.criteria) appendLine("  $l")
        appendLine("Ход PR: ${c.ready.pulls}")
        appendLine("ROADMAP: ${c.roadmap}")
        for (s in c.stages) appendLine("  [${s.flag}] ${s.title}${if (s.steps.isEmpty()) "" else " — ${s.steps}"}")
        appendLine("PR: ${c.pulls.size}")
        for (p in c.pulls) appendLine("  #${p.number} ${p.title}${if (p.draft) " (черновик)" else ""} — ${p.head}, ${p.updated}")
        appendLine("Ветки: ${c.branches.size}")
        for (b in c.branches) appendLine("  ${if (b.recent) "●" else "○"} ${b.name} — ${b.updated}")
        appendLine("Проверки последнего PR: ${checked(c.lastPull)}")
        appendLine("Проверки последнего влитого: ${checked(c.lastMerged)}")
        for (p in c.panel) {
            appendLine("О себе (${p.name}):")
            for (l in p.text.lines()) appendLine("  $l")
        }
        if (c.errors.isNotEmpty()) {
            appendLine("Ошибки источников:")
            for (e in c.errors) appendLine("  $e")
        }
    }

    private fun checked(c: Screens.Checked?): String =
        c?.let { "#${it.number} — ${it.verdict}, ${it.age}" + (it.line?.let { l -> "; «$l»" } ?: "") } ?: Screens.UNKNOWN

    fun feed(rows: List<Screens.FeedRow>): String = buildString {
        appendLine("ЛЕНТА: ${rows.size}")
        for (r in rows) appendLine("  ${r.time} ${r.project} [${r.kind}] ${r.text}")
    }

    fun questions(q: List<Screens.Question>): String = buildString {
        appendLine("ВОПРОСЫ АВТОРУ: ${q.size}")
        for (i in q) appendLine("  ${i.project}: ${i.label?.let { "[$it] " } ?: ""}${i.title}")
    }
}

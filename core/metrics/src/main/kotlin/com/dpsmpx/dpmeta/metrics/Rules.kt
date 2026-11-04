package com.dpsmpx.dpmeta.metrics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Все пороги, окна и веса DPmetaApp — здесь и только здесь (D-34, ТЗ, §6.3).
 * Смысл правила изменён — новая [RULES_VERSION] и запись в
 * `docs/CHANGELOG.md`; прежние точки истории остаются со своей версией.
 */
object Rules {
    const val RULES_VERSION: Int = 2

    /** «Кто работает» (F-16, `DPmeta:M-08`): коммит в `main` за это окно. */
    val MAIN_COMMIT_WINDOW: Duration = 6.hours

    /** «Кто работает»: ветка или открытый PR, обновлённые за это окно. */
    val BRANCH_OR_PR_WINDOW: Duration = 24.hours

    /**
     * Файла процесса нет (404) — снова спрашивается не раньше этого срока:
     * 404 у GitHub без ETag, иначе каждая синхронизация тратит на него запрос
     * (A-02: повторная — не меньше 90 % ответов 304).
     */
    val MISSING_RECHECK: Duration = 24.hours

    /** Сколько событий ленты хранить (F-05): старше — уходят. */
    const val FEED_KEEP: Int = 300

    /** Основная ветка проекта — по ней «коммит в main». */
    const val MAIN_BRANCH: String = "main"

    /**
     * PR ждёт автора (ТЗ, §8, `repo.pr.waiting_author`): открыт и не
     * черновик — дальше решает автор. Шлюз готовности (D-64): никто не ждёт
     * дольше этого срока.
     */
    val PR_WAITING_LIMIT: Duration = 72.hours

    /** Время до вливания (ТЗ, §8, `repo.pr.lead_time`): перцентиль «долгого» PR рядом с медианой. */
    const val LEAD_TIME_PERCENTILE: Int = 90
}

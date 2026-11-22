package com.dpsmpx.dpmeta.metrics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Все пороги, окна и веса DPmetaApp — здесь и только здесь (D-34, ТЗ, §6.3).
 * Смысл правила изменён — новая [RULES_VERSION] и запись в
 * `docs/CHANGELOG.md`; прежние точки истории остаются со своей версией.
 */
object Rules {
    const val RULES_VERSION: Int = 10

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

    /** D-176: имена веток пользователя не определяют их назначение. */
    val EXCLUDED_BRANCHES: Set<String> = emptySet()

    fun isExcludedBranch(name: String): Boolean = name in EXCLUDED_BRANCHES

    /** Синхронизация при входе в приложение (D-65): не чаще этого срока — вход за входом не повторяет её. */
    val ENTRY_SYNC_EVERY: Duration = 15.minutes

    /**
     * PR ждёт автора (ТЗ, §8, `repo.pr.waiting_author`): открыт и не
     * черновик — дальше решает пользователь. Дольше этого срока — фильтр «застрявшие».
     */
    val PR_WAITING_LIMIT: Duration = 72.hours

    /**
     * Время до вливания (ТЗ, §8, `repo.pr.lead_time`): перцентиль «долгого» PR
     * рядом с медианой — по последним влитым (до пяти, `Sync.MERGED_KEEP`): при
     * пяти PR P90 — самый долгий. Точка истории — `repo.pr.lead_time.p90`.
     */
    const val LEAD_TIME_PERCENTILE: Int = 90

    /** Пояс дней истории (D-66); D-176 меняет список авторов, пояс этого шага сохранён. */
    val HISTORY_ZONE: java.time.ZoneOffset = java.time.ZoneOffset.ofHours(3)

    /** Известные метки ИИ в имени автора (D-66): остальные авторы — прочие, не обязательно люди. */
    val AI_AUTHORS: Set<String> = setOf("Claude")

    /** Доля ИИ (`repo.commits.ai_share`) — по коммитам за это число последних дней. */
    const val AI_SHARE_DAYS: Int = 30

}

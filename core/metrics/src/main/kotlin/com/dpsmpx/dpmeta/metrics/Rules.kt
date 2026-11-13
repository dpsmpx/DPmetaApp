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
    const val RULES_VERSION: Int = 5

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
     * Ветки сборок экосистемы (D-65, D-33): сироты с APK и архивами — в
     * клоны не берутся и в ветки проекта не входят (не работа, а выкладка).
     * Имена: Galdhold — `windows-debug-build`, DPlang — `apk-builds`, AIM
     * Trainer — `win_debug_builds`, прежняя DPmetaApp — `linux-debug`; шаблоны
     * ([isBuildBranch], как у хука начала сессии): `*_debug` (`apk_debug`,
     * `linux_debug`, `windows_debug` DPmetaApp и DPpass — D-33) и `apk-*`
     * (`apk-build` Galdhold и прежние сборки по версиям).
     */
    val BUILD_BRANCHES: Set<String> = setOf("windows-debug-build", "apk-builds", "win_debug_builds", "linux-debug")

    fun isBuildBranch(name: String): Boolean = name in BUILD_BRANCHES || name.endsWith("_debug") || name.startsWith("apk-")

    /** Синхронизация при входе в приложение (D-65): не чаще этого срока — вход за входом не повторяет её. */
    val ENTRY_SYNC_EVERY: Duration = 15.minutes

    /**
     * PR ждёт автора (ТЗ, §8, `repo.pr.waiting_author`): открыт и не
     * черновик — дальше решает автор. Шлюз готовности (D-64): никто не ждёт
     * дольше этого срока.
     */
    val PR_WAITING_LIMIT: Duration = 72.hours

    /**
     * Время до вливания (ТЗ, §8, `repo.pr.lead_time`): перцентиль «долгого» PR
     * рядом с медианой — по последним влитым (до пяти, `Sync.MERGED_KEEP`): при
     * пяти PR P90 — самый долгий. Точка истории — `repo.pr.lead_time.p90`.
     */
    const val LEAD_TIME_PERCENTILE: Int = 90

    /**
     * История из клонов (D-66) — те же определения, что у наборов прогресса
     * сайта (`SocialMediaImage:data/progress/scope.yaml`): день — по этому
     * поясу; свои авторы — ИИ и человек, чужие (исходные проекты форков) —
     * не в счёте. «Ваше Имя» — заглушка `git config` в первом коммите
     * Galdhold (`DPprofile` F-0049), не имя человека.
     */
    val HISTORY_ZONE: java.time.ZoneOffset = java.time.ZoneOffset.ofHours(3)

    /** Автор коммитов ИИ (ТЗ, §8, `repo.commits.ai_share`; D-66 — по автору, как у наборов прогресса). */
    val AI_AUTHORS: Set<String> = setOf("Claude")

    /** Авторы коммитов человека. */
    val HUMAN_AUTHORS: Set<String> = setOf("dpsmpx", "Ваше Имя")

    /** Доля ИИ (`repo.commits.ai_share`) — по коммитам за это число последних дней. */
    const val AI_SHARE_DAYS: Int = 30

    /** Новые решения (`repo.decisions.new`) — прирост записей журнала за это число последних дней. */
    const val DECISIONS_NEW_DAYS: Int = 7
}

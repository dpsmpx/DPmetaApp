package com.dpsmpx.dpmeta.metrics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Все пороги, окна и веса DPmetaApp — здесь и только здесь (D-34, ТЗ, §6.3).
 * Смысл правила изменён — новая [RULES_VERSION] и запись в
 * `docs/CHANGELOG.md`; прежние точки истории остаются со своей версией.
 */
object Rules {
    const val RULES_VERSION: Int = 1

    /** «Кто работает» (F-16, `DPmeta:M-08`): коммит в `main` за это окно. */
    val MAIN_COMMIT_WINDOW: Duration = 6.hours

    /** «Кто работает»: ветка или открытый PR, обновлённые за это окно. */
    val BRANCH_OR_PR_WINDOW: Duration = 24.hours

    /** Основная ветка проекта — по ней «коммит в main». */
    const val MAIN_BRANCH: String = "main"
}

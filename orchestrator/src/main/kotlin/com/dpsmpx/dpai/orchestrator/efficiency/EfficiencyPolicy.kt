package com.dpsmpx.dpai.orchestrator.efficiency

import com.dpsmpx.dpai.contexts.ContextPolicy
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass

/**
 * Настройки слоя экономии (D-40; `docs/EFFICIENCY.md`, §10) — в одном
 * месте, без чисел по коду. [DEFAULT] — слой включён; [LEGACY] — выключен
 * целиком: так оркестратор ведёт себя, как до D-40 (базовая линия
 * сравнения и рубильник).
 */
data class EfficiencyPolicy(
    val context: ContextBudgetPolicy = ContextBudgetPolicy(),
    val resume: ResumePolicy = ResumePolicy(),
    val routing: RoutingPolicy = RoutingPolicy(),
    val budget: BudgetPolicy = BudgetPolicy(),
    /** Проваленная проверка — выжимкой и handoff в следующую попытку (§5). */
    val feedVerification: Boolean = true,
    /** Handoff из локальных фактов: изменённые файлы (`git status`), итог проверки. */
    val enrichHandoff: Boolean = true,
    /** Кеш prompt между сессиями разных рабочих деревьев (§6). */
    val shareSystemPromptCache: Boolean = true,
    /** Та же задача уже в очереди — вторая не создаётся (§6). */
    val rejectDuplicateTasks: Boolean = true,
    /**
     * В prompt — что среда сделает после хода сама (проверка, `git status`,
     * коммит, handoff): модель не тратит ход на механическую работу (§1).
     */
    val environmentFacts: Boolean = true,
    /** Рабочий набор файлов и итог хода — в handoff свежей сессии (§4, D-41). */
    val workingSet: WorkingSetPolicy = WorkingSetPolicy(),
) {
    companion object {
        val DEFAULT = EfficiencyPolicy()
        val LEGACY = EfficiencyPolicy(
            context = ContextBudgetPolicy(enabled = false),
            resume = ResumePolicy(enabled = false),
            routing = RoutingPolicy(enabled = false),
            budget = BudgetPolicy(enabled = false),
            feedVerification = false,
            enrichHandoff = false,
            shareSystemPromptCache = false,
            rejectDuplicateTasks = false,
            environmentFacts = false,
            workingSet = WorkingSetPolicy(enabled = false),
        )
    }
}

/**
 * Бюджет контекста хода (§3): от окна модели — ответ, системный prompt и
 * инструменты Claude Code, запас — и лишь доля остатка: остальное нужно
 * ходу на свою работу. Окно — наблюдаемое у прошлых ходов задачи, иначе
 * [defaultWindow]. В режиме бюджета `FRUGAL` — меньше на [frugalScale].
 */
data class ContextBudgetPolicy(
    val enabled: Boolean = true,
    val defaultWindow: Long = 200_000,
    val reservedOutput: Long = 32_000,
    val providerOverhead: Long = 25_000,
    val safetyMargin: Double = 0.2,
    val promptShare: Double = 0.25,
    val minTokens: Long = 4_000,
    val maxTokens: Long = 60_000,
    val frugalScale: Double = 0.6,
) {
    init {
        require(defaultWindow > reservedOutput + providerOverhead) { "окно больше ответа и системного prompt" }
        require(safetyMargin in 0.0..0.9 && promptShare in 0.01..1.0 && frugalScale in 0.1..1.0) { "доли — в (0, 1]" }
        require(minTokens in 500..maxTokens) { "minTokens — 500…maxTokens" }
    }

    /** Бюджет в оценочных токенах для окна [window] (неизвестно — [defaultWindow]). */
    fun tokens(window: Long?, mode: BudgetMode = BudgetMode.NORMAL): Long {
        val w = window?.takeIf { it > reservedOutput + providerOverhead } ?: defaultWindow
        val base = (w - reservedOutput - providerOverhead) * (1 - safetyMargin) * promptShare
        val scaled = if (mode == BudgetMode.FRUGAL) base * frugalScale else base
        return scaled.toLong().coerceIn(minTokens, maxTokens)
    }

    /** Политика сборки контекста: выключено — как до D-40 (только предел знаков). */
    fun policy(window: Long?, mode: BudgetMode = BudgetMode.NORMAL): ContextPolicy =
        if (enabled) ContextPolicy.budgeted(tokens(window, mode)) else ContextPolicy()
}

/**
 * Продолжение (§4): [delta] — `--resume` несёт только изменившиеся
 * источники; [checkpointAt] — доля окна, с которой история сессии
 * провайдера считается большой и продолжение идёт свежей сессией с
 * handoff; [checkpointOnOverflow] — так же после переполнения.
 */
data class ResumePolicy(
    val enabled: Boolean = true,
    val delta: Boolean = true,
    val checkpointAt: Double? = 0.6,
    val checkpointOnOverflow: Boolean = true,
) {
    init {
        require(checkpointAt == null || checkpointAt in 0.1..0.95) { "checkpointAt — 0.1…0.95" }
    }
}

/** Маршрут класса задачи: модель (`null` — аккаунта), усилие (`null` — по умолчанию Claude Code), право на субагентов. */
data class Route(val model: String? = null, val effort: String? = null, val subagents: Boolean = true)

/**
 * Модель и усилие по классу задачи (§7). [ladder] — ступени модели от
 * дешёвой; [efforts] — ступени усилия. Эскалация — на ступень после
 * каждых [escalateAfter] проваленных проверок. [downgradable] — классы,
 * которые режим `FRUGAL` может удешевить (правильность там не страдает).
 */
data class RoutingPolicy(
    val enabled: Boolean = true,
    val routes: Map<TaskClass, Route> = DEFAULT_ROUTES,
    val ladder: List<String> = listOf("haiku", "sonnet", "opus"),
    val efforts: List<String> = listOf("low", "medium", "high", "xhigh", "max"),
    val escalateAfter: Int = 1,
    val downgradable: Set<TaskClass> = setOf(
        TaskClass.TRIVIAL, TaskClass.CLASSIFICATION, TaskClass.EXTRACTION, TaskClass.SUMMARIZATION, TaskClass.REPOSITORY_RECON,
    ),
) {
    init {
        require(ladder.isNotEmpty() && efforts.isNotEmpty() && escalateAfter >= 1) { "ступени не пусты, escalateAfter ≥ 1" }
    }

    companion object {
        /**
         * По умолчанию: мелкое — дешёвая модель с малым усилием и без
         * субагентов; обычная разработка — как до D-40 (модель и усилие
         * аккаунта); трудная отладка — больше усилия; архитектура и
         * критичное ревью — сильнейшая ступень.
         */
        val DEFAULT_ROUTES: Map<TaskClass, Route> = mapOf(
            TaskClass.TRIVIAL to Route("haiku", "low", subagents = false),
            TaskClass.CLASSIFICATION to Route("haiku", "low", subagents = false),
            TaskClass.EXTRACTION to Route("haiku", "low", subagents = false),
            TaskClass.SUMMARIZATION to Route("haiku", "low", subagents = false),
            TaskClass.REPOSITORY_RECON to Route("sonnet", "low", subagents = false),
            TaskClass.NORMAL_ENGINEERING to Route(),
            TaskClass.COMPLEX_DEBUGGING to Route(effort = "high"),
            TaskClass.ARCHITECTURE to Route("opus", "high"),
            TaskClass.CRITICAL_REVIEW to Route("opus", "high"),
        )
    }
}

/**
 * Рабочий набор задачи (§4, D-41): файлы, которые её сессии читали и
 * правили (по потоку, внутри рабочего дерева), — до [maxFiles] путей;
 * итог последнего завершённого хода — до [maxSummaryChars] знаков. Идут
 * в handoff и в prompt свежей сессии; продолжению `--resume` не нужны.
 */
data class WorkingSetPolicy(val enabled: Boolean = true, val maxFiles: Int = 40, val maxSummaryChars: Int = 2_000) {
    init {
        require(maxFiles in 0..1_000 && maxSummaryChars in 0..20_000) { "maxFiles — 0…1000, maxSummaryChars — 0…20000" }
    }
}

/** Режим бюджета (§8): `FRUGAL` — подписка слота у предела (D-30). */
enum class BudgetMode { NORMAL, FRUGAL }

/**
 * Режим бюджета по загрузке подписки (§8): у предела — меньше контекст,
 * дешевле допустимые классы, задачи ниже [deferBelow] ждут сброса окна.
 */
data class BudgetPolicy(val enabled: Boolean = true, val deferBelow: Priority? = Priority.NORMAL)

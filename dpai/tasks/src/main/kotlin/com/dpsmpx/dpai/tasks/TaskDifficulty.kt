package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.measure.Measure

/** Сложность ТЗ автоматизации §6.2 — отдельна от прежнего назначения TaskClass. */
enum class TaskDifficulty(val rank: Int) { TRIVIAL(1), SIMPLE(2), MODERATE(3), COMPLEX(4), CRITICAL(5) }

/** Факты до начала работы: неизвестное значение не означает нулевой объём или отсутствие риска. */
data class TaskFacts(
    val files: Measure<Int> = Measure.unknown("факт не указан"),
    val changedLines: Measure<Int> = Measure.unknown("факт не указан"),
    val modules: Measure<Int> = Measure.unknown("факт не указан"),
    val dependencies: Measure<Int> = Measure.unknown("факт не указан"),
    val architectural: Measure<Boolean> = Measure.unknown("факт не указан"),
    val securitySensitive: Measure<Boolean> = Measure.unknown("факт не указан"),
    val irreversible: Measure<Boolean> = Measure.unknown("факт не указан"),
    val novelSolution: Measure<Boolean> = Measure.unknown("факт не указан"),
    val checksAvailable: Measure<Boolean> = Measure.unknown("факт не указан"),
    val specificationComplete: Measure<Boolean> = Measure.unknown("факт не указан"),
    val regressionRisk: Measure<Boolean> = Measure.unknown("факт не указан"),
) {
    init { require(listOfNotNull(files.orNull(), changedLines.orNull(), modules.orNull(), dependencies.orNull()).all { it >= 0 }) }
}

data class DifficultyAssessment(
    val difficulty: TaskDifficulty,
    val reasons: List<String>,
    val unknown: List<String>,
    val independentAudit: Boolean,
    val confirmation: Boolean,
)

/** Никаких вызовов модели и догадок по имени провайдера; явный высокий минимум не понижается. */
object TaskDifficultyClassifier {
    fun assess(facts: TaskFacts, minimum: TaskDifficulty = TaskDifficulty.TRIVIAL,
               taskClass: TaskClass = TaskClass.NORMAL_ENGINEERING): DifficultyAssessment {
        val files = facts.files.orNull()
        val changedLines = facts.changedLines.orNull()
        val modules = facts.modules.orNull()
        val dependencies = facts.dependencies.orNull()
        val architectural = facts.architectural.orNull()
        val securitySensitive = facts.securitySensitive.orNull()
        val irreversible = facts.irreversible.orNull()
        val novelSolution = facts.novelSolution.orNull()
        val checksAvailable = facts.checksAvailable.orNull()
        val specificationComplete = facts.specificationComplete.orNull()
        val regressionRisk = facts.regressionRisk.orNull()
        val missing = buildList {
            if (files == null) add("число файлов")
            if (changedLines == null) add("объём изменений")
            if (modules == null) add("число модулей")
            if (dependencies == null) add("зависимости")
            if (architectural == null) add("архитектурный масштаб")
            if (securitySensitive == null) add("влияние на безопасность")
            if (irreversible == null) add("обратимость")
            if (novelSolution == null) add("необходимость нового решения")
            if (checksAvailable == null) add("автоматические проверки")
            if (specificationComplete == null) add("полнота задания")
            if (regressionRisk == null) add("риск регрессий")
        }
        val reasons = arrayListOf<String>()
        val classified = when {
            securitySensitive == true || irreversible == true -> {
                reasons += "безопасность или труднообратимые последствия"; TaskDifficulty.CRITICAL
            }
            architectural == true || novelSolution == true ||
                (files ?: 0) >= 20 || (modules ?: 0) >= 4 || (changedLines ?: 0) >= 1000 || (dependencies ?: 0) >= 3 -> {
                reasons += "архитектура, новое решение или несколько подсистем"; TaskDifficulty.COMPLEX
            }
            missing.isNotEmpty() || checksAvailable != true || specificationComplete != true || regressionRisk == true -> {
                reasons += "неполные исходные данные, проверки или риск регрессий"; TaskDifficulty.MODERATE
            }
            (files ?: Int.MAX_VALUE) <= 1 && (changedLines ?: Int.MAX_VALUE) <= 20 && (modules ?: Int.MAX_VALUE) <= 1 && dependencies == 0 -> {
                reasons += "малое механическое изменение с известными проверками"; TaskDifficulty.TRIVIAL
            }
            (files ?: Int.MAX_VALUE) <= 3 && (changedLines ?: Int.MAX_VALUE) <= 200 && (modules ?: Int.MAX_VALUE) <= 1 && dependencies == 0 -> {
                reasons += "локальное изменение с полной спецификацией"; TaskDifficulty.SIMPLE
            }
            else -> { reasons += "функция ограниченного масштаба"; TaskDifficulty.MODERATE }
        }
        val classFloor = when (taskClass) {
            TaskClass.CRITICAL_REVIEW -> TaskDifficulty.CRITICAL
            TaskClass.ARCHITECTURE, TaskClass.COMPLEX_DEBUGGING -> TaskDifficulty.COMPLEX
            else -> TaskDifficulty.TRIVIAL
        }
        val result = maxOf(classified, minimum, classFloor)
        if (result > classified) reasons += "сохранён высокий минимум задачи или прежнего класса"
        return DifficultyAssessment(result, reasons, missing, result == TaskDifficulty.CRITICAL,
            result == TaskDifficulty.CRITICAL || missing.isNotEmpty())
    }
}

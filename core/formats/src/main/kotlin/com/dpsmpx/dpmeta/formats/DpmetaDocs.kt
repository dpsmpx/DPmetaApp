package com.dpsmpx.dpmeta.formats

/**
 * Счётчики документов DPmeta для метрик `eco.*` (ТЗ, §8): заголовки
 * `C-`, `RISK-`, `SQ-` — как у наборов прогресса сайта
 * (`SocialMediaImage:data/progress/scope.yaml`, счётчики DPmeta).
 */
object DpmetaDocs {
    /** Пути от корня DPmeta (подсистема `dpmeta/` DPmetaApp — читатель подставляет каталог). */
    const val CONTRACTS = "docs/CONTRACTS.md"
    const val RISKS = "docs/RISKS.md"
    const val QUEUE = "docs/SYNC_QUEUE.md"
    const val REPORTS = "docs/reports"

    private val CONTRACT = Regex("^#{2,3} +C-\\d+", RegexOption.MULTILINE)
    private val RISK = Regex("^#{2,3} +RISK-\\d+")
    private val ITEM = Regex("^#{2,3} +SQ-\\d+", RegexOption.MULTILINE)
    private val STATUS = Regex("^- \\*\\*Статус:?\\*\\*:? *(.*)$")

    /** Контракты (`eco.contracts`). */
    fun contracts(text: String): Int = CONTRACT.findAll(text).count()

    /** Пункты очереди согласований (`eco.sync_queue`). */
    fun queue(text: String): Int = ITEM.findAll(text).count()

    /**
     * Риски (`eco.risks_open`): всего и открытых. Открыт — поле «Статус» его
     * раздела начинается с «открыт» или «частично» (у частично решённого
     * открытая часть есть); поля нет — открыт: не закрытый явно риск не
     * считается закрытым.
     */
    fun risks(text: String): Pair<Int, Int> {
        var total = 0
        var open = 0
        var inRisk = false
        var status: String? = null
        fun close() {
            if (!inRisk) return
            total++
            val s = status?.trim()?.lowercase()
            if (s == null || s.startsWith("открыт") || s.startsWith("частично")) open++
        }
        for (line in text.lines()) {
            if (line.startsWith("#")) {
                close()
                inRisk = RISK.containsMatchIn(line)
                status = null
                continue
            }
            if (inRisk && status == null) STATUS.matchEntire(line)?.let { status = it.groupValues[1] }
        }
        close()
        return total to open
    }
}

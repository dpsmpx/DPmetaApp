package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.formats.DecisionLog
import com.dpsmpx.dpmeta.formats.Panel
import com.dpsmpx.dpmeta.formats.Roadmap
import com.dpsmpx.dpmeta.formats.StatusBlocks
import com.dpsmpx.dpmeta.formats.TodoList
import com.dpsmpx.dpmeta.github.GitHub

/**
 * Пункты панели проекта (F-10) — строки для экрана из файла или каталога,
 * прочитанных при открытии карточки. Разбирают те же разборщики, что и
 * синхронизация; не разобралось — строка с причиной, а не пустота.
 */
object Panels {
    /** Последних решений в пункте `decisions`. */
    const val LAST_DECISIONS = 3

    /** Пункт по тексту файла: первая строка — итог, дальше — подробности. */
    fun fromText(item: Panel.Item, text: String): List<String> = when (item.kind) {
        Panel.Kind.README_BLOCK -> StatusBlocks.parse(text, item.path).lines { s ->
            val blocks = s.blocks.filter { item.block == null || it.name == item.block }
            if (blocks.isEmpty()) listOf(item.block?.let { "блока «$it» нет" } ?: "блоков состояния нет") else blocks.map { "${it.name}: ${it.text}" }
        }
        Panel.Kind.ROADMAP -> Roadmap.parse(text, item.path).lines { r ->
            listOf("этапов ${r.stagesDone} из ${r.stages.size}") + r.stages.map { st ->
                "[${st.flag.code}] ${st.title}" + if (st.stepsTotal == 0) "" else " — ${st.stepsDone}/${st.stepsTotal}"
            }
        }
        Panel.Kind.DECISIONS -> DecisionLog.parse(text, Regex(item.id ?: return listOf("нет шаблона номера")), item.path).lines { d ->
            listOf("решений ${d.entries.size}") + d.entries.takeLast(LAST_DECISIONS).map { "${it.id} — ${it.title}" }
        }
        Panel.Kind.TODO -> TodoList.parse(text, item.path).lines { t -> listOf("открыто ${t.open}, за автором ${t.forAuthor}") }
        Panel.Kind.COUNT, Panel.Kind.DOCUMENT -> listOf("не файл: пункт «${item.kind.code}»")
    }

    /** Пункт `count` по каталогу: записи по маске — файлы и подкаталоги (объект контента — каталог). */
    fun fromDirectory(item: Panel.Item, entries: List<GitHub.Entry>): List<String> =
        listOf(entries.count { item.matches(it.name) }.toString())

    /** Отказ чтения — причина словом, код — в конце (ТЗ, §6.9; UX-13: слово, а не имя кода). */
    fun failed(f: Outcome.Failure): List<String> =
        listOf(f.diagnostics.firstOrNull()?.let { "не прочитано: ${Screens.failure(it).text} — ${it.message} (${it.code.name})" } ?: "не прочитано")

    private fun <T> Outcome<T>.lines(show: (T) -> List<String>): List<String> = when (this) {
        is Outcome.Success -> show(value)
        is Outcome.Failure -> failed(this)
    }
}

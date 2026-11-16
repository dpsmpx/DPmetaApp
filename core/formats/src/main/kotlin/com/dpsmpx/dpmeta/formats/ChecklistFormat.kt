package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Чекбоксы файла Markdown (D-176): прогресс по файлам, которые выбрал пользователь. Пункт —
 * элемент списка (`-`, `*`, `+` или `1.`) с флажком `[ ]` (открыт) или `[x]`, `[X]`, `[+]`
 * (сделан), на любой глубине вложенности; внутри блоков кода не считается. Остальная
 * разметка файла не важна: любой текст — законный чек-лист, пусть и пустой.
 */
data class Checklist(val done: Int, val total: Int, val open: List<Item>) {
    /** Открытый пункт: текст без разметки (не длиннее [TEXT_MAX]) и номер строки с 1. */
    data class Item(val text: String, val line: Int)

    fun toJson(): JsonObject = JsonObject.of(
        "done" to JsonNumber.of(done),
        "total" to JsonNumber.of(total),
        "open" to JsonArray(open.map { JsonObject.of("text" to JsonString(it.text), "line" to JsonNumber.of(it.line)) }),
    )

    companion object {
        /** Сколько открытых пунктов хранить: карточке хватает первых, счёт — по всем. */
        const val OPEN_KEEP = 20

        /** Предел текста пункта: строка карточки, а не документ. */
        const val TEXT_MAX = 200

        private val ITEM = Regex("^[ \\t]*(?:[-*+]|\\d{1,9}[.)])[ \\t]+\\[([ xX+])][ \\t]+(.*)$")

        fun parse(text: String): Outcome<Checklist> {
            var done = 0
            var total = 0
            val open = mutableListOf<Item>()
            for (l in Markdown.prose(text)) {
                val m = ITEM.find(l.text) ?: continue
                total++
                if (m.groupValues[1] != " ") {
                    done++
                } else if (open.size < OPEN_KEEP) {
                    open += Item(Markdown.plain(m.groupValues[2]).take(TEXT_MAX), l.number)
                }
            }
            return Outcome.success(Checklist(done, total, open))
        }
    }
}

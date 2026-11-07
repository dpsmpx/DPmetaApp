package com.dpsmpx.dpai.ui

/**
 * Вкладки «AI» (D-112, AI:A-1): шесть, значками без подписей (D-61); подпись —
 * долгим нажатием на значок и в заголовке открытой вкладки. «Журнал» — не
 * вкладка, а раздел «Настроек AI» (вход — значком в заголовке).
 * Порядок — порядок панели; номер вкладки — `ordinal`.
 */
enum class AiTab(val title: String) {
    SUMMARY("Сводка"),
    TASKS("Задачи"),
    TARGETS("Targets"),
    REQUESTS("Запросы"),
    MODELS("Модели"),
    FILES("Файлы"),
    ;

    /**
     * Что читает TalkBack у пункта панели: название и, у «Запросов», сколько
     * ждут ответа — число на значке само по себе ничего не говорит.
     */
    fun spoken(waiting: Int = 0): String =
        if (this == REQUESTS && waiting > 0) "$title, ждут ответа: $waiting" else title

    companion object {
        /** Вкладка по сохранённому номеру; чужой номер (прежняя раскладка) — «Сводка». */
        fun of(index: Int): AiTab = entries.getOrElse(index) { SUMMARY }
    }
}

/** Заголовок экрана «Настроек AI» и подпись значка, который их открывает (D-112). */
const val AI_SETTINGS_TITLE = "Настройки AI"

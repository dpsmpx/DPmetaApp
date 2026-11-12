package com.dpsmpx.dpai.ui

/**
 * Вкладки «AI» (D-112, D-146): Сводка, Задачи, Targets, Запросы, Модели — подписями
 * в верхней панели ([AI_SECTION_TABS]); «Файлы» — раздел IDE левой панели приложения.
 * «Журнал» — не вкладка, а раздел «Настроек AI». Номер вкладки — `ordinal`.
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

/**
 * Разделы приложения вне окна «AI» — для левой панели окна «AI» (`DPmetaApp:D-112`):
 * их экраны — у пульта, открывает их оболочка приложения. В отдельной сборке «AI»
 * их нет.
 */
enum class AppSection(val title: String) { SUMMARY("Сводка"), PROJECTS("Проекты"), SETTINGS("Настройки"), HELP("Справка") }

/** Вкладки раздела AI в верхней панели (D-113): без «Файлов» — это раздел IDE. */
val AI_SECTION_TABS: List<AiTab> = AiTab.entries.filter { it != AiTab.FILES }

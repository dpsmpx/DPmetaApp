package com.dpsmpx.dpai.ui

/**
 * Цвета профилей Claude (`DPmetaApp:D-83`; ТЗ DPmetaApp, F-17): только кружком
 * у имени профиля — не фоном и не текстом. Набор — свой, вне смысловых цветов
 * ([AppPalette.SEMANTIC], `DPmetaApp:D-74`): кружок профиля не спутать с
 * «готово», «тревогой» или «ИИ». Отличие от смысловых и друг от друга и
 * контраст к фону карточки — `ProfileColorsTest`.
 */
object ProfileColors {
    /** Цвет профиля: ключ (хранится отметкой), имя для выбора, пара для светлой и тёмной темы. */
    data class Color(val key: String, val title: String, val light: Long, val dark: Long) {
        fun of(dark: Boolean): Long = if (dark) this.dark else light
    }

    val ALL: List<Color> = listOf(
        Color("graphite", "графит", 0xFF545B63, 0xFFA0A4A8),
        Color("brown", "коричневый", 0xFF6B4A3A, 0xFFB08A6E),
        Color("olive", "оливковый", 0xFF5C641C, 0xFFC5CE7C),
        Color("sand", "песочный", 0xFF857A55, 0xFFE0D6A8),
    )

    /** Цвет по ключу; нет такого (или профиль без цвета) — `null`. */
    fun of(key: String?): Color? = ALL.firstOrNull { it.key == key }
}

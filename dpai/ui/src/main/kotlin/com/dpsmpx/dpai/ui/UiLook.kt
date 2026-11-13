package com.dpsmpx.dpai.ui

/**
 * Оформление всего приложения — пульта и «AI» (`DPmetaApp:D-112`, `DPmetaApp:D-115`;
 * D-145): строгий стиль инструмента разработчика из принятых прототипов —
 * нейтральные поверхности и один акцент. Настройка «Оформление»: тема, акцент
 * (или цвета обоев телефона, D-74), размер текста, плотность, шрифт редактора.
 * Цвета — ARGB без Compose: модуль чистый Kotlin, тему строят оболочки.
 */
data class UiLook(
    val theme: Theme = Theme.SYSTEM,
    val accent: Accent = Accent.TEAL,
    val text: TextSize = TextSize.M,
    val density: Density = Density.NORMAL,
    val editorFont: Int = EDITOR_DEFAULT,
) {
    enum class Theme(val key: String, val title: String) { SYSTEM("system", "Как в телефоне"), LIGHT("light", "Светлая"), DARK("dark", "Тёмная") }

    /** Акцент: цвет на светлой и тёмной теме; [WALLPAPER] — цвета обоев (Android 12+, иначе — бирюзовый). */
    enum class Accent(val key: String, val title: String, val light: Long, val dark: Long) {
        TEAL("teal", "Бирюзовый", 0xFF0B6E76, 0xFF4FC1C9),
        BLUE("blue", "Синий", 0xFF2158D8, 0xFF6E9CF5),
        GREEN("green", "Зелёный", 0xFF1A7F37, 0xFF3FB950),
        VIOLET("violet", "Фиолетовый", 0xFF6D3BD6, 0xFFA78BFA),
        GRAPHITE("graphite", "Графит", 0xFF3D4450, 0xFFAAB2BD),
        WALLPAPER("wallpaper", "Обои телефона", 0xFF0B6E76, 0xFF4FC1C9),
    }

    /** Размер текста: множитель к системному (S — 13,5 пт, M — 15, L — 17, XL — 19,5 у основного текста прототипа). */
    enum class TextSize(val key: String, val title: String, val factor: Float) {
        S("s", "Мелкий", 0.9f), M("m", "Обычный", 1.0f), L("l", "Крупный", 1.13f), XL("xl", "Очень крупный", 1.3f),
    }

    /** Плотность: множитель отступов и размеров в dp; текст от него не меняется. */
    enum class Density(val key: String, val title: String, val factor: Float) {
        NORMAL("normal", "Обычная", 1.0f), COMPACT("compact", "Плотная", 0.9f),
    }

    fun toMap(): Map<String, String> = mapOf(
        THEME to theme.key, ACCENT to accent.key, TEXT to text.key, DENSITY to density.key, EDITOR_FONT to editorFont.toString(),
    )

    companion object {
        /** Файл настроек оформления — общий для пульта и «AI»: одно приложение, одно оформление. */
        const val PREFS = "dpmeta.ui"
        const val THEME = "theme"
        const val ACCENT = "accent"
        const val TEXT = "text"
        const val DENSITY = "density"
        const val EDITOR_FONT = "editor_font"
        const val EDITOR_DEFAULT = 14
        val EDITOR_RANGE = 10..24

        /** Из сохранённых строк; неизвестное и испорченное — по умолчанию, не отказ. */
        fun fromMap(m: Map<String, *>): UiLook = UiLook(
            theme = Theme.entries.firstOrNull { it.key == m[THEME] } ?: Theme.SYSTEM,
            accent = Accent.entries.firstOrNull { it.key == m[ACCENT] } ?: Accent.TEAL,
            text = TextSize.entries.firstOrNull { it.key == m[TEXT] } ?: TextSize.M,
            density = Density.entries.firstOrNull { it.key == m[DENSITY] } ?: Density.NORMAL,
            editorFont = (m[EDITOR_FONT] as? String)?.toIntOrNull()?.coerceIn(EDITOR_RANGE) ?: EDITOR_DEFAULT,
        )

        /** Нейтральные цвета прототипа: фон, поверхность, вторая поверхность, границы, текст. */
        private class Neutral(
            val bg: Long, val surface: Long, val surface2: Long, val border: Long, val border2: Long,
            val text: Long, val text2: Long, val err: Long, val onAccent: Long,
        )

        private val LIGHT = Neutral(0xFFF6F7F9, 0xFFFFFFFF, 0xFFEEF0F3, 0xFFE1E4E8, 0xFFC9CED6, 0xFF15181D, 0xFF545B66, 0xFFC4262E, 0xFFFFFFFF)
        private val DARK = Neutral(0xFF0E1014, 0xFF15181E, 0xFF1C2028, 0xFF262B34, 0xFF3A414D, 0xFFE7EAEE, 0xFFA3ABB6, 0xFFF85149, 0xFF0E1014)

        /**
         * Схема Material 3 из нейтральных цветов и акцента. Карточки и поля — на
         * поверхности, страница — на фоне; ИИ — смысловой цвет `AppPalette`.
         */
        fun scheme(accent: Accent, dark: Boolean): AppPalette.Scheme {
            val n = if (dark) DARK else LIGHT
            val a = if (dark) accent.dark else accent.light
            val ai = AppPalette.semantic(AppPalette.Role.AI, dark)
            return AppPalette.Scheme(
                primary = a, onPrimary = n.onAccent, primaryContainer = mix(a, n.surface, 0.16), onPrimaryContainer = n.text,
                secondary = n.text2, onSecondary = n.surface, secondaryContainer = n.surface2, onSecondaryContainer = n.text,
                tertiary = ai, onTertiary = n.onAccent, tertiaryContainer = mix(ai, n.surface, 0.16), onTertiaryContainer = n.text,
                background = n.bg, onBackground = n.text, surface = n.bg, onSurface = n.text,
                surfaceVariant = n.surface2, onSurfaceVariant = n.text2, outline = n.border2, outlineVariant = n.border,
                error = n.err, onError = n.onAccent, errorContainer = mix(n.err, n.surface, 0.16), onErrorContainer = n.text,
                surfaceContainerLowest = n.surface, surfaceContainerLow = n.surface, surfaceContainer = n.surface,
                surfaceContainerHigh = n.surface2, surfaceContainerHighest = n.surface,
                inverseSurface = n.text, inverseOnSurface = n.bg, inversePrimary = if (dark) accent.light else accent.dark,
            )
        }

        /** Смесь: доля [part] цвета [top] поверх непрозрачного [base]. */
        fun mix(top: Long, base: Long, part: Double): Long {
            fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toDouble()
            fun one(shift: Int) = Math.round(ch(top, shift) * part + ch(base, shift) * (1 - part)).coerceIn(0, 255) shl shift
            return 0xFF000000 or one(16) or one(8) or one(0)
        }
    }
}

package com.dpsmpx.dpai.ui

/**
 * Функции `@Preview` файла Kotlin для предпросмотра на телефоне (`DPmetaApp:D-132`;
 * D-167): их открывает `PreviewActivity` библиотеки `ui-tooling` отладочной сборки по
 * имени `класс.функция`. Показываются функции верхнего уровня без параметров; класс —
 * фасад файла (`ИмяФайлаKt` или `@file:JvmName`). Разбор — по тексту, без компилятора:
 * комментарии и строки пропускаются грубо (достаточно для обычного кода Compose).
 */
object ComposePreviews {
    /** Функция предпросмотра: [name] — как в коде, [composable] — `класс.функция` для `PreviewActivity`. */
    data class Preview(val name: String, val composable: String, val line: Int)

    /** Найденное в файле: [skipped] — функции `@Preview`, которые не показать, и почему. */
    data class Found(val previews: List<Preview>, val skipped: List<Pair<String, String>>)

    private val PACKAGE = Regex("^\\s*package\\s+([A-Za-z_][\\w.]*)", RegexOption.MULTILINE)
    private val JVM_NAME = Regex("^\\s*@file:JvmName\\(\\s*\"([A-Za-z_$][\\w$]*)\"\\s*\\)", RegexOption.MULTILINE)
    private val FUN = Regex("^(\\s*)(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:private|internal|public)\\s+)?fun\\s+([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)")
    private val PREVIEW = Regex("@Preview\\b")

    /** Функции `@Preview` файла [fileName] с текстом [text]. */
    fun find(fileName: String, text: String): Found {
        val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
        val facade = JVM_NAME.find(text)?.groupValues?.get(1) ?: facadeName(fileName)
        val cls = if (pkg.isEmpty()) facade else "$pkg.$facade"
        val lines = text.lines()
        val previews = ArrayList<Preview>()
        val skipped = ArrayList<Pair<String, String>>()
        var pending = false
        var depth = 0
        for ((i, raw) in lines.withIndex()) {
            val line = raw.substringBefore("//")
            if (PREVIEW.containsMatchIn(line)) pending = true
            val m = FUN.find(line)
            if (m != null && pending) {
                val (_, name, params) = m.destructured
                pending = false
                when {
                    depth > 0 -> skipped += name to "функция внутри класса — пока не показывается"
                    params.isNotBlank() -> skipped += name to "с параметрами (@PreviewParameter) — пока не показывается"
                    else -> previews += Preview(name, "$cls.$name", i + 1)
                }
            } else if (m != null) {
                pending = false
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth < 0) depth = 0
        }
        return Found(previews, skipped)
    }

    /** Класс-фасад файла: `my-screen.kt` → `My_screenKt`, `Main.kt` → `MainKt`. */
    fun facadeName(fileName: String): String {
        val base = fileName.substringAfterLast('/').removeSuffix(".kt")
        val clean = base.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return clean.replaceFirstChar { it.uppercaseChar() } + "Kt"
    }

    private val APP_ID = Regex("applicationId\\s*=?\\s*\"([A-Za-z][\\w.]*)\"")
    private val SUFFIX = Regex("applicationIdSuffix\\s*=?\\s*\"(\\.?[\\w.]+)\"")
    private val DEBUG_BLOCK = Regex("(?:getByName\\(\\s*\"debug\"\\s*\\)|\\bdebug)\\s*\\{")

    /**
     * Пакет отладочной сборки из текста `build.gradle(.kts)` модуля: `applicationId` и
     * `applicationIdSuffix` блока `debug`; нет `applicationId` — `null`.
     */
    fun applicationId(gradle: String): String? {
        val id = APP_ID.find(gradle)?.groupValues?.get(1) ?: return null
        val debug = DEBUG_BLOCK.find(gradle) ?: return id
        // Блок debug — до парной скобки.
        var depth = 0
        var end = gradle.length
        for (k in debug.range.last until gradle.length) {
            when (gradle[k]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = k; break } }
            }
        }
        val suffix = SUFFIX.find(gradle.substring(debug.range.first, end))?.groupValues?.get(1) ?: return id
        return id + (if (suffix.startsWith('.')) suffix else ".$suffix")
    }
}

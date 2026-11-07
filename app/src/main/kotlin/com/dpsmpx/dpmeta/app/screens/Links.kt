package com.dpsmpx.dpmeta.app.screens

/**
 * Переходы в документах (ТЗ, F-09): относительные пути внутри репозитория,
 * якоря заголовков и ссылки `Проект:путь` между репозиториями. Чистый
 * Kotlin — проверяется тестами на JVM.
 */
object Links {
    sealed interface Target {
        /** Файл [path] проекта [project]; [anchor] — якорь заголовка или null. */
        data class File(val project: String, val path: String, val anchor: String?) : Target

        /** Якорь в том же документе. */
        data class Anchor(val anchor: String) : Target

        /** Адрес вне репозиториев: первое касание копирует, второе открывает — только `https` (D-75, `LinkTaps`). */
        data class External(val url: String) : Target
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val CROSS = Regex("^([A-Za-z][A-Za-z0-9_.-]*):([^\\s`#]+(?:#[^\\s`]+)?)$")

    /**
     * Ссылка [target] из файла [base] проекта [project]: относительная —
     * от каталога [base]; `/путь` — от корня репозитория; выход за корень —
     * null (такой ссылки нет).
     */
    fun resolve(project: String, base: String, target: String): Target? {
        val t = target.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("#")) return Target.Anchor(t.removePrefix("#"))
        if (SCHEME.containsMatchIn(t)) return Target.External(t)
        val (pathPart, anchor) = t.split('#', limit = 2).let { it[0] to it.getOrNull(1)?.takeIf { a -> a.isNotEmpty() } }
        val start = if (pathPart.startsWith("/")) emptyList() else base.split('/').dropLast(1)
        val parts = start.toMutableList()
        for (seg in pathPart.removePrefix("/").split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        if (parts.isEmpty()) return null
        return Target.File(project, parts.joinToString("/"), anchor)
    }

    /**
     * Ссылка `Проект:путь` (код в обратных кавычках): [names] — имена и
     * прежние имена проектов реестра → имя проекта. Не проект — null.
     */
    fun crossRef(code: String, names: Map<String, String>): Target.File? {
        val m = CROSS.matchEntire(code.trim()) ?: return null
        val project = names[m.groupValues[1]] ?: return null
        val (path, anchor) = m.groupValues[2].split('#', limit = 2).let { it[0] to it.getOrNull(1) }
        if (path.split('/').any { it == ".." || it.isEmpty() }) return null
        // `Проект:M-08` — номер записи, а не файл: путь — с каталогом или расширением.
        if ('/' !in path && '.' !in path) return null
        return Target.File(project, path, anchor)
    }

    /**
     * Якорь заголовка как у GitHub: нижний регистр, пробелы — дефисы, без
     * знаков, кроме дефиса и подчёркивания; буквы любого алфавита остаются.
     */
    fun slug(heading: String): String = heading.trim().lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .replace(' ', '-')

    /** Файл кода — по запросу и без сохранения в кэше (F-09); тексты процесса — с кэшем. */
    fun cacheable(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in TEXT

    private val TEXT = setOf("md", "txt", "toml", "yaml", "yml", "json", "csv")
}

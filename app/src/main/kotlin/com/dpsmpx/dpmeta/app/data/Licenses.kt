package com.dpsmpx.dpmeta.app.data

/**
 * Лицензии компонентов для «О программе» (F-50, A-12): строки таблиц `docs/PROVENANCE.md`
 * и `dpai/docs/PROVENANCE.md`, которые сборка кладёт в APK как есть, — поэтому экран
 * совпадает с документом. Берутся таблицы с колонкой «Лицензия», кроме таблиц идей:
 * первая колонка — компонент, «Версия» — если есть. Без Android: проверяется на JVM.
 */
object Licenses {
    data class Row(val section: String, val component: String, val version: String?, val license: String)

    /** Разобрать markdown [text] документа; [source] — подпись документа перед разделом. */
    fun parse(source: String, text: String): List<Row> {
        val out = ArrayList<Row>()
        var section = source
        var header: List<String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#")) { section = "$source · ${line.trimStart('#').trim()}"; header = null; continue }
            if (!line.startsWith("|")) { header = null; continue }
            val cells = cells(line)
            if (header == null) {
                header = cells.takeIf { c -> c.any { it.startsWith("Лицензия") } && c.firstOrNull() != "Идея" } ?: emptyList()
                continue
            }
            if (header.isEmpty() || cells.all { it.matches(Regex("^:?-{3,}:?$")) }) continue
            val license = header.indexOfFirst { it.startsWith("Лицензия") }
            val version = header.indexOf("Версия")
            val component = cells.getOrNull(0)?.let(::plain).orEmpty()
            val l = cells.getOrNull(license)?.let(::plain).orEmpty()
            if (component.isNotEmpty()) out += Row(section, component, cells.getOrNull(version)?.let(::plain)?.takeIf { version >= 0 && it.isNotEmpty() }, l)
        }
        return out
    }

    private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Текст ячейки без разметки: `код`, **жирный**, ссылки [текст](адрес). */
    private fun plain(cell: String): String = cell.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1").replace("`", "").replace("**", "").trim()
}

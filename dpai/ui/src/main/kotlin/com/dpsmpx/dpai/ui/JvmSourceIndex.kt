package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.lsp.jdwp.JvmSources
import java.util.concurrent.ConcurrentHashMap

/**
 * Исходники Kotlin/Java дерева для отладки APK (`DPmetaApp:D-126`; D-161): [files] —
 * пути гостя файлов `.kt`/`.java`, [read] — текст файла (нет — `null`). Пакет — из
 * строки `package`; файл класса — по имени и пакету: сначала путь
 * `…/<пакет по папкам>/<имя>`, затем любой файл с этим именем и той же строкой
 * `package` (Kotlin не требует, чтобы папки совпадали с пакетом).
 */
class JvmSourceIndex(files: List<String>, private val read: (String) -> String?) : JvmSources {
    private val byName: Map<String, List<String>> = files.groupBy { it.substringAfterLast('/') }
    private val packages = ConcurrentHashMap<String, String>()

    override fun packageOf(path: String): String? {
        packages[path]?.let { return it }
        val text = read(path) ?: return null
        return packageLine(text).also { packages[path] = it }
    }

    override fun find(pkg: String, file: String): String? {
        val list = byName[file].orEmpty()
        val suffix = "/" + (if (pkg.isEmpty()) "" else pkg.replace('.', '/') + "/") + file
        return list.firstOrNull { it.endsWith(suffix) && packageOf(it) == pkg } ?: list.firstOrNull { packageOf(it) == pkg }
    }

    companion object {
        /** Расширения исходников, которые отлаживаются по JDWP. */
        val EXTENSIONS: Set<String> = setOf("kt", "java")

        /** Сколько первых строк файла смотреть в поисках `package`. */
        const val MAX_HEADER_LINES: Int = 200

        private val PACKAGE = Regex("^\\s*package\\s+([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)")
        private val DECLARATION = Regex("^(?:(?:public|private|internal|protected|abstract|final|open|data|sealed|enum|annotation)\\s+)*(?:class|interface|object|fun|enum)\\b")

        /** Пакет из текста файла: строка `package` до первого `import` или объявления; нет — `""`. */
        fun packageLine(text: String): String {
            var comment = false
            for (raw in text.lineSequence().take(MAX_HEADER_LINES)) {
                val line = raw.trim()
                if (comment) {
                    if ("*/" in line) comment = false
                    continue
                }
                if (line.startsWith("/*")) {
                    if ("*/" !in line) comment = true
                    continue
                }
                if (line.startsWith("//") || line.startsWith("@file:") || line.isEmpty()) continue
                PACKAGE.find(line)?.let { return it.groupValues[1] }
                if (line.startsWith("import ") || DECLARATION.containsMatchIn(line)) return ""
            }
            return ""
        }
    }
}

package com.dpsmpx.dpai.ui

enum class CodeToken { KEYWORD, STRING, COMMENT, NUMBER }
data class CodeSpan(val start: Int, val end: Int, val kind: CodeToken)
data class CodeHighlight(val language: String, val spans: List<CodeSpan>, val limited: Boolean)

/** Лёгкая лексическая подсветка, не разбор языка и не LSP (D-131). Один проход, ограниченный объём. */
object CodeSyntax {
    const val MAX_CHARS = 128 * 1024
    const val MAX_SPANS = 5000
    private val common = setOf("if", "else", "for", "while", "return", "class", "break", "continue", "try", "catch", "throw", "import", "true", "false", "null", "public", "private", "protected", "static", "void", "new", "this", "switch", "case", "default", "do", "const")
    private val python = setOf("def", "elif", "in", "not", "and", "or", "is", "None", "True", "False", "from", "as", "with", "except", "finally", "raise", "pass", "yield", "lambda", "global", "nonlocal", "assert", "del", "async", "await")
    private val kotlin = setOf("fun", "val", "var", "when", "object", "interface", "override", "suspend", "data", "sealed", "enum", "is", "in", "as", "package", "internal", "open", "companion", "null", "super", "typeof", "instanceof", "extends", "implements", "final", "boolean", "int", "long", "double", "float", "char", "byte", "short", "throws")
    private val cpp = setOf("auto", "bool", "int", "long", "double", "float", "char", "short", "unsigned", "signed", "struct", "typedef", "using", "namespace", "template", "typename", "virtual", "override", "nullptr", "delete", "sizeof", "include", "define", "ifdef", "endif", "extern", "volatile", "constexpr", "noexcept")

    fun language(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "c", "h", "cpp", "cc", "cxx", "hpp", "hxx" -> "C/C++"
        "py", "pyi" -> "Python"
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "json" -> "JSON"
        else -> "Текст"
    }

    fun highlight(path: String, text: String): CodeHighlight {
        val language = language(path)
        if (language == "Текст") return CodeHighlight(language, emptyList(), false)
        val keywords = common + when (language) { "Python" -> python; "C/C++" -> cpp; else -> kotlin }
        val spans = mutableListOf<CodeSpan>()
        val end = minOf(text.length, MAX_CHARS)
        var i = 0
        while (i < end && spans.size < MAX_SPANS) {
            val start = i
            val c = text[i]
            when {
                language == "Python" && c == '#' || language != "Python" && language != "JSON" && text.startsWith("//", i) -> {
                    while (i < end && text[i] != '\n') i++
                    spans += CodeSpan(start, i, CodeToken.COMMENT)
                }
                language != "Python" && language != "JSON" && text.startsWith("/*", i) -> {
                    i += 2
                    while (i < end && !text.startsWith("*/", i)) i++
                    i = minOf(end, i + 2)
                    spans += CodeSpan(start, i, CodeToken.COMMENT)
                }
                c == '"' || c == '\'' -> {
                    val triple = language in setOf("Python", "Kotlin") && i + 2 < end && text[i + 1] == c && text[i + 2] == c
                    val quote = if (triple) "$c$c$c" else "$c"
                    i += quote.length
                    while (i < end && !text.startsWith(quote, i)) {
                        if (text[i] == '\\' && i + 1 < end) i++
                        i++
                    }
                    if (i < end) i = minOf(end, i + quote.length)
                    spans += CodeSpan(start, i, CodeToken.STRING)
                }
                c.isDigit() -> {
                    i++
                    while (i < end && (text[i].isLetterOrDigit() || text[i] in "._")) i++
                    spans += CodeSpan(start, i, CodeToken.NUMBER)
                }
                c.isLetter() || c == '_' -> {
                    i++
                    while (i < end && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    if (text.substring(start, i) in keywords) spans += CodeSpan(start, i, CodeToken.KEYWORD)
                }
                else -> i++
            }
        }
        return CodeHighlight(language, spans, i < text.length)
    }
}

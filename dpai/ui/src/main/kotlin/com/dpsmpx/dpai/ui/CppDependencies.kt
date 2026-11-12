package com.dpsmpx.dpai.ui

/** Каталог подсказок Alpine: вывод компилятора не становится именем пакета или shell-кодом (D-139). */
object CppDependencies {
    data class Library(val name: String, val packages: List<String>, val flags: List<String>, val headers: List<String>, val links: List<String> = emptyList())
    data class Report(val exitCode: Int, val libraries: List<Library>, val unknownHeaders: List<String>) {
        val packages: List<String> get() = libraries.flatMap { it.packages }.distinct()
        val flags: List<String> get() = libraries.flatMap { it.flags }.distinct()
        val useful: Boolean get() = libraries.isNotEmpty() || unknownHeaders.isNotEmpty()
    }
    val catalog = listOf(
        Library("ncurses", listOf("ncurses-dev"), listOf("-lncursesw"), listOf("ncurses.h", "curses.h", "ncursesw/curses.h", "ncursesw/ncurses.h"), listOf("ncurses", "ncursesw", "curses", "tinfo")),
        Library("панели ncurses", listOf("ncurses-dev"), listOf("-lpanelw", "-lncursesw"), listOf("panel.h", "ncursesw/panel.h"), listOf("panel", "panelw")),
        Library("меню ncurses", listOf("ncurses-dev"), listOf("-lmenuw", "-lncursesw"), listOf("menu.h", "ncursesw/menu.h"), listOf("menu", "menuw")),
        Library("формы ncurses", listOf("ncurses-dev"), listOf("-lformw", "-lncursesw"), listOf("form.h", "ncursesw/form.h"), listOf("form", "formw")),
        Library("OpenSSL", listOf("openssl-dev"), listOf("-lssl", "-lcrypto"), listOf("openssl/"), listOf("ssl", "crypto")),
        Library("zlib", listOf("zlib-dev"), listOf("-lz"), listOf("zlib.h", "zconf.h"), listOf("z")),
        Library("libcurl", listOf("curl-dev"), listOf("-lcurl"), listOf("curl/"), listOf("curl")),
        Library("SQLite", listOf("sqlite-dev"), listOf("-lsqlite3"), listOf("sqlite3.h", "sqlite3ext.h"), listOf("sqlite3")),
        Library("readline", listOf("readline-dev"), listOf("-lreadline"), listOf("readline/"), listOf("readline")),
        Library("libpng", listOf("libpng-dev"), listOf("-lpng"), listOf("png.h", "pngconf.h"), listOf("png", "png16")),
        Library("JPEG", listOf("libjpeg-turbo-dev"), listOf("-ljpeg"), listOf("jpeglib.h"), listOf("jpeg")),
        Library("fmt", listOf("fmt-dev"), listOf("-lfmt"), listOf("fmt/"), listOf("fmt")),
        Library("Boost", listOf("boost-dev"), emptyList(), listOf("boost/")),
        Library("Eigen", listOf("eigen-dev"), listOf("-I/usr/include/eigen3"), listOf("Eigen/", "eigen3/Eigen/")),
        Library("JSON nlohmann", listOf("nlohmann-json"), emptyList(), listOf("nlohmann/")),
        Library("заголовки C++", listOf("g++"), emptyList(), listOf("iostream", "vector", "string", "map", "set", "memory", "thread", "algorithm", "filesystem", "bits/stdc++.h")),
        Library("заголовки C", listOf("musl-dev"), emptyList(), listOf("stdio.h", "stdlib.h", "stdint.h", "stddef.h", "unistd.h")),
    )
    private val clangHeader = Regex("(?:fatal )?error:\\s*['\"<]([^'\"<>\\r\\n]{1,240})['\">]\\s+file not found")
    private val gccHeader = Regex("fatal error:\\s*([^:\\r\\n]{1,240}):\\s*No such file or directory")
    private val linker = Regex("(?:cannot find|library not found for|unable to find library)\\s+['\"]?-l([A-Za-z0-9_+.-]{1,100})")
    private val cursesSymbol = Regex("(?:undefined reference to\\s+['`\"]?|undefined symbol:\\s*)(?:initscr|endwin|stdscr|wprintw|printw|wrefresh|wgetch|newwin|cbreak|noecho|keypad|start_color|init_pair)\\b")
    private val tools = mapOf("clang++" to "clang", "clang" to "clang", "g++" to "g++", "gcc" to "gcc", "cmake" to "cmake", "make" to "make", "ninja" to "ninja", "pkg-config" to "pkgconf", "python3" to "python3")
    fun analyze(output: String, exitCode: Int): Report {
        if (exitCode == 0) return Report(exitCode, emptyList(), emptyList())
        val text = plain(output.takeLast(EditorRunOutput.MAX_BYTES))
        val headers = (clangHeader.findAll(text).map { it.groupValues[1] } + gccHeader.findAll(text).map { it.groupValues[1].trim() }).distinct().take(50).toList()
        val found = linkedSetOf<Library>()
        val unknown = mutableListOf<String>()
        for (header in headers) {
            val lib = catalog.firstOrNull { l -> l.headers.any { if (it.endsWith('/')) header.startsWith(it) else header == it } }
            if (lib == null) unknown += header else found += lib
        }
        for (match in linker.findAll(text)) catalog.firstOrNull { match.groupValues[1] in it.links }?.let { found += it }
        if (cursesSymbol.containsMatchIn(text)) found += catalog.first()
        for ((tool, pkg) in tools) {
            if (Regex("(?:^|[\\s:])${Regex.escape(tool)}:\\s*(?:not found|command not found)(?:\\s|$)").containsMatchIn(text)) {
                found += Library(tool, listOf(pkg), emptyList(), emptyList())
            }
        }
        return Report(exitCode, found.toList(), unknown)
    }
    /** Шаблон предлагается отдельно; произвольная команда проекта не переписывается. */
    fun singleFileCommand(report: Report, previousCommand: String = ""): String {
        val previous = catalog.filter { library ->
            (library.flags + library.links.map { "-l$it" }).any { flag ->
                Regex("(?<!\\S)${Regex.escape(flag)}(?!\\S)").containsMatchIn(previousCommand)
            }
        }.flatMap { it.flags }
        val flags = (previous + report.flags).distinct().joinToString(" ")
        return "DPAI_BIN=\$(mktemp /tmp/dpai-cpp.XXXXXX) || exit\n" +
            "trap 'rm -f \"\$DPAI_BIN\"' EXIT\n" +
            "clang++ \"\$DPAI_FILE\" -std=c++20 -o \"\$DPAI_BIN\"${if (flags.isEmpty()) "" else " $flags"} && \"\$DPAI_BIN\""
    }
    /** ANSI CSI/OSC убираются итеративно: чужой вывод ограничен по памяти и не вызывает рекурсивного regex. */
    private fun plain(text: String): String = buildString {
        var at = 0
        while (at < text.length) {
            val c = text[at++]
            if (c == '\u001b' && at < text.length) {
                when (text[at++]) {
                    '[' -> { while (at < text.length && text[at].code !in 0x40..0x7e) at++; if (at < text.length) at++ }
                    ']' -> while (at < text.length) {
                        if (text[at++] == '\u0007') break
                        if (text[at - 1] == '\u001b' && at < text.length && text[at] == '\\') { at++; break }
                    }
                }
            } else if (c == '\n' || c == '\r' || c == '\t' || c.code >= 32) append(c)
        }
    }
}

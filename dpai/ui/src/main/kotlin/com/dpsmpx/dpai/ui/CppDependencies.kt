package com.dpsmpx.dpai.ui

/** Каталог подсказок Alpine: вывод компилятора не становится именем пакета или shell-кодом (D-139). */
object CppDependencies {
    data class Library(val name: String, val packages: List<String>, val flags: List<String>, val headers: List<String>, val links: List<String> = emptyList())
    enum class Issue(val advice: String) {
        TOOLCHAIN("Неполный или повреждённый тулчейн C++. Установите полный набор, затем проверьте среду; если пакеты уже числятся установленными — восстановите их."),
        PACKAGE_WRITE("Менеджер пакетов не смог записать файлы. В Android apk из интерактивного терминала может упираться в /proc. Кнопки IDE выполняют apk отдельно без /proc. Если ошибка повторится, проверьте запись в корневой системе."),
        EXECUTION_PERMISSION("Нет доступа к файлу или запрещено выполнение. Во внешнем хранилище Android возможен noexec: шаблон IDE собирает исполняемый файл во внутреннем /tmp. Проверьте также права исходников."),
        DISK_FULL("Закончилось место. Освободите место в памяти приложения/устройства и повторите установку или сборку."),
        COMPILER_ERROR("Компилятор сообщил ошибку. Откройте указанную в выводе строку исходника, исправьте код и запустите снова."),
        LINK_FLAGS("Компоновщик не нашёл определение символа. Проверьте, что в сборку включены все исходные файлы и нужные библиотеки; для проекта используйте его CMake/Make-команду."),
        PROGRAM_EXIT("Программа или команда завершилась с ненулевым кодом. Причина — в выводе терминала; проверьте входные данные и команду запуска."),
    }
    val TOOLCHAIN_PACKAGES = listOf("build-base", "clang", "lld", "compiler-rt")
    data class Report(val exitCode: Int, val libraries: List<Library>, val unknownHeaders: List<String>, val issues: List<Issue> = emptyList()) {
        val packages: List<String> get() = libraries.flatMap { it.packages }.distinct()
        val flags: List<String> get() = libraries.flatMap { it.flags }.distinct()
        val useful: Boolean get() = exitCode != 0
        val advice: List<String> get() = issues.map { it.advice }
        fun withFailure(output: String): Report {
            val next = analyze(output, 1)
            return copy(libraries = (libraries + next.libraries).distinct(), unknownHeaders = (unknownHeaders + next.unknownHeaders).distinct(), issues = (issues + next.issues).distinct())
        }
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
        val issues = linkedSetOf<Issue>()
        val unknown = mutableListOf<String>()
        for (header in headers) {
            val lib = catalog.firstOrNull { l -> l.headers.any { if (it.endsWith('/')) header.startsWith(it) else header == it } }
            if (lib == null) unknown += header else found += lib
        }
        for (match in linker.findAll(text)) catalog.firstOrNull { match.groupValues[1] in it.links }?.let { found += it }
        if (cursesSymbol.containsMatchIn(text)) found += catalog.first()
        val brokenToolchain = text.contains("DPAI_CXX_TOOLCHAIN:") ||
            (text.contains("clang") && (text.contains("unable to execute command") || text.contains("invalid linker name"))) ||
            Regex("(?:crtbeginS?\\.o|crtendS?\\.o|crt[1in]\\.o|libclang_rt\\.[^\\s:]*builtins[^\\s:]*|library -lgcc(?:_s)?)").containsMatchIn(text) ||
            found.any { it.name == "заголовки C++" || it.name == "заголовки C" }
        if (brokenToolchain) issues += Issue.TOOLCHAIN
        for ((tool, pkg) in tools) {
            if (Regex("(?:^|[\\s:])${Regex.escape(tool)}:\\s*(?:not found|command not found)(?:\\s|$)").containsMatchIn(text)) {
                if (tool in setOf("clang++", "clang", "g++", "gcc")) issues += Issue.TOOLCHAIN
                else found += Library(tool, listOf(pkg), emptyList(), emptyList())
            }
        }
        if (Issue.TOOLCHAIN in issues) found += Library("полный тулчейн C++", TOOLCHAIN_PACKAGES, emptyList(), emptyList())
        if (text.contains("No space left on device", ignoreCase = true)) issues += Issue.DISK_FULL
        if (text.contains("Permission denied", ignoreCase = true) || text.contains("Read-only file system", ignoreCase = true) || text.contains("DPAI_PACKAGE_WRITE:")) {
            issues += if (text.contains("apk", ignoreCase = true) || text.contains("failed to extract") || text.contains("failed to write database") || text.contains("DPAI_PACKAGE_WRITE:")) Issue.PACKAGE_WRITE else Issue.EXECUTION_PERMISSION
        }
        if (found.isEmpty() && unknown.isEmpty() && issues.isEmpty()) issues += when {
            text.contains("undefined reference") || text.contains("undefined symbol") -> Issue.LINK_FLAGS
            text.contains("error:") -> Issue.COMPILER_ERROR
            else -> Issue.PROGRAM_EXIT
        }
        return Report(exitCode, found.toList(), unknown, issues.toList())
    }
    /** Шаблон предлагается отдельно; произвольная команда проекта не переписывается. */
    fun singleFileCommand(report: Report, previousCommand: String = ""): String {
        val previous = catalog.filter { library ->
            (library.flags + library.links.map { "-l$it" }).any { flag ->
                Regex("(?<!\\S)${Regex.escape(flag)}(?!\\S)").containsMatchIn(previousCommand)
            }
        }.flatMap { it.flags }
        val flags = (previous + report.flags).distinct().joinToString(" ")
        return CppToolchain.preflight + "\nDPAI_BIN=\$(mktemp /tmp/dpai-cpp.XXXXXX) || exit\n" +
            "trap 'rm -f \"\$DPAI_BIN\"' EXIT\n" +
            "clang++ \"\$DPAI_FILE\" -std=c++20 -fuse-ld=lld -o \"\$DPAI_BIN\"${if (flags.isEmpty()) "" else " $flags"} && \"\$DPAI_BIN\""
    }
    /** ANSI CSI/OSC убираются итеративно: чужой вывод ограничен по памяти и не вызывает рекурсивного regex. */
    internal fun plain(text: String): String = buildString {
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

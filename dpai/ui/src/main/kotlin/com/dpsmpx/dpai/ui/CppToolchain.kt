package com.dpsmpx.dpai.ui

/** Полный штатный тулчейн и проверка среды; защитные механизмы компилятора не отключаются (D-140). */
object CppToolchain {
    val preflight = """
        for DPAI_TOOL in clang++ ld.lld gcc g++; do
            command -v "${'$'}DPAI_TOOL" >/dev/null 2>&1 || { printf 'DPAI_CXX_TOOLCHAIN: %s not found\n' "${'$'}DPAI_TOOL"; exit 127; }
        done
        for DPAI_CRT in crtbeginS.o crtendS.o libgcc.a libgcc_s.so; do
            DPAI_PATH=${'$'}(clang++ -print-file-name="${'$'}DPAI_CRT")
            test -r "${'$'}DPAI_PATH" || { printf 'DPAI_CXX_TOOLCHAIN: %s not found\n' "${'$'}DPAI_CRT"; exit 127; }
        done
    """.trimIndent()
    /** Проба пишет только свои временные файлы; исходники и настройки проекта не затрагиваются. */
    val probeCommand: List<String> get() = listOf("/bin/sh", "-c", """
        for DPAI_DIR in /usr/bin /usr/lib /lib/apk/db; do
            DPAI_PROBE=${'$'}(mktemp "${'$'}DPAI_DIR/.dpai-write.XXXXXX") || { printf 'DPAI_PACKAGE_WRITE: %s\n' "${'$'}DPAI_DIR"; exit 73; }
            rm -f "${'$'}DPAI_PROBE" || exit 73
        done
        $preflight
        DPAI_CPP=${'$'}(mktemp /tmp/dpai-check.XXXXXX.cpp) || exit
        DPAI_BIN=${'$'}(mktemp /tmp/dpai-check.XXXXXX) || { rm -f "${'$'}DPAI_CPP"; exit 1; }
        trap 'rm -f "${'$'}DPAI_CPP" "${'$'}DPAI_BIN"' EXIT
        printf '#include <iostream>\n#include <cstdio>\nint main() { std::cout << "DPAI_CPP_OK" << std::endl; return 0; }\n' > "${'$'}DPAI_CPP" || exit
        clang++ -std=c++20 -fuse-ld=lld "${'$'}DPAI_CPP" -o "${'$'}DPAI_BIN" && "${'$'}DPAI_BIN"
    """.trimIndent())
}

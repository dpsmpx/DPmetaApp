package com.dpsmpx.dpai.ui

/** Каталог окружения определяется локально; путь исходника никогда не подставляется в shell-код (D-141). */
object ProjectRuntime {
    val context = """
        DPAI_PROJECT=${'$'}(dirname "${'$'}DPAI_FILE")
        DPAI_SCAN="${'$'}DPAI_PROJECT"
        while :; do
            if test -e "${'$'}DPAI_SCAN/.git" || test -f "${'$'}DPAI_SCAN/pyproject.toml" || test -f "${'$'}DPAI_SCAN/requirements.txt" || test -f "${'$'}DPAI_SCAN/settings.gradle" || test -f "${'$'}DPAI_SCAN/settings.gradle.kts" || test -f "${'$'}DPAI_SCAN/build.gradle" || test -f "${'$'}DPAI_SCAN/build.gradle.kts"; then
                DPAI_PROJECT="${'$'}DPAI_SCAN"; break
            fi
            test "${'$'}DPAI_SCAN" = "${'$'}DPAI_ROOT" && break
            test "${'$'}DPAI_SCAN" = / && break
            DPAI_SCAN=${'$'}(dirname "${'$'}DPAI_SCAN")
        done
        DPAI_KEY=${'$'}(printf '%s' "${'$'}DPAI_PROJECT" | sha256sum); DPAI_KEY=${'$'}{DPAI_KEY%% *}
        DPAI_STUDIO_HOME=${'$'}{DPAI_STUDIO_HOME:-/root/.local/share/dpai}
        DPAI_PROJECT_HOME="${'$'}DPAI_STUDIO_HOME/projects/${'$'}DPAI_KEY"
    """.trimIndent()
    fun args(root: String, file: String, script: String, extra: List<String> = emptyList()): List<String> =
        listOf("/bin/sh", "-c", "DPAI_ROOT=\"\$1\"; DPAI_FILE=\"\$2\"; shift 2\n$script", "dpai-runtime", root, file) + extra
    fun validPaths(root: String, file: String): Boolean = listOf(root, file).all { it.startsWith('/') && it.none { c -> c.code < 32 } }
}

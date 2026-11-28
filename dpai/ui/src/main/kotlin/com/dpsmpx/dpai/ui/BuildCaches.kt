package com.dpsmpx.dpai.ui

/**
 * Кэши гостя общие для терминала, IDE, ИИ и пульта (DPmetaApp:D-193).
 * Старый Bionic home учитывается без переноса файлов активного Gradle.
 * Явные значения человека сильнее; каталоги не находятся в дереве проекта.
 */
object BuildCaches {
    const val GRADLE = "/root/.gradle"
    const val LEGACY_GRADLE = "${AndroidNativeJdk.HOME}/.gradle"

    fun environment(base: Map<String, String>, directory: (String) -> Boolean): Map<String, String> {
        val gradle = if (directory("$LEGACY_GRADLE/wrapper/dists") || directory("$LEGACY_GRADLE/caches")) LEGACY_GRADLE else GRADLE
        val cache = base["XDG_CACHE_HOME"] ?: "/root/.cache"
        val npm = base["npm_config_cache"] ?: base["NPM_CONFIG_CACHE"] ?: "/root/.npm"
        return mapOf(
            "GRADLE_USER_HOME" to gradle,
            "XDG_CACHE_HOME" to cache,
            "PIP_CACHE_DIR" to "$cache/pip",
            "npm_config_cache" to npm,
        ) + base
    }
}

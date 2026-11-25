package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.Redactor

/** Живые факты из ограниченного хвоста, без оценки времени или выдуманного числа задач. */
data class TaskProgress(val phase: Phase, val detail: String = "", val fraction: Float? = null, val current: Int? = null, val total: Int? = null) {
    enum class Phase { DOWNLOAD, VERIFY, CACHE, INSTALLED, UNPACK, READY, WAITING, TASK, CONFIGURE, PACKAGES }

    companion object {
        private val managed = Regex("^DPAI_PROGRESS (download|verify|cache|installed|unpack|ready|waiting|packages) (.+)$")
        private val task = Regex("^> Task (:[^\\s]+)(?: (?:UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?$")
        private val packages = Regex("^\\(([0-9]+)/([0-9]+)\\) (.+)$")
        private val percent = Regex("([0-9]+(?:\\.[0-9]+)?)%")

        /** Только последние 16 КБ; штатные статусы Gradle — прогресс, не ошибка. */
        fun latest(output: String): TaskProgress? {
            var state: TaskProgress? = null
            for (raw in output.takeLast(16_000).splitToSequence('\n', '\r')) {
                val line = raw.trim()
                val marker = managed.matchEntire(line)
                val gradle = task.matchEntire(line)
                val apk = packages.matchEntire(line)
                state = when {
                    marker != null -> TaskProgress(when (marker.groupValues[1]) {
                        "download" -> Phase.DOWNLOAD
                        "verify" -> Phase.VERIFY
                        "cache" -> Phase.CACHE
                        "installed" -> Phase.INSTALLED
                        "unpack" -> Phase.UNPACK
                        "packages" -> Phase.PACKAGES
                        "ready" -> Phase.READY
                        else -> Phase.WAITING
                    }, Redactor.STANDARD.redact(marker.groupValues[2]).take(160))
                    line.startsWith("> Configure project ") || line.startsWith("Calculating task graph") || line.startsWith("Starting a Gradle Daemon") || line.contains("single-use Daemon") ->
                        TaskProgress(Phase.CONFIGURE, Redactor.STANDARD.redact(line.removePrefix("> Configure project ")).take(160))
                    gradle != null -> TaskProgress(Phase.TASK, Redactor.STANDARD.redact(gradle.groupValues[1]).take(160))
                    apk != null -> {
                        val n = apk.groupValues[1].toIntOrNull()
                        val all = apk.groupValues[2].toIntOrNull()
                        if (n != null && all != null && n in 1..all && all <= 1_000_000) TaskProgress(
                            Phase.PACKAGES, Redactor.STANDARD.redact(apk.groupValues[3]).take(160), n.toFloat() / all, n, all,
                        ) else state
                    }
                    line.startsWith("Downloading https://") || line == "Fetching distribution." -> TaskProgress(Phase.DOWNLOAD, "Gradle")
                    state?.phase in setOf(Phase.DOWNLOAD, Phase.PACKAGES) -> {
                        val value = percent.findAll(line).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it in 0f..100f }
                        if (value != null) state?.copy(fraction = value / 100f) else state
                    }
                    else -> state
                }
            }
            return state
        }
    }
}

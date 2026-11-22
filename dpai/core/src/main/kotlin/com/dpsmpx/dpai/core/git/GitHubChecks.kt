package com.dpsmpx.dpai.core.git

/** Единый смысл итогов GitHub REST и gh; неизвестное никогда не становится успешным. */
object GitHubChecks {
    enum class Verdict { PASSED, FAILED, PENDING, UNKNOWN }

    fun state(value: String?): Verdict = when (value) {
        "SUCCESS", "NEUTRAL", "SKIPPED" -> Verdict.PASSED
        "FAILURE", "ERROR", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE" -> Verdict.FAILED
        "PENDING", "QUEUED", "IN_PROGRESS", "WAITING", "REQUESTED", "EXPECTED" -> Verdict.PENDING
        else -> Verdict.UNKNOWN
    }

    fun checkRun(status: String?, conclusion: String?): Verdict =
        if (status != "completed") Verdict.PENDING else when (state(conclusion?.uppercase(java.util.Locale.ROOT))) {
            Verdict.PASSED -> Verdict.PASSED
            else -> Verdict.FAILED
        }

    fun statusContext(value: String?): Verdict = when (value) {
        "success" -> Verdict.PASSED
        "pending" -> Verdict.PENDING
        else -> Verdict.FAILED
    }
}

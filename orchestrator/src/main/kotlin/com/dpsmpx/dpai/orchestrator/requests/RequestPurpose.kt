package com.dpsmpx.dpai.orchestrator.requests

/**
 * Назначение запроса человеку (D-44) — закрытый список: что делает ответ,
 * решает назначение, и только код оркестратора создаёт запрос с ним.
 * Текст модели, репозитория или хука запроса не порождает и действия не
 * выбирает: в запросе он может быть разве что строкой текста, а вариант
 * ответа — один из заданных здесь.
 */
enum class RequestPurpose(val code: String) {
    /** Проверка канала (экран «Проверка запросов»): ответ только записывается. */
    PROBE("probe"),

    /** Задача `BLOCKED`: повторить, ответить текстом к следующему ходу, отменить. */
    TASK_BLOCKED("task.blocked"),

    /** Задача `COMPLETION_UNVERIFIED` (D-23): принять с исключением, отменить, решить позже. */
    TASK_UNVERIFIED("task.unverified"),

    /** Сверка после перезапуска оставила вопрос (`docs/RECOVERY.md`, §5): принять к сведению. */
    RECOVERY("recovery.question"),
    ;

    companion object {
        fun of(code: String): RequestPurpose? = entries.firstOrNull { it.code == code }
    }
}

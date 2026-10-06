package com.dpsmpx.dpai.core.events

/**
 * Вид события (D-12; ТЗ, §13). Имя — публичный контракт: по нему читаются
 * сохранённые события. Типы только добавляются, строкой в
 * `tools/verify/registry/event_types.txt` (`tools/verify/check_contracts.py`).
 */
enum class EventType {
    // --- задачи ---
    TASK_CREATED,
    TASK_QUEUED,
    TASK_ASSIGNED,
    TASK_STATE_CHANGED,

    // --- сессии ---
    SESSION_STARTED,
    SESSION_STATE_CHANGED,
    SESSION_HEARTBEAT,
    SESSION_OUTPUT,
    SESSION_TOOL_STARTED,
    SESSION_TOOL_COMPLETED,
    SESSION_NEAR_LIMIT,
    SESSION_PAUSED,
    SESSION_BLOCKED,
    SESSION_FAILED,
    SESSION_COMPLETED,

    // --- проверка ---
    VERIFICATION_STARTED,
    VERIFICATION_PASSED,
    VERIFICATION_FAILED,

    // --- handoff и продолжение ---
    HANDOFF_CREATED,
    CONTINUATION_SCHEDULED,
    CONTINUATION_STARTED,

    // --- аккаунты и targets ---
    ACCOUNT_STATE_CHANGED,
    ACCOUNT_RATE_LIMITED,
    ACCOUNT_AUTH_REQUIRED,
    EXECUTION_TARGET_OFFLINE,

    // --- Git ---
    GIT_DIRTY,
    GIT_COMMIT_CREATED,
    PULL_REQUEST_CREATED,
    MERGE_CONFLICT_DETECTED,
}

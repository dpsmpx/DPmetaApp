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
    TASK_DEPENDENCIES_CHANGED,
    TASK_PRIORITY_CHANGED,
    TASK_ATTEMPT_FAILED,
    TASK_CONTINUED,

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
    SESSION_CREATED,
    SESSION_TURN_STARTED,
    SESSION_TURN_FINISHED,
    /** Как собран запрос хода: оценка размера, режим, маршрут модели (D-40). */
    SESSION_PROMPT_PREPARED,
    /** Расход хода со слов провайдера (D-40). */
    SESSION_TURN_USAGE,
    /** Инструменты хода — замер оркестратора по потоку: вызовы, вывод, повторы (D-41). */
    SESSION_TURN_TOOLS,

    // --- проверка ---
    VERIFICATION_STARTED,
    VERIFICATION_PASSED,
    VERIFICATION_FAILED,
    TASK_VERIFICATION_RECORDED,

    // --- handoff и продолжение ---
    HANDOFF_CREATED,
    CONTINUATION_SCHEDULED,
    CONTINUATION_STARTED,

    // --- аккаунты и targets ---
    ACCOUNT_STATE_CHANGED,
    ACCOUNT_RATE_LIMITED,
    ACCOUNT_AUTH_REQUIRED,
    EXECUTION_TARGET_OFFLINE,
    ACCOUNT_SUBSCRIPTION_REGISTERED,
    ACCOUNT_SLOT_REGISTERED,
    ACCOUNT_LOGIN_CHANGED,
    ACCOUNT_IDENTITY_CONFIRMED,
    ACCOUNT_OVERLAP_DETECTED,
    ACCOUNT_NEAR_LIMIT,
    ACCOUNT_LIMIT_RESET,
    ACCOUNT_LOGIN_EXPIRING,
    ACCOUNT_ERROR_RECORDED,
    ACCOUNT_ERRORS_CLEARED,
    ACCOUNT_SLOT_DISABLED,
    ACCOUNT_SLOT_ENABLED,
    ACCOUNT_SESSION_LEASED,
    ACCOUNT_SESSION_RELEASED,
    ACCOUNT_SWITCHED,

    // --- execution target и процессы на нём (этап 3) ---
    EXECUTION_TARGET_REGISTERED,
    EXECUTION_TARGET_STATE_CHANGED,
    TARGET_PROCESS_STARTED,
    TARGET_PROCESS_OUTPUT,
    TARGET_PROCESS_HEARTBEAT,
    TARGET_PROCESS_EXITED,

    // --- Git ---
    REPOSITORY_REGISTERED,
    WORKTREE_CREATED,
    WORKTREE_CLAIMED,
    WORKTREE_RELEASED,
    WORKTREE_REMOVED,
    GIT_DIRTY,
    GIT_COMMIT_CREATED,
    PULL_REQUEST_CREATED,
    MERGE_CONFLICT_DETECTED,
    TASK_SCOPE_CHANGED,
    TASK_MERGE_REQUESTED,
    TASK_MERGE_DECLINED,
    TASK_MERGED,

    // --- запросы человеку (D-44) ---
    USER_REQUEST_CREATED,
    USER_REQUEST_DELIVERED,
    USER_REQUEST_ANSWERED,
    USER_REQUEST_EXPIRED,
    USER_REQUEST_CANCELLED,
    USER_REQUEST_FAILED,

    // --- команды оркестратора (D-42) ---
    OPERATION_RECORDED,
    TASK_POLICY_CHANGED,
    TASK_HOLD_CHANGED,
    TASK_COUNTERS_RESET,

    // --- загрузка окон подписки и порог (D-42, D-45) ---
    ACCOUNT_USAGE_OBSERVED,
    ACCOUNT_NEAR_THRESHOLD_SET,

    // --- настройки контура: targets и предел сессий (D-42, п. 5, 6) ---
    CONTROL_TARGET_ENABLED_SET,
    CONTROL_SESSION_LIMIT_SET,

    // --- архив задач (D-42) ---
    TASK_ARCHIVE_CHANGED,

    // --- передача задач между подписками при пределе (D-56) ---
    ACCOUNT_ROTATION_SET,

    // --- ревью задачи (D-73) ---
    TASK_REVIEW_RECORDED,

    // --- перенос задачи на другой слот (D-77) ---
    TASK_SLOT_EXCLUDED,
}

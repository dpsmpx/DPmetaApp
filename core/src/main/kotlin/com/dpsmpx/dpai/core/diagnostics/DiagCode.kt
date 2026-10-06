package com.dpsmpx.dpai.core.diagnostics

/** Серьёзность находки. `ERROR` делает результат отказом (D-06). */
enum class Severity { INFO, WARNING, ERROR }

/**
 * Категория — «что сломалось» крупно: по ней группируются уведомления и
 * сводки. Подробность — у [DiagCode].
 */
enum class Category {
    INPUT,
    FORMAT,
    STATE,
    INTEGRITY,
    EXECUTION,
    NETWORK,
    STORAGE,
    UNSUPPORTED,
    INTERNAL,
}

/**
 * Класс повтора (ТЗ, §21): по нему политика повтора решает, повторять ли
 * отказ. Слепого повтора нет: `NON_RETRYABLE` не повторяется, `UNKNOWN` —
 * по политике и не больше одного раза (`docs/SCHEDULER.md`, §5).
 */
enum class Retryability { RETRYABLE, NON_RETRYABLE, UNKNOWN }

/**
 * Стабильный код диагностики (D-05).
 *
 * Имя кода — публичный контракт: по нему пишутся ожидания тестов и корпуса,
 * по нему читаются сохранённые события. Коды не переименовываются и не
 * удаляются — только добавляются, строкой в `tools/verify/registry/diag_codes.txt`
 * (`tools/verify/check_contracts.py`).
 */
enum class DiagCode(val category: Category, val defaultSeverity: Severity, val retry: Retryability) {
    // --- JSON (D-09) ---
    JSON_EMPTY(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    JSON_TOO_LARGE(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    JSON_TOO_DEEP(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    JSON_SYNTAX(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    JSON_DUPLICATE_KEY(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    JSON_NUMBER_OUT_OF_RANGE(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- поля документа ---
    FIELD_MISSING(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    FIELD_TYPE(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    FIELD_UNKNOWN(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    VALUE_INVALID(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- формат и версии (D-09) ---
    FORMAT_MISMATCH(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    FORMAT_VERSION_MISSING(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    FORMAT_VERSION_NEWER(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    FORMAT_VERSION_UNSUPPORTED(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- идентификаторы (D-08) ---
    ID_INVALID(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    ID_WRONG_KIND(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- хеши (D-10) ---
    HASH_INVALID(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    HASH_MISMATCH(Category.INTEGRITY, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- автоматы состояний (D-11) ---
    INVALID_TRANSITION(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    EXPLICIT_OPERATION_REQUIRED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- события (D-12) ---
    EVENT_DUPLICATE(Category.INTEGRITY, Severity.INFO, Retryability.NON_RETRYABLE),
    EVENT_SEQUENCE_GAP(Category.INTEGRITY, Severity.WARNING, Retryability.RETRYABLE),
    EVENT_SEQUENCE_CONFLICT(Category.INTEGRITY, Severity.ERROR, Retryability.NON_RETRYABLE),
    EVENT_TYPE_UNKNOWN(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- исполнение: цепочка причин ТЗ, §5.1 ---
    SESSION_FAILED(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    EXECUTION_HOST_UNAVAILABLE(Category.EXECUTION, Severity.ERROR, Retryability.RETRYABLE),
    SSH_CONNECTION_FAILED(Category.NETWORK, Severity.ERROR, Retryability.RETRYABLE),
    NETWORK_UNREACHABLE(Category.NETWORK, Severity.ERROR, Retryability.RETRYABLE),

    // --- хранилище (D-24, D-25) ---
    STORAGE_IO_FAILED(Category.STORAGE, Severity.ERROR, Retryability.RETRYABLE),
    STORAGE_UNAVAILABLE(Category.STORAGE, Severity.ERROR, Retryability.NON_RETRYABLE),
    STORAGE_CORRUPTED(Category.INTEGRITY, Severity.ERROR, Retryability.NON_RETRYABLE),
    STORAGE_RECOVERED_LKG(Category.INTEGRITY, Severity.WARNING, Retryability.NON_RETRYABLE),
    JOURNAL_TAIL_TRUNCATED(Category.INTEGRITY, Severity.WARNING, Retryability.NON_RETRYABLE),
    JOURNAL_CORRUPTED(Category.INTEGRITY, Severity.ERROR, Retryability.NON_RETRYABLE),
    SNAPSHOT_INVALID(Category.INTEGRITY, Severity.WARNING, Retryability.NON_RETRYABLE),
    MIGRATION_APPLIED(Category.FORMAT, Severity.INFO, Retryability.NON_RETRYABLE),
    MIGRATION_FAILED(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    BACKUP_INVALID(Category.INTEGRITY, Severity.ERROR, Retryability.NON_RETRYABLE),
    RESTORE_RESUMED(Category.INTEGRITY, Severity.WARNING, Retryability.NON_RETRYABLE),

    // --- аккаунты и подписки (D-29, D-30) ---
    ENTITY_NOT_FOUND(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    ACCOUNT_CONFIG_DIR_IN_USE(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    ACCOUNT_AUTH_METHOD_FORBIDDEN(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    ACCOUNT_IDENTITY_MISMATCH(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    ACCOUNT_DUPLICATE_SUBSCRIPTION(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    ACCOUNT_IDENTITY_UNKNOWN(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    ACCOUNT_ENVIRONMENT_SANITIZED(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    ACCOUNT_LEASE_CONFLICT(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    ACCOUNT_NONE_AVAILABLE(Category.STATE, Severity.ERROR, Retryability.RETRYABLE),
    ACCOUNT_SWITCH_LOOP(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- общее ---
    /** Строгая семантика (D-05): неподдержанное — явный отказ, а не «похожее». */
    UNSUPPORTED(Category.UNSUPPORTED, Severity.ERROR, Retryability.NON_RETRYABLE),
    INTERNAL_ERROR(Category.INTERNAL, Severity.ERROR, Retryability.UNKNOWN),
    ;
}

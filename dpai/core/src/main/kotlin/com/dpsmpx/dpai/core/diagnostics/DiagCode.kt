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

    // --- execution target и процессы (этап 3, D-26, D-31) ---
    PROCESS_START_FAILED(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    PROCESS_TIMEOUT(Category.EXECUTION, Severity.ERROR, Retryability.RETRYABLE),
    PROCESS_EXIT_NONZERO(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    PROCESS_ORPHANS_LEFT(Category.EXECUTION, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Аргумент процесса не выражается в кодировке локали: запуск исказил бы его (D-05). */
    PROCESS_ARGUMENT_UNENCODABLE(Category.EXECUTION, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Объект задания Windows для процессов хода не создан (A-9): фоновые потомки хода могли остаться. */
    PROCESS_JOB_UNAVAILABLE(Category.EXECUTION, Severity.WARNING, Retryability.NON_RETRYABLE),
    SSH_HOST_KEY_REJECTED(Category.NETWORK, Severity.ERROR, Retryability.NON_RETRYABLE),
    SSH_AUTH_FAILED(Category.NETWORK, Severity.ERROR, Retryability.NON_RETRYABLE),
    AGENT_REPLY_INVALID(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    AGENT_RUN_LOST(Category.EXECUTION, Severity.ERROR, Retryability.NON_RETRYABLE),
    RUN_RECONCILIATION_REQUIRED(Category.INTEGRITY, Severity.ERROR, Retryability.RETRYABLE),
    /**
     * Остановка хода запрошена до того, как супервизор взял ход (A-4): процесс
     * ещё не запущен и не запустится — супервизор увидит просьбу и запишет выход.
     */
    AGENT_STOP_PENDING(Category.EXECUTION, Severity.WARNING, Retryability.RETRYABLE),
    TARGET_CHECK_FAILED(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    TARGET_CHECK_WARNING(Category.EXECUTION, Severity.WARNING, Retryability.NON_RETRYABLE),
    TARGET_DISABLED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- Claude Code CLI (этап 4, D-32) ---
    CLAUDE_AUTH_REQUIRED(Category.EXECUTION, Severity.ERROR, Retryability.NON_RETRYABLE),
    CLAUDE_RATE_LIMITED(Category.EXECUTION, Severity.ERROR, Retryability.RETRYABLE),
    CLAUDE_API_ERROR(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    CLAUDE_RESULT_ERROR(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    CLAUDE_BUDGET_EXCEEDED(Category.EXECUTION, Severity.ERROR, Retryability.NON_RETRYABLE),
    CLAUDE_MAX_TURNS(Category.EXECUTION, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Контекст не влез в окно модели (D-40): продолжение — с меньшим, а не повтор того же. */
    CLAUDE_CONTEXT_OVERFLOW(Category.EXECUTION, Severity.ERROR, Retryability.RETRYABLE),
    /** Claude Code target не знает флаг хода: повтор — без него (D-40). */
    CLAUDE_FLAG_UNSUPPORTED(Category.EXECUTION, Severity.ERROR, Retryability.RETRYABLE),
    CLAUDE_STREAM_MALFORMED(Category.FORMAT, Severity.WARNING, Retryability.NON_RETRYABLE),
    CLAUDE_STREAM_INCOMPLETE(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    CLAUDE_SESSION_MISMATCH(Category.INTEGRITY, Severity.ERROR, Retryability.NON_RETRYABLE),
    CLAUDE_EVENT_UNKNOWN(Category.FORMAT, Severity.INFO, Retryability.NON_RETRYABLE),
    RUNNER_TURN_ACTIVE(Category.STATE, Severity.ERROR, Retryability.RETRYABLE),

    // --- сессии (этап 6) ---
    SESSION_BINDING_MISMATCH(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    SESSION_ALREADY_EXISTS(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),
    SESSION_HANDED_OFF(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),

    // --- задачи и планировщик (этап 7) ---
    DEPENDENCY_CYCLE(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    DEPENDENCY_FAILED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    LOOP_DETECTED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    RETRY_LIMIT_REACHED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    CONTINUATION_LIMIT_REACHED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- контекст и handoff (этап 8) ---
    CONTEXT_PROJECT_BOUNDARY(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    CONTEXT_SECRET_EXCLUDED(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),
    CONTEXT_SECRET_INCLUDED(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    CONTEXT_TRUNCATED(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    HANDOFF_ALREADY_EXISTS(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),

    // --- экономия контекста и токенов (D-40) ---
    /** Критичный источник (правила, задача, handoff, итог проверки) не влез в бюджет: сборка — отказ, а не обрезка. */
    CONTEXT_BUDGET_EXCEEDED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Тот же текст под вторым ключом — включён один раз, второй ключ ссылается на первый. */
    CONTEXT_DEDUPLICATED(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),
    /** Режим бюджета удешевил маршрут задачи (только классы, где это допустимо). */
    MODEL_DOWNGRADED_BY_BUDGET(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    /** Та же цель уже стоит в очереди проекта: вторая задача не создаётся. */
    TASK_DUPLICATE(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- одобрения, Git и рабочие деревья (этап 9) ---
    APPROVAL_REQUIRED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    APPROVAL_INVALID(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    WORKTREE_OWNED(Category.STATE, Severity.ERROR, Retryability.RETRYABLE),
    WORKTREE_NOT_OWNED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    WORKTREE_PATH_IN_USE(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    BRANCH_IN_USE(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    GIT_COMMAND_FAILED(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    GIT_OUTPUT_INVALID(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    FILE_OVERLAP_SERIALIZE(Category.STATE, Severity.WARNING, Retryability.RETRYABLE),
    FILE_OVERLAP_REJECTED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- проверка (этап 10) ---
    VERIFICATION_PROFILE_MISSING(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    VERIFICATION_STEP_UNAVAILABLE(Category.EXECUTION, Severity.ERROR, Retryability.UNKNOWN),
    VERIFICATION_FAILED(Category.EXECUTION, Severity.ERROR, Retryability.NON_RETRYABLE),
    VERIFICATION_BASELINE_FAILURE(Category.EXECUTION, Severity.WARNING, Retryability.NON_RETRYABLE),
    VERIFICATION_REQUIRED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    VERIFICATION_WAIVED(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),

    // --- ревью задачи (D-73) ---
    /** Ревью нашло блокирующее: задача — на повтор с выжимкой находок. */
    REVIEW_BLOCKING(Category.EXECUTION, Severity.WARNING, Retryability.RETRYABLE),
    /** Ревью не дало итога: сбой сессии, ответ не по схеме, нет слота. */
    REVIEW_FAILED(Category.EXECUTION, Severity.ERROR, Retryability.RETRYABLE),
    /** `DONE` ждёт ревью, назначенных политикой задачи. */
    REVIEW_REQUIRED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Ревью роли принято без итога — решением человека. */
    REVIEW_WAIVED(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),

    // --- восстановление (этап 13) ---
    STATE_CONFLICT(Category.INTEGRITY, Severity.WARNING, Retryability.RETRYABLE),
    RECOVERY_RESTARTED(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),
    /** Запуск хода не подтверждён: сбой до записи о нём (A-42) или ответ агента потерян (A-12, A-55); target мог его и не получить. */
    RECOVERY_START_UNCONFIRMED(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),
    ORPHAN_FOUND(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    WORKTREE_MISSING(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    MERGE_CONFLICT(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Рабочая копия не готова к слиянию (A-68, A-54): выгружена не ветка-цель или есть изменения. */
    MERGE_TARGET_NOT_READY(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- экосистема и внешние сервисы (этапы 15, 16) ---
    ECOSYSTEM_DISABLED(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),
    ECOSYSTEM_UNAVAILABLE(Category.EXECUTION, Severity.ERROR, Retryability.RETRYABLE),
    CONNECTOR_REPLY_INVALID(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    OPERATION_NOT_ALLOWED(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    RUN_BUDGET_EXHAUSTED(Category.STATE, Severity.WARNING, Retryability.NON_RETRYABLE),
    CLAUDE_AUTH_STATUS_INVALID(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),

    /** Ответ `/usage` (D-48) без загрузки окон: не JSON, нет `usage_report` — версия Claude Code другая. */
    CLAUDE_USAGE_REPORT_INVALID(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Нарушен машинно проверяемый инвариант (ТЗ, §118): ошибка ядра, а не входа. */
    INVARIANT_VIOLATED(Category.INTEGRITY, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- запросы человеку (D-44) ---
    /** Запрос уже не ждёт ответа: отвечен иначе, истёк, снят или неизвестен. */
    REQUEST_NOT_PENDING(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Ответ не подходит запросу: чужой вариант, другой хеш, нет обязательного текста. */
    REQUEST_ANSWER_INVALID(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Задачу или сессию остановил человек (ответ на запрос, команда D-42): не сбой и не повод для повтора. */
    CANCELLED_BY_USER(Category.STATE, Severity.INFO, Retryability.NON_RETRYABLE),
    /**
     * Команда (D-42) принята в очередь такта, но итога ещё нет — контур
     * занят вызовом target. Не отказ: экран не меняет ключ нажатия и не
     * бросает следующие команды (ревью 18 октября 2026).
     */
    COMMAND_QUEUED(Category.STATE, Severity.WARNING, Retryability.RETRYABLE),

    // --- форматы экосистемы для DPmetaApp (DPmetaApp:docs/direction/TECHNICAL_TASK.md, §10) ---
    /** Файл чужого формата (TOML, реестр, журнал решений) не разобран: место и причина, а не догадка. */
    FORMAT_UNRECOGNIZED(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Реестр DPmeta другой схемы или с неизвестным ключом (F-01): работа — на последнем распознанном. */
    REGISTRY_SCHEMA_UNSUPPORTED(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- сеть и GitHub для DPmetaApp (DPmetaApp:docs/direction/TECHNICAL_TASK.md, §6.8, §6.9) ---
    /** Адрес не из списка разрешённых `:core:net` (DPmetaApp:D-10): запрос не уходит. */
    HOST_NOT_ALLOWED(Category.NETWORK, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** GitHub не принял токен или у токена нет прав (401, 403): нужен новый вход (DPmetaApp:D-17). */
    GITHUB_AUTH_EXPIRED(Category.NETWORK, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Лимит запросов GitHub исчерпан (403 с нулевым остатком, 429): остальное — после сброса лимита. */
    GITHUB_RATE_LIMITED(Category.NETWORK, Severity.ERROR, Retryability.RETRYABLE),
    /** Репозиторий или файл не найден — или скрыт от токена (404 GitHub). */
    GITHUB_NOT_FOUND(Category.NETWORK, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Ключ Keystore ждёт входа пользователя (DPmetaApp:D-59, T-01): секрет откроется после PIN или биометрии. */
    DEVICE_AUTH_REQUIRED(Category.STATE, Severity.ERROR, Retryability.RETRYABLE),

    // --- провайдеры по ключу: API-профили (D-98) ---
    /** Провайдер не принял ключ API-профиля (401, 403): ключ неверен, отозван или без прав. */
    PROVIDER_AUTH_FAILED(Category.NETWORK, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Предел запросов или расхода у провайдера (429): повтор — позже. */
    PROVIDER_RATE_LIMITED(Category.NETWORK, Severity.ERROR, Retryability.RETRYABLE),
    /** Провайдер перегружен или сбоит (5xx, 529, ошибка в потоке). */
    PROVIDER_UNAVAILABLE(Category.NETWORK, Severity.ERROR, Retryability.RETRYABLE),
    /** Провайдер отверг запрос (400, 404 и прочие 4xx): модель, параметры, длина. */
    PROVIDER_REQUEST_REJECTED(Category.INPUT, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Ответ провайдера не по формату или длиннее предела: не догадка, а отказ (D-87). */
    PROVIDER_REPLY_INVALID(Category.FORMAT, Severity.ERROR, Retryability.UNKNOWN),
    /** У API-профиля нет ключа или он не расшифровывается (новая установка, сброс Keystore). */
    PROVIDER_KEY_MISSING(Category.STATE, Severity.ERROR, Retryability.NON_RETRYABLE),

    // --- маршруты Claude Code (D-116; ТЗ DPmetaApp, F-18, F-20) ---
    /** Сервис не принял токен маршрута `/fire` (401, 403): токен неверен или отозван — нужен новый. */
    ROUTINE_TOKEN_INVALID(Category.NETWORK, Severity.ERROR, Retryability.NON_RETRYABLE),
    /** Предел запусков маршрута (429): 30 в час на маршрут, 100 — на аккаунт; повтор — позже или другим маршрутом. */
    ROUTINE_LIMIT_REACHED(Category.NETWORK, Severity.ERROR, Retryability.RETRYABLE),

    // --- уведомления пульта DPmetaApp (ТЗ DPmetaApp, §6.9, F-51, F-52; DPmetaApp:D-85) ---
    /** Событие неизвестного каталогу типа или правило тревоги с неизвестным типом, полем, неверным `regex`. */
    RULE_INVALID(Category.FORMAT, Severity.ERROR, Retryability.NON_RETRYABLE),

    /** Запечатанный сегмент журнала пропал или испорчен — восстановлен из копии (D-95). */
    JOURNAL_SEGMENT_RESTORED(Category.INTEGRITY, Severity.WARNING, Retryability.NON_RETRYABLE),

    // --- общее ---
    /** Строгая семантика (D-05): неподдержанное — явный отказ, а не «похожее». */
    UNSUPPORTED(Category.UNSUPPORTED, Severity.ERROR, Retryability.NON_RETRYABLE),
    INTERNAL_ERROR(Category.INTERNAL, Severity.ERROR, Retryability.UNKNOWN),
    ;
}

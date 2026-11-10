package com.dpsmpx.dpai.core.text

import kotlin.time.Duration

/**
 * Словарь «идентификатор → слово для человека» (D-88; D-14: интерфейс
 * по-русски, идентификаторы — латиницей).
 *
 * Идентификаторы (`TaskState`, `DiagCode`, `Priority`, …) — публичный
 * контракт: по ним пишутся журнал, сохранённые события и ожидания
 * тестов, и переименовать их нельзя (D-05, D-12). Но человеку на экране
 * нужно слово, а не `COMPLETION_UNVERIFIED`. Раньше состояния и причины
 * показывались как `state.name`, и это нарушало D-14 в самых частых
 * подписях (аудит 30 октября 2026, удобство U-2, U-3).
 *
 * Неизвестный идентификатор показывается как есть, а не теряется: новый
 * код без слова в словаре виден в интерфейсе, и это повод его добавить
 * (D-13: неизвестное — не ноль).
 *
 * Живёт в ядре (D-89): тексты для человека собирают и экраны (`ui`), и
 * оркестратор (запросы, уведомления, чат), и команда `dpai` (`cli`).
 * Словарь — на строках идентификаторов, поэтому ни от чего не зависит.
 */
object Words {
    /** Состояние задачи — короткой строкой в карточке и листе. */
    fun task(state: String): String = when (state) {
        "BACKLOG" -> "в бэклоге"
        "QUEUED" -> "в очереди"
        "READY" -> "готова к запуску"
        "RUNNING" -> "в работе"
        "WAITING" -> "ждёт"
        "BLOCKED" -> "заблокирована"
        "IMPLEMENTED" -> "сделана, ждёт проверки"
        "VERIFYING" -> "проверяется"
        "VERIFIED" -> "проверена"
        "COMPLETION_UNVERIFIED" -> "без проверки: нужна ручная проверка"
        "DONE" -> "выполнена"
        "FAILED" -> "провалена"
        "CANCELLED" -> "отменена"
        else -> state
    }

    /** Состояние сессии. */
    fun session(state: String): String = when (state) {
        "CREATED" -> "создана"
        "QUEUED" -> "в очереди"
        "STARTING" -> "запускается"
        "RUNNING" -> "идёт"
        "WAITING" -> "ждёт ответа"
        "PAUSED" -> "пауза"
        "BLOCKED" -> "заблокирована"
        "NEAR_LIMIT" -> "у предела подписки"
        "VERIFYING" -> "проверяется"
        "RECOVERY_PENDING" -> "ждёт сверки после перезапуска"
        "COMPLETED" -> "завершена"
        "FAILED" -> "провалена"
        "CANCELLED" -> "отменена"
        else -> state
    }

    /** Состояние слота аккаунта. */
    fun account(state: String): String = when (state) {
        "AUTH_REQUIRED" -> "нужен вход"
        "AVAILABLE" -> "доступен"
        "BUSY" -> "занят"
        "COOLDOWN" -> "перерыв после серии ошибок"
        "RATE_LIMITED" -> "предел подписки"
        "ERROR" -> "ошибка"
        "DISABLED" -> "выключен"
        else -> state
    }

    /** Вид строки сессии на экране: состояние сессии и «молчит» (`STALE`, D-13). */
    fun display(state: String): String = when (state) {
        "STALE" -> "молчит — давно нет событий"
        else -> session(state)
    }

    /** Здоровье системы на сводке (ТЗ, §79). */
    fun health(h: String): String = when (h) {
        "HEALTHY" -> "всё в порядке"
        "DEGRADED" -> "работает с ограничениями"
        "RECOVERY" -> "восстанавливается после перезапуска"
        "BLOCKED" -> "остановлено — нужен человек"
        "STORAGE_ERROR" -> "сбой хранилища"
        "EXECUTION_UNAVAILABLE" -> "исполнители недоступны"
        "SECURITY_LOCKDOWN" -> "заблокировано по безопасности"
        else -> h
    }

    /** Итог хода Claude. */
    fun turn(status: String): String = when (status) {
        "COMPLETED" -> "завершён"
        "FAILED" -> "сорвался"
        "INCOMPLETE" -> "не завершён"
        else -> status
    }

    /** Строгость находки ревью (D-73). */
    fun severity(s: String): String = when (s) {
        "BLOCKING" -> "блокирует"
        "MAJOR" -> "серьёзное"
        "MINOR" -> "мелкое"
        "NOTE" -> "заметка"
        else -> s
    }

    /** Вид target: где идёт работа. */
    fun targetType(t: String): String = when (t) {
        "LOCAL" -> "этот компьютер"
        "TERMUX_LOCAL" -> "Termux на телефоне"
        "LINUX_SSH" -> "Linux по SSH"
        "WINDOWS_REMOTE" -> "Windows по SSH"
        "MOCK" -> "проверочный"
        "IN_APP" -> "Linux в приложении"
        else -> t
    }

    /** Итог команды (D-06) — для вывода `dpai`. */
    fun operation(status: String): String = when (status) {
        "SUCCEEDED" -> "принято"
        "SUCCEEDED_WITH_WARNINGS" -> "принято с замечаниями"
        "FAILED" -> "не принято"
        else -> status
    }

    /**
     * Длительность по-русски: «меньше минуты», «3 мин», «2 ч 5 мин»,
     * «3 дн 4 ч» — вместо `Duration.toString()` («1m», «2h 5m»). Одна на
     * всё приложение: сброс окна подписки, пауза слота, повтор, срок запроса.
     */
    /** Уровень источника контекста (окно «Контекст», ТЗ, §74; A-15). */
    fun sourceLevel(level: String): String = when (level) {
        "GLOBAL" -> "общее"
        "PROJECT" -> "проект"
        "REPOSITORY" -> "репозиторий"
        "TASK" -> "задача"
        "DOCUMENT" -> "документ"
        "CODE" -> "код"
        "HANDOFF" -> "передача"
        "TOOL" -> "итог инструмента"
        else -> level
    }

    /** Секретность источника (A-15). */
    fun sensitivity(s: String): String = when (s) {
        "PUBLIC" -> "открытое"
        "INTERNAL" -> "внутреннее"
        "SENSITIVE" -> "чувствительное"
        "SECRET" -> "секрет"
        else -> s
    }

    /** Почему передан контекст следующей сессии (handoff, A-15). */
    fun handoff(reason: String): String = when (reason) {
        "NEAR_LIMIT" -> "у предела подписки"
        "MANUAL" -> "по запросу человека"
        "TARGET_FAILED" -> "target недоступен"
        "ACCOUNT_EXHAUSTED" -> "лимит слота исчерпан"
        "APP_STOPPED" -> "приложение остановлено"
        "PAUSED" -> "пауза"
        "TURN_FINISHED" -> "ход закончен"
        "VERIFICATION_FAILED" -> "проверка не прошла"
        "CONTEXT_OVERFLOW" -> "контекст переполнен"
        else -> reason
    }

    /**
     * Сбой словами для экрана и `dpai` (аудит удобства 1 ноября 2026, A-17):
     * «нет соединения», а не «ConnectException: null». Имя класса — только в
     * журнале приложения, где его читают при разборе.
     */
    fun failure(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() && it != "null" }
        val what = when (e.javaClass.simpleName) {
            "ConnectException" -> "нет соединения: на том конце никто не слушает"
            "SocketTimeoutException", "HttpTimeoutException", "TimeoutException" -> "истекло время ожидания"
            "UnknownHostException" -> "имя хоста не найдено"
            "NoRouteToHostException" -> "нет пути до хоста (сеть или VPN)"
            "FileNotFoundException", "NoSuchFileException" -> "файл не найден"
            "AccessDeniedException", "SecurityException" -> "нет доступа"
            "BindException" -> "порт уже занят"
            "OutOfMemoryError" -> "не хватило памяти"
            else -> return msg ?: "непредвиденный сбой (подробности — в журнале приложения)"
        }
        return if (msg == null) what else "$what ($msg)"
    }

    fun duration(d: Duration): String {
        val m = d.inWholeMinutes.coerceAtLeast(0)
        return when {
            m < 1 -> "меньше минуты"
            m < 60 -> "$m мин"
            m < 24 * 60 -> "${m / 60} ч" + (m % 60).let { if (it > 0) " $it мин" else "" }
            else -> "${m / (24 * 60)} дн" + (m % (24 * 60) / 60).let { if (it > 0) " $it ч" else "" }
        }
    }

    fun priority(p: String): String = when (p) {
        "CRITICAL" -> "критичный"
        "HIGH" -> "высокий"
        "NORMAL" -> "обычный"
        "LOW" -> "низкий"
        "BACKGROUND" -> "фоновый"
        else -> p
    }

    fun taskClass(c: String): String = when (c) {
        "TRIVIAL" -> "простая"
        "CLASSIFICATION" -> "классификация"
        "EXTRACTION" -> "извлечение данных"
        "SUMMARIZATION" -> "сводка"
        "REPOSITORY_RECON" -> "разбор репозитория"
        "NORMAL_ENGINEERING" -> "обычная разработка"
        "COMPLEX_DEBUGGING" -> "сложная отладка"
        "ARCHITECTURE" -> "архитектура"
        "CRITICAL_REVIEW" -> "критическое ревью"
        else -> c
    }

    /**
     * Причина отказа или предупреждения — «что случилось» словами (U-2):
     * человеку нужен смысл и что делать, а не `CLAUDE_RATE_LIMITED`.
     * Подробности (`Diagnostic.message`) остаются рядом и никуда не деваются.
     */
    fun reason(code: String): String = when (code) {
        "CLAUDE_RATE_LIMITED" -> "предел подписки Claude исчерпан — ждите сброса окна или слота с запасом"
        "CLAUDE_AUTH_REQUIRED" -> "Claude просит войти заново в каталоге слота"
        "CLAUDE_FLAG_UNSUPPORTED" -> "Claude Code на target не знает части ключей — ход идёт без них"
        "SSH_CONNECTION_FAILED" -> "нет связи с target по SSH"
        "SSH_AUTH_FAILED" -> "target не принял ключ приложения — добавьте его в authorized_keys («Инструкция»)"
        "SSH_HOST_KEY_UNKNOWN" -> "ключ хоста не подтверждён"
        "SSH_HOST_KEY_CHANGED" -> "ключ хоста изменился — вход остановлен"
        "TARGET_OFFLINE" -> "target недоступен"
        "TARGET_DEGRADED" -> "target отвечает медленно"
        "PROCESS_START_FAILED" -> "программа на target не запустилась"
        "PROCESS_TIMEOUT" -> "превышен срок хода"
        "PROCESS_ORPHANS_LEFT" -> "после остановки остались процессы"
        "PROCESS_ARGUMENT_UNENCODABLE" -> "аргумент команды не кодируется в локаль target"
        "ACCOUNT_SWITCH_LOOP" -> "слишком много переходов между подписками — переход остановлен"
        "ACCOUNT_LEASE_CONFLICT" -> "слот занят другой задачей"
        "STORAGE_IO_FAILED" -> "не удалось прочитать или записать файл"
        "STORAGE_CORRUPTED" -> "файл состояния испорчен"
        "STORAGE_UNAVAILABLE" -> "хранилище закрыто после отказа записи — нужен перезапуск"
        "STORAGE_RECOVERED_LKG" -> "прочитана прежняя версия: последняя правка не сохранилась"
        "JOURNAL_TAIL_TRUNCATED" -> "оборван хвост журнала: последние записи не подтверждены и сохранены в карантин"
        "JOURNAL_CORRUPTED" -> "журнал испорчен: нужен явный разбор"
        "JOURNAL_SEGMENT_RESTORED" -> "старая часть журнала пропала или испорчена — восстановлена из копии"
        "SNAPSHOT_INVALID" -> "снимок состояния не сошёлся с журналом — состояние пересчитано"
        "MIGRATION_FAILED" -> "не удалось обновить формат файла"
        "RUN_RECONCILIATION_REQUIRED" -> "пропущены события хода — нужна сверка с target"
        "EVENT_SEQUENCE_CONFLICT" -> "события хода пришли не по порядку"
        "COMMAND_QUEUED" -> "команда принята в очередь: подтверждения ещё нет"
        "TASK_DUPLICATE" -> "такая задача уже есть"
        "GIT_CONFLICT" -> "конфликт Git при слиянии"
        "GIT_DIRTY_WORKTREE" -> "в дереве есть незакоммиченные правки"
        "VALUE_INVALID" -> "неверное значение"
        "UNSUPPORTED" -> "так не поддерживается"
        "NOT_FOUND" -> "не найдено"
        "PERMISSION_DENIED" -> "нет прав"
        "INTERNAL_ERROR" -> "внутренний сбой приложения"

        // Разбор данных: сюда человек попадает только через испорченный файл или чужой формат.
        "JSON_EMPTY" -> "пустой файл данных"
        "JSON_TOO_LARGE" -> "файл данных слишком велик"
        "JSON_TOO_DEEP" -> "файл данных слишком глубоко вложен"
        "JSON_SYNTAX" -> "файл данных не разбирается: ошибка формата"
        "JSON_DUPLICATE_KEY" -> "в файле данных повторяется ключ"
        "JSON_NUMBER_OUT_OF_RANGE" -> "число в файле вне допустимого диапазона"
        "FIELD_MISSING" -> "в файле нет обязательного поля"
        "FIELD_TYPE" -> "поле в файле не того типа"
        "FIELD_UNKNOWN" -> "в файле есть неизвестное поле"
        "FORMAT_MISMATCH" -> "файл не того формата"
        "FORMAT_VERSION_MISSING" -> "у файла нет версии формата"
        "FORMAT_VERSION_NEWER" -> "файл новее этой версии приложения"
        "FORMAT_VERSION_UNSUPPORTED" -> "версия формата файла не поддерживается"
        "FORMAT_UNRECOGNIZED" -> "формат файла не распознан"
        "REGISTRY_SCHEMA_UNSUPPORTED" -> "схема реестра не поддерживается"
        "ID_INVALID" -> "неверный идентификатор"
        "ID_WRONG_KIND" -> "идентификатор другого вида"
        "HASH_INVALID" -> "неверная контрольная сумма"
        "HASH_MISMATCH" -> "контрольная сумма не сошлась: файл изменён"
        "INVALID_TRANSITION" -> "такой переход состояния невозможен"
        "EXPLICIT_OPERATION_REQUIRED" -> "нужна явная команда человека"
        "STATE_CONFLICT" -> "состояние противоречит ожидаемому"
        "INVARIANT_VIOLATED" -> "нарушено внутреннее правило согласованности"
        "ENTITY_NOT_FOUND" -> "запись не найдена"
        "EVENT_DUPLICATE" -> "событие уже записано"
        "EVENT_SEQUENCE_GAP" -> "пропуск в нумерации событий"
        "EVENT_TYPE_UNKNOWN" -> "неизвестный тип события"
        "MIGRATION_APPLIED" -> "формат файла обновлён"
        "BACKUP_INVALID" -> "резервная копия не читается"
        "RESTORE_RESUMED" -> "восстановление копии продолжено"
        "CANCELLED_BY_USER" -> "отменено человеком"
        "OPERATION_NOT_ALLOWED" -> "операция не разрешена"
        "RUN_BUDGET_EXHAUSTED" -> "предел расхода на запуск исчерпан"
        "REQUEST_NOT_PENDING" -> "запрос уже закрыт"
        "REQUEST_ANSWER_INVALID" -> "неверный ответ на запрос"

        // Аккаунты и слоты.
        "ACCOUNT_CONFIG_DIR_IN_USE" -> "каталог слота уже занят"
        "ACCOUNT_AUTH_METHOD_FORBIDDEN" -> "такой способ входа запрещён"
        "ACCOUNT_IDENTITY_MISMATCH" -> "аккаунт слота не тот, что ожидался"
        "ACCOUNT_DUPLICATE_SUBSCRIPTION" -> "такая подписка уже заведена"
        "ACCOUNT_IDENTITY_UNKNOWN" -> "личность аккаунта не определена"
        "ACCOUNT_ENVIRONMENT_SANITIZED" -> "лишние переменные входа убраны из окружения"
        "ACCOUNT_NONE_AVAILABLE" -> "нет доступного слота — все заняты или у предела"
        "CLAUDE_AUTH_STATUS_INVALID" -> "состояние входа Claude прочитать не удалось"
        "CLAUDE_USAGE_REPORT_INVALID" -> "загрузку окон подписки прочитать не удалось"

        // Процессы, target и связь.
        "PROCESS_EXIT_NONZERO" -> "программа на target завершилась с ошибкой"
        "PROCESS_JOB_UNAVAILABLE" -> "объект задания на Windows недоступен: фоновые процессы не удержать"
        "SSH_HOST_KEY_REJECTED" -> "ключ хоста отвергнут"
        "EXECUTION_HOST_UNAVAILABLE" -> "исполнитель недоступен"
        "NETWORK_UNREACHABLE" -> "сеть недоступна"
        "AGENT_REPLY_INVALID" -> "агент на target ответил не по протоколу"
        "AGENT_RUN_LOST" -> "запуск агента потерян"
        "AGENT_STOP_PENDING" -> "остановка ещё не подхвачена агентом"
        "TARGET_CHECK_FAILED" -> "проверка target не прошла"
        "TARGET_CHECK_WARNING" -> "проверка target прошла с замечаниями"
        "TARGET_DISABLED" -> "target выключен"

        // Ход Claude.
        "CLAUDE_API_ERROR" -> "ошибка API Claude"
        "CLAUDE_RESULT_ERROR" -> "ход завершился ошибкой"
        "CLAUDE_BUDGET_EXCEEDED" -> "предел стоимости хода исчерпан"
        "CLAUDE_MAX_TURNS" -> "достигнут предел числа шагов хода"
        "CLAUDE_CONTEXT_OVERFLOW" -> "контекст переполнен"
        "CLAUDE_STREAM_MALFORMED" -> "поток событий Claude испорчен"
        "CLAUDE_STREAM_INCOMPLETE" -> "поток событий Claude оборвался"
        "CLAUDE_SESSION_MISMATCH" -> "сессия провайдера не та, что ожидалась"
        "CLAUDE_EVENT_UNKNOWN" -> "неизвестное событие Claude"
        "RUNNER_TURN_ACTIVE" -> "ход уже идёт"
        "SESSION_FAILED" -> "сессия провалена"
        "SESSION_BINDING_MISMATCH" -> "сессия привязана к другому target"
        "SESSION_ALREADY_EXISTS" -> "такая сессия уже есть"
        "SESSION_HANDED_OFF" -> "работа передана другой сессии"

        // Задачи, контекст, планировщик.
        "DEPENDENCY_CYCLE" -> "зависимости замыкаются в кольцо"
        "DEPENDENCY_FAILED" -> "зависимость провалена"
        "LOOP_DETECTED" -> "обнаружено зацикливание"
        "RETRY_LIMIT_REACHED" -> "предел повторов исчерпан"
        "CONTINUATION_LIMIT_REACHED" -> "предел продолжений исчерпан"
        "CONTEXT_PROJECT_BOUNDARY" -> "источник вне границы проекта"
        "CONTEXT_SECRET_EXCLUDED" -> "секрет не включён в контекст"
        "CONTEXT_SECRET_INCLUDED" -> "секрет включён в контекст по разрешению"
        "CONTEXT_TRUNCATED" -> "контекст сокращён по бюджету"
        "CONTEXT_BUDGET_EXCEEDED" -> "бюджет контекста исчерпан"
        "CONTEXT_DEDUPLICATED" -> "повтор в контексте убран"
        "HANDOFF_ALREADY_EXISTS" -> "передача работы уже записана"
        "MODEL_DOWNGRADED_BY_BUDGET" -> "модель понижена из-за бюджета"
        "APPROVAL_REQUIRED" -> "нужно одобрение человека"
        "APPROVAL_INVALID" -> "одобрение не подходит к этому действию"

        // Git, проверка, ревью, восстановление.
        "WORKTREE_OWNED" -> "дерево занято другой задачей"
        "WORKTREE_NOT_OWNED" -> "дерево принадлежит не этой задаче"
        "WORKTREE_PATH_IN_USE" -> "путь дерева уже занят"
        "WORKTREE_MISSING" -> "рабочее дерево пропало"
        "BRANCH_IN_USE" -> "ветка занята"
        "GIT_COMMAND_FAILED" -> "команда Git завершилась ошибкой"
        "GIT_OUTPUT_INVALID" -> "вывод Git не разобран"
        "FILE_OVERLAP_SERIALIZE" -> "пересечение файлов: задачи выстроены по очереди"
        "FILE_OVERLAP_REJECTED" -> "пересечение файлов: запуск отклонён"
        "MERGE_CONFLICT" -> "слияние дало конфликт"
        "MERGE_TARGET_NOT_READY" -> "цель слияния не готова"
        "VERIFICATION_PROFILE_MISSING" -> "профиль проверки не задан"
        "VERIFICATION_STEP_UNAVAILABLE" -> "шаг проверки не удалось выполнить на target"
        "VERIFICATION_FAILED" -> "проверка не прошла"
        "VERIFICATION_BASELINE_FAILURE" -> "проверка падала ещё до правки (базовая линия)"
        "VERIFICATION_REQUIRED" -> "нужна проверка перед завершением"
        "VERIFICATION_WAIVED" -> "проверка принята без прогона"
        "REVIEW_BLOCKING" -> "ревью нашло блокирующее замечание"
        "REVIEW_FAILED" -> "ревью не прошло"
        "REVIEW_REQUIRED" -> "нужно ревью перед завершением"
        "REVIEW_WAIVED" -> "ревью принято без прогона"
        "RECOVERY_RESTARTED" -> "контур восстановлен после перезапуска"
        "RECOVERY_START_UNCONFIRMED" -> "не подтверждено, начался ли ход"
        "ORPHAN_FOUND" -> "найдены процессы без владельца"

        // Экосистема и коннекторы.
        "ECOSYSTEM_DISABLED" -> "работа с экосистемой выключена"
        "ECOSYSTEM_UNAVAILABLE" -> "экосистема недоступна"
        "CONNECTOR_REPLY_INVALID" -> "внешний источник ответил не по формату"

        // Сеть и GitHub модулей DPmetaApp (DPmetaApp:D-10, D-17).
        "HOST_NOT_ALLOWED" -> "адрес не из списка разрешённых — запрос не отправлен"
        "GITHUB_AUTH_EXPIRED" -> "GitHub не принял вход — войдите заново"
        "GITHUB_RATE_LIMITED" -> "лимит запросов GitHub исчерпан — остальное после сброса"
        "GITHUB_NOT_FOUND" -> "на GitHub не найдено или скрыто от входа"
        "DEVICE_AUTH_REQUIRED" -> "нужен вход по PIN или биометрии"

        // Провайдеры по ключу (D-98).
        "PROVIDER_AUTH_FAILED" -> "провайдер не принял ключ"
        "PROVIDER_RATE_LIMITED" -> "предел запросов у провайдера — повторите позже"
        "PROVIDER_UNAVAILABLE" -> "провайдер перегружен или недоступен"
        "PROVIDER_REQUEST_REJECTED" -> "провайдер отверг запрос"
        "PROVIDER_REPLY_INVALID" -> "провайдер ответил не по формату"
        "PROVIDER_KEY_MISSING" -> "у профиля нет ключа — введите его заново"

        // Маршруты Claude Code (D-116).
        "ROUTINE_TOKEN_INVALID" -> "токен маршрута не принят — выпустите новый"
        "ROUTINE_LIMIT_REACHED" -> "предел запусков маршрута — позже или другим маршрутом"

        // Правила тревог пульта (DPmetaApp:D-85), общий словарь D-89.
        "RULE_INVALID" -> "правило тревоги не принято — проверьте его условия"
        else -> code
    }
}

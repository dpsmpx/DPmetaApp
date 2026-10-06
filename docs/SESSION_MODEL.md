# Модель сессии

Сессия — временный исполнитель задачи: связка задача + аккаунт +
execution target + worktree + процесс Claude (ТЗ, §29). Истинное
состояние задачи живёт в оркестраторе (D-01); сессия может умереть, и
задача продолжится в другой.

## 1. Автомат состояний

Таблица — `core`, `SessionLifecycle` (D-11). Начальное состояние —
`CREATED`; конечные — `COMPLETED`, `FAILED`, `CANCELLED`.

| Из | В (обычный переход) |
|---|---|
| `CREATED` | `QUEUED`, `CANCELLED` |
| `QUEUED` | `STARTING`, `CANCELLED` |
| `STARTING` | `RUNNING`, `FAILED`, `CANCELLED`, `RECOVERY_PENDING` |
| `RUNNING` | `WAITING`, `PAUSED`, `BLOCKED`, `NEAR_LIMIT`, `VERIFYING`, `COMPLETED`, `FAILED`, `CANCELLED`, `RECOVERY_PENDING` |
| `WAITING` | `RUNNING`, `PAUSED`, `BLOCKED`, `NEAR_LIMIT`, `FAILED`, `CANCELLED`, `RECOVERY_PENDING` |
| `PAUSED` | `RUNNING`, `FAILED`, `CANCELLED`, `RECOVERY_PENDING` |
| `BLOCKED` | `RUNNING`, `PAUSED`, `FAILED`, `CANCELLED`, `RECOVERY_PENDING` |
| `NEAR_LIMIT` | `RUNNING`, `PAUSED`, `VERIFYING`, `COMPLETED`, `FAILED`, `CANCELLED`, `RECOVERY_PENDING` |
| `VERIFYING` | `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `RECOVERY_PENDING` |
| `RECOVERY_PENDING` | `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `COMPLETED`, `FAILED`, `CANCELLED` | — |

Смысл состояний:

- `WAITING` — ждёт внешнего: ответа пользователя, одобрения, ресурса;
- `BLOCKED` — не может продолжать без вмешательства (повтор той же
  ошибки, конфликт слияния, ТЗ, §21);
- `NEAR_LIMIT` — близок предел сессии или бюджета: время создать handoff
  (ТЗ, §27);
- `VERIFYING` — сессия ведёт проверку своей работы;
- `RECOVERY_PENDING` — после перезапуска оркестратора состояние процесса
  неизвестно, идёт сверка (ТЗ, §54); неразрешённая сверка оставляет
  сессию здесь с записью конфликта (`docs/RECOVERY.md`).

`COMPLETED` сессии значит «исполнитель закончил свой ход», а не «задача
выполнена»: завершение задачи — по её автомату и только после проверки
(D-23, `docs/SCHEDULER.md`).

## 2. Продолжение

Из конечного состояния обычного перехода нет (ТЗ, §8: `COMPLETED →
RUNNING` недопустимо). Продолжение — явная операция, которая создаёт
**новую** сессию оркестратора с `continuesSessionId` = прежней; новая
сессия может возобновить сессию провайдера (`--resume
<providerSessionId>`) или начать новую с handoff. Так история прежней
сессии остаётся неизменной, а повтор операции продолжения идемпотентен
по ключу (прежняя сессия, handoff).

## 3. Повтор перехода

Запрос перехода в текущее состояние — не ошибка и не изменение
(`Transition.changed = false`): повтор события после переподключения
не портит состояние (ТЗ, §101, §102).

## 4. Свежесть heartbeat

Свежесть — не состояние автомата, а оценка знания о сессии по возрасту
последнего heartbeat (`core`, `HeartbeatFreshness`; ТЗ, §15):

| Возраст | Оценка |
|---|---|
| меньше порога `aging` | `FRESH` |
| меньше порога `stale` | `AGING` |
| меньше порога `unknown` | `STALE` |
| не меньше порога `unknown` | `UNKNOWN` |
| проба target подтвердила, что процесса нет | `DEAD` |

Отсутствие heartbeat само по себе никогда не даёт `DEAD` (D-13): только
проба. Сессия в `RUNNING` со свежестью `STALE` показывается как
устаревшая (ТЗ, §122), а не как работающая.

## 5. Связь с задачей и ресурсами

Перед запуском проверяется, что `accountId`, `credentialRef` и
`executionTargetId` сессии согласованы (ТЗ, §66), а worktree не занят
другой изолированной сессией (ТЗ, §29). Реализация — этапы 5, 6, 9.

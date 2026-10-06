# DP_AI_Orcestrator

Control plane для множества сессий Claude Code и Claude Agent SDK:
задачи и их зависимости, аккаунты, execution targets, рабочие деревья,
контекст и handoff, события, мониторинг, уведомления, проверка
результата и продолжение работы в другой сессии. Нативное
Android-приложение управляет исполнителями, которые работают в Termux,
на Linux- или Windows-хосте (`docs/direction/MASTER_TECHNICAL_TASK.md`).

> **Состояние: начало разработки.** Идут этапы 0 и 1 — основание
> репозитория и ядро (`docs/direction/ROADMAP/README.md`). Запускать
> Claude оркестратор пока не умеет.

## Прогресс

Раздел строит `tools/verify/readme_progress.py` из плана, критериев
выхода этапов, журнала решений и `TODO.md`; проверка `static` следит,
что он не отстал от документов.

<!-- progress:begin -->
<!-- progress:end -->

## Главное правило

> AI-сессия не является состоянием системы. AI-сессия является
> заменяемым исполнителем состояния системы (ТЗ, §150).

- **Состояние — у оркестратора** (D-01): задача, контекст, handoff,
  события и рабочие деревья переживают смену сессии, аккаунта и
  перезапуск приложения.
- **Заявление AI — не завершение** (D-23): «готово» → проверка →
  `VERIFIED` → `DONE`.
- **Детерминированное ядро** (D-22): планировщик, автоматы, повторы и
  восстановление не зависят от случайности модели.
- **Неизвестное — не ноль** (D-13): нет heartbeat — «неизвестно», а не
  «упала».
- **Только официальные интерфейсы** (D-04): Claude Code CLI и Agent SDK,
  каждый аккаунт — в своих лимитах.
- **Секреты защищены по умолчанию** (D-14).

## Устройство

Логика — в модулях на чистом Kotlin/JVM, которые собираются и
проверяются без Android SDK; Android-приложение и `cli` — оболочки над
одним ядром (D-02). Модули повторяют подсистемы ТЗ, §6:

```
   android/ (этап 11)        cli/  dpai
            \                /
             ui/ ─ orchestrator/
                       │
 tasks · sessions · contexts · verification · monitoring · notifications
 accounts · execution · git · security · storage · ecosystem · connectors · analytics
                       │
                     core/   Outcome · Diagnostic · ID · Clock · хеши · JSON · автоматы · события
```

Границы и направления зависимостей — `docs/ARCHITECTURE.md`, §2.

## Сборка и проверка

Нужно: JDK 21, Python 3.11+, git. Android SDK — с этапа 11.

```sh
./gradlew build                    # ядро и все тесты
tools/verify/run.sh static         # быстрые проверки без сборки
tools/verify/run.sh affected       # после правки: static + затронутые модули
tools/verify/run.sh full           # перед слиянием
./gradlew :cli:installDist && cli/build/install/dpai/bin/dpai version
```

Что проверяет каждый шаг — `docs/PROCESS.md`, «Проверки». GitHub
Actions не используется (D-15).

## Документы

| Что | Где |
|---|---|
| ТЗ | `docs/direction/MASTER_TECHNICAL_TASK.md` |
| Решения | `docs/direction/DECISIONS.md` |
| План по этапам | `docs/direction/ROADMAP/README.md` |
| Объём V1 | `docs/direction/V1_SCOPE.md` |
| Матрица готовности | `docs/direction/COMPLETION_MATRIX.md` |
| Архитектура | `docs/ARCHITECTURE.md` |
| Модель данных | `docs/DATA_MODEL.md` |
| Исполнение | `docs/EXECUTION_MODEL.md` |
| Аккаунты | `docs/ACCOUNT_MODEL.md` |
| Сессии | `docs/SESSION_MODEL.md` |
| Контекст | `docs/CONTEXT_MODEL.md` |
| Планировщик | `docs/SCHEDULER.md` |
| Восстановление | `docs/RECOVERY.md` |
| Безопасность | `docs/SECURITY_MODEL.md` |
| Тестирование | `docs/TESTING.md` |
| Производительность | `docs/PERFORMANCE.md` |
| Процесс | `docs/PROCESS.md` |
| Происхождение | `docs/PROVENANCE.md` |
| Журнал изменений | `docs/CHANGELOG.md` |
| Текущие задачи | `TODO.md` |

Правила для всех, кто меняет код и документы, — людей и ИИ-ассистентов:
`CLAUDE.md`.

## Лицензия

Apache License 2.0 — `LICENSE`.

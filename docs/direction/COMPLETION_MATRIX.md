# Матрица готовности

Что из ТЗ реализовано (ТЗ, §157, п. 2–4). Строки — чек-лист итогового
определения готовности ТЗ, §158; состояние меняется вместе с критериями
выхода этапа (`docs/direction/ROADMAP/README.md`), а не отдельно.

Обозначения: **✓** — готово и проверено; **◐** — частично (что есть —
в столбце «Сейчас»); **—** — не начато.

Срез на 6 октября 2026.

| Пункт ТЗ, §158 | Этап | Состояние | Сейчас |
|---|---|---|---|
| Core architecture complete | 0, 1 | ✓ | DoD ТЗ, §126: ID, результаты, диагностика, время, хеши, JSON, автоматы, версии форматов, события, детерминированные тесты (этап 1) |
| Persistent state reliable | 2 | ✓ | журнал с цепочкой хешей, атомарные документы с LKG, снимки, миграции; тесты по модели сбоев (этап 2) |
| Accounts complete | 5 | ◐ | подписки и слоты, вывод состояния из фактов, пределы окна пяти часов и недели (из `rate_limit_event`), защиты от пересечения, выбор слота, журнал и снимок, сверка перед ходом (D-27, D-29, D-30); возможности задачи — этап 7 |
| Execution targets complete | 3 | ◐ | модель target, здоровье с порогами и предохранителем, `doctor`, процессы без сирот, SSH через OpenSSH (D-31), агент `dpai agent` с журналом и сверкой пропусков; mock и локально проверено, SSH на настоящем хосте и Termux на устройстве — открыты |
| Claude runner complete | 4 | ✓ | `ClaudeRunner` через CLI (D-32): ходы с `--resume`, строгий разбор `stream-json` на записанном корпусе, `MockRunner` со сценариями ТЗ, §89, вывод через `Redactor` |
| Sessions complete | 6 | ✓ | реестр, идемпотентное создание и продолжение, сверка слота перед ходом, heartbeat без поспешного «упала», драйвер поверх `ClaudeRunner`, журнал и снимок |
| Scheduler complete | 7 | ✓ | детерминированный порядок со старением и ручным приоритетом, план по возможностям target, повтор по классу причины, петли и предел продолжений |
| Task DAG complete | 7 | ✓ | зависимости, обнаружение цикла с перечнем, блокировка зависимых при отказе |
| Context manager complete | 8 | — | |
| Handoff complete | 8 | — | |
| Git/worktree isolation complete | 9 | — | |
| Verification integration complete | 10 | — | |
| Monitoring complete | 11 | — | |
| Notifications complete | 12 | ◐ | агрегация, эскалация, предел частоты и сводка, deep links; уведомления аккаунтов; каналы Android — этап 11 |
| Recovery complete | 13 | ◐ | пропуски и конфликты событий (этап 1); восстановление хранилища, карантин, возобновление копии (этап 2); сверка канала запуска и потерянный супервизор (этап 3) |
| Security audit complete | 18 | — | модель угроз: `docs/SECURITY_MODEL.md` |
| Performance audit complete | 17 | — | бюджеты: `docs/PERFORMANCE.md` |
| Stress test complete | 17, 19 | — | |
| Chaos test complete | 13, 19 | — | |
| Endurance test complete | 19 | — | |
| Mobile lifecycle audit complete | 11, 19 | — | |
| Backup/restore verified | 2 | ✓ | набор ТЗ, §61 на модели сбоев (этап 2); на устройстве — этап 19 |
| All invariants verified | 19 | — | список: `docs/DATA_MODEL.md`, «Инварианты» |
| Documentation complete | все | ◐ | документы ТЗ, §138 заведены (этап 0) |
| Final deep audit PASS | 19 | — | |
| No critical findings | 19 | — | |
| No unresolved high-severity stability findings | 19 | — | |
| Reproducible build | 0, 20 | ◐ | wrapper и версии закреплены со сверкой сумм |
| Release checkpoint | 20 | — | |

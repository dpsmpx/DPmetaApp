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
| Accounts complete | 5 | ◐ | подписки и слоты, вывод состояния из фактов, пределы окна пяти часов и недели, защиты от пересечения, выбор слота, журнал и снимок (D-27, D-29, D-30); сверка перед запуском — с этапами 3, 4, 6 |
| Execution targets complete | 3 | — | |
| Claude runner complete | 4 | — | факты CLI собраны: `docs/research/CLAUDE_CODE_CLI.md` |
| Sessions complete | 6 | ◐ | автомат жизненного цикла и свежесть heartbeat (этап 1) |
| Scheduler complete | 7 | ◐ | автомат задачи с цепочкой проверки (этап 1) |
| Task DAG complete | 7 | — | |
| Context manager complete | 8 | — | |
| Handoff complete | 8 | — | |
| Git/worktree isolation complete | 9 | — | |
| Verification integration complete | 10 | — | |
| Monitoring complete | 11 | — | |
| Notifications complete | 12 | ◐ | агрегация, эскалация, предел частоты и сводка, deep links; уведомления аккаунтов; каналы Android — этап 11 |
| Recovery complete | 13 | ◐ | пропуски и конфликты событий (этап 1); восстановление хранилища, карантин, возобновление копии (этап 2) |
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

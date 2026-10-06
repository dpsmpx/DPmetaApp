# Матрица готовности

Что из ТЗ реализовано (ТЗ, §157, п. 2–4). Строки — чек-лист итогового
определения готовности ТЗ, §158; состояние меняется вместе с критериями
выхода этапа (`docs/direction/ROADMAP/README.md`), а не отдельно.

Обозначения: **✓** — готово и проверено; **◐** — частично (что есть —
в столбце «Сейчас»); **—** — не начато.

Срез на 6 октября 2026.

| Пункт ТЗ, §158 | Этап | Состояние | Сейчас |
|---|---|---|---|
| Core architecture complete | 0, 1 | ◐ | скелет модулей и проверки; примитивы `core` — этап 1 |
| Persistent state reliable | 2 | — | |
| Accounts complete | 5 | — | |
| Execution targets complete | 3 | — | |
| Claude runner complete | 4 | — | факты CLI собраны: `docs/research/CLAUDE_CODE_CLI.md` |
| Sessions complete | 6 | ◐ | автомат жизненного цикла (этап 1) |
| Scheduler complete | 7 | — | |
| Task DAG complete | 7 | — | |
| Context manager complete | 8 | — | |
| Handoff complete | 8 | — | |
| Git/worktree isolation complete | 9 | — | |
| Verification integration complete | 10 | — | |
| Monitoring complete | 11 | — | |
| Notifications complete | 12 | — | |
| Recovery complete | 13 | — | |
| Security audit complete | 18 | — | модель угроз: `docs/SECURITY_MODEL.md` |
| Performance audit complete | 17 | — | бюджеты: `docs/PERFORMANCE.md` |
| Stress test complete | 17, 19 | — | |
| Chaos test complete | 13, 19 | — | |
| Endurance test complete | 19 | — | |
| Mobile lifecycle audit complete | 11, 19 | — | |
| Backup/restore verified | 2 | — | |
| All invariants verified | 19 | — | список: `docs/DATA_MODEL.md`, «Инварианты» |
| Documentation complete | все | ◐ | документы ТЗ, §138 заведены (этап 0) |
| Final deep audit PASS | 19 | — | |
| No critical findings | 19 | — | |
| No unresolved high-severity stability findings | 19 | — | |
| Reproducible build | 0, 20 | ◐ | wrapper и версии закреплены со сверкой сумм |
| Release checkpoint | 20 | — | |

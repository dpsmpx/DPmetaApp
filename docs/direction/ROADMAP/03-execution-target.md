# Этап 3 — Execution target

Состояние: в работе.

Сделано по D-31: всё, что не требует настоящего SSH-хоста и телефона.
Открыто — проверка по SSH на настоящем target и Claude Code в Termux
(`TODO.md`).

## Цель

Единый интерфейс к месту, где работает Claude: локальный Termux или
помощник, удалённый Linux/Windows по SSH, mock (ТЗ, §11, §139, Phase 3).

## Объём

- Решение «Отложено»: транспорт контроллер ↔ target.
- `ExecutionTarget` (ТЗ, §7), `ExecutionTargetManager`, `HealthChecker`
  со свежестью и предохранителем (ТЗ, §81).
- Жизненный цикл процесса: старт, остановка, полная остановка, тайм-аут;
  сироты не остаются.
- Агент на target (оболочка `cli` в режиме исполнителя) с журналом
  событий и последовательными номерами (ТЗ, §56).
- `doctor` для target: связь, SSH, Termux, Claude runtime, Git, диск,
  память, часы (ТЗ, §34).

## Критерии выхода

- [x] Процесс на target запускается и полностью останавливается через
      единый интерфейс — на mock и локально (ТЗ, §139, Phase 3):
      `ExecutionTargetManagerTest`, `AgentCommandTest`.
- [ ] То же по SSH на настоящем target: `AgentCommandTest`,
      `sshTargetStartsAndStopsThroughUnifiedInterface` с `dpai.test.ssh`
      (`docs/TESTING.md`, §1).
- [x] Недоступный target переходит в `OFFLINE` по проверке здоровья, а
      не по одному пропуску (несколько порогов): `TargetHealthTest`.
- [x] Пропуск номера события на канале обнаруживается и ведёт к сверке:
      `RunChannelTest`.
- [x] Тайм-аут и обрыв связи не оставляют процессов-сирот (тест):
      `LocalProcessHostTest`, `AgentRuntimeTest`.
- [ ] Claude Code в Termux на Redmi Note 13: нативно, в окружении Linux
      поверх Termux или телефон — только контроллер (D-26; `TODO.md`).

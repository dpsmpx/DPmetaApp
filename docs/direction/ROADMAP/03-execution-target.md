# Этап 3 — Execution target

Состояние: выполнен (7 октября 2026).

Сделано по D-31, проверено на mock, локально, по SSH на Windows и
Linux и на телефоне (Termux + proot, D-33).

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
- [x] То же по SSH на настоящем Windows-target (OpenSSH Server 9.5,
      7 октября 2026): `AgentCommandTest`,
      `sshTargetStartsAndStopsThroughUnifiedInterface` с `dpai.test.ssh`
      (`docs/TESTING.md`, §8); супервизор через WMI пережил сессию SSH.
- [x] То же по SSH на Linux-target (Ubuntu 24.04 в WSL2, OpenSSH 9.6,
      7 октября 2026): `AgentCommandTest`; супервизор под `setsid`.
- [x] Недоступный target переходит в `OFFLINE` по проверке здоровья, а
      не по одному пропуску (несколько порогов): `TargetHealthTest`.
- [x] Пропуск номера события на канале обнаруживается и ведёт к сверке:
      `RunChannelTest`.
- [x] Тайм-аут и обрыв связи не оставляют процессов-сирот (тест):
      `LocalProcessHostTest`, `AgentRuntimeTest`.
- [x] Claude Code в Termux: нативно — нет, в окружении Linux поверх
      Termux (proot-distro, Alpine) — да; проверено на Realme C21Y
      (D-33; `docs/research/CLAUDE_CODE_CLI.md`, §9).
- [x] Поток событий хода вместо опроса (D-76, D-79, 22 октября 2026):
      `dpai agent follow` — строки по мере записи журнала до конца хода,
      признак жизни, конец при ушедшем читателе; контроллер ведёт идущие
      ходы потоком, без него — опросом (`AgentRuntimeTest`,
      `FollowingEventSourceTest`, `LiveSystemTest`); по SSH — долгий
      канал `exec` OpenSSH и JSch (`AgentCommandTest`,
      `JschProcessHostTest` с `dpai.test.ssh`, облако). Задержка на
      устройстве — `docs/DEVICE_CHECK.md`, §7.7.

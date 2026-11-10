# Настройка: ПК с Windows как target (доступ с телефона из любого места)

Claude Code работает на ПК, телефон управляет им по SSH (D-26, D-31)
через общую частную сеть телефона и ПК (D-68): ПК не открывается в интернет. Начатый ход
продолжается на ПК и без связи; проверку, коммит и следующий ход делает
телефон, когда связь есть. **Подключение по сети ещё не проверено на
устройстве** (9 октября 2026); проверено — Windows-target по кабелю
(`docs/TESTING.md`, §8; `docs/DEVICE_CHECK.md`, §10).

Команды — в PowerShell **от администратора**, каждая — одной строкой.

**Основной сценарий — Claude на телефоне (D-50, D-53).** Вход и Claude
Code — в Termux на телефоне («Инструкция», разделы 1–7), файлы, проверки
и Git — на ПК; входить в Claude на ПК не нужно.

**Быстрее — кнопками (D-52, D-53).** После общей сети (§1) и переноса
`dpai-agent.zip` в «Загрузки» ПК: Targets → «Инструкция» → «10. ПК с
Windows» → «Копировать», вставить в PowerShell от администратора — это
§2 (без Node.js и Claude Code), §3, §4 и поля для §5 с отпечатками ключа
хоста. Затем target ПК (§5, «Каталог Claude слота» — пусто) и у него
«Проект»: «Где работает Claude» — Termux, «Связать автоматически» — это
шаги 1–4 §7. Ниже — то же вручную. §6 (вход на ПК) — только если Claude
работает на самом ПК.

## 1. Общая сеть телефона и ПК

Ключи телефона входят на ПК только из частных сетей (D-68): дом
(`192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`) и VPN-сети с адресами
`100.64.0.0/10`. Android держит одну VPN за раз, поэтому второй VPN ради
ПК не ставится — свой VPN телефона остаётся.

1. Дома — один Wi-Fi. В VPN на телефоне включить «доступ к локальной
   сети» или исключить из VPN приложения DP AI и Termux (раздельное
   туннелирование); в VPN на ПК — «разрешить локальную сеть». DPpass на
   телефоне пускает локальные сети мимо туннеля сам. Вне дома — ПК на
   точке доступа телефона, интернет ПК — через раздачу туннеля DPpass
   (`docs/SETUP_PC_LINUX.md`, §1; D-84): «Хост» — адрес ПК в сети
   раздачи. Или свой VPN, где телефон и ПК видят друг друга (свой
   WireGuard, ZeroTier, Tailscale без другого VPN на телефоне): «Хост» —
   адрес ПК в нём.
2. Адрес ПК: `Get-NetIPAddress -AddressFamily IPv4 | Select-Object IPAddress, InterfaceAlias`.
   Сеть Windows — «Частная»: в «Общедоступной» брандмауэр не пускает
   вход.
3. Питание: сон при питании от сети — «Никогда».

## 2. SSH, Java, Git, Claude Code

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0; Set-Service sshd -StartupType Automatic; Start-Service sshd
if (-not (Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue)) { New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (sshd)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 }
notepad C:\ProgramData\ssh\sshd_config
```

В файле **первой строкой** (выше любых `Match`) — `PasswordAuthentication no`,
сохранить, затем `Restart-Service sshd`.

```powershell
winget install -e --id EclipseAdoptium.Temurin.21.JRE; winget install -e --id Git.Git; winget install -e --id OpenJS.NodeJS.LTS
```

В новом окне PowerShell: `npm install -g @anthropic-ai/claude-code; claude --version`.

## 3. Агент `dpai`

В приложении: Targets → «Инструкция» → «Сохранить dpai-agent.zip в
Загрузки»; перенесите файл на ПК в папку «Загрузки».

```powershell
Expand-Archive "$HOME\Downloads\dpai-agent.zip" -DestinationPath $HOME -Force; Set-Content "$HOME\dpai-agent.cmd" '@call "%~dp0dpai\bin\dpai.bat" %*' -Encoding ascii
& "$HOME\dpai-agent.cmd" agent doctor
```

Ожидается одна строка JSON с `"ok":true`.

Служба агента (по желанию, D-109) — задача при входе пользователя, без
прав администратора; ход и без неё переживает обрыв SSH:

```powershell
& "$HOME\dpai-agent.cmd" agent service install
```

Ожидается в конце строка «служба: running». Снять — `agent service
remove`.

## 4. Ключ приложения

Строка ключа — Targets → «Ключ приложения» → «Показать открытый ключ».
Только из частных сетей (D-68); для администратора Windows — общий файл:

```powershell
Add-Content C:\ProgramData\ssh\administrators_authorized_keys 'from="192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10" ВСТАВЛЕННАЯ_СТРОКА'; icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r /grant Administrators:F /grant SYSTEM:F
```

## 5. Target в приложении

Targets → «Добавить»: «Хост» — адрес ПК в общей сети (§1), «Порт» — `22`,
«Пользователь» — `$env:USERNAME`, «Команда агента dpai» —
`C:/Users/<пользователь>/dpai-agent.cmd`, «Каталог Claude слота» —
`C:/Users/<пользователь>/.claude-slot-1`, «Windows» — отметить.
«Получить ключ хоста» — сверить отпечаток с выводом:

```powershell
Get-ChildItem C:\ProgramData\ssh\ssh_host_*_key.pub | ForEach-Object { ssh-keygen -l -f $_.FullName }
```

Затем «Проверить».

## 6. Вход в Claude и проект

```powershell
$env:CLAUDE_CONFIG_DIR = "$HOME\.claude-slot-1"; claude
```

Войти аккаунтом с подпиской, `/exit`; в приложении — «Проверить вход».
Проект: репозиторий Git на ПК, Targets → «Проект» (каталоги — пути
`C:/Users/<пользователь>/…`).

Фоновые программы хода (находка A-9, `docs/audits/2026-10-08-pre-device-review.md`):
пауза и отмена хода останавливают и программы, которые инструменты Claude
запустили в фоне отдельно от него (сервер разработки, демон), — их держит
объект задания Windows, который создаёт помощник PowerShell агента
(`docs/EXECUTION_MODEL.md`, §1.3; 22 октября 2026). Пока это не проверено
на ПК автора (`TODO.md`): если после остановки такая программа осталась в
диспетчере задач или в журнале хода есть `PROCESS_JOB_UNAVAILABLE`
(политика запретила `Add-Type`), завершите её вручную. Программы,
запущенные в первые секунды хода, пока помощник готовится, объект не
держит; ход, кончившийся сам, фоновые программы не трогает — как на Linux.

## 7. Без входа в Claude на ПК: Claude на телефоне (D-50)

В приложении шаги 1–4 делает кнопка «Связать автоматически» в форме
проекта (D-53); ниже — вручную.

Claude Code работает в Termux на телефоне со входом телефона, а файлы и
команды задачи — на ПК: Claude Code сам ходит на ПК по SSH к
`dpai agent mcp`. Каталог Claude на ПК (§5) и вход в Claude на ПК (§6)
тогда не нужны; Termux-target со входом в Claude — по «Инструкции» в
приложении. Команды `run` идут через Git Bash (ставится с Git, §2).

1. В Termux — клиент SSH и ключ внутри proot (там идёт Claude):

```sh
proot-distro login alpine -- sh -c 'apk add -q openssh-client && mkdir -p /root/.ssh && ssh-keygen -q -t ed25519 -N "" -f /root/.ssh/dpai_workspace && cat /root/.ssh/dpai_workspace.pub'
```

2. Напечатанную строку — на ПК (PowerShell от администратора), только из
   частных сетей (D-68):

```powershell
Add-Content C:\ProgramData\ssh\administrators_authorized_keys 'from="192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10" СТРОКА_КЛЮЧА_ТЕЛЕФОНА'
```

3. Ключ хоста ПК — в known_hosts proot: на вопрос `ssh` сверить отпечаток
   с выводом PowerShell из §5 и ответить `yes`:

```sh
proot-distro login alpine -- ssh -i /root/.ssh/dpai_workspace ПОЛЬЗОВАТЕЛЬ@100.x.y.z exit
```

4. Проверка сервера дерева: команда ниже ждёт строку на входе — вставьте
   `{"jsonrpc":"2.0","id":1,"method":"ping"}`, ответ —
   `{"id":1,"jsonrpc":"2.0","result":{}}`; выход — Ctrl+D.

```sh
proot-distro login alpine -- ssh -T -i /root/.ssh/dpai_workspace ПОЛЬЗОВАТЕЛЬ@100.x.y.z C:/Users/ПОЛЬЗОВАТЕЛЬ/dpai-agent.cmd agent mcp --root C:/Users/ПОЛЬЗОВАТЕЛЬ
```

5. В приложении: target ПК — как в §5, «Каталог Claude слота» — пусто.
   Targets → «Проект» у ПК: каталоги — как в §6; «Где работает Claude» —
   Termux; «Ключ SSH» — `/root/.ssh/dpai_workspace`. Задачи идут на
   телефон, деревья, проверка и слияние — на ПК.


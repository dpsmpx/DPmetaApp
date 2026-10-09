# Настройка: ПК с Windows как target (доступ с телефона из любого места)

Claude Code работает на ПК, телефон управляет им по SSH (D-26, D-31)
через частную сеть Tailscale: ПК не открывается в интернет. Начатый ход
продолжается на ПК и без связи; проверку, коммит и следующий ход делает
телефон, когда связь есть. **Подключение по сети ещё не проверено на
устройстве** (9 октября 2026); проверено — Windows-target по кабелю
(`docs/TESTING.md`, §8; `docs/DEVICE_CHECK.md`, §10).

Команды — в PowerShell **от администратора**, каждая — одной строкой.

## 1. Tailscale

1. На ПК: `winget install --id Tailscale.Tailscale -e`, затем войти в
   Tailscale. На телефоне — Tailscale из Google Play, тот же аккаунт.
   Android держит одну VPN за раз — другую VPN выключите.
2. Адрес ПК (вида `100.x.y.z`): `tailscale ip -4`.
3. Питание: сон при питании от сети — «Никогда».

## 2. SSH, Java, Git, Claude Code

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0; Set-Service sshd -StartupType Automatic; Start-Service sshd
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

## 4. Ключ приложения

Строка ключа — Targets → «Ключ приложения» → «Показать открытый ключ».
Только из сети Tailscale; для администратора Windows — общий файл:

```powershell
Add-Content C:\ProgramData\ssh\administrators_authorized_keys 'from="100.64.0.0/10" ВСТАВЛЕННАЯ_СТРОКА'; icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r /grant Administrators:F /grant SYSTEM:F
```

## 5. Target в приложении

Targets → «Добавить»: «Хост» — адрес Tailscale, «Порт» — `22`,
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

Войти аккаунтом с подпиской, `/exit`; в приложении — «Вход Claude».
Проект: репозиторий Git на ПК, Targets → «Проект» (каталоги — пути
`C:/Users/<пользователь>/…`).

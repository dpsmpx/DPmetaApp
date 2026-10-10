# Настройка ПК с Windows как target одной командой (D-52; docs/SETUP_PC_WINDOWS.md, §2–§5).
# Строки — по одной инструкции: PcSetup склеивает их через «; » в одну строку для вставки.
# Строки с «#» в начале и пустые — пропускаются. DPAI_KEY_LINE — строка ключа приложения.
$ErrorActionPreference = 'Continue'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')) { Write-Warning 'Запустите PowerShell от администратора и вставьте команду снова'; return }
Write-Host '[1/5] OpenSSH Server'
if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) { Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 | Out-Null }
Set-Service sshd -StartupType Automatic
Start-Service sshd
$cfg = "$env:ProgramData\ssh\sshd_config"
if ((Get-Content $cfg -Raw) -notmatch '(?m)^PasswordAuthentication no') { Copy-Item $cfg "$cfg.bak-dpai" -Force; Set-Content $cfg ("PasswordAuthentication no`r`n" + (Get-Content $cfg -Raw)) -Encoding ascii -NoNewline; Restart-Service sshd }
Write-Host '[2/5] Java, Git, Node.js, Claude Code'
foreach ($p in 'EclipseAdoptium.Temurin.21.JRE', 'Git.Git', 'OpenJS.NodeJS.LTS') { winget install -e --id $p --silent --accept-source-agreements --accept-package-agreements | Out-Null }
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not (Get-Command claude -ErrorAction SilentlyContinue)) { npm install -g @anthropic-ai/claude-code | Out-Null }
Write-Host '[3/5] Ключ приложения (только из сети Tailscale; прежний ключ DP AI заменяется)'
$ak = "$env:ProgramData\ssh\administrators_authorized_keys"
$keep = @(if (Test-Path $ak) { Get-Content $ak | Where-Object { $_ -notmatch ' dpai-android$' } })
Set-Content $ak ($keep + 'from="100.64.0.0/10" DPAI_KEY_LINE') -Encoding ascii
icacls $ak /inheritance:r /grant Administrators:F /grant SYSTEM:F | Out-Null
Write-Host '[4/5] Агент dpai'
$zip = "$HOME\Downloads\dpai-agent.zip"
if (Test-Path $zip) { Expand-Archive $zip -DestinationPath $HOME -Force; Set-Content "$HOME\dpai-agent.cmd" '@call "%~dp0dpai\bin\dpai.bat" %*' -Encoding ascii; & "$HOME\dpai-agent.cmd" agent doctor } else { Write-Warning "Нет $zip: сохраните его в приложении (Инструкция), перенесите на ПК и вставьте команду снова" }
Write-Host '[5/5] Поля для «Добавить» в приложении'
$h = ($HOME -replace '\\', '/')
$ip = if (Get-Command tailscale -ErrorAction SilentlyContinue) { (tailscale ip -4 | Select-Object -First 1) } else { 'нет Tailscale: winget install --id Tailscale.Tailscale -e' }
Write-Host "  Хост: $ip   Порт: 22   Пользователь: $env:USERNAME   Windows: отметить"
Write-Host "  Команда агента dpai: $h/dpai-agent.cmd"
Write-Host "  Каталог Claude слота: $h/.claude-slot-1"
Write-Host '  Отпечатки ключа хоста — сверить с «Получить ключ хоста»:'
Get-ChildItem "$env:ProgramData\ssh\ssh_host_*_key.pub" | ForEach-Object { ssh-keygen -l -f $_.FullName }

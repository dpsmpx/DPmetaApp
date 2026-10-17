# Настройка ПК с Windows как target одной командой (D-52; docs/SETUP_PC_WINDOWS.md, §2–§5).
# Строки — по одной инструкции: PcSetup склеивает их через «; » в одну строку для вставки.
# Строки с «#» в начале и пустые — пропускаются. DPAI_KEY_LINE — строка ключа приложения.
$ErrorActionPreference = 'Continue'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrators')) { Write-Warning 'Запустите PowerShell от администратора и вставьте команду снова'; return }
Write-Host '[1/5] OpenSSH Server и брандмауэр'
if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) { Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 | Out-Null }
Set-Service sshd -StartupType Automatic
Start-Service sshd
if (-not (Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue)) { New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (sshd)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 | Out-Null }
$cfg = "$env:ProgramData\ssh\sshd_config"
if ((Get-Content $cfg -Raw) -notmatch '(?m)^PasswordAuthentication no') { Copy-Item $cfg "$cfg.bak-dpai" -Force; Set-Content $cfg ("PasswordAuthentication no`r`n" + (Get-Content $cfg -Raw)) -Encoding ascii -NoNewline; Restart-Service sshd }
Write-Host '[2/5] Java и Git (Claude Code на ПК не нужен: он работает на телефоне, D-50)'
foreach ($p in 'EclipseAdoptium.Temurin.21.JRE', 'Git.Git') { winget install -e --id $p --silent --accept-source-agreements --accept-package-agreements | Out-Null }
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
Write-Host '[3/5] Ключ приложения (только из частных сетей — дом, VPN; прежний ключ DP AI заменяется)'
$ak = "$env:ProgramData\ssh\administrators_authorized_keys"
$keep = @(if (Test-Path $ak) { Get-Content $ak | Where-Object { $_ -notmatch ' dpai-android$' } })
Set-Content $ak ($keep + 'from="DPAI_NETS" DPAI_KEY_LINE') -Encoding ascii
icacls $ak /inheritance:r /grant Administrators:F /grant SYSTEM:F | Out-Null
Write-Host '[4/5] Агент dpai'
$zip = "$HOME\Downloads\dpai-agent.zip"
if (Test-Path $zip) { Expand-Archive $zip -DestinationPath $HOME -Force; Set-Content "$HOME\dpai-agent.cmd" '@call "%~dp0dpai\bin\dpai.bat" %*' -Encoding ascii; & "$HOME\dpai-agent.cmd" agent doctor; Write-Host '  (claude found:false на ПК — так и должно быть)' } else { Write-Warning "Нет $zip: сохраните его в приложении (Инструкция), перенесите на ПК и вставьте команду снова" }
Write-Host '[5/5] Поля для «Добавить» в приложении'
$h = ($HOME -replace '\\', '/')
$ips = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } | ForEach-Object { "$($_.IPAddress) ($($_.InterfaceAlias))" }) -join ', '
Write-Host "  Хост — адрес ПК в сети, общей с телефоном (домашний Wi-Fi или ваша VPN-сеть): $ips"
Write-Host "  Порт: 22   Пользователь: $env:USERNAME   Windows: отметить"
Get-NetConnectionProfile | ForEach-Object { if ($_.NetworkCategory -eq 'Public') { Write-Warning "Сеть $($_.InterfaceAlias) — «Общедоступная»: телефон может не достучаться; сделать частной: Set-NetConnectionProfile -InterfaceAlias '$($_.InterfaceAlias)' -NetworkCategory Private" } }
Write-Host "  Команда агента dpai: $h/dpai-agent.cmd"
Write-Host "  Каталог Claude слота: оставить пустым (Claude — на телефоне)"
Write-Host '  Отпечатки ключа хоста — сверить с «Получить ключ хоста»:'
Get-ChildItem "$env:ProgramData\ssh\ssh_host_*_key.pub" | ForEach-Object { ssh-keygen -l -f $_.FullName }

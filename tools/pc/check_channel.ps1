# Проверка канала к домашнему ПК (DPmetaApp:D-88, docs/PC_CHANNEL.md) — Windows 11, PowerShell.
# Только читает: адреса, маршрут, UPnP, службу SSH и брандмауэр. Ничего не меняет и никуда не
# отправляет, кроме двух запросов «какой у меня внешний адрес» (api.ipify.org, api6.ipify.org).
# Итог печатается без полных адресов — его можно прислать в сессию как есть.
#   powershell -ExecutionPolicy Bypass -File tools\pc\check_channel.ps1
$ErrorActionPreference = 'SilentlyContinue'

function Hide6([string]$a) { if (-not $a) { return '—' }; $p = $a.Split(':'); return ($p[0..1] -join ':') + ':…' }
function Hide4([string]$a) { if (-not $a) { return '—' }; $p = $a.Split('.'); return "$($p[0]).$($p[1]).…" }
function IsPrivate4([string]$a) {
    if (-not $a) { return $false }
    return $a -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.)'
}

$r = [ordered]@{}

# 1. IPv6: глобальный адрес от роутера и выход в интернет по IPv6.
$v6 = Get-NetIPAddress -AddressFamily IPv6 | Where-Object {
    $_.PrefixOrigin -eq 'RouterAdvertisement' -and $_.AddressState -eq 'Preferred' -and $_.IPAddress -notlike 'fe80*'
}
$stable = $v6 | Where-Object { $_.SuffixOrigin -ne 'Random' } | Select-Object -First 1
$r['IPv6: глобальный адрес'] = if ($v6) { 'есть' } else { 'нет' }
$r['IPv6: постоянный (не временный)'] = if ($stable) { Hide6 $stable.IPAddress } else { 'нет' }
$pub6 = Invoke-RestMethod -Uri 'https://api6.ipify.org' -TimeoutSec 10
$r['IPv6: выход в интернет'] = if ($pub6) { 'да' } else { 'нет' }

# 2. IPv4: внешний адрес и двойной NAT (CGNAT) по второму-третьему узлу маршрута.
$pub4 = Invoke-RestMethod -Uri 'https://api.ipify.org' -TimeoutSec 10
$r['IPv4: внешний адрес'] = Hide4 $pub4
$hops = (Test-NetConnection 1.1.1.1 -TraceRoute -Hops 4 -WarningAction SilentlyContinue).TraceRoute
$inner = @($hops | Select-Object -Skip 1 | Where-Object { IsPrivate4 $_ })
$r['IPv4: NAT за роутером (CGNAT)'] = if ($inner.Count -gt 0) { "похоже, да ($($inner.Count) частн. узл. после роутера)" } else { 'нет признаков' }

# 3. UPnP на роутере: можно ли открыть порт без ручной настройки.
$upnp = $null
try { $upnp = (New-Object -ComObject HNetCfg.NATUPnP).StaticPortMappingCollection } catch { }
$r['UPnP на роутере'] = if ($null -ne $upnp) { 'включён' } else { 'нет или выключен' }

# 4. SSH-сервер Windows: служба, порт, правило брандмауэра, профиль сети.
$sshd = Get-Service sshd
$r['OpenSSH Server'] = if ($sshd) { "$($sshd.Status)" } else { 'не установлен' }
$listen = Get-NetTCPConnection -State Listen -LocalPort 22
$r['Порт 22 слушается'] = if ($listen) { (($listen | ForEach-Object { $_.LocalAddress } | Sort-Object -Unique) -join ', ') -replace '[0-9a-f]{1,4}(:[0-9a-f]{1,4}){3,}', '…' } else { 'нет' }
$fw = Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP'
$r['Брандмауэр: правило SSH'] = if ($fw) { "$($fw.Enabled), профили: $($fw.Profile)" } else { 'нет' }
$r['Профиль сети'] = ((Get-NetConnectionProfile | ForEach-Object { "$($_.InterfaceAlias): $($_.NetworkCategory)" }) -join '; ')

# 5. Сон: ПК в сне снаружи недоступен.
$sleep = (powercfg /query SCHEME_CURRENT SUB_SLEEP STANDBYIDLE | Select-String 'Current AC Power Setting Index|Текущий индекс').ToString()
$r['Сон от сети (секунды, 0x0 — никогда)'] = if ($sleep) { ($sleep -split ':')[-1].Trim() } else { 'неизвестно' }

Write-Output ''
Write-Output '=== Канал к ПК: итог (адреса скрыты — можно прислать в сессию) ==='
$r.GetEnumerator() | ForEach-Object { Write-Output ("{0}: {1}" -f $_.Key, $_.Value) }
Write-Output ''
if ($stable) {
    Write-Output 'Для проверки с телефона (VPN включён) нужен полный постоянный IPv6 — он ниже, в чат его не присылайте:'
    Write-Output "  $($stable.IPAddress)"
}

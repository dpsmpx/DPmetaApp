# Security & Stability Audit — DPpass 0.5.0

Дата: 5 октября 2026.
Основание: review ветки feature/v0.5.0-adaptive относительно main, архитектурных документов и публичной документации Android/Xray/Zapret2.

## Статус

Кодовая реализация: внесена в отдельную ветку.

Хост-полный прогон: не выполнен из этого окружения.

Android device validation: не выполнена из этого окружения.

Поэтому этот документ является глубоким статическим аудитом реализации, а не заявлением о том, что Android APK уже прошёл физическую проверку.

## Критические проверки

### 1. Туннель до ядра

Сервис поднимает TUN до запуска кандидатов, но закрывает TUN при отсутствии успешной session. При остановке порядок обратный: сначала engine, затем fd TUN. Исходящие sockets ядра проходят через VpnService.protect.

Оценка: PASS по архитектурному контракту; device-only runtime не проверен.

### 2. DNS и IPv6

Существующая модель продолжает отправлять IPv6 в TUN, перехватывать DNS TUN и запрещать insecure TLS. Automatic domestic split действует после явных правил.

Оценка: PASS статически; отсутствие утечки на конкретных прошивках требует реального устройства.

### 3. Локальные входы

Xray не получает SOCKS/HTTP loopback input в APK. Тестовый loopback input остаётся отдельной host-only возможностью.

Оценка: PASS.

### 4. Логи и clipboard

В 0.5.0 автоматическая передача журнала в clipboard при Activity.onStop и остановке сервиса удалена. Clipboard доступен только по явной кнопке. Это существенно уменьшает возможность случайной передачи локального журнала другому foreground-компоненту.

Оценка: PASS статически.

### 5. Хранилище

Adaptive state использует тот же encrypted BlobStore, что и основная конфигурация Android. Сырые статистики не добавляют отдельный plaintext-файл.

Оценка: PASS архитектурно; реальные свойства Keystore находятся в device-only части этапа 3.

### 6. Adaptive poisoning

Модель имеет prior, EWMA, bounded state и hysteresis. Одно failure event не меняет профиль. Повторная деградация меняет порядок кандидатов и может запустить recovery.

Оценка: PASS по защите от одиночных сбоев; пороги требуют натурного калибрования.

### 7. Automatic domestic split

Явные правила выполняются раньше автоматического слоя. В Xray geosite и geoip условия разделены на отдельные правила, поэтому доменное и IP сопоставления действительно работают как независимые ветви.

Оценка: PASS после исправления компилятора.

### 8. Configuration migration

Формат поднят с v1 до v2. Есть явная migration 1→2 с тестом; новый routing flag устанавливается для старых документов.

Оценка: PASS.

## Residual security risks

### R1 — VPN нельзя полностью скрыть от Android-приложений

Android прямо моделирует VPN как TRANSPORT_VPN; приложения/системные API могут различать тип сети. DPpass не пытается это подделывать.

### R2 — VPN нельзя гарантированно скрыть от сайтов

Сайт может видеть IP выхода, TLS/HTTP поведение, адресные и временные характеристики. DPpass не контролирует серверную часть интернета.

### R3 — LAN bypass может раскрыть локальные candidates

Существующий bypass_lan выводит локальные сети из TUN. Это полезно для локальной сети, но не является режимом максимальной anonymity/privacy. Для строгого privacy-профиля в дальнейшем нужен отдельный policy, а не тихое изменение существующего поведения.

### R4 — adaptive probe overhead

Каждые 15 секунд выполняется health probe. Это добавляет небольшой сетевой и battery overhead. Значения должны быть измерены на реальных устройствах этапа 3.

### R5 — probe RTT не равен ICMP ping

Текущее измерение — end-to-end полезный health probe через proxy path. Это лучше для практической стабильности туннеля, чем голый ICMP, но нельзя интерпретировать его как чистый network-layer RTT.

### R6 — threshold calibration

BAD_JITTER_MILLIS=150 и BAD_RTT_MILLIS=3000 являются стартовыми значениями, а не результатом большого dataset. Они должны быть валидированы по реальным операторам и моделям устройств.

## Zapret2 comparison

Zapret2 auto-hostlist наблюдает повторяемые ошибки по hostname, использует окно времени и retransmission-based failure events, затем добавляет hostname в auto-list.

DPpass переносит именно идею feedback loop, а не raw packet desynchronization. Для Android VpnService это архитектурно корректнее и безопаснее, чем попытка воспроизвести Linux NFQUEUE механизм.

## Android privacy verification still required

Перед реальным 0.5.0 release необходимо на устройствах проверить:
- FLAG_SECURE на всех product screens;
- отсутствие clipboard export без explicit action;
- отсутствие внешнего чтения noBackupFilesDir;
- Keystore behavior after screen-lock credential changes;
- VPN start/stop races;
- process death and restart;
- Always-on / lockdown;
- DNS and IPv6 leak tests;
- battery impact of adaptive probe loop;
- jitter stability and false-recovery rate over long runs.

## Final static verdict

Security architecture: HIGH, with known non-solvable Android VPN visibility limits.

Stability architecture: HIGH potential, not yet empirically validated.

Release readiness: NOT READY.

Причина последнего статуса не в очевидной критической уязвимости ветки, а в том, что репозиторий сам требует device validation и полного hostcheck перед слиянием.
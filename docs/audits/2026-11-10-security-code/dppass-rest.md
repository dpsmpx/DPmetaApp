> Приложение к `docs/audits/2026-11-10-security-code.md` — полный отчёт прохода (DPpass: остаток модулей и оболочка ПК) как он сдан при сведении аудита (11 ноября 2026); номера находок те же, что в основном документе. Серьёзность, перепроверка и порядок исправлений — в основном документе; при расхождении прав он.

# Аудит DPpass — остаток (agent-dppass-rest)

Дата: 2026-11-11. Только чтение, `src/main`. Пути — от корня репозитория DPmetaApp.
Состояние: завершено.

## Находки

### R-01 — Средняя — ошибка кода (отмена, утечка TUN на Linux)
- `dppass/desktop/src/main/kotlin/com/dpsmpx/dppass/desktop/DesktopSession.kt:306-313` (и `:421-427` для режима без сервера); источники отмены — `:181-191` (`reconnect`), `:221-231` (`disconnect`: `running?.cancel()`); закрытие в ветках отказа — в отменяемом контексте `:399`, `:403`, `:441`.
- Что не так: `withContext(Dispatchers.IO) { graph.tunnels.establish(...) }` — блокирующий подъём TUN и правил (`LinuxTunnel.kt:53-82`). Если сеанс отменили, пока он идёт, `withContext` отбрасывает результат (CancellationException при возврате), и `DesktopTunnel` не закрывается: `try/catch (CancellationException)` начинается только с `:327`. Так же `withContext(Dispatchers.IO) { handle?.close() }` в `:399/:403/:441` при уже отменённой задаче не выполнит блок.
- Сценарий: «Подключить», затем сразу «Отключить» (или переподключение). `stopNow` видит `session == null` и ставит «Отключено», а правила `ip rule` и `dppass0` живы: трафик ПК уходит в TUN, который никто не читает (нет сети). Следующее подключение: `down()` снимает правила, `openTun` → EBUSY («устройство занято: DPpass уже запущен?») до перезапуска процесса.
- Рекомендация: подъём TUN — под `NonCancellable` с проверкой отмены после и закрытием; закрытие handle во всех ветках — `withContext(NonCancellable + Dispatchers.IO)`; или `try/finally` вокруг всего от establish до передачи владения.
- Уверенность: высокая (код + документированная семантика `withContext`). Известно: по документам нет; возможно, тот же класс, что уже найденная «утечка дескриптора TUN при отмене» (если та была про Android) — здесь ПК.

### R-02 — Средняя — расхождение с документом (T4, DNS_LEAK_RISK не доходит до пользователя)
- `dppass/config/src/main/kotlin/com/dpsmpx/dppass/config/ConfigLoader.kt:86-87` (предупреждение в `diagnostics`) → `dppass/connection/src/main/kotlin/com/dpsmpx/dppass/connection/Core.kt:190-192` (журнал — только ошибки), `:196-199` (`importAndActivate` берёт `l.value`) → `Activation.kt:166,178` (notes — без находок загрузчика) → `dppass/app/src/main/kotlin/com/dpsmpx/dppass/app/ui/ImportFlow.kt:99-104` (только ошибки, иначе тост «готово»); ПК — `dppass/desktop/src/main/kotlin/com/dpsmpx/dppass/desktop/ui/ImportFlow.kt:170-172`.
- Что не так: SECURITY_MODEL T4 называет мерой «предупреждение `DNS_LEAK_RISK`, если сервер DNS по умолчанию идёт мимо туннеля». Предупреждение вычисляется (`routing/Dns.kt` `DnsValidator`), но при импорте документа не показывается и не пишется в журнал; строк про утечку в интерфейсе нет.
- Сценарий: документ DPpass из «Поделиться»/буфера (T13) с `dns.default` → `route: direct` и `leak_protection: true`; окно подтверждения общее (R-10), после применения — «Документ применён». Имена всех сайтов уходят открытым DNS сети.
- Рекомендация: показывать предупреждения загрузки и активации (минимум `DNS_LEAK_RISK`) в окне подтверждения или после применения; логировать warnings.
- Уверенность: высокая. Известно: нет.

### R-03 — Средняя — то же на ПК (P-04): логин и пароль устройства раздачи — шлюзу любой сети
- `dppass/desktop/src/main/kotlin/com/dpsmpx/dppass/desktop/RelayNetworks.kt:61-65` (без адреса кандидаты — все частные шлюзы маршрутов по умолчанию), `:94-98` (SOCKS5 RFC 1929 — логин/пароль открытым текстом); вызовы: `ui/RelaysPanel.kt:100-103,156-168` (при открытии вкладки и смене списка — для КАЖДОГО сохранённого устройства), `DesktopSession.kt:255-266` (каждое подключение через устройство).
- Сценарий: ПК в кафе/чужой Wi-Fi; шлюз слушает любой порт и отвечает «метод 0x02» — получает логин/пароль всех сохранённых устройств раздачи, без нажатия пользователя (открыта вкладка).
- Рекомендация: как для P-04 — проверять сеть (имя/признак сети устройства, привязку к интерфейсу), не перебирать чужие шлюзы автоматически, сначала проверять приветствие без пароля.
- Уверенность: высокая. Известно: на Android — найдено (P-04); на ПК — то же, плюс автоматический перебор при открытии вкладки.

### R-04 — Низкая — T3: «Копировать журнал» на ПК без уровня STRICT
- `dppass/desktop/src/main/kotlin/com/dpsmpx/dppass/desktop/ui/LogPanel.kt:38,48`; `dppass/diagnostics/src/main/kotlin/com/dpsmpx/dppass/diagnostics/Log.kt:18-22,71` (Logger по умолчанию `Redactor()` = STANDARD); на Android — `LogExport.text`, `DeviceLog.kt:276` (STRICT).
- Что не так: на ПК в буфер уходит `LogEvent.render()` после STANDARD — IP-адреса и имена хостов (серверов, подписок, тексты ошибок ядра) остаются; T3 обещает журнал наружу «после STRICT (без IP и имён хостов)». На Linux у буфера нет пометки секрета.
- Рекомендация: копировать через тот же `Redactor(STRICT)`/`LogExport`.
- Уверенность: высокая. Известно: нет (F-1 — только про историю буфера Windows).

### R-05 — Низкая — отпечаток: самописный QUIC Initial проверки сети
- `dppass/network/src/main/kotlin/com/dpsmpx/dppass/network/Quic.kt:123-160`; пробы мимо туннеля — `SocketProber.kt:185-209`, план — `Assessment.kt:64-77`; автоматически после смены сети — D-67 (`ChangeChecks.kt`, `NetworkWatch.kt`).
- Что не так: ClientHello в Initial — 3 шифра, только X25519, без GREASE, без `psk_key_exchange_modes`, 3 параметра транспорта — не похож ни на один браузер. DPI, которая расшифровывает QUICv1 Initial (на это проба и рассчитана), узнаёт DPpass по пакету к `speed.cloudflare.com`/`www.google.com` — с реальным адресом пользователя, до 4 раз в час без нажатия.
- Рекомендация: записать в модель угроз (T8/T12) или формировать Initial по образцу распространённого клиента; хотя бы отключить UDP-пробу в авто-проверке.
- Уверенность: код — высокая; что DPI это использует — гипотеза. Известно: нет (D-60 говорит о «заморозке», не об отпечатке).

### R-06 — Низкая — «Очистить журнал подключений» не стирает копию в карантине
- `dppass/connection/src/main/kotlin/com/dpsmpx/dppass/connection/Analyzer.kt:343` (порча → `quarantine.keep("connections", bytes, …)`), `:519-525` (`clearJournal` удаляет только `connections/unique`); `dppass/storage/src/main/kotlin/com/dpsmpx/dppass/storage/Storage.kt:207-219` — удаления карантина нет вовсе.
- Сценарий: журнал уникальных однажды не прочитался (версия, сбой) — его полная копия (приложение, адрес, порт) живёт в `quarantine/connections-*.data` бессрочно; переключатель и «Очистить» её не трогают.
- Рекомендация: чистить карантин анализатора вместе с журналом или не карантинить журнал (D-106: стирание по кнопке).
- Уверенность: высокая (код); сценарий редкий. Известно: нет.

### R-07 — Низкая — D-98: правила поставщика с `apps` меняют выбор приложений
- `dppass/connection/src/main/kotlin/com/dpsmpx/dppass/connection/ProviderPolicy.kt:30-34` (правила поставщика целиком), `:64-75` (`notTaken` сверяет только `provider.apps`); `dppass/subscriptions/src/main/kotlin/com/dpsmpx/dppass/subscriptions/Pipeline.kt:64` (маршрутизация документа без фильтра); `dppass/tunnel/src/main/kotlin/com/dpsmpx/dppass/tunnel/Tunnel.kt:63` → `routing/AppLowering.kt:193-207`.
- Что не так: окно согласия (`ui/ProviderPolicyDialog.kt` consent, на Android — `provider_policy_confirm`) обещает «ваши остаются: выбор приложений». Ведущие правила поставщика `apps → direct` (своих списков нет — по умолчанию) переносятся в исключения VpnService: приложение молча идёт мимо туннеля; при своих списках те же правила дают отказ активации (`APP_RULES_IN_ENGINE`).
- Рекомендация: отвергать/снимать условия `apps` в маршрутизации поставщика с находкой, как `Layers.reserved`.
- Уверенность: высокая (код). Влияние ограничено: поставщик с согласием и так может `default: direct`. Известно: нет.

### R-08 — Низкая — импорт из буфера на Android: чтение без предела на главном потоке
- `dppass/app/src/main/kotlin/com/dpsmpx/dppass/app/ui/ImportFlow.kt:57-58` (`fromClipboard`), `:256-257` (кнопка «Вставить»); ср. файл и «Поделиться» с пределом — `MainActivity.kt:148,502`.
- Что не так: `ClipData.Item.coerceToText` для `content://` читает поток целиком (AOSP), синхронно на UI-потоке; предел 1 МиБ проверяется только потом в `ImportClassifier`.
- Сценарий: приложение кладёт в буфер URI своего провайдера с бесконечным потоком; пользователь жмёт «Из буфера» — ANR/OOM.
- Рекомендация: брать только `item.text`, URI — через ограниченный поток в фоне.
- Уверенность: гипотеза по поведению Android (код DPpass — подтверждён). Известно: нет.

### R-09 — Низкая — сопряжение: отмена в момент accept теряет сокет
- `dppass/app/src/main/kotlin/com/dpsmpx/dppass/app/ui/ShareScreen.kt:224-230`; `dppass/app/src/main/kotlin/com/dpsmpx/dppass/app/PairingLink.kt:50`.
- Что не так: `withContext(Dispatchers.IO) { PairingLink.acceptOne(...) }` вернул `Socket`, а задача уже отменена (закрыли окно) — результат отброшен, принятый сокет не закрыт до выхода процесса.
- Уверенность: высокая, окно гонки узкое. Известно: нет.

### R-10 — Инфо — окно применения документа без сводки
- Android `ui/ImportFlow.kt:89-94` (`import_document_text`: «Серверы, маршрутизация и DNS из документа заменят текущие»); ПК `ui/ImportFlow.kt:138`.
- Пользователь не видит, что документ, например, ведёт всё `direct` или DNS мимо туннеля; формально D-42 выполнено. Связано с R-02.

### R-11 — Инфо — то же на ПК: `split_sites` открытым текстом
- `dppass/desktop/src/main/kotlin/com/dpsmpx/dppass/desktop/DesktopPrefs.kt:64-66,86-96` — файл Properties в каталоге данных (0700), не в зашифрованном хранилище. Аналог уже найденного на Android.

### R-12 — Инфо — ссылка `socks5://` с паролем через системный выбор
- `dppass/app/src/main/kotlin/com/dpsmpx/dppass/app/ui/ShareScreen.kt:361-370`. У системного окна «Поделиться» (Android 13+) своя «Копировать» — без `EXTRA_IS_SENSITIVE`. Гипотеза по поведению системы; намерение задокументировано.

### R-13 — Инфо — `User-Agent: DPpass` ориентиру с реальным адресом
- `dppass/network/src/main/kotlin/com/dpsmpx/dppass/network/SocketProber.kt:226,292`. По D-60 так задумано; при этом T14/D-60 говорят «о пользователе ничего» — факт использования DPpass Cloudflare видит, с D-67 — без нажатия. Внутри TLS, сети не виден.

### R-14 — Инфо (гипотеза) — `connectServerless` останавливает ядро только при отмене
- `dppass/connection/src/main/kotlin/com/dpsmpx/dppass/connection/Core.kt:500-507`: иное исключение из `check` оставит запущенное ядро с TUN. Нынешние `check` возвращают `Outcome` — практического пути не нашёл.

### R-15 — Инфо — `SplitSites.parse` цитирует строку пользователя
- `dppass/routing/src/main/kotlin/com/dpsmpx/dppass/routing/SplitSites.kt:535,543` — в отличие от `SiteEntry.parse` (D-22). Ввод пользователя, не секрет.

### R-16 — Инфо — согласие D-98 не показывает, что придёт
- `dppass/desktop/src/main/kotlin/com/dpsmpx/dppass/desktop/ui/ProviderPolicyDialog.kt:59-75`: общее согласие без действия по умолчанию и маршрута DNS поставщика; дальше правила меняются с подпиской. Задокументировано (T2), но вместе с R-02 пользователь не узнаёт о DNS мимо туннеля от поставщика (не проверял, выводит ли конвейер подписки предупреждения в окно согласия).

## Покрытие

Полностью:
- routing: Model, Layers, AppLowering, Dns, RuleIndex, RouteEvaluator, RoutingValidator, SiteLists, SplitSites.
- connection: UserPolicy (PolicyBuilder/Store/Codec/Service), Policy (HealthBook/LatencyBook/CandidatePlanner), Serverless, SplitFallback, ProviderLists, ProviderPolicy, Analyzer, Usage, Regime, Adaptive, StabilityBook.
- engine: CapabilityChecker; xray/Geodata, Health, Speed, XrayProxyEngine, XrayBackend, XrayRuntime, XrayIpv6, XrayGeoResolver.
- network: SocketProber, Quic, Assessment, ChangeChecks, Cooldown, Quiet, UnderlyingNetworks.
- app: NetworkCheck, TetherInterfaces, PairingLink, QrPicture (чтение); ui/QrCamera, QrCameraScreen, ShareScreen, PairingDialog, LocalProxyScreen, LogScreen, ImportFlow, ExportFlow.
- desktop: DesktopSession, RelayNetworks, RelayPairing, WindowsRelayNetworks, LinuxShareInterfaces, WindowsShareInterfaces, ShareWatch, SessionWatch, NetworkWatch, SubscriptionAutoUpdate, SocketOwners, LinuxProbeNetwork, DesktopNetworkCheck, DesktopPrefs, SelfCheck, WindowsSelfCheck; ui/SecretClipboard, ImportFlow, LocalProxyDialog, SharePanel, LogPanel, ProviderPolicyDialog.

Выборочно:
- connection/Core.kt (155-235, 315-560), Activation.kt (160-200), ConnectionTable/ConnectionCells (оглавление).
- engine/Engine.kt (LocalProxySpec, ShareSpec, оглавление).
- app: ExpiryAlarms (PendingIntent, приёмники, уведомление); ui/Ui.kt (secure/copy/sheet), SettingsScreen (change, editSplitSites, choiceRow), RelaysScreen (pairWithHost); Connections/Subscriptions/Servers/Explain/Apps — grep по копированию/секретам/диалогам.
- desktop/ui: RelaysPanel (rescan, pair, addDevice), ConnectionsPanel (опрос, переключатели); прочие панели — grep по буферу/файлам/процессам.
- Для проверки сценариев: config/ConfigLoader.kt (36-100), subscriptions/Pipeline.kt (255-290), connection/Subscriptions.kt (270-295, 380-384), tunnel/Tunnel.kt (55-95), desktop/LinuxTunnel.kt (53-105), storage Quarantine (grep), diagnostics Log.kt/DeviceLog.kt (фрагменты).

Не смотрел: network/Model.kt; connection Orchestrator, HappRouting (кроме ссылок); app/ui GridTable, Presentation, PowerButton, PullToRefresh, AboutScreen, ExplainScreens целиком, ServersScreen/SubscriptionsScreen/ConnectionsScreen/AppsScreen/UsageScreen/ExpiryReminderScreen целиком; desktop DesktopPlatform (оглавление), DesktopNotify, LinuxNative, WindowsNative, ui/ConnectPanel, DesktopWindow, NetworkPanel, ServersPanel, SubscriptionsPanel, SiteListsDialog, SplitSitesDialog, UsageDialog, Widgets, Format, AppIcon.

## Что проверено и в порядке
- Маршрутизация: суффикс — по границе метки; индекс правил даёт то же первое правило; записи списков нормализуются `Host.normalizeDomain`, `keyword:` — только ASCII, `regexp:` — отказ; предел 1000/200; запись в двух списках — ошибка; порядок BLOCK > PROXY > DIRECT; свои списки раньше поставщика; списки поставщика — только при `useProviderLists`; id слоёв у поставщика — отказ ревизии.
- Пустой «только выбранные» после переноса правил — отказ (`tunnel/Tunnel.kt:67`); политика читается строго, пакеты проверяются.
- Без сервера: DNS только напрямую, `leakProtection=false` по смыслу режима; пустой свой выбор приложений — отказ туннеля.
- Анализатор: журнал уникальных — только при `keepUnique`; без байтов и источника; предел 50 000 строк и 255 символов; выключение очищает учёт моста и память; хранение — зашифрованное.
- Учёт трафика — агрегаты по суткам и типу сети; адаптивная модель — только числа, ключ «контекст|id профиля» (id — усечённый SHA-256 ссылки), 256 записей, разбор устойчив к мусору.
- Движок: геоданные — SHA-256 и размер каждого файла при переносе, атомарное переименование, имена без путей; версия ядра закреплена; тексты ядра — через `Redactor` и с пределом; замер скорости — через `proxy`, 25 МБ/8 с, пауза; проба IPv6 — через сервер.
- Сеть: TLS — платформенный, с `endpointIdentificationAlgorithm=HTTPS`; подмена DNS (bogon); бюджет D-14, тайм-ауты; Android — сокеты и резолвер сети под туннелем; Linux — `SO_MARK`, без метки сокет закрыт; Windows — привязка к интерфейсу или закрытие.
- Раздача: Android — интерфейсы, названные системой, или не upstream с двумя снимками; ПК Linux — только `ipv4.method shared` через `/usr/bin/nmcli`, UUID проверяется, upstream исключён; Windows — ICS; везде частные IPv4, отказ при сомнении.
- Сопряжение (хозяин): порт только на адресах раздачи, одно соединение, окно 2 мин, отмена опрашивается; код сверяется в диалоге под `FLAG_SECURE`.
- QR: разрешение камеры перепроверяется, кадры в памяти, проверка размера плоскости Y, камера закрывается в фоне; картинка — предел 32 МиБ и пикселей.
- Диалоги с секретами — `Ui.secure`; копирование логина/пароля — с `EXTRA_IS_SENSITIVE`; экспорт с секретами — только в файл после предупреждения; на Windows — форматы запрета истории/облака.
- Напоминания: `PendingIntent` IMMUTABLE, приёмники не экспортированы, публичная версия уведомления без имени подписки.
- Автообновление подписок на ПК: срок — относительный, 1 мин…1 ч.

# Модель безопасности

Угрозы и защита DPmetaApp 1.0 — ТЗ, §11 (T-01…T-18); здесь — что из этого
уже есть в коде, где и чем проверяется. Пункты проходятся целиком на
выпуске (этап 8). Подсистемы — по своим документам (`dpai/docs/SECURITY_MODEL.md`,
`dppass/docs/SECURITY_MODEL.md`).

## Сделано (этапы 2–3)

| | Угроза | Защита в коде | Где | Проверка |
|---|---|---|---|---|
| T-01 | Кража или потеря телефона | вход по PIN устройства на всё приложение — пульт и «AI», в том числе из уведомлений и «недавних»: раз за запуск процесса и после часа в фоне; отказ закрывает окна, служба «AI» и уведомления работают (D-59); токен GitHub — под ключом Android Keystore (AES-256-GCM), ключ открыт 5 минут после входа, закрыт — пульт просит вход, когда токен нужен | `app/…/AppLock.kt`, `app/…/LockPolicy.kt`, `app/…/LockActivity.kt`, `shell/…/DpmetaApplication.kt`, `app/…/data/SecretStore.kt` | `LockPolicyTest`; на устройстве (`TODO.md`, «На устройстве») |
| T-02 | Утечка секрета через журнал, снимок экрана, резервную копию | тип `Secret` (печатается как `<redacted>`); раскрытие — в одном месте `:core:net` (проверка `modules.root`); шифротекст — в `noBackupFilesDir`; окно пульта — `FLAG_SECURE`; ошибка запроса не несёт значения заголовка | `core/net/…/Auth.kt`, `core/net/…/JdkTransport.kt`, `UrlTransport.kt`, `app/…/PultActivity.kt` | `NetTest.badHeaderIsRefusedWithoutItsValue`, `GitHubTest.deviceFlowStepsWithoutWaiting`, `tools/check` (`modules.root`) |
| T-03 | Вредоносное приложение на том же телефоне | из активностей экспортирован только вход лаунчера — `DpmetaActivity` (пульт за входом биометрией или PIN, F-45); экран «AI» и `PultActivity` не экспортируются, вход «AI» из лаунчера снят; «AI» открывается из своей вкладки явным интентом (D-53); ещё наружу — поставщик диска «AI» `AppDiskProvider`, только под `MANAGE_DOCUMENTS` системы (`DP_AI_Orcestrator:D-96`) | `shell/src/main/AndroidManifest.xml`, `app/src/main/AndroidManifest.xml` | Lint; манифест APK (`aapt2 dump badging`) |
| T-04 | Подмена ответов сети | только https с системными корнями, своих `TrustManager` нет; переадресации выключены (адрес не уходит мимо списка); http — только сайт и без секрета | `core/net/…/Net.kt` (`AllowList`) | `NetTest.refusesHostsNotInListAndPlainHttpWithSecrets` |
| T-04 | Подмена ответов сети: ссылки в документах (F-09) | внешние адреса в документах только подчёркиваются — пульт их не открывает; переходы — только по файлам репозиториев реестра, без выхода за корень; файлы кода не сохраняются в кэше | `app/…/screens/Links.kt`, `app/…/ui/DocText.kt` | `LinksTest` |
| T-07 | Лишние права токенов | PAT — только чтение (Contents, Pull requests, Metadata), так и просит экран; GitHub App — device flow (D-17) | `app/…/ui/PultUi.kt`, `core/github/…/DeviceFlow.kt` | `GitHubTest` |
| T-09 | Цепочка поставки | сторонних зависимостей у модулей DPmetaApp нет: JDK, ядро `dpai/`, Compose из того же каталога, что у оболочки | `docs/PROVENANCE.md` | `tools/check` |
| T-11 | Обход правил выгрузки сайта через приложение | выгрузки сайта в коде приложения нет — ни команды, ни адреса, ни ключа хостинга; выгружает только подсистема SocialMediaImage (D-40, D-48); изменяющих вызовов Admin API, скрейпинга и автоматизации браузера нет (D-08, D-20) | весь код приложения | `tools/check` (`code.forbidden`; A-06, A-07) |

## Ещё нет

- T-05, T-06, T-17 — действия и чат Claude: этап 5 (`:core:actions`).
- T-08 — экран блокировки и виджет: этап 7.
- T-10 — ключ выпуска: этап 8.
- T-12…T-16 — площадки, мгновенный канал, слушатель уведомлений: этапы 6–7.
- T-18 — журнал отладки файлом: этап 5.

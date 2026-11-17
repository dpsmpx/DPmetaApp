# Модель безопасности

Угрозы и защита DPmetaApp 1.0 — ТЗ, §11 (T-01…T-18); здесь — что из этого
уже есть в коде, где и чем проверяется. Пункты проходятся целиком на
выпуске (этап 8). Подсистемы — по своим документам (`dpai/docs/SECURITY_MODEL.md`,
`DPpass:docs/SECURITY_MODEL.md`).

## Сделано (обзор T-01…T-11 — `docs/audits/2026-11-15-threats.md`)

| | Угроза | Защита в коде | Где | Проверка |
|---|---|---|---|---|
| T-01 | Кража или потеря телефона | вход по PIN устройства на всё приложение — пульт и «AI», в том числе из уведомлений и «недавних»: раз за запуск процесса и после часа в фоне (монотонное время, перевод часов не обходит); до входа окна невидимы и каждое показанное закрыто экраном входа, в «недавних» снимков окон нет (Android 13+); отказ закрывает все окна во всех задачах, служба «AI» и уведомления работают (D-59); токен GitHub — под ключом Android Keystore (AES-256-GCM), ключ открыт 5 минут после входа, закрыт — пульт просит вход, когда токен нужен; блокировку экрана сняли — Keystore закрывает ключ навсегда: ключ сбрасывается, токен вводится заново; окно PIN закрыто без входа (в том числе «Домой», погас экран) — не отказ: экран входа с «Войти», окно снова при показе; отказ — только «Закрыть» или «назад» на экране входа; окно запроса «AI» поверх приложений даёт опасный ответ только без нужды во входе, иначе — подтверждение на экране «AI» после входа (D-61) | `app/…/AppLock.kt`, `app/…/LockPolicy.kt`, `app/…/LockActivity.kt`, `shell/…/DpmetaApplication.kt`, `app/…/data/SecretStore.kt`, `dpai/android/…/RequestOverlay.kt` | `LockPolicyTest`; на устройстве (`TODO.md`, «На устройстве») |
| T-02 | Утечка секрета через журнал, снимок экрана, резервную копию | тип `Secret` (печатается как `<redacted>`); раскрытие — в одном месте `:core:net` (проверка `modules.root`); шифротекст — в `noBackupFilesDir`; окно пульта — `FLAG_SECURE`; ошибка запроса не несёт значения заголовка | `core/net/…/Auth.kt`, `core/net/…/JdkTransport.kt`, `UrlTransport.kt`, `app/…/PultActivity.kt` | `NetTest.badHeaderIsRefusedWithoutItsValue`, `GitHubTest.deviceFlowStepsWithoutWaiting`, `tools/check` (`modules.root`) |
| T-03 | Вредоносное приложение на том же телефоне | из активностей экспортирован только вход лаунчера — `DpmetaActivity` (за входом по PIN, D-59); экран «AI» и `PultActivity` не экспортируются, вход «AI» из лаунчера снят; «AI» открывается из своей вкладки явным интентом (D-53); ещё наружу — поставщик диска «AI» `AppDiskProvider`, только под `MANAGE_DOCUMENTS` системы (`DP_AI_Orcestrator:D-96`); поставщик «Поделиться» экспортом `ExportProvider` не экспортируется, видит только каталог экспорта в кэше, право чтения — по URI получателю одной отправки (D-67) | `shell/src/main/AndroidManifest.xml`, `app/src/main/AndroidManifest.xml` | Lint; манифест APK (`aapt2 dump badging`) |
| T-04 | Подмена ответов сети | только https с системными корнями, своих `TrustManager` нет; переадресации выключены (адрес не уходит мимо списка); http — только сайт и без секрета | `core/net/…/Net.kt` (`AllowList`) | `NetTest.refusesHostsNotInListAndPlainHttpWithSecrets` |
| T-04 | Подмена ответов сети: ссылки в документах (F-09) | внешний адрес (D-75): первое касание копирует и показывает его целиком, второе той же ссылки открывает в браузере телефона — только `https` с хостом; `http`, `intent:`, `javascript:`, `file:` и прочие не открываются — адрес остаётся скопированным, на экране — почему; правило одно на пульт, «AI» и терминал; переходы — только по файлам репозиториев реестра, без выхода за корень; файлы кода не сохраняются в кэше | `dpai/ui/…/LinkTaps.kt`, `app/…/screens/Links.kt`, `app/…/ui/DocText.kt`, `app/…/ui/PultUi.kt` (`external`) | `LinkTapsTest`, `LinksTest` |
| T-05 | Подсказка-инъекция из репозиториев и PR в чат | инструменты «Спросить Claude» только читают срез (шесть, `AskActions.TOOLS`); действий из ответа чата нет | `app/…/screens/AskActions.kt` | `AskActionsTest` |
| T-06 | Ошибочное значимое или опасное действие | `:core:actions`: уровни 0–3, экран «что изменится», вход устройства для уровня 2, красный экран и ввод имени для уровня 3, журнал (D-07, D-37) | `core/actions/…/Actions.kt` | `ActionsTest`, `RouteActionsTest` |
| T-07 | Лишние права токенов | PAT: для чтения — «Read-only» (Contents, Pull requests, Metadata; по желанию Issues, Checks, Commit statuses — issues и CI карточки, D-176), запись — только для действий с PR (D-80) и «Опубликовать» (D-150), так и просит экран; GitHub App — device flow (D-17); Admin API — только `GET` и пути из списка; токен маршрута — свой у каждого | `app/…/ui/PultUi.kt`, `core/github/…/DeviceFlow.kt`, `dpai/providers/…/AdminClient.kt` | `GitHubTest`, `AdminClientTest` |
| T-08 | Закрытое на экране блокировки и в виджете | приватность уведомлений по умолчанию включена: на блокировке — только вид события; виджет — только числа и даты (D-144) | `app/…/data/PultAlerts.kt`, `app/…/screens/WidgetText.kt` | `WidgetTextTest` |
| T-09 | Цепочка поставки | версии — только каталогом `dpai/gradle/libs.versions.toml`, без динамических; проверка сумм Gradle (`verification-metadata.xml`, `verify-metadata=true`) у обеих сборок; строка PROVENANCE у каждой зависимости (JGit, JSch, Compose и др.) | `gradle/`, `dpai/gradle/`, `docs/PROVENANCE.md`, `dpai/docs/PROVENANCE.md` | `tools/check` (`build.verification`), `dpai/tools/verify/check_dependencies.py` |
| T-10 | Потеря ключа подписи | ключ — у автора (D-158): две копии .p12 и пароль отдельно, копия в приложении — под паролем (D-157); подпись v2 и v3 (ротация ключа) — release Gradle, `apksigner`, подпись в приложении | `dpai/build-logic/…/dpai.android-application.gradle.kts`, `dpai/android/…/ApkSign.kt`, `docs/RELEASE.md` | `apksigner verify` release (обзор угроз 15 ноября 2026), `tests/test_check_apk.py` |
| T-11 | Обход правил выгрузки сайта через приложение | SocialMediaImage вынесен из DPmetaApp (D-154): выгрузки сайта, адресов хостинга и ключей в репозитории нет; изменяющих вызовов Admin API, скрейпинга и автоматизации браузера нет (D-08, D-20) | весь код приложения | `tools/check` (`code.forbidden`; A-06, A-07) |

## Остальные (T-12…T-18)

- T-12, T-15 — площадки и публичные ответы: вне приложения (D-154,
  SocialMediaImage).
- T-13, T-14 — сняты D-173: мгновенный канал ntfy удалён.
- T-16 — снята D-173: службы чтения чужих уведомлений и запроса доступа
  к ним в приложении нет. Прежние события остаются читаемыми.
- T-17 — уровень 3 только после входа (D-37, D-59).
- T-18 — журнал отладки файлом в приложении (D-39).

Построчно — отдельным обзором; хвосты проверки PR #190 — `TODO.md`, «Система».

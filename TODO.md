# TODO

Текущие задачи, известные долги и то, что ещё не проверено. Этапы —
`docs/direction/ROADMAP/README.md`; решения — `docs/direction/DECISIONS.md`.
Закрытая запись удаляется (история — в git) или переносится в документ,
если стала знанием.

## Следующее (этап 2)

- [ ] Выбрать версию Xray по чек-листу D-18; перевести строку Xray в
      `docs/legal/THIRD_PARTY_MATRIX.md` в «разрешён».
- [ ] Матрица возможностей Xray и компилятор IR → JSON Xray с эталонными
      файлами по корпусу (`docs/direction/ROADMAP/02-xray-backend.md`).
- [ ] Эксперимент «мост fd → ядро»: TUN inbound Xray против
      hev-socks5-tunnel; итог — новое решение.
- [ ] Шаг hostcheck: выравнивание нативных библиотек APK по 16 КБ.

## Известные долги (из аудита)

Подробности и обоснование — `docs/audits/INITIAL_ARCHITECTURE_AUDIT.md`.

- [ ] **Ссылки подписки обходят проверку значений.** `ShareLinkParser`
      принимает `sni` и `host` без проверки, а `ConfigValidator.validateProfile`
      их не проверяет: ссылка с `sni=bad%20name` даёт пригодный профиль,
      тот же профиль документом отвергается (`SERVER_NAME_INVALID`,
      `DOMAIN_INVALID`). Перенести проверку значений защиты и
      транспорта в `validateProfile` и добавить файл корпуса.
- [ ] `KeystoreKeyProvider`: сбой Keystore при расшифровке ключа данных
      `EncryptingBlobStore` выдаёт за порчу записи (`STORAGE_CORRUPTED`/
      `STORAGE_READ_FAILED`), а `IOException` и `error(…)` из провайдера
      не перехватываются вовсе; `dek.wrapped` пишется без `fsync` перед
      переименованием. Нужен отдельный код «ключ хранилища недоступен»,
      атомарная запись ключа и путь восстановления без потери исходников
      (этап 3).
- [ ] `MainActivity` не запрашивает `POST_NOTIFICATIONS` (Android 13+):
      уведомление сервиса не будет видно без разрешения (этап 4 —
      вместе с интерфейсом; до этого — запись здесь).
- [ ] `SimulatedHealthChecker` лежит в основном коде `connection/` и
      попадает в APK; не используется приложением (`check_modules.py`
      это охраняет). Перенести в тестовую фикстуру, когда у `cli`
      появится своя модель сети.
- [ ] `RouteEvaluator` перебирает правила линейно: ~120 мкс на решение
      при 2000 правил на хосте (`docs/PERFORMANCE_BUDGET.md`, §3). Для
      объяснения решений достаточно; если понадобится массово —
      индекс по суффиксам.
- [ ] `ActivationStore.parse` молча пропускает повреждённые записи
      истории (`mapNotNull`) и записи `active`/`previous`, а при
      повреждённом `next_revision` считает ревизии заново с 1;
      повреждение стоит отмечать находкой, как и прочее содержимое.
- [ ] Проверка зависимостей Gradle (`gradle/verification-metadata.xml`,
      SHA-256): версии закреплены, содержимое артефактов — нет (T10).
      Генерировать там, где есть Android SDK, — иначе не войдут
      зависимости `app`.
- [ ] Тесты на пределы без тестов: `maxProfiles`, `maxRuleValues`,
      `maxEntries` подписки, история активаций (32), тайм-аут проверки
      здоровья (`docs/PERFORMANCE_BUDGET.md`, §2).
- [ ] Устаревшее в сборке: `android.useAndroidX=false` (удаляется в
      AGP 10), `Configuration.setVisible` из плагина `com.android.lint`
      (удаляется в Gradle 11) — разобрать при обновлении по D-18.
- [ ] D-12 для сборки: проверить, что `fus-statistics-gradle-plugin`
      (у Kotlin Gradle Plugin) и `analytics-library` (у AGP) ничего не
      отправляют при сборке из командной строки и в CI; при
      необходимости выключить явно и записать способ проверки.

## На устройстве

Написано, но на реальном устройстве ещё не проверялось (этап 3):

- [ ] `KeystoreKeyProvider`: создание и чтение ключа, перезапуск
      процесса, обновление приложения, смена блокировки экрана.
- [ ] `EncryptingBlobStore` + `FileBlobStore` в `noBackupFilesDir`:
      атомарная замена на файловых системах устройства.
- [ ] `DPpassVpnService`: foreground `specialUse` на Android 14+,
      уведомление, `onRevoke`, остановка с `BACKEND_NOT_AVAILABLE`.
- [ ] `FLAG_SECURE` в оболочке: скриншоты и миниатюры «Недавних».
- [ ] Правила `data_extraction_rules.xml`: резервная копия и перенос
      ничего не уносят.
- [ ] `AndroidTunnelProvider`: Builder принимает `TunnelSpec` (API 26,
      29, 33+; с `excludeRoute` и без) — вместе с реальным ядром.

## Исследования

- [ ] Обновить `docs/research/RUSSIA_NETWORK_RESTRICTIONS.md` перед
      этапом 3 (пробы сети) — состояние меняется неделями.
- [ ] Повторить опись `tools/research/happ_inventory.sh` перед этапом 4
      (импорт профиля маршрутизации — отложенное решение).

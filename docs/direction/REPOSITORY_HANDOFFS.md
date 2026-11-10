# Где сохранены решения автора — 11 ноября 2026

Принято автором: D-86…D-90. Карта ролей —
`docs/direction/PROJECT_ROLES.md`; ниже проверяемые места сохранения.
Отдельные ветки не означают автоматического слияния или начала
разработки соответствующих приложений.

| Репозиторий | Сохранённая запись/работа |
|---|---|
| DPmetaApp | D-86…D-90, карта ролей, ТЗ, мастер-ТЗ, ROADMAP, TODO; [PR #183](https://github.com/dpsmpx/DPmetaApp/pull/183) |
| DPmeta | M-17 и полная карта в `dpmeta/`; [запись в прежнем репозитории](https://github.com/dpsmpx/DPmeta/blob/dpmeta/mobile-studio-roles/docs/PROJECT_ROLES.md), `e5b8426`, без возобновления второго источника |
| SocialMediaImage + DPprofile | [PR #33](https://github.com/dpsmpx/SocialMediaImage/pull/33), `846da8a`: актуальная контентная система и компонент с обеими исходными историями, требования будущего приложения, без новой функциональности |
| DP-Verify | [Документ в main](https://github.com/dpsmpx/DP-Verify/blob/main/docs/DP_META_APP.md), `4e19caf`: V-23, общая папка, обновление в простое, проверенный прямой main без PR |
| dp_termux_setup | [Роль](https://github.com/dpsmpx/dp_termux_setup/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `3011978` |
| build_tools | [Роль](https://github.com/dpsmpx/build_tools/blob/dpmeta/mobile-studio-roles/docs/DP_META_APP.md), `d8ef970`; сгенерированный README не правился |
| DPlang / будущая IDE | [PR #48](https://github.com/dpsmpx/DPlang/pull/48), `b5f1e0b`: самостоятельная завершённость и проверка до включения |
| DPlearn | [Роль](https://github.com/dpsmpx/DPlearn/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `2ce5181` |
| DP_game_market_analytic_system | [Роль](https://github.com/dpsmpx/DP_game_market_analytic_system/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `c48a624` |
| DPmonetization | [Роль](https://github.com/dpsmpx/DPmonetization/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `7f496b1`: будущая часть SocialMediaImage, прежнее поручение архивировать отменено |
| DP_Repeated_Tasks | [Роль](https://github.com/dpsmpx/DP_Repeated_Tasks/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `dfabc84`; самостоятельность DP_Clock и постоянные задачи уже записаны в документации часов |
| DP_TextFile_Sorter | [Роль](https://github.com/dpsmpx/DP_TextFile_Sorter/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `bf51462` |
| Анализатор знаний | [Запись в изолированной линии](https://github.com/dpsmpx/MAIN_OBSIDIAN_PC_works/blob/dpmeta/knowledge-mobile-role/KNOWLEDGE_SYSTEM/docs/PROJECT_ROLE_2026_11_11.md), `676aea9`; линия PR #1 и личный vault не сливались |
| DP_Backup_Tool | [Роль](https://github.com/dpsmpx/DP_Backup_Tool/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `3567bbf` |
| dpcalls | [Роль](https://github.com/dpsmpx/dpcalls/blob/dpmeta/mobile-studio-roles/docs/direction/DP_META_APP.md), `41704bc` |
| DPpass | DPpass:D-109 в актуальном `dppass/`; архивированный прежний репозиторий не менялся |
| DPvisualisation | DPvisualisation:D-30 в актуальном `dpvisualisation/` |
| pascalabcnet, TrueJointTileMaker | Роли сохранены в общей карте; сами репозитории не менялись по поручению автора |

## Что проверено и что осталось

DP-Verify: `./dpv run full --ci` на `4e19caf`, 11/11, базовая линия 0;
публикация прямо в main выполнена после повторной проверки head.
Работа PR #14 не включалась, номер 22 сохранён за его решением.

Перенос SocialMediaImage: 359 файлов контентной системы и 144 файла
DPprofile совпадают по Git blob с экспортами, кроме перечисленных в
документе переноса адаптаций процесса. Предки обеих исходных линий
сохранены. `tools/check.sh --fast`: 263 теста, PASS, 3 пропуска рендера;
quick обеих систем проходит. Перед переключением основного источника
остаются полная приёмка/ревью переноса и удаление временных обязательных
связей в DPmetaApp. Новую функциональность SocialMediaImage не начинать.

Самопроверка DPprofile — 49/50: прежняя фикстура чистой копии без Git
не подтверждает уже выполненные 30 дней стабильности OUTLINE; тот же
сбой воспроизведён в исходном DPmetaApp, записан в TODO компонента и
корня. Факты и реальная история проверяются успешно.

DPlang: проверки ссылок и секретов проходят; полный прогон не прошёл
из-за неподготовленного компилятора и части эталонов. Код языка не
менялся, PR остаётся черновиком до штатного полного прогона.
DPlearn — static, DPmeta — свои проверки/самотесты проходят.
Остальные отдельные записи — документационные, без смены данных,
методик, языков или запуска инфраструктуры.

Для канала DPpass автор выбрал сначала проверку прямой достижимости
домашнего ПК; посредник будет выбран только по результатам. Фактическую
топологию с этого облачного окружения проверить нельзя. Адреса и ключи
автора не запрашивались и не читались.

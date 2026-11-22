package com.dpsmpx.dpai.ui

/** Действие оболочки: сохранить архив агента для ПК или открыть настройки приложения. */
enum class GuideAction(val label: String) {
    SAVE_AGENT("Сохранить dpai-agent.zip в «Загрузки»"),
    APP_SETTINGS("Открыть настройки приложения"),
}

/** Шаг: пояснение, копируемая команда для своего окружения или действие. */
data class GuideStep(val text: String, val command: String? = null, val action: GuideAction? = null)

/** Раздел: [done] — пройден ли (известно приложению), `null` — проверить нельзя. */
data class GuideSection(val id: String, val title: String, val intro: String, val steps: List<GuideStep>, val done: Boolean? = null, val optional: Boolean = false)

/** Что приложение знает о настройке — для отметок «готово». */
data class SetupState(
    val notifications: Boolean? = null,
    val hasTarget: Boolean = false,
    val loggedIn: Boolean = false,
    val hasProject: Boolean = false,
    val paired: Boolean = false,
    /** Строка открытого ключа приложения (`ecdsa-sha2-nistp256 … dpai-android`). */
    val appKey: String? = null,
    /** SHA-256 вложенного агента из текущего APK: проверяется перед распаковкой из общей папки. */
    val agentSha256: String? = null,
)

/** Linux приложения, ПК и Termux по обычному SSH (D-202); специального Termux-моста нет. */
object SetupGuide {
    fun sections(s: SetupState): List<GuideSection> = listOf(
        GuideSection(
            "path", "С чего начать",
            "Работайте в Linux приложения, подключите ПК по SSH или используйте Termux через локальный SSH. Для Alpine — три шага ниже; для сборки APK в Termux — отдельный раздел.",
            listOf(
                GuideStep("Linux в приложении, шаг 1: «Targets» → «Linux в приложении» → «Установить Alpine»."),
                GuideStep("Шаг 2: поле «Пакеты» — nodejs npm → «Поставить»; затем «Терминал» и в нём:", "npm i -g @anthropic-ai/claude-code"),
                GuideStep("Шаг 3: «Добавить слот ▾» → Claude; у нового target — «Терминал ▾» → «Claude в терминале» → /login; затем ⋮ → «Проверить вход»."),
            ),
            done = s.loggedIn.takeIf { it },
        ),
        GuideSection(
            "phone", "Телефон: фон и уведомления",
            "Разрешите приложению работать в фоне, чтобы не прерывать сессии при уходе с экрана.",
            listOf(
                GuideStep("Разрешите уведомления: в них приходят запросы, одобрения и сбои.", action = GuideAction.APP_SETTINGS),
                GuideStep("Для DP_IDE включите автозапуск и снимите ограничения батареи, если оболочка телефона их применяет."),
                GuideStep("При необходимости закрепите DP_IDE в списке недавних приложений."),
            ),
            done = s.notifications,
        ),
        GuideSection(
            "target", "Target и вход исполнителя",
            "Target — место работы: Linux приложения, Termux по SSH или ПК. Каждый слот ИИ имеет свой каталог настроек и вход исполнителя.",
            listOf(
                GuideStep("На телефоне добавьте слот в «Linux в приложении»; для ПК заполните «Добавить» по команде настройки ниже и сверьте ключ хоста."),
                GuideStep("Установите выбранный CLI в его окружении и войдите под своей подпиской. На POSIX target вход доступен из его терминала; на Windows используйте оболочку самого ПК."),
                GuideStep("Укажите абсолютный каталог слота. Для проверки входа используйте ⋮ → «Проверить вход»."),
            ),
            done = s.hasTarget,
        ),
        GuideSection(
            "login", "Проверить вход",
            "Вход относится к каталогу конкретного слота; ключ API и подписка на CLI — разные способы работы.",
            listOf(GuideStep("Откройте терминал нужного слота, выполните вход и затем «Проверить вход». Готовый слот становится доступен для задач.")),
            done = s.loggedIn,
        ),
        GuideSection(
            "project", "Рабочий проект",
            "Выберите каталог репозитория и рабочие деревья задач там, где они будут храниться.",
            listOf(
                GuideStep("В «Targets» у места хранения откройте «Проект»: каталог репозитория, каталог деревьев задач, ветка интеграции и команда проверки."),
                GuideStep("Выберите, где работает исполнитель. Если дерево находится на другом ПК, настройте SSH-ключ и known_hosts в окружении исполнителя и укажите их в форме проекта."),
                GuideStep("Рабочий проект можно выбрать и из IDE; новые проекты создаются мастером «Новый проект…»."),
            ),
            done = s.hasProject,
        ),
        GuideSection(
            "pc", "ПК с Windows",
            "ПК подключается по SSH с проверкой ключа хоста. Телефон и ПК должны видеть друг друга в вашей частной сети или VPN.",
            listOf(
                GuideStep("Сохраните архив агента и перенесите dpai-agent.zip в «Загрузки» ПК:", action = GuideAction.SAVE_AGENT),
                PcSetup.command(s.appKey)?.let {
                    GuideStep("На ПК откройте PowerShell от администратора и выполните команду. В конце она покажет поля target и отпечатки ключа хоста:", it)
                } ?: GuideStep("Ключ приложения ещё не создан — откройте инструкцию снова через секунду."),
                GuideStep("Targets → «Добавить»: заполните поля, отметьте Windows, задайте адрес ПК в общей сети. «Получить ключ хоста» — сверьте отпечаток с показанным на ПК; затем «Проверить»."),
                GuideStep("CLI исполнителя и вход на ПК настройте в его оболочке. Для задач задайте каталог слота; для проекта — пути репозитория и рабочих деревьев на ПК."),
            ),
            optional = true,
        ),
        GuideSection(
            "pc-linux", "ПК с Linux",
            "Для ПК с Linux используется тот же SSH-вход приложения и подтверждённый ключ хоста.",
            listOf(
                GuideStep("Сохраните архив агента и перенесите dpai-agent.zip в «Загрузки» ПК:", action = GuideAction.SAVE_AGENT),
                PcSetup.linuxCommand(s.appKey)?.let {
                    GuideStep("В терминале ПК выполните команду. Она запросит sudo для сервера SSH, затем покажет поля target и отпечатки:", it)
                } ?: GuideStep("Ключ приложения ещё не создан — откройте инструкцию снова через секунду."),
                GuideStep("Targets → «Добавить»: используйте показанные поля и адрес ПК в общей сети, Windows не отмечайте. Сверьте ключ хоста и нажмите «Проверить»."),
                GuideStep("Установите CLI исполнителя на ПК и выполните вход. В target укажите абсолютный каталог слота, в проекте — репозиторий и деревья задач."),
            ),
            optional = true,
        ),
        GuideSection(
            "termux-ssh", "Termux: сборка APK на этом телефоне",
            "DPmetaApp управляет Termux как обычным SSH-target на 127.0.0.1. Библиотека Termux и ключи остаются в его приложении; root и специальный мост не нужны. Java, AAPT2 и SDK должны реально работать именно в Termux.",
            listOf(
                GuideStep("В Termux установите нативные инструменты, затем проверьте вывод трёх программ:", TermuxSetup.packages),
                GuideStep("Сохраните актуальный архив агента из DPmetaApp в «Загрузки»:", action = GuideAction.SAVE_AGENT),
                GuideStep("Один раз выполните в Termux termux-setup-storage и разрешите доступ к общим файлам.", "termux-setup-storage"),
                TermuxSetup.pairing(s.appKey, s.agentSha256)?.let {
                    GuideStep("В Termux установите агент, открытый ключ DPmetaApp и отдельный SSH-сервер только на localhost:8023 без паролей. Сверьте выведенный отпечаток ключа хоста с приложением:", it)
                } ?: GuideStep("Ключ приложения или SHA-256 вложенного агента ещё не доступен — откройте инструкцию снова после подготовки контроллера."),
                GuideStep("Targets → «Добавить» → «Termux на телефоне»: заполнится адрес 127.0.0.1, порт 8023, путь агента и каталог Claude. Впишите пользователя из `id -un` Termux; проверьте ключ хоста, затем нажмите «APK-среда». Вход в Claude не нужен для ручной сборки."),
                GuideStep("Для Android-проекта подготовьте Android SDK и его лицензии в Termux, задайте ANDROID_HOME в окружении SSH-команд. Нативный AAPT2 не заменяет SDK и NDK. После установки проверьте:", TermuxSetup.androidBuild),
                GuideStep("Клонируйте репозиторий в домашний каталог Termux, затем создайте проект на этом SSH-target. Сборка выполняется в Termux, APK появится в разделе «Артефакты» и скачивается на телефон по SFTP."),
                GuideStep("Для долгих сборок снимите ограничения батареи с Termux и DPmetaApp. При необходимости вручную включите termux-wake-lock; автозапуск SSH без вашего действия не включается."),
            ),
            optional = true,
        ),
        GuideSection(
            "cli", "Командная строка dpai",
            "CLI управляет тем же контроллером после явной привязки. С ПК можно использовать локальный проброс порта через adb.",
            listOf(
                GuideStep("В настройках доступа откройте «Командная строка», включите API и получите код привязки."),
                GuideStep("На подключённом по USB ПК с adb настройте локальный проброс порта:", "adb forward tcp:47777 tcp:47777"),
                GuideStep("Выполните dpai control pair с показанным кодом. Не передавайте код другим."),
            ),
            done = s.paired,
            optional = true,
        ),
        GuideSection(
            "trouble", "Если подключение не работает",
            "Проверьте адрес, сеть, службу SSH и путь к агенту на самом ПК.",
            listOf(
                GuideStep("В домашнем VPN разрешите доступ к локальной сети. Вне дома используйте адрес ПК в вашей общей VPN-сети."),
                GuideStep("Ошибка ключа хоста требует сверки отпечатка; не отключайте проверку known_hosts."),
                GuideStep("После переустановки восстановите копию приложения и при необходимости добавьте новую открытую строку SSH-ключа в authorized_keys своих ПК."),
            ),
        ),
    )
}

package com.dpsmpx.dpai.ui

/** Действие оболочки у шага: сохранить вложенный агент в «Загрузки», открыть настройки приложения. */
enum class GuideAction(val label: String) {
    SAVE_AGENT("Сохранить dpai-agent.zip в «Загрузки»"),
    APP_SETTINGS("Открыть настройки приложения"),
}

/** Шаг: пояснение, команда Termux (одна строка — копируется целиком) или действие. */
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
)

/**
 * Инструкция по настройке в самом приложении: Termux-target на том же
 * телефоне (D-33, D-35), агент, `sshd`, ключ, вход в Claude, проект,
 * командная строка (D-47). Источник шагов — `docs/DEVICE_CHECK.md`, §2,
 * §5, §6, §8.4; правка там — правка здесь. Команды — одной строкой: при
 * копировании из сообщений перенос рвал команду (9 октября 2026), кнопка
 * «Копировать» копирует строку целиком.
 */
object SetupGuide {
    const val HOME = "/data/data/com.termux/files/home"
    const val AGENT = "$HOME/dpai/bin/dpai"
    const val SLOT_DIR = "$HOME/.claude-slot-1"

    fun sections(s: SetupState): List<GuideSection> = listOf(
        GuideSection(
            "overview", "Что получится",
            "DP AI управляет сессиями Claude Code, которые работают в Termux на этом же телефоне. Приложение подключается к Termux по SSH " +
                "(127.0.0.1:8022), агент dpai запускает Claude Code и проверки. Настройка — один раз, около 30 минут; " +
                "команды Termux копируйте кнопкой «Копировать» и вставляйте в Termux долгим нажатием → «Paste».",
            listOf(
                GuideStep("Понадобятся: Termux (из F-Droid или GitHub; из Google Play тоже работает, но без запуска sshd приложением), подписка Claude, около 1,5 ГБ места."),
                GuideStep("Отметка ✓ у раздела — приложение видит, что шаг выполнен."),
            ),
        ),
        GuideSection(
            "phone", "1. Телефон: фон и уведомления",
            "HyperOS и другие оболочки усыпляют фоновые приложения. Без этих настроек сессии будут обрываться.",
            listOf(
                GuideStep("Разрешите уведомления DP AI: в них приходят запросы, одобрения и сбои.", action = GuideAction.APP_SETTINGS),
                GuideStep("Для DP AI и для Termux: «Автозапуск» — включить; «Контроль активности» / «Батарея» — «Нет ограничений»."),
                GuideStep("В списке недавних приложений закрепите DP AI и Termux (долгое нажатие на карточку → замок)."),
                GuideStep("Android 14+: «Для разработчиков» → «Отключить ограничения для дочерних процессов». Без этого Termux теряет процессы с сообщением «[Process completed (signal 9)]»."),
            ),
            done = s.notifications,
        ),
        GuideSection(
            "termux", "2. Termux и пакеты",
            "Claude Code на телефоне работает в Alpine внутри proot поверх Termux. На вопросы pkg о файлах настроек отвечайте Enter.",
            listOf(
                GuideStep("Обновить Termux и поставить пакеты:", "pkg update -y && pkg upgrade -y && pkg install -y openssh openjdk-21 git unzip proot-distro nodejs python"),
                GuideStep("Alpine для Claude Code:", "proot-distro install alpine && proot-distro login alpine -- apk add libgcc libstdc++"),
                GuideStep("Доступ к «Загрузкам» (спросит разрешение — «Разрешить»):", "termux-setup-storage"),
            ),
        ),
        GuideSection(
            "agent", "3. Агент dpai и Claude Code",
            "Агент — того же выпуска, что приложение: сохраните его из приложения и распакуйте в Termux.",
            listOf(
                GuideStep("Сохранить архив агента:", action = GuideAction.SAVE_AGENT),
                GuideStep("Распаковать агент и команду dpai:", "rm -rf ~/dpai && unzip -q ~/storage/downloads/dpai-agent.zip -d ~ && chmod +x ~/dpai/bin/dpai ~/dpai/claude-proot.sh && ln -sf ~/dpai/bin/dpai \$PREFIX/bin/dpai"),
                GuideStep(
                    "Claude Code для proot (около 230 МБ):",
                    "npm uninstall -g @anthropic-ai/claude-code 2>/dev/null; mkdir -p ~/.local/share/claude-musl && cd ~/.local/share/claude-musl && npm pack @anthropic-ai/claude-code-linux-arm64-musl && tar xzf anthropic-ai-claude-code-linux-arm64-musl-*.tgz --strip-components=1 && rm anthropic-ai-claude-code-linux-arm64-musl-*.tgz; cd ~",
                ),
                GuideStep("Команда claude — через proot; проверка версии (первый запуск — несколько секунд):", "cp ~/dpai/claude-proot.sh \$PREFIX/bin/claude && chmod +x \$PREFIX/bin/claude && claude --version"),
                GuideStep("Проверка агента — одна строка JSON с \"ok\":true, у claude и git — \"found\":true:", "dpai agent doctor"),
                GuideStep(
                    "Необязательно — исполнители Codex и Gemini (D-100): слоты Codex и Gemini на этом target. Не ставятся в Termux — их место Linux в приложении (вкладка Targets):",
                    "npm install -g @openai/codex @google/gemini-cli && codex --version && gemini --version",
                ),
            ),
        ),
        GuideSection(
            "sshd", "4. sshd и автозапуск",
            "Приложение подключается к sshd Termux на порту 8022 только по ключу — пароли выключены.",
            listOf(
                GuideStep("Запустить sshd:", "mkdir -p ~/.ssh && chmod 700 ~/.ssh && sshd -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no && termux-wake-lock"),
                GuideStep("Имя пользователя Termux (вида u0_a123) — понадобится в шаге 6:", "whoami"),
                GuideStep(
                    "Автозапуск sshd после перезагрузки телефона. Termux из Google Play выполняет ~/.termux/boot сам; Termux из F-Droid — с приложением Termux:Boot (откройте его один раз):",
                    "mkdir -p ~/.termux/boot && printf '#!/data/data/com.termux/files/usr/bin/sh\\ntermux-wake-lock\\npgrep -x sshd >/dev/null || sshd -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no\\n' > ~/.termux/boot/10-dpai-sshd && chmod 700 ~/.termux/boot/10-dpai-sshd",
                ),
                GuideStep(
                    "Termux из F-Droid или GitHub: разрешите приложению запускать sshd само (D-46), затем на карточке Termux во вкладке Targets — «Разрешить»:",
                    "mkdir -p ~/.termux && grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> ~/.termux/termux.properties",
                ),
            ),
        ),
        GuideSection(
            "key", "5. Ключ приложения",
            "Termux должен знать ключ приложения. Ключ принимается только с самого телефона (from=\"127.0.0.1,::1\"); прежний ключ DP AI (после переустановки) заменяется.",
            listOf(
                if (s.appKey != null) {
                    GuideStep("Добавить ключ в ~/.ssh/authorized_keys:", "sed -i '/ dpai-android\$/d' ~/.ssh/authorized_keys 2>/dev/null; echo 'from=\"127.0.0.1,::1\" ${s.appKey}' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys")
                } else {
                    GuideStep("Ключ ещё не создан — откройте эту инструкцию снова через секунду.")
                },
            ),
        ),
        GuideSection(
            "target", "6. Target в приложении",
            "Вкладка Targets → «Добавить». Поля:",
            listOf(
                GuideStep("Имя — Termux; Хост — 127.0.0.1; Порт — 8022; Пользователь — вывод whoami; «Windows» — не отмечать."),
                GuideStep("Команда агента dpai:", AGENT),
                GuideStep("Каталог Claude слота:", SLOT_DIR),
                GuideStep("«Получить ключ хоста» — сверьте отпечаток целиком со строкой того же типа (ECDSA, ED25519):", "for k in \$PREFIX/etc/ssh/ssh_host_*_key.pub; do ssh-keygen -l -f \"\$k\"; done"),
                GuideStep("Совпал — «Совпадает — сохранить». Затем на карточке target — «Проверить»: связь, агент, Claude Code, Git — зелёные."),
            ),
            done = s.hasTarget,
        ),
        GuideSection(
            "login", "7. Вход в Claude",
            "Входите сами, формой Claude, сразу в каталог слота. Переменные ANTHROPIC_* перекрыли бы вход по подписке — их быть не должно.",
            listOf(
                GuideStep("Должно напечатать 0:", "env | grep -c '^ANTHROPIC_'"),
                GuideStep("Вход: выберите аккаунт Claude с подпиской, откройте ссылку (долгое нажатие), вставьте код; затем /exit.", "mkdir -p ~/.claude-slot-1 && CLAUDE_CONFIG_DIR=\$HOME/.claude-slot-1 claude"),
                GuideStep("В приложении: Targets → у target «Вход Claude». На сводке появится подписка и «Аккаунтов доступно: 1»."),
            ),
            done = s.loggedIn,
        ),
        GuideSection(
            "project", "8. Проект с Git",
            "Задачи работают в репозитории: у каждой — своя ветка и рабочее дерево, после проверки — коммит и запрос на слияние.",
            listOf(
                GuideStep("Имя и почта для коммитов (подставьте свои):", "git config --global user.name \"Имя\" && git config --global user.email \"you@example.org\""),
                GuideStep("Пустой репозиторий для начала:", "mkdir -p ~/dpai-proj ~/dpai-trees && cd ~/dpai-proj && git init -q -b main && git commit -q --allow-empty -m 'начало'; cd ~"),
                GuideStep("Targets → у target «Проект»: каталог репозитория — $HOME/dpai-proj, деревьев задач — $HOME/dpai-trees, ветка — main, команда проверки — например python -m unittest -v."),
            ),
            done = s.hasProject,
            optional = true,
        ),
        GuideSection(
            "cli", "9. Командная строка dpai",
            "Команды dpai в Termux управляют приложением: задачи, сессии, запросы, лимиты (D-47).",
            listOf(
                GuideStep("Targets → «Командная строка» → «Показать код», затем в Termux (вместо 123456 — код с экрана):", "dpai control pair 123456"),
                GuideStep("Проверка:", "dpai status"),
                GuideStep("Справка по командам:", "dpai help"),
            ),
            done = s.paired,
            optional = true,
        ),
        GuideSection(
            "pc", "10. ПК с Windows",
            "Основной сценарий (D-50): вход и Claude Code — в Termux на этом телефоне (разделы 2–7), а файлы, проверки и Git задачи — на ПК по SSH. " +
                "Входить в Claude на ПК не нужно. Нужна общая сеть телефона и ПК — свой VPN оставьте, второй ставить не надо (D-68).",
            listOf(
                GuideStep(
                    "Сеть дома — один Wi-Fi: в VPN на телефоне включите «доступ к локальной сети» или исключите из VPN приложения DP AI и Termux " +
                        "(раздельное туннелирование); в VPN на ПК — «разрешить локальную сеть». Вне дома — только если ваш VPN — своя сеть, где " +
                        "телефон и ПК видят друг друга (свой WireGuard, ZeroTier и т. п.): тогда «Хост» — адрес ПК в ней.",
                ),
                GuideStep("Сохраните архив агента и перенесите dpai-agent.zip на ПК в папку «Загрузки»:", action = GuideAction.SAVE_AGENT),
                PcSetup.command(s.appKey)?.let {
                    GuideStep("На ПК: PowerShell от администратора, вставить команду (5–10 минут; повторять безопасно). В конце — поля для «Добавить» и отпечатки ключа хоста:", it)
                } ?: GuideStep("Ключ приложения ещё не создан — откройте эту инструкцию снова через секунду."),
                GuideStep("Targets → «Добавить» по выведенным полям («Хост» — адрес ПК в общей сети, команда покажет адреса ПК), «Каталог Claude слота» — пусто, «Windows» — отметить; «Получить ключ хоста» — сверить с отпечатками; затем «Проверить». Не подключается — сеть Windows должна быть «Частной», команда подскажет."),
                GuideStep("У target ПК — «Проект»: каталоги репозитория и деревьев на ПК (вида C:/Users/имя/…); «Где работает Claude» — Termux; «Связать автоматически» — приложение само даст Claude в Termux ключ для входа на ПК и проверит вход; «Сохранить»."),
            ),
            optional = true,
        ),
        GuideSection(
            "pc-linux", "11. ПК с Linux",
            "Как раздел 10, но ПК с Linux (D-70): Claude Code и вход — в Termux на этом телефоне, файлы, проверки и Git задачи — на ПК по SSH. " +
                "Нужна общая частная сеть телефона и ПК (D-84).",
            listOf(
                GuideStep(
                    "Сеть дома — один Wi-Fi (DPpass на телефоне пускает локальные сети мимо туннеля). Вне дома — ПК на точке доступа " +
                        "телефона (Wi-Fi, USB или Bluetooth), интернет ПК — через раздачу туннеля DPpass: на телефоне DPpass → «Настройки» → «Раздача туннеля», " +
                        "на ПК — его устройство раздачи (DPpass для Linux) или прокси раздачи вручную. «Хост» — адрес ПК в этой сети, он другой, чем дома.",
                ),
                GuideStep("Сохраните архив агента и перенесите dpai-agent.zip на ПК в папку «Загрузки»:", action = GuideAction.SAVE_AGENT),
                PcSetup.linuxCommand(s.appKey)?.let {
                    GuideStep("На ПК: терминал от своего пользователя (не root), в bash или zsh (в fish сначала наберите bash), вставить команду целиком; sudo спросит пароль (5–10 минут; повторять безопасно). В конце — поля для «Добавить» и отпечатки ключа хоста:", it)
                } ?: GuideStep("Ключ приложения ещё не создан — откройте эту инструкцию снова через секунду."),
                GuideStep("Targets → «Добавить» по выведенным полям, «Каталог Claude слота» — пусто, «Windows» — не отмечать; «Получить ключ хоста» — сверить с отпечатками; затем «Проверить»."),
                GuideStep("У target ПК — «Проект»: каталоги репозитория и деревьев на ПК (вида /home/имя/…); «Где работает Claude» — Termux; «Связать автоматически»; «Сохранить»."),
            ),
            optional = true,
        ),
        GuideSection(
            "trouble", "Если что-то не работает",
            "Подробности ошибок — во вкладке «Журнал».",
            listOf(
                GuideStep("«sshd на порту 8022: не слушает» — запустить снова:", "pgrep -x sshd >/dev/null || sshd -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no"),
                GuideStep("«[Process completed (signal 9)]» в Termux — ограничения дочерних процессов (раздел 1)."),
                GuideStep("«Claude Code: тайм-аут» при «Проверить» — первый запуск proot медленный: нажмите «Проверить» ещё раз."),
                GuideStep("dpai: «канал недоступен» — откройте приложение; «токен не подходит» — новый код и dpai control pair."),
                GuideStep("После переустановки DP AI — карточка «Восстановление после переустановки» во вкладке Targets: одна команда в Termux, остальное приложение делает само (D-54)."),
            ),
        ),
    )
}

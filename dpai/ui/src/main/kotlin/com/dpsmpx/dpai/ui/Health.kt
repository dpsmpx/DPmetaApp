package com.dpsmpx.dpai.ui

/** Уровень пункта проверки (DPmetaApp:D-123, D-158): ошибка, внимание, шаг первой настройки. */
enum class HealthLevel(val title: String) {
    ERROR("Ошибки"),
    WARNING("Требует внимания"),
}

/** Кнопка пункта: что делать — решает оболочка; здесь — только подпись. */
enum class HealthAction(val label: String) {
    INSTALL_LINUX("Установить Linux"),
    REPAIR_PACKAGES("Восстановить пакеты"),
    INSTALL_CLAUDE("Поставить Claude Code"),
    OPEN_TARGETS("Открыть Targets"),
    OPEN_SECRETS("Секреты"),
    GIT_IDENTITY("Задать автора…"),
    APP_SETTINGS("Разрешения приложения"),
    TERMUX_GUIDE("Инструкция Termux"),
    OPEN_STORAGE("Хранилище"),
    OPEN_LOG("Журнал"),
}

/** Пункт проверки: [title] — что не так, [detail] — почему и что делать, [actions] — решения кнопкой. */
data class HealthItem(val id: String, val level: HealthLevel, val title: String, val detail: String, val actions: List<HealthAction> = emptyList())

/** Шаг мастера настройки: [done] — сделан ли; `null` — к этому устройству не относится. */
/** Шаг мастера настройки; [optional] — по желанию: несделанный не держит мастер открытым (`DPmetaApp:D-150`). */
data class SetupStepItem(val id: String, val title: String, val text: String, val done: Boolean?, val action: HealthAction? = null, val optional: Boolean = false)

/** Состояние Termux-target на этом телефоне (sshd на loopback, D-46). */
enum class TermuxState { LISTENING, NOT_INSTALLED, NO_RUN_COMMAND, NO_PERMISSION, REQUESTED_NOT_UP, STARTING }

/** Повреждённый пакет Alpine: имя и что именно не так. */
data class BrokenPackage(val name: String, val why: String)

/**
 * Linux приложения — факты с хоста, без процесса гостя.
 * [repairFailure] — причина последней неудачной починки пакетов (`null` — не было).
 */
data class LinuxFacts(
    val installed: Boolean,
    val broken: List<BrokenPackage> = emptyList(),
    val repairFailure: String? = null,
    val git: Boolean = false,
    val gitHelper: Boolean = false,
    val gitConfigForeign: Boolean = false,
    val gitIdentity: Boolean = false,
    val claude: Boolean = false,
    val dns: Boolean = true,
)

/** Самый долгий такт службы за последнюю минуту и его самая долгая часть. */
data class TickFacts(val maxMs: Long, val avgMs: Long, val slowest: String? = null, val slowestMs: Long? = null)

/**
 * Всё, что проверка знает о приложении. [linux] — `null`: Linux в приложении на
 * этом устройстве не работает (не arm64). [githubToken] — токен GitHub сохранён;
 * [githubForAi] — выдан ли Linux («давать ИИ», `DPmetaApp:D-114`); `null` — неизвестно.
 */
data class HealthFacts(
    val linux: LinuxFacts? = null,
    val slots: Int = 0,
    val loggedIn: Boolean = false,
    val githubToken: Boolean? = null,
    val githubForAi: Boolean? = null,
    val termux: TermuxState? = null,
    val termuxPort: Int = 8022,
    val tick: TickFacts? = null,
    val freeBytes: Long? = null,
)

/**
 * Проверка приложения (DPmetaApp:D-123, D-158): из фактов — пункты для экрана
 * «Проверка» и полосы над вкладками, и шаги мастера первой настройки. Слова —
 * для человека: что случилось, почему и что делать; кнопка — где решение есть.
 * Что чинится само, решает оболочка (`HealthAuto`); здесь только сказано, что
 * приложение делает это само.
 */
object Health {
    /** Такт дольше — проверка говорит о медленном контуре (обычно такт — доли секунды). */
    const val SLOW_TICK_MS: Long = 10_000

    /** Свободного места меньше — предупреждение: запас 1 ГБ (DPmetaApp:D-113) близко. */
    const val LOW_SPACE_BYTES: Long = 2L shl 30

    fun items(f: HealthFacts): List<HealthItem> = buildList {
        val linux = f.linux
        if (linux != null && linux.installed) {
            if (linux.broken.isNotEmpty()) add(brokenItem(linux))
            if (!linux.dns) {
                add(
                    HealthItem(
                        "linux.dns", HealthLevel.WARNING, "У Linux нет адреса DNS",
                        "В /etc/resolv.conf гостя нет серверов: установка пакетов, Git и ИИ не найдут серверы по имени. " +
                            "Приложение пишет DNS сети телефона перед каждым запуском и при смене сети — сейчас сети, видимо, нет. " +
                            "Подключитесь к сети и проверьте снова.",
                    ),
                )
            }
            if (linux.git && linux.gitConfigForeign) {
                add(
                    HealthItem(
                        "git.config", HealthLevel.WARNING, "Помощник входа GitHub не записан",
                        "/etc/gitconfig в Linux настроен вручную, и приложение его не перезаписывает. Без помощника Git не возьмёт " +
                            "токен GitHub из GH_TOKEN. Перенесите свои строки в ~/.gitconfig и удалите /etc/gitconfig — приложение " +
                            "запишет его заново.",
                    ),
                )
            }
            if (linux.git && !linux.gitIdentity) {
                add(
                    HealthItem(
                        "git.identity", HealthLevel.WARNING, "Git в Linux не знает автора коммитов",
                        "В ~/.gitconfig нет user.name и user.email: git commit в терминале и у ИИ откажет («Please tell me who you are»). " +
                            "Имя и почту впишите сами — приложение их не подставляет.",
                        listOf(HealthAction.GIT_IDENTITY),
                    ),
                )
            }
            if (linux.git && f.githubToken == true && f.githubForAi == false) {
                add(
                    HealthItem(
                        "git.token", HealthLevel.WARNING, "Git в Linux без доступа к закрытым репозиториям",
                        "Токен GitHub сохранён, но Linux его не получает («давать ИИ» выключено): git fetch и push закрытых " +
                            "репозиториев в терминале и у ИИ спросят логин и откажут. Если ИИ и терминалу нужен GitHub — включите " +
                            "«давать ИИ» у токена GitHub: процессы Linux получат его после входа (PIN или отпечаток), на диск он не пишется.",
                        listOf(HealthAction.OPEN_SECRETS),
                    ),
                )
            }
        }
        f.termux?.takeIf { it != TermuxState.LISTENING && it != TermuxState.STARTING }?.let { add(termuxItem(it, f.termuxPort)) }
        f.tick?.takeIf { it.maxMs >= SLOW_TICK_MS }?.let { t ->
            val part = t.slowest?.let { name -> " Дольше всего — $name${t.slowestMs?.let { " (${seconds(it)})" }.orEmpty()}." }.orEmpty()
            add(
                HealthItem(
                    "tick.slow", HealthLevel.WARNING, "Контур отвечает медленно",
                    "Самый долгий такт за минуту — ${seconds(t.maxMs)}, средний — ${seconds(t.avgMs)} (обычно — доли секунды).$part " +
                        "Пока такт идёт, команды с экрана ждут. Частая причина — target, недоступный по сети, или подвисшая проверка " +
                        "лимитов; подробности — строки «[такт] медленный» в журнале.",
                    listOf(HealthAction.OPEN_LOG, HealthAction.OPEN_TARGETS),
                ),
            )
        }
        f.freeBytes?.takeIf { it < LOW_SPACE_BYTES }?.let { free ->
            add(
                HealthItem(
                    "space.low", HealthLevel.WARNING, "Мало места на телефоне",
                    "Свободно ${gib(free)}. Установки, клоны и сборки не начнутся, если после них останется меньше 1 ГБ. " +
                        "Освободите место или уберите ненужное в хранилище приложения.",
                    listOf(HealthAction.OPEN_STORAGE),
                ),
            )
        }
    }

    /** Шаги мастера первой настройки — по порядку. */
    fun setup(f: HealthFacts): List<SetupStepItem> {
        val linux = f.linux
        val installed = linux?.installed == true
        return listOf(
            SetupStepItem(
                "linux", "Linux в приложении",
                if (linux == null) "На этом устройстве Linux в приложении не работает (нужен arm64) — используйте ПК или Termux-target."
                else "Alpine внутри приложения: терминал, IDE и ИИ работают в нём. Около 10 МБ, без сети.",
                linux?.installed, HealthAction.INSTALL_LINUX.takeIf { linux != null && !installed },
            ),
            SetupStepItem(
                "claude", "Claude Code",
                "Ставится в Linux приложения из npm вместе с Git, Node.js и bash — нужна сеть и около 1 ГБ.",
                linux?.let { it.installed && it.claude }, HealthAction.INSTALL_CLAUDE.takeIf { installed && !linux.claude },
            ),
            SetupStepItem(
                "login", "Слот и вход в Claude",
                "На вкладке «Targets»: «Добавить слот» → Claude, затем «Терминал» → «Claude в терминале» → /login и «Проверить вход».",
                f.loggedIn, HealthAction.OPEN_TARGETS.takeIf { !f.loggedIn },
            ),
            SetupStepItem(
                "github", "Токен GitHub",
                "По желанию: нужен для карточек GitHub, клонов, PR и «Опубликовать». Без него проекты создаются и работают на устройстве. " +
                    "ИИ и терминал получают токен, только если включить «давать ИИ».",
                f.githubToken, HealthAction.OPEN_SECRETS.takeIf { f.githubToken == false }, optional = true,
            ),
            SetupStepItem(
                "git", "Автор коммитов Git",
                "Имя и почта для git commit в Linux приложения — вписываете вы.",
                linux?.let { it.installed && it.git && it.gitIdentity },
                HealthAction.GIT_IDENTITY.takeIf { installed && linux.git && !linux.gitIdentity },
            ),
        )
    }

    /** Настройка закончена: все шаги, которые относятся к устройству, сделаны. */
    fun setupDone(f: HealthFacts): Boolean = setup(f).all { it.optional || it.done != false }

    /** Сколько пунктов требуют внимания — для полосы и строки сводки. */
    fun count(items: List<HealthItem>): Int = items.size

    /**
     * Причина отказа `apk` словами — по выводу, без пересказа чужого текста целиком:
     * сеть, запрет записи Android, место; иначе — последняя строка вывода.
     */
    fun apkReason(output: String): String {
        val text = CppDependencies.plain(output)
        return when {
            text.contains("DNS: transient error") || text.contains("temporary error (try again later)") || text.contains("could not connect") ->
                "нет связи с репозиторием Alpine (сеть или DNS) — подключитесь к сети и повторите"
            text.contains("No space left on device", ignoreCase = true) -> "кончилось место"
            text.contains("failed to extract") && text.contains("Permission denied") ->
                "Android не дал записать файлы пакета (Permission denied)"
            else -> text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(200) ?: "причина не названа"
        }
    }

    /** Есть ли в тексте `~/.gitconfig` и `user.name`, и `user.email` (раздел `[user]`). */
    fun gitIdentity(config: String): Boolean {
        var inUser = false
        var name = false
        var email = false
        for (raw in config.lineSequence()) {
            val line = raw.substringBefore('#').substringBefore(';').trim()
            if (line.startsWith("[")) {
                inUser = line.removePrefix("[").substringBefore(']').trim().equals("user", ignoreCase = true)
                continue
            }
            if (!inUser || '=' !in line) continue
            val key = line.substringBefore('=').trim().lowercase()
            val value = line.substringAfter('=').trim().trim('"')
            if (value.isEmpty()) continue
            if (key == "name") name = true
            if (key == "email") email = true
        }
        return name && email
    }

    /** Имя автора коммитов: не пустое, без перевода строки и управляющих знаков, до 100 знаков. */
    fun identityNameProblem(name: String): String? = when {
        name.isBlank() -> "впишите имя"
        name.length > 100 -> "имя — до 100 знаков"
        name.any { it.isISOControl() } -> "в имени — перевод строки или управляющий знак"
        else -> null
    }

    /** Почта автора коммитов: одно `@`, без пробелов и управляющих знаков, до 200 знаков. */
    fun identityEmailProblem(email: String): String? = when {
        email.isBlank() -> "впишите почту"
        email.length > 200 -> "почта — до 200 знаков"
        email.any { it.isWhitespace() || it.isISOControl() } -> "в почте — пробел или управляющий знак"
        email.count { it == '@' } != 1 || email.startsWith("@") || email.endsWith("@") -> "почта — вида имя@домен"
        else -> null
    }

    private fun brokenItem(linux: LinuxFacts): HealthItem {
        val names = linux.broken.joinToString(", ") { it.name }
        val why = linux.broken.joinToString("; ") { "${it.name} — ${it.why}" }
        val last = linux.repairFailure?.let { " Последняя попытка не удалась: $it." }.orEmpty()
        return HealthItem(
            "linux.broken", HealthLevel.ERROR, "Пакеты Linux повреждены: $names",
            "Прежняя установка распаковала их не до конца ($why). Причина была в том, что Android запрещает жёсткие ссылки, " +
                "а приложение тогда их не заменяло — теперь заменяет. Пока пакеты повреждены, каждая установка в Linux " +
                "завершается ошибкой («N errors»), даже чужого пакета. Приложение чинит их само перед установкой и при " +
                "проверке; нужна сеть.$last",
            listOf(HealthAction.REPAIR_PACKAGES),
        )
    }

    private fun termuxItem(state: TermuxState, port: Int): HealthItem {
        val (detail, actions) = when (state) {
            TermuxState.NOT_INSTALLED ->
                "Target на 127.0.0.1:$port заведён, а Termux не установлен. Если работаете через Linux в приложении — " +
                    "уберите этот target на вкладке «Targets»." to listOf(HealthAction.OPEN_TARGETS)
            TermuxState.NO_RUN_COMMAND ->
                "Этот Termux (сборка из Google Play) не принимает команды других приложений, и приложение не может само " +
                    "запустить в нём sshd. Запустите sshd в Termux автозапуском (Termux:Boot, ~/.termux/boot) или виджетом — " +
                    "или уберите target, если он больше не нужен." to listOf(HealthAction.TERMUX_GUIDE, HealthAction.OPEN_TARGETS)
            TermuxState.NO_PERMISSION ->
                "Нет разрешения «Запуск команд в среде Termux» — приложение не может запустить sshd. Дайте его: настройки " +
                    "приложения → Разрешения → Другие разрешения." to listOf(HealthAction.APP_SETTINGS, HealthAction.OPEN_TARGETS)
            else ->
                "Приложение попросило Termux запустить sshd, но порт $port так и не открылся. Обычно в " +
                    "~/.termux/termux.properties нет строки allow-external-apps = true или система остановила Termux. " +
                    "Команды — в инструкции Termux; target, который больше не нужен, можно убрать." to
                    listOf(HealthAction.TERMUX_GUIDE, HealthAction.OPEN_TARGETS)
        }
        return HealthItem("termux", HealthLevel.WARNING, "Termux-target недоступен: sshd не слушает порт $port", detail, actions)
    }

    private fun seconds(ms: Long): String = if (ms < 1000) "$ms мс" else "${(ms + 500) / 1000} с"

    private fun gib(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
}

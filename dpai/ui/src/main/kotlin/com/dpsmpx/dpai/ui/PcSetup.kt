package com.dpsmpx.dpai.ui

/**
 * Одна команда, которая готовит ПК как target: Linux — [linuxCommand]
 * (D-70); Windows — [command], PowerShell от администратора (D-52): OpenSSH Server только по ключу, Java, Git,
 * Node.js и Claude Code, ключ приложения в `administrators_authorized_keys`
 * (только из частных сетей — дом и VPN, [PRIVATE_NETS]; прежний ключ DP AI заменяется), агент из
 * `Загрузки\dpai-agent.zip` и `agent doctor`, затем — значения полей
 * «Добавить» и отпечатки ключа хоста для сверки. Повторный запуск
 * безопасен. Текст — `pc-setup.ps1` рядом: по инструкции в строке,
 * склеивается в одну строку (кнопка «Копировать» берёт её целиком).
 */
object PcSetup {
    private const val PLACEHOLDER = "DPAI_KEY_LINE"
    private const val NETS_PLACEHOLDER = "DPAI_NETS"

    /**
     * Откуда ключи телефона входят на ПК (D-68): частные сети — домашняя
     * (192.168/16, 10/8, 172.16/12) и VPN-сети с адресами CGNAT (100.64/10);
     * из открытого интернета — нет. Не только Tailscale: у автора свой VPN,
     * а два VPN на Android вместе не работают.
     */
    const val PRIVATE_NETS = "192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,100.64.0.0/10"

    /** Строка ключа приложения — как её даёт Keystore: тип, base64, комментарий `dpai-android`. */
    private val KEY = Regex("^[a-z0-9-]+ [A-Za-z0-9+/]+={0,2} dpai-android$")

    /** Команда для [appKey]; `null` — ключа нет или строка не похожа на ключ (в одинарные кавычки PowerShell не подставляется). */
    fun command(appKey: String?): String? {
        val key = appKey?.trim() ?: return null
        if (!KEY.matches(key)) return null
        val template = checkNotNull(PcSetup::class.java.getResourceAsStream("pc-setup.ps1")) { "нет pc-setup.ps1" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return template.lines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("; ")
            .replace(PLACEHOLDER, key)
            .replace(NETS_PLACEHOLDER, PRIVATE_NETS)
    }

    /**
     * Команда для ПК с Linux (D-70) — одной строкой, как у Windows: строки
     * скрипта `pc-setup.sh` (без комментариев) — в одинарных кавычках для
     * `printf` во временный файл (`mktemp`), затем `bash` его и удаление. Так `sudo`
     * спрашивает пароль в терминале, команды скрипта не читают его остаток
     * как свой вход, а человек видит каждую строку. Ключ подставляется
     * только после проверки [KEY]. `null` — ключа нет или строка не похожа
     * на ключ.
     */
    fun linuxCommand(appKey: String?): String? {
        val key = appKey?.trim() ?: return null
        if (!KEY.matches(key)) return null
        val lines = checkNotNull(PcSetup::class.java.getResourceAsStream("pc-setup.sh")) { "нет pc-setup.sh" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .replace(PLACEHOLDER, key)
            .lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        // Своё имя файла (mktemp), а не общее: заранее созданный чужой файл с тем же именем подменил бы
        // скрипт, который идёт с кешем sudo (ревью 17 октября 2026).
        return "f=\$(mktemp) && printf '%s\\n' ${lines.joinToString(" ") { quote(it) }} > \"\$f\" && bash \"\$f\"; rm -f \"\$f\""
    }

    /** Строка в одинарных кавычках оболочки: кавычка внутри закрывает, экранируется и открывает снова. */
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

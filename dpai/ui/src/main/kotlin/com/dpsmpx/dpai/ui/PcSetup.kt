package com.dpsmpx.dpai.ui

/**
 * Одна команда PowerShell (от администратора), которая готовит ПК с
 * Windows как target (D-52): OpenSSH Server только по ключу, Java, Git,
 * Node.js и Claude Code, ключ приложения в `administrators_authorized_keys`
 * (только из сети Tailscale, прежний ключ DP AI заменяется), агент из
 * `Загрузки\dpai-agent.zip` и `agent doctor`, затем — значения полей
 * «Добавить» и отпечатки ключа хоста для сверки. Повторный запуск
 * безопасен. Текст — `pc-setup.ps1` рядом: по инструкции в строке,
 * склеивается в одну строку (кнопка «Копировать» берёт её целиком).
 */
object PcSetup {
    private const val PLACEHOLDER = "DPAI_KEY_LINE"

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
    }
}

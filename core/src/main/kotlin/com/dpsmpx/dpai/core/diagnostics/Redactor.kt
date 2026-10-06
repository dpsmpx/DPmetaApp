package com.dpsmpx.dpai.core.diagnostics

/**
 * Редактирование свободного текста перед журналом и диагностикой (D-14, T8).
 *
 * Типизированные секреты защищает [Secret]; редактор — вторая линия для
 * текста, пришедшего снаружи: вывод процессов, сообщения провайдера,
 * исключения, URL. Лучше вырезать лишнее, чем пропустить ключ:
 *
 * - блоки закрытых ключей;
 * - известные виды токенов (Anthropic, GitHub, AWS, Slack, Google, Telegram);
 * - заголовки авторизации и `Bearer …`;
 * - пары `имя=значение` и `имя: значение` с секретными именами;
 * - логин и пароль в URL;
 * - длинные последовательности букв и цифр (32+), кроме UUID (идентификаторы
 *   сессий провайдера, `docs/DATA_MODEL.md`, §1), путей и хешей вида
 *   `sha256:…` (D-10).
 */
class Redactor private constructor() {
    fun redact(text: String): String {
        var out = PRIVATE_KEY.replace(text, PRIVATE_KEY_MARK)
        for (re in KNOWN_TOKENS) out = re.replace(out, MARK)
        out = BEARER.replace(out) { m -> m.groupValues[1] + MARK }
        out = SECRET_PAIR.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + MARK }
        out = URL_USERINFO.replace(out) { m -> m.groupValues[1] + MARK + "@" }
        out = LONG_TOKEN.replace(out) { m -> if (looksLikeToken(out, m)) MARK else m.value }
        return out
    }

    private fun looksLikeToken(text: String, m: MatchResult): Boolean {
        val v = m.value
        if (!(v.any(Char::isDigit) && v.any(Char::isLetter))) return false
        if (UUID.matches(v)) return false
        if (m.range.first >= 7 && text.startsWith("sha256:", m.range.first - 7)) return false
        // Путь из коротких сегментов — не токен; base64 со «/» — токен.
        if ('/' in v && v.split('/').all { it.length < 24 }) return false
        return true
    }

    companion object {
        const val MARK: String = "<redacted>"
        const val PRIVATE_KEY_MARK: String = "<redacted:private-key>"

        val STANDARD: Redactor = Redactor()

        private val PRIVATE_KEY = Regex(
            "-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z]+ )?PRIVATE KEY-----|$)",
        )
        private val KNOWN_TOKENS = listOf(
            Regex("\\bsk-ant-[A-Za-z0-9_-]{8,}"),
            Regex("\\bsk-(?:proj-)?[A-Za-z0-9]{20,}"),
            Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
            Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            Regex("\\b\\d{8,10}:AA[A-Za-z0-9_-]{33}\\b"),
        )
        private val BEARER = Regex("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]+")
        private val SECRET_PAIR = Regex(
            "(?i)\\b(password|passwd|pwd|secret|client[_-]?secret|token|access[_-]?token|refresh[_-]?token|" +
                "api[_-]?key|access[_-]?key|private[_-]?key|authorization|auth|credentials?|session[_-]?key|cookie)" +
                "(\\s*[=:]\\s*)(\"?)(?!<redacted)[^\\s\"&,;]+",
        )
        private val URL_USERINFO = Regex("\\b([a-zA-Z][a-zA-Z0-9+.-]{1,15}://)[^\\s/@\"'<>]+@")
        private val LONG_TOKEN = Regex("(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_=-]{32,}(?![A-Za-z0-9+/_=-])")
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

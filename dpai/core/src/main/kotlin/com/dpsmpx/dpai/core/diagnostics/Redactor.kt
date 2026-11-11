package com.dpsmpx.dpai.core.diagnostics

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

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
 * - пары `имя=значение` и `имя: значение` с секретными именами, в том
 *   числе с приставкой (`DB_PASSWORD`, `GITHUB_TOKEN`, `OPENAI_API_KEY`) и
 *   в JSON (`"password": "…"`; аудит C-01, C-02);
 * - логин и пароль в URL;
 * - длинные последовательности букв и цифр (32+), кроме UUID (идентификаторы
 *   сессий провайдера, `docs/DATA_MODEL.md`, §1), путей и хешей вида
 *   `sha256:…` (D-10).
 *
 * [STRICT] — для текста, который покидает приложение и виден посторонним
 * (уведомления на экране блокировки, отчёты, которыми делятся; аудит
 * 2026-10-07, A-6): длинной считается последовательность от 24 знаков, и
 * цифра в ней не нужна; путь прячется, если в нём есть длинный сегмент.
 * Лишнее вырезается чаще — это цена.
 */
class Redactor private constructor(private val strict: Boolean) {
    fun redact(text: String): String {
        var out = PRIVATE_KEY.replace(text, PRIVATE_KEY_MARK)
        for (re in KNOWN_TOKENS) out = re.replace(out, MARK)
        out = BEARER.replace(out) { m -> m.groupValues[1] + MARK }
        out = SECRET_PAIR.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + MARK }
        out = URL_USERINFO.replace(out) { m -> m.groupValues[1] + MARK + "@" }
        val long = if (strict) STRICT_LONG_TOKEN else LONG_TOKEN
        out = long.replace(out) { m ->
            // «имя=id» — одна последовательность алфавита токена (`=` в нём ради base64): id в значении
            // не секрет, иначе `request=req_…` скрывался целиком (журнал приложения, 9 октября 2026).
            val value = NAME_VALUE.matchEntire(m.value)?.groupValues?.get(2)
            when {
                value != null && (TYPED_ID.matches(value) || UUID.matches(value)) -> m.value
                looksLikeToken(out, m) -> MARK
                else -> m.value
            }
        }
        return out
    }

    /**
     * Строка вывода, которая может быть JSON (поток `stream-json` Claude):
     * правятся только строковые значения, а структура и экранирование
     * остаются целы. Правка сырой строки как текста съедала `\` перед
     * кавычкой (`password: \"x\"` → `password: <redacted>"x\"`), и строка
     * переставала разбираться — итог хода терялся (ревью 8 октября 2026).
     * Не JSON — правка как текста.
     */
    fun redactLine(line: String): String {
        val head = line.trimStart()
        if (head.startsWith("{") || head.startsWith("[")) {
            val parsed = JsonParser().parse(line)
            if (parsed is Outcome.Success) return CanonicalJson.write(redactValues(parsed.value))
        }
        return redact(line)
    }

    private fun redactValues(v: JsonValue): JsonValue = when (v) {
        is JsonString -> JsonString(redact(v.value))
        is JsonArray -> JsonArray(v.items.map(::redactValues))
        // Поле с секретным именем — значение целиком (аудит C-02): `{"password":"…"}` в выводе процесса.
        is JsonObject -> JsonObject(
            v.fields.mapValues { (k, x) -> if (x is JsonString && SECRET_NAME.matches(k)) JsonString(MARK) else redactValues(x) },
        )
        else -> v
    }

    private fun looksLikeToken(text: String, m: MatchResult): Boolean {
        if (m.range.first >= 7 && text.startsWith("sha256:", m.range.first - 7)) return false
        return looksLikeToken(m.value)
    }

    private fun looksLikeToken(v: String): Boolean {
        if (!v.any(Char::isLetter)) return false
        if (!strict && !v.any(Char::isDigit)) return false
        if (UUID.matches(v) || TYPED_ID.matches(v)) return false
        // Путь из коротких сегментов — не токен; base64 со «/» — токен.
        // Сегмент-id оркестратора или UUID — тоже не токен: `task/tsk_…` (ветка задачи) скрывался в уведомлении слияния.
        if ('/' in v && v.split('/').all { it.length < 24 || TYPED_ID.matches(it) || UUID.matches(it) }) return false
        return true
    }

    companion object {
        const val MARK: String = "<redacted>"
        const val PRIVATE_KEY_MARK: String = "<redacted:private-key>"

        val STANDARD: Redactor = Redactor(strict = false)

        /** Для текста, видимого вне приложения (A-6). */
        val STRICT: Redactor = Redactor(strict = true)

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
        /** Секретные слова имени. */
        private const val SECRET_WORDS = "password|passwd|pwd|secret|client[_-]?secret|token|access[_-]?token|refresh[_-]?token|" +
            "api[_-]?key|access[_-]?key|private[_-]?key|authorization|auth|credentials?|session[_-]?key|cookie"

        /**
         * Имя с секретным словом в конце и любыми словами перед ним через `_`, `.`
         * или `-` (`DB_PASSWORD`, `GITHUB_TOKEN`, `aws_secret_access_key`; аудит
         * C-01): граница — перед всем именем, а не перед словом — `\b` не видит
         * границы после `_`. Класс знаков сканируется итеративно: повтор группы
         * «слово + разделитель» переполнял стек Java Regex на длинном имени.
         * Последний разделитель сохраняет границу перед секретным словом.
         */
        private const val SECRET_NAME_PATTERN = "(?:[A-Za-z0-9_.-]*[_.-])?(?:$SECRET_WORDS)"

        /** Имя поля JSON целиком — секретное (аудит C-02). */
        private val SECRET_NAME = Regex("(?i)$SECRET_NAME_PATTERN")

        /** Пара «имя=значение», «имя: значение» и `"имя": "значение"` (закрывающая кавычка имени — в разделителе). */
        private val SECRET_PAIR = Regex(
            "(?i)(?<![A-Za-z0-9_.-])($SECRET_NAME_PATTERN)" +
                "(\"?\\s*[=:]\\s*)(\"?)(?!<redacted)[^\\s\"&,;]+",
        )
        private val URL_USERINFO = Regex("\\b([a-zA-Z][a-zA-Z0-9+.-]{1,15}://)[^\\s/@\"'<>]+@")
        private val LONG_TOKEN = Regex("(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_=-]{32,}(?![A-Za-z0-9+/_=-])")
        private val STRICT_LONG_TOKEN = Regex("(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_=-]{24,}(?![A-Za-z0-9+/_=-])")
        /** «имя=значение»: имя — слово, значение — всё после первого `=`. */
        private val NAME_VALUE = Regex("([A-Za-z][A-Za-z0-9_]{0,31}=)(.+)")

        /** Идентификаторы оркестратора (`ses_` + ULID, `docs/DATA_MODEL.md`, §1) — не секреты. */
        private val TYPED_ID = Regex("[a-z]{2,3}_[0-9A-HJKMNP-TV-Z]{26}")
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

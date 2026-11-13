package com.dpsmpx.dpai.lsp

/**
 * Адрес файла для службы: `file://` и абсолютный путь POSIX на машине
 * службы (в госте Linux — путь гостя). Всё, кроме букв, цифр и `-._~/`,
 * кодируется процентами по байтам UTF-8 — так же, как это делает clangd,
 * иначе адрес в диагностике не совпадёт с адресом открытого файла.
 */
object DocumentUri {
    private const val SAFE = "-._~/"
    private const val HEX = "0123456789ABCDEF"

    fun ofPath(path: String): String {
        require(path.startsWith("/")) { "путь службы — абсолютный" }
        val sb = StringBuilder("file://")
        for (b in path.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in SAFE)) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }
        return sb.toString()
    }

    /** Путь из адреса `file://`; чужая схема, хост или битое кодирование — `null`. */
    fun toPath(uri: String): String? {
        if (!uri.startsWith("file:///")) return null
        val raw = uri.substring("file://".length)
        val bytes = java.io.ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (c.code > 0x7f) return null
                bytes.write(c.code)
                i++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}

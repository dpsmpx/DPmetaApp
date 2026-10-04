package com.dpsmpx.dppass.core

/**
 * IP-адрес как байты. Разбор — собственный и чисто текстовый:
 * `java.net.InetAddress.getByName` для литералов запрещён
 * (`tools/security/check_forbidden_apis.py`), потому что для
 * не-литерала он молча идёт в DNS — утечка запроса мимо туннеля.
 */
class IpAddress private constructor(private val bytes: ByteArray) {
    val isV4: Boolean get() = bytes.size == 4
    val isV6: Boolean get() = bytes.size == 16
    val bitLength: Int get() = bytes.size * 8

    fun toBytes(): ByteArray = bytes.copyOf()

    fun bit(index: Int): Int = (bytes[index / 8].toInt() shr (7 - index % 8)) and 1

    override fun equals(other: Any?): Boolean = other is IpAddress && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = if (isV4) {
        bytes.joinToString(".") { (it.toInt() and 0xff).toString() }
    } else {
        formatV6(bytes)
    }

    companion object {
        fun of(bytes: ByteArray): IpAddress {
            require(bytes.size == 4 || bytes.size == 16) { "IP-адрес — 4 или 16 байт" }
            return IpAddress(bytes.copyOf())
        }

        fun parse(text: String): IpAddress? = parseV4(text) ?: parseV6(text)

        fun parseV4(text: String): IpAddress? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                if (p.length > 1 && p[0] == '0') return null // ведущие нули неоднозначны (восьмеричная запись)
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return IpAddress(out)
        }

        fun parseV6(input: String): IpAddress? {
            var text = input
            if (text.startsWith('[') && text.endsWith(']')) text = text.substring(1, text.length - 1)
            if (text.isEmpty() || '%' in text || text.count { it == ':' } < 2) return null
            val doubleColon = text.indexOf("::")
            if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null

            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                val out = mutableListOf<Int>()
                val items = part.split(':')
                for ((i, g) in items.withIndex()) {
                    if (i == items.lastIndex && '.' in g) {
                        val v4 = parseV4(g)?.toBytes() ?: return null
                        out += ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
                        out += ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
                        continue
                    }
                    if (g.isEmpty() || g.length > 4 || !g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                    out += g.toInt(16)
                }
                return out
            }

            val values: List<Int> = if (doubleColon >= 0) {
                val head = groups(text.substring(0, doubleColon)) ?: return null
                val tail = groups(text.substring(doubleColon + 2)) ?: return null
                val missing = 8 - head.size - tail.size
                if (missing < 1) return null
                head + List(missing) { 0 } + tail
            } else {
                groups(text) ?: return null
            }
            if (values.size != 8) return null
            val out = ByteArray(16)
            for ((i, v) in values.withIndex()) {
                out[2 * i] = (v shr 8).toByte()
                out[2 * i + 1] = v.toByte()
            }
            return IpAddress(out)
        }

        private fun formatV6(b: ByteArray): String {
            val g = IntArray(8) { ((b[2 * it].toInt() and 0xff) shl 8) or (b[2 * it + 1].toInt() and 0xff) }
            // самая длинная серия нулей (от двух групп) сворачивается в «::» (RFC 5952)
            var bestStart = -1
            var bestLen = 1
            var i = 0
            while (i < 8) {
                if (g[i] == 0) {
                    var j = i
                    while (j < 8 && g[j] == 0) j++
                    if (j - i > bestLen) {
                        bestStart = i
                        bestLen = j - i
                    }
                    i = j
                } else {
                    i++
                }
            }
            fun hex(range: IntRange) = range.joinToString(":") { g[it].toString(16) }
            return if (bestStart < 0) hex(0..7) else hex(0 until bestStart) + "::" + hex(bestStart + bestLen until 8)
        }
    }
}

/**
 * Имя хоста или IP. Домен хранится в нормальной форме: нижний
 * регистр, без завершающей точки, только ASCII (IDN — в punycode:
 * преобразование с потерей не делается молча, D-05).
 */
sealed interface Host {
    data class Domain(val name: String) : Host {
        override fun toString(): String = name
    }

    data class Ip(val address: IpAddress) : Host {
        override fun toString(): String = address.toString()
    }

    companion object {
        private val LABEL = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")

        fun parse(text: String): Host? {
            IpAddress.parse(text)?.let { return Ip(it) }
            return normalizeDomain(text)?.let(::Domain)
        }

        /** Нормальная форма домена или `null`, если это не домен. */
        fun normalizeDomain(text: String): String? {
            val name = text.trim().lowercase().removeSuffix(".")
            if (name.isEmpty() || name.length > 253) return null
            val labels = name.split('.')
            if (labels.any { !LABEL.matches(it) }) return null
            // Чисто числовой «домен» вида 1.2.3 — не домен и не IP.
            if (labels.all { l -> l.all { it.isDigit() } }) return null
            return name
        }
    }
}

data class Endpoint(val host: Host, val port: Int) {
    init {
        require(port in 1..65535) { "порт вне 1..65535" }
    }

    override fun toString(): String = when (host) {
        is Host.Ip -> if (host.address.isV6) "[${host.address}]:$port" else "${host.address}:$port"
        is Host.Domain -> "${host.name}:$port"
    }
}

data class PortRange(val first: Int, val last: Int) {
    init {
        require(first in 1..65535 && last in first..65535) { "диапазон портов вне 1..65535" }
    }

    operator fun contains(port: Int): Boolean = port in first..last

    override fun toString(): String = if (first == last) "$first" else "$first-$last"

    companion object {
        fun parse(text: String): PortRange? {
            val parts = text.trim().split('-')
            val a = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val b = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else a
            if (parts.size > 2 || a !in 1..65535 || b !in a..65535) return null
            return PortRange(a, b)
        }
    }
}

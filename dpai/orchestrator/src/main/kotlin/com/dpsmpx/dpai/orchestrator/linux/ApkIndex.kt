package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Индексы репозиториев Alpine из кеша apk гостя (D-195): `APKINDEX.<8 hex>.tar.gz`, где 8 hex —
 * начало SHA-1 адреса `<репозиторий>/<арх>/APKINDEX.tar.gz` (так их называет apk-tools 3).
 * Читаются с хоста, без сети и процесса гостя, только для репозиториев из
 * `/etc/apk/repositories`: какие версии даёт зеркало и какой пакет даёт команду (`cmd:`).
 * Подпись индекса сверяет сам apk при загрузке; здесь индекс — подсказка, а ставит apk.
 */
object ApkIndex {
    /** Что даёт индекс: последняя версия каждого пакета и пакеты по команде. */
    class Index(val versions: Map<String, String>, val commands: Map<String, List<String>>) {
        val empty: Boolean get() = versions.isEmpty()
    }

    /** Больше — не индекс Alpine (community — около 3 МБ сжатого, 11 МБ текста). */
    const val MAX_GZ = 16 shl 20
    const val MAX_TEXT = 64 shl 20

    fun fileName(repository: String, arch: String): String {
        val url = "${repository.trimEnd('/')}/$arch/APKINDEX.tar.gz"
        val sha = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return "APKINDEX.${sha.take(8)}.tar.gz"
    }

    /** Текст `APKINDEX` из `.tar.gz` (несколько членов gzip подряд: подпись и индекс); иное — `null`. */
    fun extract(gz: ByteArray): String? {
        val tar = try {
            GZIPInputStream(ByteArrayInputStream(gz)).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TEXT) return null
                }
                out.toByteArray()
            }
        } catch (_: IOException) {
            return null
        }
        var at = 0
        while (at + 512 <= tar.size) {
            val name = String(tar, at, 100, Charsets.US_ASCII).substringBefore('\u0000')
            if (name.isEmpty()) return null
            val size = String(tar, at + 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8) ?: return null
            val body = at + 512
            if (size < 0 || body + size > tar.size) return null
            if (name == "APKINDEX") return String(tar, body, size.toInt(), Charsets.UTF_8)
            at = body + ((size + 511) / 512 * 512).toInt()
        }
        return null
    }

    /** Разбор текста индекса: `P:`, `V:`, `p:`; у пакета с несколькими версиями — старшая. */
    fun parse(text: String): Index {
        val versions = HashMap<String, String>()
        val commands = HashMap<String, MutableList<String>>()
        var name: String? = null
        var version: String? = null
        var provides: String? = null
        fun flush() {
            val n = name
            val v = version
            if (n != null && v != null) {
                val old = versions[n]
                if (old == null || (AlpineVersion.compare(v, old) ?: 0) > 0) versions[n] = v
                provides?.split(' ')?.forEach { p ->
                    val key = p.substringBefore('=')
                    if (key.startsWith("cmd:")) commands.getOrPut(key.removePrefix("cmd:")) { mutableListOf() }.let { if (n !in it) it += n }
                }
            }
            name = null; version = null; provides = null
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> flush()
                line.startsWith("P:") -> name = line.substring(2)
                line.startsWith("V:") -> version = line.substring(2)
                line.startsWith("p:") -> provides = line.substring(2)
            }
        }
        flush()
        return Index(versions, commands)
    }

    /**
     * Индексы [repositories] под [arch] из кеша гостя, слитые в один; файла нет — пропуск
     * (второе — репозитории без индекса в кеше). Ссылки на пути — не читаются.
     */
    fun read(vfs: Vfs, rootfs: String, repositories: List<String>, arch: String): Pair<Index, List<String>> {
        val cache = Vfs.join(rootfs, LinuxEnvironment.CACHE_DIR.removePrefix("/"))
        val parents = listOf(Vfs.join(rootfs, "var"), Vfs.join(Vfs.join(rootfs, "var"), "cache"), cache)
        if (parents.any(vfs::isSymlink)) return Index(emptyMap(), emptyMap()) to repositories
        val versions = HashMap<String, String>()
        val commands = HashMap<String, MutableList<String>>()
        val missing = mutableListOf<String>()
        for (repo in repositories) {
            val path = Vfs.join(cache, fileName(repo, arch))
            val size = runCatching { vfs.size(path) }.getOrNull()
            val text = if (size == null || size > MAX_GZ || vfs.isSymlink(path)) null else runCatching { vfs.read(path) }.getOrNull()?.let(::extract)
            if (text == null) { missing += repo; continue }
            val idx = parse(text)
            for ((n, v) in idx.versions) {
                val old = versions[n]
                if (old == null || (AlpineVersion.compare(v, old) ?: 0) > 0) versions[n] = v
            }
            for ((c, ps) in idx.commands) commands.getOrPut(c) { mutableListOf() }.let { list -> ps.filter { it !in list }.forEach { list += it } }
        }
        return Index(versions, commands) to missing
    }

    /** Архитектура гостя: `/etc/apk/arch`; нет — `null`. */
    fun arch(vfs: Vfs, rootfs: String): String? =
        (AlpinePackages.text(vfs, rootfs, "etc/apk/arch") as? com.dpsmpx.dpai.core.result.Outcome.Success)?.value?.trim()?.takeIf { Regex("[a-z0-9_]{2,16}").matches(it) }
}

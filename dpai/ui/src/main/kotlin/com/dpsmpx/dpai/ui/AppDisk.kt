package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.storage.vfs.Vfs
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Диск приложения для менеджеров файлов (D-96): что видно, что можно
 * писать и как путь документа превращается в файл. Смысл — здесь, на
 * JVM с тестами; `DocumentsProvider` оболочки только зовёт эти функции.
 *
 * - Секретов на диске нет вовсе: ни в списках, ни по прямому пути.
 * - Писать можно только в домашней папке гостя Linux (`/root`).
 * - Символьные ссылки не проходятся и не открываются: гость сам создаёт
 *   ссылки, и `../../control-token` не должен стать окном к токену.
 *
 * Пути — относительные от корня данных приложения, с `/`; корень — `""`.
 */
object AppDisk {
    /** Домашняя папка гостя Linux (D-75): единственное место записи. */
    const val LINUX_HOME = "linux/rootfs/root"

    /** Токен канала `dpai` (`Pairing.TOKEN`, D-47, D-92). */
    private const val CONTROL_TOKEN = "control-token"

    /** Отметка «диск открыт» — в корне данных приложения. */
    const val OPEN_FLAG = "app-disk-open"

    private fun parts(rel: String): List<String> = rel.split('/').filter { it.isNotEmpty() }

    /** Путь документа допустим: корень или безопасный относительный путь (без `..` и абсолютного начала). */
    fun valid(rel: String): Boolean = rel.isEmpty() || Vfs.isSafeRelative(rel)

    /**
     * Секрет — не показывается и не открывается (D-96): токен канала,
     * каталоги слотов Claude в госте целиком, файлы входа и ключи SSH где
     * угодно.
     */
    /** Каталоги слотов в домашней папке гостя: `.claude-slot-N`, `.codex-slot-N`, `.gemini-slot-N`. */
    private val SAFE_DOT_NAMES = setOf(".gitignore", ".gitattributes", ".editorconfig")

    fun hidden(rel: String): Boolean {
        val p = parts(rel)
        if (p.firstOrNull() in setOf(CONTROL_TOKEN, "manual-profiles")) return true
        if (p.any { it == ".ssh" || it == ".credentials.json" }) return true
        val home = parts(LINUX_HOME)
        if (p.size <= home.size || p.take(home.size) != home) return false
        // Аудит A-01: новый способ входа не должен расширять доступ менеджеру
        // файлов. Скрытые конфиги закрыты по умолчанию, в том числе внутри проекта.
        return p.drop(home.size).any { it.startsWith('.') && it !in SAFE_DOT_NAMES }
    }

    /** Можно ли менять (создать, заменить, удалить, переименовать) документ [rel]: только внутри `/root` гостя. */
    fun writable(rel: String): Boolean = valid(rel) && !hidden(rel) && rel.startsWith("$LINUX_HOME/")

    /** В каталоге [rel] можно создавать: сам `/root` гостя и всё внутри него. */
    fun canCreateIn(rel: String): Boolean = valid(rel) && !hidden(rel) && (rel == LINUX_HOME || rel.startsWith("$LINUX_HOME/"))

    /**
     * Файл документа [rel] под корнем [root] — без следования ссылкам: ни
     * одна часть пути не символьная ссылка. Секрет, ссылка или путь не по
     * правилам — `null`.
     */
    fun resolve(root: Path, rel: String): Path? {
        if (!valid(rel) || hidden(rel)) return null
        var p = root
        for (part in parts(rel)) {
            p = p.resolve(part)
            if (Files.isSymbolicLink(p)) return null
        }
        return p
    }

    /** Видимые имена в каталоге [rel]: без секретов и без символьных ссылок, по алфавиту. */
    fun children(root: Path, rel: String): List<String> {
        val dir = resolve(root, rel) ?: return emptyList()
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        // Не Stream.toList(): его (Java 16) нет в Android 11.
        return Files.list(dir).use { s -> s.iterator().asSequence().map { it.fileName.toString() }.toList() }
            .filter { name ->
                val child = if (rel.isEmpty()) name else "$rel/$name"
                !hidden(child) && !Files.isSymbolicLink(dir.resolve(name))
            }.sorted()
    }

    /**
     * Проверка уже открытого файла (от гонки «проверил — подменили ссылкой —
     * открыл»): настоящий путь открытого дескриптора [real] — под корнем
     * [root] и не секрет. `null` — можно; иначе — почему нельзя.
     */
    fun refuseOpened(root: Path, real: String): String? {
        val base = root.toRealPath().toString().trimEnd('/')
        if (real != base && !real.startsWith("$base/")) return "файл вне данных приложения"
        val rel = real.removePrefix(base).trimStart('/')
        return if (hidden(rel)) "секрет — на диск не выводится" else null
    }

    /** Имя нового документа допустимо: одна часть пути, не секрет. */
    fun validName(name: String): Boolean = Vfs.isSafeRelative(name) && '/' !in name && !hidden("$LINUX_HOME/$name")
}

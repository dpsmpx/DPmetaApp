package com.dpsmpx.dpai.storage.vfs

import java.io.IOException

/**
 * Файловая система хранилища (D-24): всё, что оркестратор пишет на диск,
 * идёт через этот интерфейс. Пути — относительные, с `/`, внутри корня.
 *
 * Модель долговечности — как у POSIX: данные файла переживают сбой только
 * после [fsync], а создание, переименование и удаление записи каталога —
 * после [fsyncDir] её каталога. Тесты проверяют хранилище моделью, которая
 * при «сбое» теряет всё незафиксированное (`SimulatedVfs` в тестах storage).
 *
 * Отказ — `IOException`; хранилище превращает его в диагностику
 * `STORAGE_IO_FAILED` и не продолжает запись вслепую.
 */
interface Vfs {
    @Throws(IOException::class)
    fun read(path: String): ByteArray?

    fun exists(path: String): Boolean

    /** Размер файла в байтах без чтения содержимого; нет файла — `null`. Где нельзя иначе — чтением. */
    @Throws(IOException::class)
    fun size(path: String): Long? = read(path)?.size?.toLong()

    /** Имена файлов прямо в каталоге [dir] (без подкаталогов), по порядку. */
    @Throws(IOException::class)
    fun list(dir: String): List<String>

    /** Создать или усечь файл и записать [bytes]. Не долговечно до [fsync]. */
    @Throws(IOException::class)
    fun write(path: String, bytes: ByteArray)

    @Throws(IOException::class)
    fun append(path: String, bytes: ByteArray)

    @Throws(IOException::class)
    fun truncate(path: String, size: Long)

    @Throws(IOException::class)
    fun fsync(path: String)

    /**
     * Дописать и сделать долговечным — то же, что [append] и [fsync] подряд.
     * Реализация может обойтись одним открытием файла (журнал дописывает на
     * каждое событие, и лишнее открытие на Windows стоит дорого).
     */
    @Throws(IOException::class)
    fun appendDurably(path: String, bytes: ByteArray) {
        append(path, bytes)
        fsync(path)
    }

    /** Атомарная замена: после переименования [to] — целиком новый файл или целиком прежний. */
    @Throws(IOException::class)
    fun rename(from: String, to: String)

    @Throws(IOException::class)
    fun delete(path: String)

    @Throws(IOException::class)
    fun fsyncDir(dir: String)

    @Throws(IOException::class)
    fun mkdirs(dir: String)

    /**
     * Символьная ссылка [path] → [target]; цель записывается как есть и не
     * проверяется: корневая система Linux-среды в приложении (D-75) ссылается
     * на свои абсолютные пути. Пишущий через ссылку сам следит, чтобы запись
     * не ушла за корень, — хранилище ссылок не создаёт.
     */
    @Throws(IOException::class)
    fun symlink(path: String, target: String): Unit = throw IOException("символьные ссылки не поддерживаются")

    /**
     * Запись [path] — символьная ссылка (сама запись, без перехода по ней).
     * Нужна, где в каталог пишет и чужой код — гость Linux-среды (D-75): запись
     * через его ссылку ушла бы к её цели. Где ссылок нет — `false`.
     */
    fun isSymlink(path: String): Boolean = false

    /** Права POSIX — младшие 9 бит [mode] (`rwxrwxrwx`); где прав POSIX нет (Windows), ничего не делает. */
    @Throws(IOException::class)
    fun setMode(path: String, mode: Int) {}

    companion object {
        /** Каталог пути: `a/b/c` → `a/b`, `c` → `` (корень). */
        fun parentOf(path: String): String = path.substringBeforeLast('/', "")

        fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

        /** Безопасный относительный путь: без `..`, без абсолютного начала, без пустых частей. */
        fun isSafeRelative(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

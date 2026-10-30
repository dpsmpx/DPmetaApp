package com.dpsmpx.dpai.testkit

import java.io.File

/**
 * Внешние программы, без которых часть проверок не имеет смысла
 * (`docs/TESTING.md`, §1; правило «сбой инструмента — не сбой кода»,
 * `CLAUDE.md`).
 *
 * Тест, которому нужен настоящий `git` или POSIX-оболочка, при их
 * отсутствии **пропускается** с причиной, а не падает `NullPointerException`
 * из `valueOrNull()!!`: падение выглядит как дефект кода и уводит разбор
 * в ложную сторону (аудит 30 октября 2026, стабильность H-1).
 *
 * ```kotlin
 * @BeforeEach
 * fun needsGit() = assumeTrue(Tools.gitAvailable(), "нет git в PATH: проверка на настоящем git — на машине с git")
 * ```
 */
object Tools {
    /** Есть ли программа в `PATH`: имя файла, исполняемого для этой ОС. */
    fun available(name: String): Boolean {
        val dirs = (System.getenv("PATH") ?: return false).split(File.pathSeparatorChar).filter { it.isNotBlank() }
        // Windows ищет по PATHEXT: `git` — это `git.exe`.
        val names = if (isWindows()) {
            listOf(name) + (System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD").split(';').map { name + it.lowercase() }
        } else {
            listOf(name)
        }
        return dirs.any { dir -> names.any { n -> File(dir, n).let { it.isFile && it.canExecute() } } }
    }

    fun gitAvailable(): Boolean = available("git")

    fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** POSIX-оболочка и обычные её программы: на Windows их нет, там тесты пропускаются. */
    fun posixAvailable(): Boolean = !isWindows() && available("sh")
}

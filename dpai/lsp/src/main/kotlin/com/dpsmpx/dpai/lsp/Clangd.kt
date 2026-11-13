package com.dpsmpx.dpai.lsp

/**
 * clangd — языковая служба C и C++ (D-137): команда запуска и то, что
 * редактору нужно знать о проекте до запуска.
 *
 * Семантика верна только с настоящими флагами сборки — файлом
 * `compile_commands.json` (его пишет CMake с
 * `CMAKE_EXPORT_COMPILE_COMMANDS=ON`). Без него clangd разбирает файл с
 * флагами по умолчанию: подсветка ошибок и дополнение работают, но чужих
 * заголовков проекта он не найдёт — это показывается человеку, а не
 * скрывается ([compileCommandsDir] — `null`).
 */
object Clangd {
    const val PROGRAM: String = "clangd"

    /** Расширения, которые редактор отдаёт clangd. */
    private val CPP = setOf("cc", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h++", "ipp", "inl", "tpp")

    /** `languageId` LSP по имени файла; `null` — не C и не C++. Заголовок `.h` — C++: так его разбирает и clangd в проекте C++. */
    fun languageId(fileName: String): String? {
        val ext = fileName.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            ext == "c" -> "c"
            ext == "h" || ext == "C" || ext == "H" || ext.lowercase() in CPP -> "cpp"
            else -> null
        }
    }

    /** Где искать `compile_commands.json` относительно корня проекта, по порядку. */
    val COMPILE_COMMANDS_DIRS: List<String> = listOf("", "build", "build/debug", "build/Debug", "build/release", "build/Release", "out", "cmake-build-debug", "cmake-build-release")

    const val COMPILE_COMMANDS: String = "compile_commands.json"

    /** Каталог с `compile_commands.json` среди [existingFiles] (пути от корня проекта); `null` — файла нет. */
    fun compileCommandsDir(existingFiles: Collection<String>): String? {
        val present = existingFiles.toSet()
        return COMPILE_COMMANDS_DIRS.firstOrNull { (if (it.isEmpty()) COMPILE_COMMANDS else "$it/$COMPILE_COMMANDS") in present }
    }

    /** Корень проекта для службы и каталог `compile_commands.json` в нём (`null` — файла нет). */
    data class Project(val root: String, val compileCommandsDir: String?)

    /**
     * Команда `sh`, которая ищет проект файла на машине службы: от каталога
     * файла [fileDir] вверх до [stopDir] — первый каталог с
     * `compile_commands.json` (в нём или в [COMPILE_COMMANDS_DIRS]) или с
     * `.git`. Пути идут аргументами, а не текстом сценария. Ответ разбирает
     * [parseProject].
     */
    fun locateCommand(fileDir: String, stopDir: String): List<String> {
        require(fileDir.startsWith("/") && stopDir.startsWith("/")) { "пути службы — абсолютные" }
        val script = "s=\"\$1\"; d=\"\$1\"; stop=\"\$2\"; shift 2; " +
            "while :; do " +
            "for c in \"\$@\"; do if [ -f \"\$d/\$c/$COMPILE_COMMANDS\" ]; then echo \"root=\$d\"; echo \"cc=\$d/\$c\"; exit 0; fi; done; " +
            "if [ -e \"\$d/.git\" ]; then echo \"root=\$d\"; exit 0; fi; " +
            "if [ \"\$d\" = \"\$stop\" ]; then break; fi; " +
            "n=\"\${d%/*}\"; if [ -z \"\$n\" ] || [ \"\$n\" = \"\$d\" ]; then break; fi; d=\"\$n\"; " +
            "done; echo \"root=\$s\""
        return listOf("sh", "-c", script, "sh", fileDir.trimEnd('/').ifEmpty { "/" }, stopDir.trimEnd('/').ifEmpty { "/" }) + COMPILE_COMMANDS_DIRS
    }

    /** Ответ [locateCommand]; без строки `root=` с абсолютным путём — `null`. */
    fun parseProject(lines: List<String>): Project? {
        val root = lines.firstOrNull { it.startsWith("root=/") }?.removePrefix("root=") ?: return null
        val cc = lines.firstOrNull { it.startsWith("cc=/") }?.removePrefix("cc=")?.trimEnd('/')
        return Project(root, cc)
    }

    /**
     * Команда службы. [jobs] — потоков разбора; [backgroundIndex] — индекс
     * всего проекта в фоне: нужен ссылкам и переименованию между файлами,
     * стоит памяти и батареи, поэтому включается явно. Индекс и кэш
     * заголовков — на диске (`.cache/clangd` проекта), не в памяти телефона.
     */
    fun command(compileCommandsDir: String?, jobs: Int = 2, backgroundIndex: Boolean = false): List<String> {
        require(jobs in 1..16) { "потоков — от 1 до 16" }
        require(compileCommandsDir == null || compileCommandsDir.startsWith("/")) { "каталог compile_commands — абсолютный путь службы" }
        return buildList {
            add(PROGRAM)
            add("--log=error")
            add("-j=$jobs")
            add("--pch-storage=disk")
            add("--background-index=${if (backgroundIndex) "true" else "false"}")
            // Заголовки в файл сама служба не дописывает: правка вне места курсора — только явная.
            add("--header-insertion=never")
            add("--limit-results=200")
            add("--limit-references=1000")
            if (compileCommandsDir != null) add("--compile-commands-dir=$compileCommandsDir")
        }
    }
}

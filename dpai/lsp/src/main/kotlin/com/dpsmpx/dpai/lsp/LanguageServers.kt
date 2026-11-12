package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json

/** Язык редактора с семантической службой (DPmetaApp:D-105, D-110). */
enum class CodeLanguage(val title: String) { CPP("C++"), PYTHON("Python"), KOTLIN("Kotlin") }

/**
 * Запуск одной службы (D-142): argv без shell, переменные окружения и
 * постоянные настройки, которыми клиент отвечает на `workspace/configuration`.
 * [pull] — диагностику служба сама не шлёт, её спрашивают
 * (`textDocument/diagnostic`).
 */
data class ServerLaunch(
    val name: String,
    val command: List<String>,
    val settings: JsonObject = JsonObject(emptyMap()),
    val environment: Map<String, String> = emptyMap(),
    val pull: Boolean = false,
)

/** Какой язык и какой `languageId` LSP у файла — по имени. */
object LanguageServers {
    fun language(fileName: String): CodeLanguage? = when {
        Clangd.languageId(fileName) != null -> CodeLanguage.CPP
        PythonServers.languageId(fileName) != null -> CodeLanguage.PYTHON
        KotlinLsp.languageId(fileName) != null -> CodeLanguage.KOTLIN
        else -> null
    }

    fun languageId(fileName: String): String? = Clangd.languageId(fileName) ?: PythonServers.languageId(fileName) ?: KotlinLsp.languageId(fileName)

    internal fun extension(fileName: String): String = fileName.substringAfterLast('/').substringAfterLast('.', "")
}

/**
 * Службы Python (DPmetaApp:D-110). Лёгкая — pylsp (Jedi): дополнение,
 * сведения, подпись, переходы, использования, переименование; тяжёлая —
 * pyright с проверкой типов. С обеими работает ruff: замечания стиля и
 * ошибок, исправления готовыми правками, формат. Свои замечания и формат
 * pylsp выключены, чтобы одно замечание не приходило дважды.
 *
 * Интерпретатор — тот же, что у запуска ▶ (venv проекта, DPmetaApp:D-109):
 * по нему служба находит установленные модули.
 */
object PythonServers {
    enum class Kind(val title: String) {
        LIGHT("лёгкая: pylsp и ruff"),
        HEAVY("с проверкой типов: pyright и ruff"),
    }

    private val EXTENSIONS = setOf("py", "pyi", "pyw")

    fun languageId(fileName: String): String? = if (LanguageServers.extension(fileName) in EXTENSIONS) "python" else null

    /** Пакеты Alpine лёгкой службы. */
    val LIGHT_PACKAGES: List<String> = listOf("py3-lsp-server", "ruff")

    /** Пакеты Alpine тяжёлой службы: Node.js для pyright и тот же ruff. Сам pyright — из реестра npm ([PYRIGHT_URL]). */
    val HEAVY_PACKAGES: List<String> = listOf("nodejs", "ruff", "curl", "ca-certificates")

    const val PYLSP: String = "pylsp"
    const val RUFF: String = "ruff"
    const val NODE: String = "node"

    /** pyright закреплён версией и SHA-256 архива; проверено против `dist.integrity` реестра npm 12 ноября 2026. */
    const val PYRIGHT_VERSION: String = "1.1.414"
    const val PYRIGHT_SHA256: String = "bf5f473f6167c0d14175492c3263d783b4489a6956e1c06c18e15228e3a3fa42"
    const val PYRIGHT_URL: String = "https://registry.npmjs.org/pyright/-/pyright-$PYRIGHT_VERSION.tgz"

    /** Каталог pyright внутри каталога студии Linux приложения. */
    const val PYRIGHT_DIR: String = "pyright/$PYRIGHT_VERSION"

    /** Сервер pyright в распакованном архиве npm. */
    fun pyrightServer(home: String): String = "${home.trimEnd('/')}/package/langserver.index.js"

    fun pylsp(python: String?): ServerLaunch {
        val off = JsonObject.of("enabled" to json(false))
        val jedi = if (python != null) JsonObject.of("environment" to json(python)) else JsonObject(emptyMap())
        val plugins = JsonObject.of(
            "jedi" to jedi,
            "pyflakes" to off, "pycodestyle" to off, "mccabe" to off, "pylint" to off, "flake8" to off, "pydocstyle" to off,
            "yapf" to off, "autopep8" to off, "rope_autoimport" to off, "rope_completion" to off,
        )
        return ServerLaunch(PYLSP, listOf(PYLSP), JsonObject.of("pylsp" to JsonObject.of("plugins" to plugins)))
    }

    fun pyright(home: String, python: String?): ServerLaunch {
        val analysis = JsonObject.of(
            // Только открытые файлы: проверка всего проекта стоит памяти и батареи телефона.
            "diagnosticMode" to json("openFilesOnly"),
            "typeCheckingMode" to json("standard"),
            "autoSearchPaths" to json(true),
            "useLibraryCodeForTypes" to json(true),
        )
        val pythonSettings = linkedMapOf<String, JsonValue>("analysis" to analysis)
        if (python != null) pythonSettings["pythonPath"] = json(python)
        // Порядок импортов и формат — у ruff.
        val settings = JsonObject.of("python" to JsonObject(pythonSettings), "pyright" to JsonObject.of("disableOrganizeImports" to json(true)))
        return ServerLaunch("pyright", listOf(NODE, pyrightServer(home), "--stdio"), settings)
    }

    fun ruff(): ServerLaunch = ServerLaunch(RUFF, listOf(RUFF, "server"))
}

/**
 * Официальная служба Kotlin JetBrains (DPmetaApp:D-110). Сборки — EAP и
 * перестают запускаться примерно через 30 дней (код выхода [EXPIRED_EXIT]),
 * поэтому версия не закреплена: последнюю узнают со страницы выпусков
 * ([build]), архив сверяют с SHA-256 из файла JetBrains рядом.
 *
 * Встроенные JVM и лаунчер собраны под glibc, а Linux приложения — Alpine
 * (musl), поэтому служба запускается на JDK 25 Alpine командой из
 * `product-info.json` ([command]): glibc-библиотеки наблюдения за файлами
 * идут через gcompat, а RocksDB — musl-сборкой официального rocksdbjni той
 * же версии ([ROCKSDB_VERSION]; набор JNI-символов совпал полностью).
 * Передача статистики и отчётов JetBrains выключена `--data-sharing=none`.
 */
object KotlinLsp {
    fun languageId(fileName: String): String? = if (LanguageServers.extension(fileName) in setOf("kt", "kts")) "kotlin" else null

    const val RELEASES_LATEST: String = "https://github.com/Kotlin/kotlin-lsp/releases/latest"
    private val BUILD = Regex("[0-9]{3}\\.[0-9]{1,6}\\.[0-9]{1,6}")

    /** Номер сборки из адреса страницы выпуска (`…/releases/tag/v263.7084.0`); иное — `null`. */
    fun build(location: String): String? {
        val tag = location.trim().substringAfterLast("/releases/tag/", "").removePrefix("v")
        return tag.takeIf { BUILD.matches(it) }
    }

    fun validBuild(build: String): Boolean = BUILD.matches(build)

    /** Архив сборки под [arch] (`aarch64` или `x86_64`); файл `.sha256` лежит рядом. */
    fun archiveUrl(build: String, arch: String): String {
        require(validBuild(build)) { "номер сборки kotlin-lsp — цифры через точки" }
        val suffix = when (arch) {
            "aarch64" -> "-aarch64"
            "x86_64" -> ""
            else -> throw IllegalArgumentException("архитектура $arch не поддержана")
        }
        return "https://download.jetbrains.com/language-server/kotlin-server/$build/kotlin-server-$build$suffix.tar.gz"
    }

    /** Пакеты Alpine: JDK 25 для службы, gcompat для её glibc-библиотек, JDK 21 — для Gradle проекта (нужен компилятор). */
    val PACKAGES: List<String> = listOf("openjdk25-jre-headless", "gcompat", "openjdk21-jdk", "curl", "ca-certificates", "tar", "unzip")

    const val JAVA: String = "/usr/lib/jvm/java-25-openjdk/bin/java"
    const val GRADLE_JAVA_HOME: String = "/usr/lib/jvm/java-21-openjdk"

    const val ROCKSDB_VERSION: String = "10.10.1"

    /**
     * Общий архив rocksdbjni с Maven Central: в нём musl-библиотеки всех
     * архитектур (отдельного архива для aarch64-musl у версии нет). SHA-256
     * сверен с файлом `.sha256` Maven Central 12 ноября 2026.
     */
    const val ROCKSDB_URL: String = "https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/$ROCKSDB_VERSION/rocksdbjni-$ROCKSDB_VERSION.jar"
    const val ROCKSDB_SHA256: String = "3e3c9e439b89c2ac4aaa7512bc73052d44b3a6397daed6cb88168cefa6e431a9"

    /** Имя musl-библиотеки RocksDB, которую служба ищет в `lib/rocksdbjni` (та же — внутри архива [ROCKSDB_URL]). */
    fun rocksDbLibrary(arch: String): String = when (arch) {
        "aarch64" -> "librocksdbjni-linux-aarch64-musl.so"
        "x86_64" -> "librocksdbjni-linux64-musl.so"
        else -> throw IllegalArgumentException("архитектура $arch не поддержана")
    }

    /** Код выхода истёкшей сборки EAP. */
    const val EXPIRED_EXIT: Int = 7

    private val JAR = Regex("[A-Za-z0-9._+-]{1,200}\\.jar")
    private val MAIN = Regex("[A-Za-z_][A-Za-z0-9_.$]{0,300}")

    /**
     * Команда службы на JDK [java] по `product-info.json` ([productInfo]) и
     * `bin/intellij-server.vmoptions` ([vmOptions]) сборки в [home]. [heapMb]
     * заменяет предел кучи сборки. Данные сборки — чужие: имя jar с путём,
     * аргумент JVM не с `-` или главный класс не имени Java — отказ.
     */
    fun command(productInfo: String, vmOptions: String, home: String, java: String, systemPath: String, arch: String, heapMb: Int? = null): Outcome<List<String>> {
        require(home.startsWith("/") && systemPath.startsWith("/") && java.startsWith("/")) { "пути службы — абсолютные" }
        val wanted = when (arch) {
            "aarch64" -> "aarch64"
            "x86_64" -> "amd64"
            else -> return Outcome.failure(DiagCode.UNSUPPORTED, "архитектура $arch не поддержана")
        }
        fun bad(what: String): Outcome<List<String>> = Outcome.failure(DiagCode.VALUE_INVALID, "сборка kotlin-lsp непонятна: $what")
        val info = (JsonParser().parse(productInfo) as? Outcome.Success)?.value as? JsonObject ?: return bad("product-info.json не JSON")
        val launch = ((info["launch"] as? JsonArray)?.items ?: return bad("нет launch")).mapNotNull { it as? JsonObject }
            .firstOrNull { LspParse.str(it["os"]) == "Linux" && LspParse.str(it["arch"]) == wanted } ?: return bad("нет запуска Linux $wanted")
        val main = LspParse.str(launch["mainClass"])?.takeIf { MAIN.matches(it) } ?: return bad("главный класс")
        val jars = ((launch["bootClassPathJarNames"] as? JsonArray)?.items ?: return bad("нет classpath")).map { j ->
            LspParse.str(j)?.takeIf { JAR.matches(it) && ".." !in it } ?: return bad("имя jar")
        }
        if (jars.isEmpty()) return bad("пустой classpath")
        val root = home.trimEnd('/')
        val additional = ((launch["additionalJvmArguments"] as? JsonArray)?.items ?: emptyList()).map { a ->
            val s = LspParse.str(a)?.takeIf { it.startsWith("-") && it.none { c -> c.code < 32 } } ?: return bad("аргумент JVM")
            s.replace("\$IDE_HOME", root)
        }
        val options = vmOptions.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (options.any { !it.startsWith("-") }) return bad("vmoptions")
        val heap = if (heapMb != null) {
            require(heapMb in 256..16384) { "куча службы — от 256 МиБ до 16 ГиБ" }
            options.filterNot { it.startsWith("-Xmx") } + "-Xmx${heapMb}m"
        } else {
            options
        }
        return Outcome.success(
            buildList {
                add(java)
                // Журнал JVM — в stderr: в stdout идёт только протокол.
                add("-Xlog:disable")
                add("-Xlog:all=warning:stderr")
                addAll(heap)
                addAll(additional)
                add("-cp")
                add(jars.joinToString(":") { "$root/lib/$it" })
                add(main)
                add("--stdio")
                add("--data-sharing=none")
                add("--system-path=$systemPath")
            },
        )
    }

    /** Окружение службы: Gradle проекта — на JDK с компилятором; Android SDK — если поставлен. */
    fun environment(androidHome: String?): Map<String, String> = buildMap {
        put("JAVA_HOME", GRADLE_JAVA_HOME)
        if (androidHome != null) {
            put("ANDROID_HOME", androidHome)
            put("ANDROID_SDK_ROOT", androidHome)
        }
    }

    /** Запуск службы командой [command] ([KotlinLsp.command]); диагностику она отдаёт по запросу. */
    fun launch(command: List<String>, androidHome: String?): ServerLaunch = ServerLaunch("kotlin-lsp", command, environment = environment(androidHome), pull = true)

    /** Та же JVM и classpath, но вместо службы — `license status`: срок сборки EAP без запуска протокола. */
    fun statusCommand(command: List<String>): List<String> {
        val main = command.indexOf("--stdio") - 1
        require(main > 0) { "это не команда службы" }
        return command.subList(0, main + 1) + listOf("license", "status")
    }

    /** Срок сборки по выводу `license status`: дней осталось (`null` — не названо) и истекла ли. */
    data class Validity(val daysLeft: Int?, val expired: Boolean)

    private val DAYS_LEFT = Regex("\\((-?[0-9]{1,5}) days? left\\)")

    fun validity(exitCode: Int?, output: String): Validity {
        val days = DAYS_LEFT.find(output)?.groupValues?.get(1)?.toIntOrNull()
        return Validity(days, expired(exitCode, output) || (days != null && days < 0))
    }

    /** Признак истёкшей сборки в выводе службы. */
    fun expired(exitCode: Int?, stderr: String): Boolean = exitCode == EXPIRED_EXIT || "has expired" in stderr
}

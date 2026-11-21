package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.lsp.KotlinLsp
import com.dpsmpx.dpai.lsp.PythonServers
import com.dpsmpx.dpai.orchestrator.linux.GuestStorage
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Компоненты приложения в Linux приложения (D-192; `DPmetaApp:D-171`, шаг 2): то, что
 * ставится не пакетом Alpine, — Android SDK, службы IDE, Kotlin, окружения проектов, CLI
 * исполнителей, модели. О каждом — версия, версия этой сборки DP_IDE, источник, сумма,
 * размер. Читается с хоста, без процесса гостя; ссылки не проходятся. Сумма — только та,
 * что закреплена в APK и сверена при установке или записана самим установщиком (npm);
 * иной нет — так и сказано (D-13).
 */
object AppComponents {
    enum class Kind { ANDROID_SDK, IDE_PLATFORM, KOTLIN_LSP, PYRIGHT, KOTLINC, PROJECT_ENVS, NPM, MODEL }

    /** Как убрать: каталоги или файлы дома гостя ([Tree], пути от `/root`) или пакет npm гостя ([Npm]). */
    sealed interface Remove {
        data class Tree(val paths: List<String>) : Remove
        data class Npm(val pkg: String) : Remove
    }

    data class Component(
        val id: String,
        val kind: Kind,
        val title: String,
        /** Где в госте. */
        val path: String,
        /** Что стоит; `null` — версия неизвестна. */
        val version: String?,
        /** Что ставит эта сборка DP_IDE; `null` — не закреплено. */
        val expected: String?,
        val source: String?,
        val checksum: String?,
        /** Что за сумма и кем сверена. */
        val checksumNote: String,
        val size: Long?,
        /** Счёт остановлен на пределе файлов: «не меньше». */
        val sizeApprox: Boolean,
        /** Части: пакеты SDK, сборки, проекты. */
        val parts: List<String>,
        val remove: Remove,
    ) {
        /** Стоит не та версия, что ставит эта сборка. */
        val outdated: Boolean get() = expected != null && version != null && version != expected
    }

    /** Пакеты npm исполнителей: имя пакета → (название, закреплённая версия). */
    val NPM_PACKAGES: List<Triple<String, String, String>> = (listOf(LinuxEnvironment.CLAUDE_PACKAGE) + LinuxEnvironment.EXECUTOR_PACKAGES).map { spec ->
        val at = spec.lastIndexOf('@')
        val pkg = spec.substring(0, at)
        val title = when (pkg) {
            "@anthropic-ai/claude-code" -> "Claude Code"
            "@openai/codex" -> "Codex CLI"
            "@google/gemini-cli" -> "Gemini CLI"
            else -> pkg
        }
        Triple(pkg, title, spec.substring(at + 1))
    }

    /** Корень npm гостя (Alpine: префикс `/usr/local`). */
    const val NPM_ROOT = "usr/local/lib/node_modules"

    /** Больше файлов при счёте размера — «не меньше». */
    const val COUNT_LIMIT = 200_000
    private const val MAX_JSON = 4 shl 20

    /**
     * Компоненты по корню гостя [rootfs] (дом — `root`); [arch] — архитектура гостя
     * (`aarch64` на телефоне) для закреплённой суммы kotlin-lsp. Чего не поставлено — того нет
     * в списке.
     */
    fun scan(rootfs: Path, arch: String?): List<Component> {
        val out = mutableListOf<Component>()
        // Каталог дома гостя: ни одна часть пути не ссылка — гость не подменит `~/tools` ссылкой на файлы приложения.
        fun dir(rel: String): Path? = safe(rootfs, "root/$rel")?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

        dir(AndroidSdk.HOME.removePrefix("/root/"))?.let { sdk ->
            val parts = sdkParts(sdk)
            val (size, cut) = size(sdk)
            out += Component(
                "android-sdk", Kind.ANDROID_SDK, "Android SDK", AndroidSdk.HOME,
                parts.firstOrNull { it.startsWith("cmdline-tools") }?.substringAfter(' ')?.trim('(', ')'), AndroidSdk.CMDLINE_VERSION,
                "dl.google.com (sdkmanager) и сборки сообщества по вашему адресу", AndroidSdk.CMDLINE_SHA256,
                "SHA-256 cmdline-tools закреплена в APK и сверена при установке; пакеты SDK сверил sdkmanager, сборки сообщества — суммой, которую вводили вы",
                size, cut, parts, Remove.Tree(listOf(AndroidSdk.HOME)),
            )
        }
        dir(CodeServerSetup.ANDROID_SDK.removePrefix("/root/"))?.let { p ->
            val parts = sdkParts(p)
            val (size, cut) = size(p)
            out += Component(
                "ide-android-platform", Kind.IDE_PLATFORM, "Платформа Android для IDE", CodeServerSetup.ANDROID_SDK,
                parts.firstOrNull()?.substringAfter(' ')?.trim('(', ')'), null, "dl.google.com (репозиторий Google)", null,
                "SHA-1 из репозитория Google сверена при установке",
                size, cut, parts, Remove.Tree(listOf(CodeServerSetup.ANDROID_SDK)),
            )
        }
        val kotlin = dir(CodeServerSetup.KOTLIN_HOME.removePrefix("/root/").substringBeforeLast('/'))
        val system = dir(CodeServerSetup.KOTLIN_SYSTEM_BASE.removePrefix("/root/"))
        val legacyRoot = CodeServerSetup.KOTLIN_LEGACY_INDEX_BASE.removePrefix("/root/")
        // Только известный подкаталог производных Kotlin-индексов, не весь общий workspace.
        val legacy = dir(legacyRoot)?.let(::children).orEmpty().filter { it.matches(Regex("[0-9a-fA-F]{32}")) }.mapNotNull { id ->
            val rel = "$legacyRoot/$id/index/kotlin-server"
            dir(rel)?.let { it to "/root/$rel" }
        }
        if (kotlin != null || system != null || legacy.isNotEmpty()) {
            val builds = kotlin?.let(::children).orEmpty().filter { KotlinLsp.validBuild(it) }
            val current = kotlin?.let { k -> runCatching { Files.readSymbolicLink(k.resolve("current")).fileName.toString() }.getOrNull()?.takeIf(KotlinLsp::validBuild) }
            val measurements = listOfNotNull(kotlin, system).map(::size) + legacy.map { size(it.first) }
            val sum = arch?.let { runCatching { KotlinLsp.archiveSha256(it) }.getOrNull() }
            out += Component(
                "kotlin-lsp", Kind.KOTLIN_LSP, "Служба Kotlin (kotlin-lsp)", CodeServerSetup.KOTLIN_HOME.substringBeforeLast('/'),
                current ?: builds.singleOrNull(), KotlinLsp.PINNED_BUILD,
                arch?.let { runCatching { KotlinLsp.archiveUrl(KotlinLsp.PINNED_BUILD, it) }.getOrNull() } ?: "download.jetbrains.com", sum,
                "SHA-256 сборки ${KotlinLsp.PINNED_BUILD} закреплена в APK и сверена до распаковки",
                if (measurements.any { it.first == null }) null else measurements.mapNotNull { it.first }.sum(), measurements.any { it.second },
                builds + listOfNotNull("кеш и индексы".takeIf { system != null }, "старые индексы Kotlin".takeIf { legacy.isNotEmpty() }),
                Remove.Tree(listOfNotNull(CodeServerSetup.KOTLIN_HOME.substringBeforeLast('/').takeIf { kotlin != null }, CodeServerSetup.KOTLIN_SYSTEM_BASE.takeIf { system != null }) + legacy.map { it.second }),
            )
        }
        dir(CodeServerSetup.PYRIGHT_HOME.removePrefix("/root/").substringBeforeLast('/'))?.let { p ->
            val versions = children(p).filter { !it.startsWith("install.") }
            val (size, cut) = size(p)
            out += Component(
                "pyright", Kind.PYRIGHT, "Служба Python (pyright)", CodeServerSetup.PYRIGHT_HOME.substringBeforeLast('/'),
                versions.singleOrNull() ?: versions.lastOrNull(), PythonServers.PYRIGHT_VERSION, PythonServers.PYRIGHT_URL, PythonServers.PYRIGHT_SHA256,
                "SHA-256 закреплена в APK и сверена до распаковки", size, cut, versions, Remove.Tree(listOf(CodeServerSetup.PYRIGHT_HOME.substringBeforeLast('/'))),
            )
        }
        dir("${GuestStorage.IDE}/kotlin")?.let { p ->
            val versions = children(p)
            val (size, cut) = size(p)
            out += Component(
                "kotlinc", Kind.KOTLINC, "Kotlin (kotlinc)", "/root/tools/ide/kotlin",
                versions.singleOrNull() ?: versions.lastOrNull(), KotlinRuntime.VERSION, KotlinRuntime.URL, KotlinRuntime.SHA256,
                "SHA-256 закреплена в APK и сверена до распаковки", size, cut, versions, Remove.Tree(listOf("/root/tools/ide/kotlin")),
            )
        }
        dir("${GuestStorage.IDE}/projects")?.let { p ->
            val keys = children(p)
            val (size, cut) = size(p)
            out += Component(
                "project-envs", Kind.PROJECT_ENVS, "Окружения проектов (venv Python, библиотеки Kotlin)", "/root/tools/ide/projects",
                null, null, "PyPI и Maven Central по действию человека", null,
                "суммы сверяют pip и Maven при загрузке; приложение их не хранит",
                size, cut, listOf("проектов: ${keys.size}"), Remove.Tree(listOf("/root/tools/ide/projects")),
            )
        }
        val lock = npmLock(rootfs)
        for ((pkg, title, pinned) in NPM_PACKAGES) {
            val at = safe(rootfs, "$NPM_ROOT/$pkg")?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) } ?: continue
            val manifest = json(at.resolve("package.json"))
            val version = (manifest?.get("version") as? JsonString)?.value
            val integrity = ((lock?.get("packages") as? JsonObject)?.get("node_modules/$pkg") as? JsonObject)?.get("integrity")?.let { (it as? JsonString)?.value }
            val (size, cut) = size(at)
            out += Component(
                "npm:$pkg", Kind.NPM, title, "/$NPM_ROOT/$pkg", version, pinned, "registry.npmjs.org — $pkg", integrity,
                if (integrity != null) "integrity записана npm при установке и им же сверена" else "сумма неизвестна: npm её не записал",
                size, cut, emptyList(), Remove.Npm(pkg),
            )
        }
        dir(GuestStorage.MODELS)?.let { m ->
            for (name in children(m).filter { it.endsWith(".gguf") }) {
                val f = m.resolve(name)
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) continue
                out += Component(
                    "model:$name", Kind.MODEL, "Модель $name", "/root/tools/models/$name", null, null, "Hugging Face или ваш файл", null,
                    "сумма неизвестна: приложение её не хранит", runCatching { Files.size(f) }.getOrNull(), false, emptyList(),
                    Remove.Tree(listOf("/root/tools/models/$name")),
                )
            }
        }
        return out
    }

    /** Части SDK: `путь (Pkg.Revision)` по `source.properties`, до двух уровней вглубь. */
    private fun sdkParts(sdk: Path): List<String> {
        val out = mutableListOf<String>()
        for (top in children(sdk)) {
            val t = sdk.resolve(top)
            if (!Files.isDirectory(t, LinkOption.NOFOLLOW_LINKS)) continue
            revision(t)?.let { out += "$top ($it)"; continue }
            for (sub in children(t)) {
                val s = t.resolve(sub)
                if (Files.isDirectory(s, LinkOption.NOFOLLOW_LINKS)) revision(s)?.let { out += "$top;$sub ($it)" }
            }
        }
        return out
    }

    private fun revision(dir: Path): String? {
        val f = dir.resolve("source.properties")
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || runCatching { Files.size(f) }.getOrDefault(Long.MAX_VALUE) > 64 * 1024) return null
        return runCatching { Files.readAllLines(f) }.getOrNull()?.firstOrNull { it.startsWith("Pkg.Revision=") }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun npmLock(rootfs: Path): JsonObject? = safe(rootfs, "$NPM_ROOT/.package-lock.json")?.let(::json)

    /** Путь [rel] под [rootfs], если ни одна его часть не символьная ссылка; иначе `null`. */
    private fun safe(rootfs: Path, rel: String): Path? {
        var at = rootfs
        for (part in rel.split('/').filter { it.isNotEmpty() }) {
            if (part == ".." || part == ".") return null
            at = at.resolve(part)
            if (Files.isSymbolicLink(at)) return null
        }
        return at
    }

    private fun json(f: Path): JsonObject? {
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return null
        if (runCatching { Files.size(f) }.getOrDefault(Long.MAX_VALUE) > MAX_JSON) return null
        val text = runCatching { Files.newBufferedReader(f, Charsets.UTF_8).use { it.readText() } }.getOrNull() ?: return null
        return (JsonParser().parse(text) as? Outcome.Success)?.value as? JsonObject
    }

    private fun children(dir: Path): List<String> = try {
        Files.list(dir).use { s -> s.iterator().asSequence().map { it.fileName.toString() }.filter { !it.startsWith('.') }.sorted().toList() }
    } catch (_: IOException) {
        emptyList()
    }

    /** Размер без перехода по ссылкам; дальше [COUNT_LIMIT] файлов — остановка (второе — `true`). */
    fun size(dir: Path): Pair<Long?, Boolean> {
        var total = 0L
        var count = 0
        var cut = false
        return try {
            Files.walkFileTree(
                dir,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        if (++count >= COUNT_LIMIT) {
                            cut = true
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
            total to cut
        } catch (_: IOException) {
            null to false
        }
    }

    /** Убирать с хоста можно только это: SDK целиком и то, что внутри `~/tools/ide` и `~/tools/models`. */
    internal fun removable(guest: String): Boolean {
        val parts = guest.split('/')
        if (parts.first().isNotEmpty() || parts.drop(1).any { it.isEmpty() || it == "." || it == ".." }) return false
        val legacyIndex = Regex("${Regex.escape(CodeServerSetup.KOTLIN_LEGACY_INDEX_BASE)}/[0-9a-fA-F]{32}/index/kotlin-server").matches(guest)
        return legacyIndex || guest == AndroidSdk.HOME || listOf("/root/tools/ide/", "/root/tools/models/").any { guest.startsWith(it) && guest.length > it.length }
    }

    /**
     * Убрать [Remove.Tree] с хоста: путь гостя → под [rootfs]; только [removable]; родители —
     * не ссылки (иначе удаление ушло бы за пределы дома гостя). Обход — чтением, без перехода
     * по ссылкам; удаление — через [vfs] (D-24), ссылки удаляются сами, их цели не трогаются.
     * Итог — сколько освобождено (байты).
     */
    fun delete(rootfs: Path, remove: Remove.Tree, vfs: Vfs = NioVfs(rootfs)): Outcome<Long> {
        var freed = 0L
        for (guest in remove.paths) {
            if (!removable(guest)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "убирать можно только зарегистрированные компоненты приложения и их индексы", guest)
            val rel = guest.removePrefix("/")
            val parts = rel.split('/')
            for (i in 1 until parts.size) {
                if (vfs.isSymlink(parts.take(i).joinToString("/"))) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "на пути компонента — символьная ссылка", guest)
            }
            val at = rootfs.resolve(rel)
            if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) continue
            // Порядок удаления: файлы и ссылки — раньше своего каталога.
            val order = mutableListOf<String>()
            try {
                if (Files.isSymbolicLink(at) || !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isSymbolicLink(at)) freed += Files.size(at)
                    order += rel
                } else {
                    Files.walkFileTree(
                        at,
                        object : SimpleFileVisitor<Path>() {
                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (attrs.isRegularFile) freed += attrs.size()
                                order += rootfs.relativize(file).toString().replace('\\', '/')
                                return FileVisitResult.CONTINUE
                            }

                            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                                if (exc != null) throw exc
                                order += rootfs.relativize(dir).toString().replace('\\', '/')
                                return FileVisitResult.CONTINUE
                            }
                        },
                    )
                }
                for (path in order) vfs.delete(path)
            } catch (e: IOException) {
                return Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "компонент убран не целиком", e, guest)))
            }
        }
        return Outcome.success(freed)
    }

    /** Сводка над списком: сколько компонентов и сколько занимают; неизвестное — «не меньше». */
    fun summary(list: List<Component>): String {
        if (list.isEmpty()) return "Компонентов приложения пока нет: SDK, службы IDE, CLI и модели ставятся по кнопкам на своих экранах."
        val known = list.mapNotNull { it.size }.sum()
        val approx = list.any { it.size == null || it.sizeApprox }
        val old = list.count { it.outdated }
        return "Компонентов: ${list.size} · занято ${if (approx) "не меньше " else ""}${AlpineView.size(known)}" +
            if (old > 0) " · не та версия, что ставит DP_IDE: $old" else ""
    }

    /** Подробности компонента: подпись → значение. */
    fun details(c: Component): List<Pair<String, String>> = listOfNotNull(
        "Где" to c.path,
        "Версия" to (c.version ?: "неизвестна"),
        c.expected?.takeIf { c.version != it }?.let { "Ставит эта сборка DP_IDE" to it },
        "Занимает" to (if (c.sizeApprox) "не меньше " else "") + AlpineView.size(c.size),
        c.source?.let { "Источник" to it },
        "Сумма" to (c.checksum ?: "неизвестна"),
        "О сумме" to c.checksumNote,
        c.parts.takeIf { it.isNotEmpty() }?.let { "Состав" to it.joinToString() },
    )

    /** Команда гостя: убрать пакет npm исполнителя — только из [NPM_PACKAGES]. */
    fun npmUninstall(pkg: String): Outcome<List<String>> {
        if (NPM_PACKAGES.none { it.first == pkg }) return Outcome.failure(DiagCode.VALUE_INVALID, "пакет npm не из списка исполнителей", pkg)
        return Outcome.success(listOf("/bin/sh", "-lc", "npm uninstall -g \"\$1\"", "dpai-npm-uninstall", pkg))
    }
}

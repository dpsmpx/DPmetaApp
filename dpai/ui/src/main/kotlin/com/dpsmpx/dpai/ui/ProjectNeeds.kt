package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.ApkIndex
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment

/**
 * Совместимость окружения с проектом (D-195; `DPmetaApp:D-171`, шаг 5): что нужно проекту в
 * Linux приложения — команды по файлам стека (CMake, Gradle, npm, Python, Cargo, Go…) и по
 * шагам его ручных профилей, Android SDK по «Нужно» профиля (D-186) — и есть ли это.
 * Команда есть, если её даёт установленный пакет (`cmd:` в базе apk); пакет для
 * недостающей — из своего выбора для частых команд, иначе — из индекса зеркала в кеше
 * ([ApkIndex]); не нашли — так и сказано, без угадывания.
 */
object ProjectNeeds {
    /** Что нужно: команда, откуда видно, чем ставится; [found] — пакет, что её даёт сейчас. */
    data class Need(val command: String, val why: String, val found: String?, val install: String?)

    data class Check(val project: String, val needs: List<Need>, val sdk: List<String>) {
        val missing: List<Need> get() = needs.filter { it.found == null }
        val ok: Boolean get() = missing.isEmpty() && sdk.isEmpty()
    }

    /** Файл в корне проекта → команды стека. */
    private val MARKERS: List<Pair<String, List<String>>> = listOf(
        "CMakeLists.txt" to listOf("cmake", "make", "c++"),
        "Makefile" to listOf("make", "cc"),
        "meson.build" to listOf("meson", "ninja"),
        "build.gradle" to listOf("java"),
        "build.gradle.kts" to listOf("java"),
        "settings.gradle" to listOf("java"),
        "settings.gradle.kts" to listOf("java"),
        "pom.xml" to listOf("mvn", "java"),
        "package.json" to listOf("node", "npm"),
        "requirements.txt" to listOf("python3"),
        "pyproject.toml" to listOf("python3"),
        "setup.py" to listOf("python3"),
        "Cargo.toml" to listOf("cargo", "rustc"),
        "go.mod" to listOf("go"),
    )

    /** Пакет Alpine для частых команд: индекс зеркала здесь подсказывает не то (`cmd:java` → `java-common`). */
    private val PREFERRED: Map<String, String> = mapOf(
        "c++" to "g++", "g++" to "g++", "cc" to "gcc", "gcc" to "gcc", "make" to "make", "cmake" to "cmake", "ninja" to "samurai",
        "meson" to "meson", "java" to "openjdk21-jdk", "javac" to "openjdk21-jdk", "node" to "nodejs", "npm" to "npm",
        "python3" to "python3", "pip" to "py3-pip", "pip3" to "py3-pip", "pytest" to "py3-pytest", "cargo" to "cargo",
        "rustc" to "rust", "go" to "go", "mvn" to "maven", "git" to "git", "bash" to "bash", "clang" to "clang", "clang++" to "clang",
    )

    /** Команды, что даёт приложение само или проект: их apk не ставит. */
    private val NOT_APK = setOf("sh", "/bin/sh", "env", "kotlinc", "gradle", "gradlew", "cmd.exe", "powershell", "pwsh")

    private val COMMAND = Regex("[A-Za-z0-9][A-Za-z0-9+._-]{0,63}")

    /** Команды по файлам [files] корня проекта (имена). */
    fun fromFiles(files: Set<String>): List<Pair<String, String>> =
        MARKERS.filter { it.first in files }.flatMap { (f, cmds) -> cmds.map { it to f } }

    /** Команды по шагам профиля: `argv[0]` — простое имя (не путь в проекте, не оболочка). */
    fun fromProfile(kind: ManualProfileKind, profile: ManualProfile): List<Pair<String, String>> =
        profile.steps.mapNotNull { s ->
            val c = s.command.firstOrNull()?.substringAfterLast('/') ?: return@mapNotNull null
            if (s.command.first().contains('/') && !s.command.first().startsWith("/usr/bin/") && !s.command.first().startsWith("/bin/")) return@mapNotNull null
            if (c in NOT_APK || !COMMAND.matches(c)) null else c to "профиль «${if (kind == ManualProfileKind.BUILD) "Сборка" else "Проверки"}», шаг «${s.name.take(40)}»"
        }

    /**
     * Проверка проекта [project]: [wanted] — команды и откуда видно; [inv] — пакеты гостя;
     * [index] — индекс зеркала (может быть пустым); [sdkMissing] — чего из SDK нет по «Нужно».
     */
    fun check(project: String, wanted: List<Pair<String, String>>, inv: AlpinePackages.Inventory, index: ApkIndex.Index?, sdkMissing: List<String>): Check {
        val needs = wanted.groupBy({ it.first }, { it.second }).map { (cmd, whys) ->
            val found = AlpinePackages.dependency("cmd:$cmd")?.let(inv::satisfying)?.firstOrNull()?.name
            Need(cmd, whys.distinct().joinToString("; "), found, if (found == null) suggest(cmd, index) else null)
        }.sortedWith(compareBy({ it.found != null }, { it.command }))
        return Check(project, needs, sdkMissing)
    }

    /**
     * Пакет для команды: для частых — свой выбор (индекс в кеше бывает не у всех репозиториев,
     * и его молчание — ещё не «нет пакета»); для прочих — по индексу зеркала.
     */
    fun suggest(cmd: String, index: ApkIndex.Index?): String? {
        PREFERRED[cmd]?.let { return it }
        val candidates = index?.commands?.get(cmd).orEmpty().filter { LinuxEnvironment.PACKAGE.matches(it) }
        return candidates.sortedWith(compareBy({ it != cmd }, { it.length }, { it })).firstOrNull()
    }

    /** Пакеты, чтобы поставить недостающее (без повторов, проверенные имена). */
    fun installs(c: Check): List<String> = c.missing.mapNotNull { it.install }.distinct().filter { LinuxEnvironment.PACKAGE.matches(it) }.take(LinuxEnvironment.MAX_PACKAGES)

    /** Предупреждение к удалению: какие команды проектов уйдут вместе с пакетами [gone]. */
    fun removalWarnings(gone: List<AlpinePackages.Package>, checks: List<Check>): List<String> {
        val goneNames = gone.map { it.name }.toSet()
        return checks.flatMap { c -> c.needs.filter { it.found != null && it.found in goneNames }.map { "${c.project}: ${it.command} (${it.found})" } }
    }

    /** Строка проверки для экрана. */
    fun text(c: Check): String = buildString {
        if (c.ok) { append("объявленные пакеты и пути найдены, но запуск инструментов не проверен"); if (c.needs.isNotEmpty()) append(": ${c.needs.joinToString { it.command }}"); return@buildString }
        if (c.missing.isNotEmpty()) append("нет: " + c.missing.joinToString { n -> n.command + (n.install?.let { " (пакет $it)" } ?: " (пакет не найден)") })
        if (c.sdk.isNotEmpty()) { if (isNotEmpty()) append("; "); append("Android SDK: нет ${c.sdk.joinToString()}") }
    }
}

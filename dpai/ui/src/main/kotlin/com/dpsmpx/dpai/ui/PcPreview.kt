package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.TargetPlatform

/**
 * Предпросмотр интерфейсов на ПК (`DPmetaApp:D-132`; D-169): Robolectric в тестовой
 * JVM модуля проекта рисует функции Compose без параметров и разметки XML в PNG.
 * Временные init-скрипт Gradle и тест лежат в домашнем каталоге ПК (`DIR`), файлы
 * проекта не меняются; итог — `index.tsv` и PNG в `DIR/out`.
 */
object PcPreview {
    /** Каталог предпросмотра от домашнего каталога ПК. */
    const val DIR = ".dpmetaapp/preview"
    const val INIT = "init.gradle"
    /** Корень исходников теста (пакет `dpai.preview`) от [DIR]. */
    const val SRC = "src"
    const val TEST = "$SRC/dpai/preview/DpaiRenderPreviews.java"
    const val OUT = "out"
    const val INDEX = "index.tsv"
    const val TEST_CLASS = "dpai.preview.DpaiRenderPreviews"

    /** Сколько функций и разметок за один запуск Gradle. */
    const val MAX_ITEMS = 20

    private val LAYOUT = Regex("^[a-z0-9_]+$")
    private val COMPOSABLE = Regex("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$")

    /** Текст init-скрипта Gradle (ресурс). */
    fun initScript(): String = resource("dpai-preview.init.gradle.txt")

    /** Текст теста рендера (ресурс). */
    fun testSource(): String = resource("DpaiRenderPreviews.java.txt")

    private fun resource(name: String): String =
        PcPreview::class.java.getResourceAsStream("preview/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("нет ресурса предпросмотра $name")

    /** Путь проекта Gradle по каталогу модуля от корня проекта: `app` → `:app`, `feature/home` → `:feature:home`, корень — `:`. */
    fun modulePath(dir: String): String = ":" + dir.trim('/').replace('/', ':')

    /** Проект Gradle файла: [project] — каталог корня от корня дерева, [module] — путь модуля (`:app`). */
    data class Place(val project: String, val module: String)

    /**
     * Модуль — ближайший вверх от файла [path] каталог с `build.gradle(.kts)`; корень —
     * от модуля вверх ближайший с `settings.gradle(.kts)`. Пути — от корня дерева;
     * [exists] — есть ли файл. Нет модуля или корня — `null`. Модуль с нестандартным
     * `projectDir` в `settings` так не найти — Gradle скажет, что задачи нет.
     */
    fun locate(path: String, exists: (String) -> Boolean): Place? {
        fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
        fun parent(p: String) = p.substringBeforeLast('/', "")
        fun up(from: String, names: List<String>): String? {
            var dir = from
            repeat(MAX_DEPTH) {
                if (names.any { exists(join(dir, it)) }) return dir
                if (dir.isEmpty()) return null
                dir = parent(dir)
            }
            return null
        }
        val module = up(parent(path), listOf("build.gradle.kts", "build.gradle")) ?: return null
        val project = up(module, listOf("settings.gradle.kts", "settings.gradle")) ?: return null
        return Place(project, modulePath(module.removePrefix(project)))
    }

    /**
     * Путь каталога [rel] дерева [root] так, как его понимает ПК: `~` корня — домашний
     * каталог [home]; на Windows косые — прямые (PowerShell и Gradle их понимают).
     */
    fun pcDir(platform: TargetPlatform, root: String, home: String, rel: String): String {
        var base = if (platform == TargetPlatform.WINDOWS) root.replace('\\', '/') else root
        if (base == "~" || base.startsWith("~/")) base = home.trimEnd('/') + base.removePrefix("~")
        base = base.trimEnd('/').ifEmpty { "/" }
        return if (rel.isEmpty()) base else base.trimEnd('/') + "/" + rel
    }

    private const val MAX_DEPTH = 16

    /**
     * argv запуска на ПК: Gradle wrapper проекта [root] — тест рендера модуля [module]
     * с init-скриптом [init] и тестом в [src]; итог — в [out]. Пути — как их видит ПК.
     */
    fun command(platform: TargetPlatform, root: String, module: String, init: String, src: String, out: String, compose: List<String>, layouts: List<String>): Outcome<List<String>> {
        if (compose.isEmpty() && layouts.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "нечего рисовать: нет функций @Preview и разметок")
        if (compose.size + layouts.size > MAX_ITEMS) return Outcome.failure(DiagCode.VALUE_INVALID, "за раз — не больше $MAX_ITEMS функций и разметок")
        compose.firstOrNull { !COMPOSABLE.matches(it) }?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "имя функции недопустимо: «$it»") }
        layouts.firstOrNull { !LAYOUT.matches(it) }?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "имя разметки недопустимо: «$it»") }
        if (module != ":" && !Regex("^(:[A-Za-z0-9_.-]+)+$").matches(module)) return Outcome.failure(DiagCode.VALUE_INVALID, "модуль Gradle недопустим: «$module»")
        val props = listOf(
            "-Pdpai.preview.module=$module", "-Pdpai.preview.src=$src", "-Pdpai.preview.out=$out",
            "-Pdpai.preview.compose=${compose.joinToString(",")}", "-Pdpai.preview.layouts=${layouts.joinToString(",")}",
        )
        val task = if (module == ":") ":testDebugUnitTest" else "$module:testDebugUnitTest"
        return when (platform) {
            TargetPlatform.POSIX -> Outcome.success(
                listOf("/bin/sh", "-c", "cd \"\$1\" || exit 1; shift; exec ./gradlew -q \"\$@\"", "dpai-preview", root, "-I", init, task, "--tests", TEST_CLASS) + props,
            )
            TargetPlatform.WINDOWS -> {
                // Через PowerShell -EncodedCommand (D-163): $, кавычки и пробелы путей Windows по SSH не передать.
                val q = EditorTerminalRun::quotePs
                val script = listOf(
                    "try { Set-Location -LiteralPath ${q(root)} -ErrorAction Stop } catch { Write-Output \"Папка не найдена: ${'$'}(${'$'}_.Exception.Message)\"; exit 1 }",
                    "& .\\gradlew.bat -q -I ${q(init)} ${q(task)} --tests ${q(TEST_CLASS)} " + props.joinToString(" ") { q(it) },
                    "exit ${'$'}LASTEXITCODE",
                ).joinToString("\n")
                val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                Outcome.success(listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded))
            }
        }
    }

    /** Удаляется только каталог этого завершённого запуска, после получения PNG. */
    fun cleanupCommand(platform: TargetPlatform, home: String, nonce: String): Outcome<List<String>> {
        val base = home.replace('\\', '/').trimEnd('/')
        val absolute = if (platform == TargetPlatform.WINDOWS) Regex("^[A-Za-z]:/.*").matches(base) else base.startsWith('/')
        if (!absolute || base.any { it.isISOControl() } || base.split('/').any { it == "." || it == ".." } || !Regex("^[a-f0-9]{32}$").matches(nonce)) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "каталог временного предпросмотра недопустим")
        }
        val path = "$base/$DIR/$nonce"
        return if (platform == TargetPlatform.POSIX) Outcome.success(listOf("rm", "-rf", "--", path)) else {
            val q = EditorTerminalRun.quotePs(path)
            val script = "try { if (Test-Path -LiteralPath $q) { Remove-Item -LiteralPath $q -Recurse -Force -ErrorAction Stop }; exit 0 } catch { exit 1 }"
            val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            Outcome.success(listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded))
        }
    }

    /** Строка итога: [file] — PNG в `out` (пусто — не нарисовано), [error] — почему. */
    data class Rendered(val kind: String, val name: String, val file: String?, val error: String?)

    /** Разобрать `index.tsv` теста рендера; чужие строки — мимо. */
    fun parseIndex(text: String): List<Rendered> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4 || f[0] !in setOf("compose", "layout")) return@mapNotNull null
        val ok = f[3] == "ok" && f[2].isNotEmpty() && f[2].matches(Regex("^[A-Za-z0-9_.-]+\\.png$"))
        Rendered(f[0], f[1], if (ok) f[2] else null, if (ok) null else f[3].ifEmpty { "не нарисовано" })
    }

    /** Путь каталога сервера SFTP как путь ПК: на Windows `/C:/x` → `C:/x`. */
    fun localPath(platform: TargetPlatform, sftpPath: String): String =
        if (platform == TargetPlatform.WINDOWS && Regex("^/[A-Za-z]:(/.*)?$").matches(sftpPath)) sftpPath.drop(1) else sftpPath
}

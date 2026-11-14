package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.KotlinLsp
import com.dpsmpx.dpai.lsp.PythonServers
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Установка и поиск языковых служб Python и Kotlin в Linux приложения
 * (DPmetaApp:D-110, DP_AI_Orcestrator:D-142). Сценарии — `sh` с данными в
 * argv, а не в тексте; всё скачанное сверяется до распаковки; ставится во
 * временный каталог и переносится на место целиком, прежнее не портится при
 * отказе. Запускаются только по кнопке человека.
 */
object CodeServerSetup {
    /** Каталог студии в Linux приложения (как у запуска, D-141). */
    /** Службы IDE — в разделе инструментов (D-143, DPmetaApp:D-113); прежний `~/.local/share/dpai` — ссылкой. */
    const val HOME: String = "/root/tools/ide"
    const val PYRIGHT_HOME: String = "$HOME/${PythonServers.PYRIGHT_DIR}"
    const val KOTLIN_HOME: String = "$HOME/kotlin-lsp/current"
    const val KOTLIN_SYSTEM: String = "$HOME/kotlin-lsp-system"
    const val ANDROID_SDK: String = "$HOME/android-sdk"

    private const val CURL = "curl -sS --fail --location --proto '=https' --connect-timeout 30 --max-time 1800"

    private fun sh(name: String, script: String, vararg args: String): List<String> = listOf("/bin/sh", "-c", script.trimIndent(), name) + args

    /** Скачать pyright закреплённой версии, сверить SHA-256 и положить в [PYRIGHT_HOME]. */
    fun pyrightInstall(): List<String> = sh(
        "dpai-pyright",
        """
        DEST="$1"; ROOT=${'$'}(dirname "${'$'}DEST")
        if test -f "${'$'}DEST/package/langserver.index.js"; then printf 'DPAI_PYRIGHT_OK\n'; exit 0; fi
        mkdir -p "${'$'}ROOT" || exit
        STAGE=${'$'}(mktemp -d "${'$'}ROOT/install.XXXXXX") || exit
        trap 'rm -rf "${'$'}STAGE"' EXIT
        $CURL "$2" -o "${'$'}STAGE/pyright.tgz" || exit
        printf '%s  %s\n' "$3" "${'$'}STAGE/pyright.tgz" | sha256sum -c - >/dev/null || { printf 'DPAI_PYRIGHT_CHECKSUM: failed\n'; exit 1; }
        mkdir "${'$'}STAGE/out" && tar -xzf "${'$'}STAGE/pyright.tgz" -C "${'$'}STAGE/out" || exit
        test -f "${'$'}STAGE/out/package/langserver.index.js" || { printf 'DPAI_PYRIGHT_LAYOUT: unexpected\n'; exit 1; }
        mv "${'$'}STAGE/out" "${'$'}DEST" || exit
        printf 'DPAI_PYRIGHT_OK\n'
        """,
        PYRIGHT_HOME, PythonServers.PYRIGHT_URL, PythonServers.PYRIGHT_SHA256,
    )

    /**
     * Поставить закреплённую сборку [build] kotlin-lsp под [arch]: SHA-256 —
     * из приложения (DPmetaApp:D-136), musl-RocksDB — закреплённый архив Maven
     * Central; glibc-JVM сборки удаляется (служба работает на JDK 25 Alpine),
     * прежние сборки — после успешной установки: истёкшие не запускаются.
     */
    fun kotlinInstall(build: String, arch: String): Outcome<List<String>> {
        if (!KotlinLsp.validBuild(build)) return Outcome.failure(DiagCode.VALUE_INVALID, "номер сборки kotlin-lsp непонятен: $build")
        if (build != KotlinLsp.PINNED_BUILD) return Outcome.failure(DiagCode.VALUE_INVALID, "сборка kotlin-lsp $build не закреплена в этой версии приложения — обновите DPmetaApp")
        if (arch != "aarch64" && arch != "x86_64") return Outcome.failure(DiagCode.UNSUPPORTED, "архитектура $arch не поддержана")
        return Outcome.success(
            sh(
                "dpai-kotlin-lsp",
                """
                ROOT="$1"; DEST="${'$'}ROOT/$2"
                mkdir -p "${'$'}ROOT" || exit
                STAGE=${'$'}(mktemp -d "${'$'}ROOT/install.XXXXXX") || exit
                trap 'rm -rf "${'$'}STAGE"' EXIT
                $CURL "$3" -o "${'$'}STAGE/server.tgz" || exit
                printf '%s  %s\n' "$7" "${'$'}STAGE/server.tgz" | sha256sum -c - >/dev/null || { printf 'DPAI_KOTLIN_LSP_CHECKSUM: failed\n'; exit 1; }
                $CURL "$4" -o "${'$'}STAGE/rocksdb.jar" || exit
                printf '%s  %s\n' "$5" "${'$'}STAGE/rocksdb.jar" | sha256sum -c - >/dev/null || { printf 'DPAI_ROCKSDB_CHECKSUM: failed\n'; exit 1; }
                mkdir "${'$'}STAGE/out" && tar -xzf "${'$'}STAGE/server.tgz" -C "${'$'}STAGE/out" || exit
                SERVER=${'$'}(find "${'$'}STAGE/out" -mindepth 1 -maxdepth 1 -type d | head -n 1)
                test -n "${'$'}SERVER" && test -f "${'$'}SERVER/product-info.json" && test -d "${'$'}SERVER/lib/rocksdbjni" || { printf 'DPAI_KOTLIN_LSP_LAYOUT: unexpected\n'; exit 1; }
                unzip -o -q "${'$'}STAGE/rocksdb.jar" "$6" -d "${'$'}SERVER/lib/rocksdbjni" || exit
                rm -rf "${'$'}SERVER/jbr"
                rm -rf "${'$'}DEST" && mv "${'$'}SERVER" "${'$'}DEST" || exit
                ln -sfn "${'$'}DEST" "${'$'}ROOT/current" || exit
                for OLD in "${'$'}ROOT"/[0-9]*; do test "${'$'}OLD" = "${'$'}DEST" || rm -rf "${'$'}OLD"; done
                printf 'DPAI_KOTLIN_LSP_OK\n'
                """,
                "$HOME/kotlin-lsp", build, KotlinLsp.archiveUrl(build, arch), KotlinLsp.ROCKSDB_URL, KotlinLsp.ROCKSDB_SHA256, KotlinLsp.rocksDbLibrary(arch), KotlinLsp.archiveSha256(arch),
            ),
        )
    }

    private const val VM_MARK = "--DPAI-VMOPTIONS--"

    /** Данные поставленной сборки: `product-info.json` и `vmoptions` одним выводом ([kotlinFiles]). */
    fun kotlinFilesCommand(): List<String> = sh(
        "dpai-kotlin-lsp-files",
        """
        cat "$1/product-info.json" || exit
        printf '\n%s\n' '$VM_MARK'
        cat "$1/bin/intellij-server.vmoptions" || exit
        """,
        KOTLIN_HOME,
    )

    fun kotlinFiles(output: List<String>): Pair<String, String>? {
        val mark = output.indexOf(VM_MARK).takeIf { it >= 0 } ?: return null
        return output.subList(0, mark).joinToString("\n") to output.subList(mark + 1, output.size).joinToString("\n")
    }

    /** Проект файла для службы: корень, интерпретатор запуска, архитектура, Android SDK и Android-проект. */
    data class Located(val root: String, val python: String?, val arch: String?, val androidSdk: String?, val androidProject: Boolean, val compileSdk: Int?)

    /**
     * Найти проект тем же правилом, что и запуск ▶ ([ProjectRuntime]):
     * интерпретатор (venv проекта D-109, иначе `python3`), `uname -m`,
     * поставленный Android SDK и признаки Android-проекта в файлах сборки.
     * Корень службы — самый верхний каталог с `settings.gradle(.kts)` до
     * границы: многомодульный Gradle-проект импортируется целиком, а не
     * модулем; без него — каталог проекта запуска.
     */
    fun locate(root: String, file: String): List<String> = ProjectRuntime.args(
        root, file,
        ProjectRuntime.context + "\n" + """
            DPAI_SERVICE_ROOT="${'$'}DPAI_PROJECT"; DPAI_SCAN="${'$'}DPAI_PROJECT"
            while :; do
                if test -f "${'$'}DPAI_SCAN/settings.gradle" || test -f "${'$'}DPAI_SCAN/settings.gradle.kts"; then DPAI_SERVICE_ROOT="${'$'}DPAI_SCAN"; fi
                test "${'$'}DPAI_SCAN" = "${'$'}DPAI_ROOT" && break
                test "${'$'}DPAI_SCAN" = / && break
                DPAI_SCAN=${'$'}(dirname "${'$'}DPAI_SCAN")
            done
            printf 'root=%s\n' "${'$'}DPAI_SERVICE_ROOT"
            if test -x "${'$'}DPAI_PROJECT_HOME/python/bin/python"; then printf 'python=%s\n' "${'$'}DPAI_PROJECT_HOME/python/bin/python"
            elif command -v python3 >/dev/null 2>&1; then printf 'python=%s\n' "${'$'}(command -v python3)"; fi
            printf 'arch=%s\n' "${'$'}(uname -m)"
            if test -d "$ANDROID_SDK/platforms" && test -n "${'$'}(ls "$ANDROID_SDK/platforms" 2>/dev/null)"; then printf 'android_sdk=%s\n' "$ANDROID_SDK"; fi
            DPAI_BUILD_FILES=${'$'}(find "${'$'}DPAI_SERVICE_ROOT" -maxdepth 3 \( -name build.gradle -o -name build.gradle.kts \) -not -path '*/build/*' -not -path '*/.gradle/*' 2>/dev/null | head -n 50)
            if test -n "${'$'}DPAI_BUILD_FILES"; then
                if printf '%s\n' "${'$'}DPAI_BUILD_FILES" | while IFS= read -r F; do cat "${'$'}F"; done | grep -q 'com\.android\.\|id("com.android\|android {\|alias(libs.plugins.android'; then printf 'android_project=1\n'; fi
                N=${'$'}(printf '%s\n' "${'$'}DPAI_BUILD_FILES" | while IFS= read -r F; do cat "${'$'}F"; done | grep -oE 'compileSdk(Version)?[[:space:]]*=?[[:space:]]*[0-9]+' | grep -oE '[0-9]+${'$'}' | sort -n | tail -n 1)
                if test -n "${'$'}N"; then printf 'compile_sdk=%s\n' "${'$'}N"; fi
            fi
        """.trimIndent(),
    )

    fun parseLocated(lines: List<String>): Located? {
        fun value(key: String) = lines.lastOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c.code < 32 } }
        val root = value("root")?.takeIf { it.startsWith("/") } ?: return null
        return Located(
            root = root,
            python = value("python")?.takeIf { it.startsWith("/") },
            arch = value("arch"),
            androidSdk = value("android_sdk")?.takeIf { it.startsWith("/") },
            androidProject = value("android_project") == "1",
            compileSdk = value("compile_sdk")?.toIntOrNull()?.takeIf { it in 1..1000 },
        )
    }

    /** Платформа Android SDK из манифеста Google: куда, откуда, SHA-1 манифеста и текст лицензии. */
    data class AndroidPlatform(val path: String, val directory: String, val url: String, val sha1: String, val size: Long, val licenseId: String, val license: String)

    const val ANDROID_REPOSITORY: String = "https://dl.google.com/android/repository/"

    /** Манифест репозитория Google — тот же, по которому ставит `sdkmanager`. */
    fun androidManifest(): List<String> = listOf("curl", "-sS", "--fail", "--proto", "=https", "--connect-timeout", "30", "--max-time", "120", "${ANDROID_REPOSITORY}repository2-3.xml")

    private val PLATFORM_DIR = Regex("android-[0-9]{1,3}(\\.[0-9]{1,2})?")
    private val SHA1 = Regex("[0-9a-f]{40}")
    private val ARCHIVE = Regex("[A-Za-z0-9._-]{1,120}\\.zip")

    /**
     * Стабильная платформа уровня [api] из манифеста [xml]: `android-N`, иначе
     * `android-N.0`; бета-каналы и расширения не берутся. Чужие данные: имя
     * архива с путём, неверный SHA-1 или каталог — `null`.
     */
    fun androidPlatform(xml: String, api: Int): AndroidPlatform? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(xml.byteInputStream()) }.getOrNull() ?: return null
        val root = doc.documentElement
        fun children(e: Element, name: String): List<Element> = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }.filter { it.localName == name }
        val stable = children(root, "channel").firstOrNull { it.textContent.trim() == "stable" }?.getAttribute("id") ?: "channel-0"
        val licenses = children(root, "license").associate { it.getAttribute("id") to it.textContent }
        val packages = children(root, "remotePackage").associateBy { it.getAttribute("path") }
        val chosen = listOf("platforms;android-$api", "platforms;android-$api.0").firstNotNullOfOrNull { path ->
            packages[path]?.takeIf { p -> children(p, "channelRef").firstOrNull()?.getAttribute("ref").let { it == null || it == stable } }?.let { path to it }
        } ?: return null
        val (path, element) = chosen
        val directory = path.substringAfter(';').takeIf { PLATFORM_DIR.matches(it) } ?: return null
        val complete = children(element, "archives").flatMap { children(it, "archive") }.firstNotNullOfOrNull { children(it, "complete").firstOrNull() } ?: return null
        val url = children(complete, "url").firstOrNull()?.textContent?.trim()?.takeIf { ARCHIVE.matches(it) } ?: return null
        val sha1 = children(complete, "checksum").firstOrNull { it.getAttribute("type") == "sha1" }?.textContent?.trim()?.takeIf { SHA1.matches(it) } ?: return null
        val size = children(complete, "size").firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: return null
        val licenseId = children(element, "uses-license").firstOrNull()?.getAttribute("ref") ?: return null
        val license = licenses[licenseId] ?: return null
        return AndroidPlatform(path, directory, ANDROID_REPOSITORY + url, sha1, size, licenseId, license)
    }

    /** Отметка принятой лицензии в `licenses/` SDK — SHA-1 её текста, как пишет `sdkmanager`. */
    fun licenseHash(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Поставить платформу в [ANDROID_SDK] после того, как человек принял её лицензию. */
    fun androidInstall(platform: AndroidPlatform): List<String> = sh(
        "dpai-android-sdk",
        """
        SDK="$1"; mkdir -p "${'$'}SDK/platforms" "${'$'}SDK/licenses" || exit
        STAGE=${'$'}(mktemp -d "${'$'}SDK/install.XXXXXX") || exit
        trap 'rm -rf "${'$'}STAGE"' EXIT
        $CURL "$2" -o "${'$'}STAGE/platform.zip" || exit
        printf '%s  %s\n' "$3" "${'$'}STAGE/platform.zip" | sha1sum -c - >/dev/null || { printf 'DPAI_ANDROID_SDK_CHECKSUM: failed\n'; exit 1; }
        mkdir "${'$'}STAGE/out" && unzip -q "${'$'}STAGE/platform.zip" -d "${'$'}STAGE/out" || exit
        PLATFORM=${'$'}(find "${'$'}STAGE/out" -mindepth 1 -maxdepth 1 -type d | head -n 1)
        test -n "${'$'}PLATFORM" && test -f "${'$'}PLATFORM/android.jar" || { printf 'DPAI_ANDROID_SDK_LAYOUT: unexpected\n'; exit 1; }
        rm -rf "${'$'}SDK/platforms/$4" && mv "${'$'}PLATFORM" "${'$'}SDK/platforms/$4" || exit
        touch "${'$'}SDK/licenses/$5" || exit
        grep -qx "$6" "${'$'}SDK/licenses/$5" || printf '\n%s' "$6" >> "${'$'}SDK/licenses/$5" || exit
        printf 'DPAI_ANDROID_SDK_OK\n'
        """,
        ANDROID_SDK, platform.url, platform.sha1, platform.directory, platform.licenseId.takeIf { Regex("[a-z0-9-]{1,60}").matches(it) } ?: "android-sdk-license", licenseHash(platform.license),
    )
}

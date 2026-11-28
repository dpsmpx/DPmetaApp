package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Официальный JVM-компилятор не зависит от ABI телефона; архив проверяется до распаковки (D-141). */
object KotlinRuntime {
    const val VERSION = "2.4.10"
    const val SHA256 = "473dd66c7a3ef4b182065b3da670466c1bf2773a9dbb0ed8b33a39fe9d4f876d"
    const val URL = "https://github.com/JetBrains/kotlin/releases/download/v$VERSION/kotlin-compiler-$VERSION.zip"
    val packages = listOf("openjdk21-jdk", "bash", "curl", "unzip", "ca-certificates")
    val libraries = linkedMapOf("kotlinx.coroutines" to "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
        "kotlinx.serialization.json" to "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0",
        "com.google.gson" to "com.google.code.gson:gson:2.13.2", "okhttp3" to "com.squareup.okhttp3:okhttp:4.12.0")
    private val coordinate = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}:[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}:[A-Za-z0-9][A-Za-z0-9_.+-]{0,79}")
    val javaEnvironment = """
        if test -x /usr/lib/jvm/java-21-openjdk/bin/java; then
            JAVA_HOME=/usr/lib/jvm/java-21-openjdk; export JAVA_HOME; PATH="${'$'}JAVA_HOME/bin:${'$'}PATH"; export PATH
        fi
        JAVA_OPTS="${'$'}{JAVA_OPTS:--Xmx512m -Xms32m}"; export JAVA_OPTS
    """.trimIndent()
    private val compiler = """
        $javaEnvironment
        DPAI_KOTLINC="${'$'}DPAI_STUDIO_HOME/kotlin/$VERSION/kotlinc/bin/kotlinc"
        for DPAI_TOOL in java javac jar bash; do
            command -v "${'$'}DPAI_TOOL" >/dev/null 2>&1 || { printf 'DPAI_KOTLIN_TOOLCHAIN: %s not found\n' "${'$'}DPAI_TOOL"; exit 127; }
        done
        test -x "${'$'}DPAI_KOTLINC" || { printf 'DPAI_KOTLIN_TOOLCHAIN: Kotlin $VERSION not installed\n'; exit 127; }
    """.trimIndent()
    private val classpath = """
        DPAI_KT_CP=
        DPAI_DEPS=${'$'}(readlink -f "${'$'}DPAI_PROJECT_HOME/kotlin/current" 2>/dev/null) || DPAI_DEPS=
        if test -n "${'$'}DPAI_DEPS" && test -f "${'$'}DPAI_DEPS/classpath"; then DPAI_KT_CP=${'$'}(cat "${'$'}DPAI_DEPS/classpath"); fi
    """.trimIndent()
    private val projectGate = """
        if test -f "${'$'}DPAI_PROJECT/build.gradle" || test -f "${'$'}DPAI_PROJECT/build.gradle.kts" || test -f "${'$'}DPAI_PROJECT/settings.gradle" || test -f "${'$'}DPAI_PROJECT/settings.gradle.kts"; then
            printf 'DPAI_KOTLIN_PROJECT: %s\n' "${'$'}DPAI_PROJECT"; exit 126
        fi
    """.trimIndent()
    fun defaultCommand(script: Boolean): String = ProjectRuntime.context + "\n" + projectGate + "\n" + compiler + "\n" + classpath + "\n" + if (script) {
        "exec \"\$DPAI_KOTLINC\" -classpath \"\$DPAI_KT_CP\" -script \"\$DPAI_FILE\""
    } else {
        """
            DPAI_BUILD=${'$'}(mktemp -d /tmp/dpai-kotlin.XXXXXX) || exit
            trap 'rm -rf "${'$'}DPAI_BUILD"' EXIT
            "${'$'}DPAI_KOTLINC" "${'$'}DPAI_FILE" -jvm-target 17 -classpath "${'$'}DPAI_KT_CP" -include-runtime -d "${'$'}DPAI_BUILD/main.jar" || exit
            if test -n "${'$'}DPAI_KT_CP"; then jar --update --file "${'$'}DPAI_BUILD/main.jar" --manifest "${'$'}DPAI_DEPS/manifest" || exit; fi
            java -jar "${'$'}DPAI_BUILD/main.jar"
        """.trimIndent()
    }
    fun installCompiler(root: String, file: String, force: Boolean = true): List<String> = ProjectRuntime.args(root, file, ProjectRuntime.context + "\n" + javaEnvironment + "\n" +
        ComponentDownloads.shell + "\n" +
        (if (force) "" else "if test -x \"\$DPAI_STUDIO_HOME/kotlin/$VERSION/kotlinc/bin/kotlinc\"; then \"\$DPAI_STUDIO_HOME/kotlin/$VERSION/kotlinc/bin/kotlinc\" -version && exit 0; fi\n") + """
        DPAI_SDK="${'$'}DPAI_STUDIO_HOME/kotlin"
        mkdir -p "${'$'}DPAI_SDK" || exit
        DPAI_STAGE=${'$'}(mktemp -d "${'$'}DPAI_SDK/install.XXXXXX") || exit
        DPAI_DEST="${'$'}DPAI_SDK/$VERSION"
        trap 'if test -d "${'$'}DPAI_STAGE/previous" && ! test -d "${'$'}DPAI_DEST"; then printf "DPAI_KOTLIN_BACKUP: %s\n" "${'$'}DPAI_STAGE/previous"; else rm -rf "${'$'}DPAI_STAGE"; fi' EXIT
        dpai_fetch '$URL' '$SHA256' "${'$'}DPAI_STAGE/compiler.zip" Kotlin-compiler sha256 900 DPAI_KOTLIN_CHECKSUM || exit
        printf '%s  %s\n' '$SHA256' "${'$'}DPAI_STAGE/compiler.zip" | sha256sum -c - || { printf 'DPAI_KOTLIN_CHECKSUM: failed\n'; exit 1; }
        mkdir "${'$'}DPAI_STAGE/payload" || exit
        unzip -q "${'$'}DPAI_STAGE/compiler.zip" -d "${'$'}DPAI_STAGE/payload" || exit
        test -f "${'$'}DPAI_STAGE/payload/kotlinc/lib/kotlin-compiler.jar" || exit 1
        chmod 700 "${'$'}DPAI_STAGE/payload/kotlinc/bin/kotlinc" || exit
        "${'$'}DPAI_STAGE/payload/kotlinc/bin/kotlinc" -version || exit
        if test -e "${'$'}DPAI_DEST"; then mv "${'$'}DPAI_DEST" "${'$'}DPAI_STAGE/previous" || exit; fi
        mv "${'$'}DPAI_STAGE/payload" "${'$'}DPAI_DEST" || { test ! -e "${'$'}DPAI_STAGE/previous" || mv "${'$'}DPAI_STAGE/previous" "${'$'}DPAI_DEST"; exit 1; }
        printf 'DPAI_KOTLIN_OK\n'
    """.trimIndent())
    fun probe(root: String, file: String): List<String> = ProjectRuntime.args(root, file, ProjectRuntime.context + "\n" + compiler + "\n" + classpath + "\n" + """
        DPAI_CHECK=${'$'}(mktemp -d /tmp/dpai-kotlin-check.XXXXXX) || exit
        trap 'rm -rf "${'$'}DPAI_CHECK"' EXIT
        printf 'fun main() { println("DPAI_KOTLIN_OK") }\n' > "${'$'}DPAI_CHECK/Main.kt" || exit
        "${'$'}DPAI_KOTLINC" -version || exit
        "${'$'}DPAI_KOTLINC" "${'$'}DPAI_CHECK/Main.kt" -jvm-target 17 -include-runtime -d "${'$'}DPAI_CHECK/main.jar" && java -jar "${'$'}DPAI_CHECK/main.jar"
    """.trimIndent())

    private val resolver = """
        import java.net.URLClassLoader
        import java.nio.file.Files
        import java.nio.file.Path
        import java.util.jar.Manifest
        import java.util.jar.Attributes
        val output = Path.of(args[0])
        val repository = Path.of(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath().normalize()
        val jars = linkedSetOf<Path>()
        var loader: ClassLoader? = this::class.java.classLoader
        while (loader != null) {
            val current = loader!!
            if (current is URLClassLoader) for (url in current.getURLs()) {
                if (url.protocol == "file") {
                    val path = Path.of(url.toURI()).toAbsolutePath().normalize()
                    if (path.startsWith(repository) && path.toString().endsWith(".jar")) jars.add(path)
                }
            }
            loader = current.parent
        }
        check(jars.isNotEmpty() && jars.size <= 512) { "DPAI_MAVEN_RESOLVE: no dependency jars or too many jars" }
        Files.writeString(output.resolve("classpath"), jars.joinToString(java.io.File.pathSeparator))
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.CLASS_PATH] = jars.joinToString(" ") { it.toUri().toASCIIString() }
        val encoded = java.io.ByteArrayOutputStream().also { manifest.write(it) }.toString("UTF-8")
        // Manifest.write без версии пропускает атрибуты; после корректного folding убираем только заголовок версии.
        Files.writeString(output.resolve("manifest"), encoded.substringAfter("\r\n"))
        println("DPAI_MAVEN_OK")
    """.trimIndent()
    fun dependencies(root: String, file: String, coordinates: List<String>): Outcome<List<String>> {
        if (!ProjectRuntime.validPaths(root, file) || coordinates.isEmpty() || coordinates.size > 50 || coordinates.any { !coordinate.matches(it) }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "Нужны пути Linux и координаты group:artifact:version, не больше 50; URL, ключи и диапазоны версий не принимаются")
        }
        val program = ProjectRuntime.context + "\n" + compiler + "\n" + """
            DPAI_BASE="${'$'}DPAI_PROJECT_HOME/kotlin"
            mkdir -p "${'$'}DPAI_BASE" || exit
            DPAI_NEXT=${'$'}(mktemp -d "${'$'}DPAI_BASE/deps.XXXXXX") || exit
            DPAI_COMMITTED=0
            trap 'test "${'$'}DPAI_COMMITTED" = 1 || rm -rf "${'$'}DPAI_NEXT"' EXIT
            DPAI_OLD=${'$'}(readlink -f "${'$'}DPAI_BASE/current" 2>/dev/null) || DPAI_OLD=
            if test -n "${'$'}DPAI_OLD" && test -f "${'$'}DPAI_OLD/libraries"; then
                test "${'$'}(wc -c < "${'$'}DPAI_OLD/libraries")" -le 16384 || { printf 'DPAI_MAVEN_FORMAT: oversized\n'; exit 1; }
                test "${'$'}(head -n 1 "${'$'}DPAI_OLD/libraries")" = dpai.kotlin-libraries.v1 || { printf 'DPAI_MAVEN_FORMAT: unknown version\n'; exit 1; }
                tail -n +2 "${'$'}DPAI_OLD/libraries" > "${'$'}DPAI_NEXT/all" || exit
            else : > "${'$'}DPAI_NEXT/all"; fi
            printf '%s\n' "${'$'}@" >> "${'$'}DPAI_NEXT/all"
            if grep -Ev '^[A-Za-z0-9_][A-Za-z0-9_.-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*:[A-Za-z0-9][A-Za-z0-9_.+-]*${'$'}' "${'$'}DPAI_NEXT/all"; then printf 'DPAI_MAVEN_FORMAT: invalid coordinate\n'; exit 1; fi
            awk 'length(${'$'}0)>282 { exit 1 }' "${'$'}DPAI_NEXT/all" || { printf 'DPAI_MAVEN_FORMAT: oversized coordinate\n'; exit 1; }
            awk -F: '{ items[${'$'}1 ":" ${'$'}2] = ${'$'}0 } END { for (k in items) print items[k] }' "${'$'}DPAI_NEXT/all" | LC_ALL=C sort > "${'$'}DPAI_NEXT/unique"
            test "${'$'}(wc -l < "${'$'}DPAI_NEXT/unique")" -le 50 || exit 1
            printf 'dpai.kotlin-libraries.v1\n' > "${'$'}DPAI_NEXT/libraries"
            cat "${'$'}DPAI_NEXT/unique" >> "${'$'}DPAI_NEXT/libraries"
            printf '@file:Repository("https://repo.maven.apache.org/maven2/")\n' > "${'$'}DPAI_NEXT/Resolve.main.kts"
            while IFS= read -r DPAI_GAV; do printf '@file:DependsOn("%s")\n' "${'$'}DPAI_GAV"; done < "${'$'}DPAI_NEXT/unique" >> "${'$'}DPAI_NEXT/Resolve.main.kts"
            cat >> "${'$'}DPAI_NEXT/Resolve.main.kts" <<'DPAI_RESOLVER'
        """.trimIndent() + "\n" + resolver + "\nDPAI_RESOLVER\n" + """
            # Maven HTTP-клиент иначе печатает Set-Cookie в предупреждениях разбора даты; ошибки загрузки остаются видимыми.
            JAVA_OPTS="${'$'}JAVA_OPTS -Dorg.slf4j.simpleLogger.log.org.jetbrains.kotlin.org.apache.http.client.protocol.ResponseProcessCookies=error"; export JAVA_OPTS
            "${'$'}DPAI_KOTLINC" -script "${'$'}DPAI_NEXT/Resolve.main.kts" -- "${'$'}DPAI_NEXT" || exit
            test -s "${'$'}DPAI_NEXT/classpath" && test -s "${'$'}DPAI_NEXT/manifest" || exit 1
            ln -s "${'$'}DPAI_NEXT" "${'$'}DPAI_NEXT/link" && mv -fT "${'$'}DPAI_NEXT/link" "${'$'}DPAI_BASE/current" || exit
            DPAI_COMMITTED=1
        """.trimIndent()
        return Outcome.success(ProjectRuntime.args(root, file, program, coordinates))
    }
    fun diagnose(text: String, code: Int): RunDiagnosis {
        val advice = RunDiagnostics.common(text).toMutableList()
        val apk = linkedSetOf<String>()
        val maven = linkedSetOf<String>()
        var env = false
        var project = false
        if (text.contains("DPAI_KOTLIN_TOOLCHAIN:") || Regex("(?:java|javac|kotlinc|jar): (?:not found|command not found)").containsMatchIn(text) || text.contains("UnsupportedClassVersionError") || text.contains("kotlin-stdlib.jar") && text.contains("not found")) {
            apk += packages; env = true
            advice += "Нужны JDK 21 и официальный Kotlin/JVM $VERSION. Подготовьте среду; после повреждения пакетов или компилятора используйте восстановление."
        }
        if (text.contains("DPAI_KOTLIN_PROJECT:") || text.contains("import android.") || text.contains("import androidx.")) {
            project = true
            advice += "Этот файл относится к Gradle/Android/Compose/KMP-проекту. Выберите команду/профиль проекта с нужными SDK и target. Одиночный JVM-запуск не собирает Android-приложение."
        }
        if (!project) for ((prefix, gav) in libraries) if ((text.contains(prefix) || text.contains(prefix.replace('.', '/'))) && (text.contains("unresolved reference", true) || text.contains("ClassNotFoundException") || text.contains("NoClassDefFoundError"))) maven += gav
        if (maven.isNotEmpty()) advice += "Можно разрешить эти JVM-библиотеки из Maven Central вместе с зависимостями для одиночного файла. Версии указаны явно; Gradle-файлы проекта не меняются."
        if (text.contains("unresolved reference", true) && maven.isEmpty()) advice += "Проверьте имя/import и classpath. Для зависимостей можно указать точные Maven-координаты; для Android/Gradle используйте проектную задачу."
        if ((text.contains("ClassNotFoundException") || text.contains("NoClassDefFoundError")) && maven.isEmpty()) advice += "JVM не нашла класс. Проверьте runtime-classpath, версию библиотеки и команду запуска; шаблон .kt включает стандартную библиотеку Kotlin."
        if (text.contains("incompatible version of Kotlin") || text.contains("binary version of its metadata")) advice += "Версии компилятора и библиотеки несовместимы. Выберите совместимую версию библиотеки либо согласуйте Kotlin-плагин проекта; доступен шаблон Kotlin $VERSION."
        if (text.contains("no main manifest attribute") || text.contains("main method not found", true) || text.contains("could not find or load main class", true)) advice += "Для .kt нужна точка входа fun main() или @JvmStatic main. Скрипт запускается как .kts; многофайловый проект — своей командой."
        if (text.contains("DPAI_KOTLIN_CHECKSUM:")) { env = true; advice += "Контрольная сумма компилятора не совпала: архив не установлен. Повторите загрузку после проверки подключения." }
        if (text.contains("DPAI_MAVEN_FORMAT:")) advice += "Файл списка библиотек повреждён или имеет неизвестную версию. Прежняя конфигурация сохранена; проверьте настройки зависимостей."
        if (text.contains("Could not find artifact") || text.contains("Could not resolve") || text.contains("401 Unauthorized") || text.contains("DPAI_MAVEN_RESOLVE:")) advice += "Проверьте координаты, версию, доступ к Maven-репозиторию и интернет. Для частного репозитория используйте настройки своего проекта."
        if (text.contains("Serializer for class") || text.contains("serializer()")) advice += "Для генерируемых сериализаторов нужен Kotlin serialization compiler plugin; настройте его в Gradle-проекте."
        if (text.contains("OutOfMemoryError") || text.contains("GC overhead limit")) advice += "JVM не хватило памяти. Сократите нагрузку или настройте JAVA_OPTS/профиль проекта с учётом памяти телефона."
        if (advice.isEmpty()) advice += "Kotlin/JVM завершился с кодом $code. Исправьте указанную строку компилятора либо проверьте исключение программы и команду запуска."
        return RunDiagnosis(RunLanguage.KOTLIN, code, advice.distinct(), apk.toList(), maven = maven.toList(), environmentProblem = env, projectCommand = project)
    }
}

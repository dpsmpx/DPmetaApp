package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.TargetPlatform
import java.util.Base64

/**
 * Мастер нового проекта (`DPmetaApp:D-150`; D-181): шаблоны C++, Kotlin и Python,
 * команды сборки, проверки и запуска для target и скрипт первого коммита. Без сети
 * и процессов: файлы пишет и команды запускает оболочка. Проект — локальный Git
 * без remote; GitHub — по желанию позже («Опубликовать»).
 */
object NewProject {
    enum class Language(val title: String) {
        CPP("C++"),
        KOTLIN("Kotlin"),
        PYTHON("Python"),
    }

    /**
     * Шаблон. [pcOnly] — собирается только на ПК-target (Android SDK есть только там);
     * [packageName] — у шаблона есть пакет (Kotlin), иначе имя модуля берётся из имени проекта.
     */
    enum class Template(val language: Language, val title: String, val description: String, val pcOnly: Boolean, val packageName: Boolean) {
        CPP_CMAKE(Language.CPP, "C++ · CMake", "Программа и библиотека, тесты ctest; на телефоне — clang и cmake в Linux приложения.", false, false),
        KOTLIN_CONSOLE(Language.KOTLIN, "Kotlin · консольный (kotlinc)", "Без Gradle: kotlinc и JDK, тот же компилятор, что у ▶ в IDE. Быстро и легко для телефона.", false, true),
        KOTLIN_GRADLE(Language.KOTLIN, "Kotlin · Gradle JVM", "Gradle wrapper, приложение и тесты JUnit; первая сборка скачивает Gradle (около 0,5 ГБ).", false, true),
        KOTLIN_ANDROID(Language.KOTLIN, "Kotlin · Android-приложение", "Gradle и Compose; собирается на ПК-target с Android SDK, APK подписывается ключом проекта или общим.", true, true),
        PYTHON_PACKAGE(Language.PYTHON, "Python · пакет", "pyproject.toml, пакет в src/ и тесты unittest — без загрузок из сети.", false, false),
    }

    /** Файл шаблона: путь от корня проекта через `/`; [executable] — с правом запуска. */
    class TemplateFile(val path: String, val bytes: ByteArray, val executable: Boolean = false) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    /** Файлы Gradle wrapper — из APK (те же, что у сборки приложения, `docs/PROVENANCE.md`). */
    class GradleWrapper(val gradlew: ByteArray, val gradlewBat: ByteArray, val jar: ByteArray, val properties: ByteArray)

    /** Проверенный ввод мастера: имя папки и проекта, пакет Kotlin или модуль Python/C++. */
    data class Spec(val template: Template, val name: String, val pkg: String) {
        /** Идентификатор из имени: `my-app` → `my_app` (пространство имён C++, модуль Python). */
        val ident: String get() = name.replace('-', '_')
    }

    /** Автор первого коммита, если Git на target его ещё не знает. */
    data class GitIdentity(val name: String, val email: String)

    /**
     * Команды проекта на target: профили «Сборка» и «Проверки», «Запуск» и [verify] —
     * argv проверки задач ИИ (D-23); строкой для формы проекта — [checkText].
     */
    data class Commands(val build: ManualProfile?, val check: ManualProfile, val run: RunProfile?, val verify: List<String>)

    const val MAX_NAME = 40
    const val BRANCH = "main"
    const val FIRST_COMMIT = "Начало проекта (мастер DP_IDE)"
    const val OK_MARKER = "DPAI_NEW_PROJECT_OK"
    const val MISSING_MARKER = "DPAI_MISSING"

    /** Папка проектов без GitHub — от домашней (`DPmetaApp:D-113`). */
    const val DEFAULT_PARENT = "projects/local"

    /** Подпапка рабочих деревьев задач рядом с проектами: скрытая, у каждого проекта своя. */
    const val TREES = ".trees"

    /** Версии Gradle-шаблонов — те же, что у сборки приложения (тест сверяет с каталогом версий). */
    const val KOTLIN_VERSION = "2.4.20"
    const val AGP_VERSION = "9.4.1"
    const val JUNIT_VERSION = "6.1.3"
    const val JUNIT4_VERSION = "4.13.2"
    const val COMPOSE_BOM = "2026.09.00"
    const val ACTIVITY_COMPOSE = "1.13.0"
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 26

    private val NAME = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    private val SEGMENT = Regex("[a-z][a-z0-9_]*")
    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
        "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )
    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "byte", "case", "catch", "char", "const", "default", "double", "enum", "extends", "final",
        "finally", "float", "goto", "implements", "import", "instanceof", "int", "long", "native", "new", "private", "protected",
        "public", "short", "static", "strictfp", "switch", "synchronized", "throws", "transient", "void", "volatile",
    )
    private val PYTHON_RESERVED = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "false",
        "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "none", "nonlocal", "not", "or", "pass", "raise",
        "return", "true", "try", "while", "with", "yield",
        // Имена модулей стандартной библиотеки, которые запуск из src/ подменил бы.
        "test", "tests", "json", "os", "sys", "io", "re", "time", "random", "string", "types", "typing", "logging", "unittest",
        "email", "http", "html", "math", "abc", "ast", "code", "copy", "csv", "enum", "secrets", "select", "signal", "socket", "queue",
    )
    private val CPP_KEYWORDS = setOf(
        "alignas", "alignof", "and", "asm", "auto", "bool", "break", "case", "catch", "char", "class", "concept", "const", "constexpr",
        "continue", "default", "delete", "do", "double", "else", "enum", "explicit", "export", "extern", "false", "float", "for",
        "friend", "goto", "if", "inline", "int", "long", "main", "mutable", "namespace", "new", "noexcept", "not", "nullptr", "operator",
        "or", "private", "protected", "public", "register", "requires", "return", "short", "signed", "sizeof", "static", "std", "struct",
        "switch", "template", "this", "throw", "true", "try", "typedef", "typename", "union", "unsigned", "using", "virtual", "void",
        "volatile", "while", "xor",
    )

    /** Пакет Kotlin по умолчанию для имени проекта. */
    fun defaultPackage(name: String): String = "local." + name.trim().lowercase().replace('-', '_').ifEmpty { "app" }

    /** Проверить ввод мастера: имя — латиница, цифры и `-`; пакет — сегменты через точку. */
    fun form(template: Template, name: String, pkg: String): Outcome<Spec> {
        val n = name.trim()
        if (n.length !in 1..MAX_NAME || !NAME.matches(n)) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "имя проекта — строчная латиница и цифры, слова через «-», до $MAX_NAME знаков, первая — буква", "name")
        }
        val ident = n.replace('-', '_')
        when (template.language) {
            Language.CPP -> if (ident in CPP_KEYWORDS) return Outcome.failure(DiagCode.VALUE_INVALID, "«$n» — слово C++: выберите другое имя", "name")
            Language.PYTHON -> if (ident in PYTHON_RESERVED) return Outcome.failure(DiagCode.VALUE_INVALID, "«$n» — занятое имя Python (слово языка или модуль стандартной библиотеки)", "name")
            Language.KOTLIN -> Unit
        }
        if (!template.packageName) return Outcome.success(Spec(template, n, ident))
        val p = pkg.trim()
        val parts = p.split('.')
        if (p.length > 100 || parts.any { !SEGMENT.matches(it) }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "пакет — части из строчной латиницы, цифр и «_» через точку, каждая с буквы", "package")
        }
        parts.firstOrNull { it in KOTLIN_KEYWORDS || it in JAVA_KEYWORDS }?.let {
            return Outcome.failure(DiagCode.VALUE_INVALID, "«$it» — слово Kotlin или Java: в пакете его не может быть", "package")
        }
        if (template == Template.KOTLIN_ANDROID && parts.size < 2) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "пакет Android-приложения — не меньше двух частей: например, local.$ident", "package")
        }
        return Outcome.success(Spec(template, n, p))
    }

    /** Проверить автора коммитов: оба поля пусты — `null` (Git на target знает автора сам). */
    fun identity(name: String, email: String): Outcome<GitIdentity?> {
        val n = name.trim()
        val e = email.trim()
        if (n.isEmpty() && e.isEmpty()) return Outcome.success(null)
        if (n.isEmpty() || n.length > 100 || n.any { it.isISOControl() || it == '<' || it == '>' }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "имя автора коммитов — до 100 знаков, без < и >", "gitName")
        }
        if (!Regex("[^\\s@<>]+@[^\\s@<>]+").matches(e) || e.length > 200) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "почта автора коммитов — вида имя@адрес", "gitEmail")
        }
        return Outcome.success(GitIdentity(n, e))
    }

    /**
     * [inApp] — Linux приложения. Ограничение шаблона объясняется словами;
     * SSH-target больше не определяется по адресу loopback (D-202).
     */
    fun unavailable(template: Template, inApp: Boolean): String? =
        if (template.pcOnly && inApp) "Android-приложение собирается на ПК-target с Android SDK — выберите ПК" else null

    /** Абсолютный путь проекта: домашний каталог [home], папка [parent] от него и имя. */
    fun root(home: String, parent: String, name: String): String = join(home, parent, name)

    /** Каталог рабочих деревьев задач проекта: `<parent>/.trees/<имя>`. */
    fun worktreeRoot(home: String, parent: String, name: String): String = join(home, parent, "$TREES/$name")

    /** Папка от домашней — относительный путь без `..`, `.` и пустых частей; пусто — [DEFAULT_PARENT]. */
    fun parentProblem(parent: String): String? {
        val p = parent.trim().trim('/')
        if (p.isEmpty()) return null
        if (p.length > 200 || p.any { it.isISOControl() || it == '\\' || it == ':' }) return "папка — путь от домашнего каталога через «/», до 200 знаков"
        if (p.split('/').any { it.isEmpty() || it == "." || it == ".." }) return "папка — без «.», «..» и пустых частей"
        return null
    }

    fun normalizeParent(parent: String): String = parent.trim().trim('/').ifEmpty { DEFAULT_PARENT }

    private fun join(home: String, parent: String, tail: String): String =
        home.trimEnd('/', '\\') + "/" + normalizeParent(parent) + "/" + tail

    /** Пакеты Alpine для шаблона в Linux приложения (вместе с Git). */
    fun alpinePackages(template: Template): List<String> = (listOf("git") + when (template) {
        Template.CPP_CMAKE -> CppDependencies.TOOLCHAIN_PACKAGES + "cmake"
        Template.KOTLIN_CONSOLE, Template.KOTLIN_GRADLE, Template.KOTLIN_ANDROID -> KotlinRuntime.packages
        Template.PYTHON_PACKAGE -> PythonRuntime.packages
    }).distinct()

    /** Нужен ли kotlinc приложения (ставится отдельно от пакетов, `KotlinRuntime`). */
    fun needsKotlinc(template: Template): Boolean = template == Template.KOTLIN_CONSOLE

    // ---------- Файлы ----------

    /** Файлы проекта; Gradle-шаблону нужен [wrapper]. */
    fun files(spec: Spec, wrapper: GradleWrapper?): Outcome<List<TemplateFile>> {
        val gradle = spec.template == Template.KOTLIN_GRADLE || spec.template == Template.KOTLIN_ANDROID
        if (gradle && wrapper == null) return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "в приложении нет Gradle wrapper для шаблона", spec.template.title)
        val text = when (spec.template) {
            Template.CPP_CMAKE -> cpp(spec)
            Template.KOTLIN_CONSOLE -> kotlinConsole(spec)
            Template.KOTLIN_GRADLE -> kotlinGradle(spec)
            Template.KOTLIN_ANDROID -> kotlinAndroid(spec)
            Template.PYTHON_PACKAGE -> python(spec)
        }
        val out = text.map { (path, body) -> TemplateFile(path, body.toByteArray(Charsets.UTF_8)) }.toMutableList()
        out += TemplateFile("README.md", readme(spec).toByteArray(Charsets.UTF_8))
        if (gradle && wrapper != null) {
            out += TemplateFile("gradlew", wrapper.gradlew, executable = true)
            out += TemplateFile("gradlew.bat", wrapper.gradlewBat)
            out += TemplateFile("gradle/wrapper/gradle-wrapper.jar", wrapper.jar)
            out += TemplateFile("gradle/wrapper/gradle-wrapper.properties", wrapper.properties)
        }
        return Outcome.success(out)
    }

    private fun pkgPath(spec: Spec) = spec.pkg.replace('.', '/')

    private fun cpp(s: Spec): List<Pair<String, String>> {
        val id = s.ident
        return listOf(
            "CMakeLists.txt" to """
                cmake_minimum_required(VERSION 3.20)
                project(${s.name} LANGUAGES CXX)

                set(CMAKE_CXX_STANDARD 17)
                set(CMAKE_CXX_STANDARD_REQUIRED ON)
                # compile_commands.json — для индекса C++ в IDE.
                set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

                add_library(${id}_core src/greeting.cpp)
                target_include_directories(${id}_core PUBLIC src)

                add_executable(${s.name} src/main.cpp)
                target_link_libraries(${s.name} PRIVATE ${id}_core)

                # Запуск сборкой: cmake --build build --target run
                add_custom_target(run COMMAND ${s.name} DEPENDS ${s.name} USES_TERMINAL)

                enable_testing()
                add_executable(${id}_tests tests/greeting_test.cpp)
                target_link_libraries(${id}_tests PRIVATE ${id}_core)
                add_test(NAME greeting COMMAND ${id}_tests)
            """.trimIndent() + "\n",
            "src/greeting.hpp" to """
                #pragma once

                #include <string>

                namespace $id {

                std::string greeting(const std::string& name);

                }  // namespace $id
            """.trimIndent() + "\n",
            "src/greeting.cpp" to """
                #include "greeting.hpp"

                namespace $id {

                std::string greeting(const std::string& name) {
                    return "Привет, " + name + "!";
                }

                }  // namespace $id
            """.trimIndent() + "\n",
            "src/main.cpp" to """
                #include <iostream>

                #include "greeting.hpp"

                int main(int argc, char** argv) {
                    std::cout << $id::greeting(argc > 1 ? argv[1] : "мир") << '\n';
                    return 0;
                }
            """.trimIndent() + "\n",
            "tests/greeting_test.cpp" to """
                #include <iostream>
                #include <string>

                #include "greeting.hpp"

                int main() {
                    int failed = 0;
                    auto expect = [&failed](const std::string& got, const std::string& want) {
                        if (got != want) {
                            std::cerr << "ожидалось «" << want << "», получено «" << got << "»\n";
                            ++failed;
                        }
                    };
                    expect($id::greeting("мир"), "Привет, мир!");
                    if (failed == 0) std::cout << "тесты пройдены\n";
                    return failed == 0 ? 0 : 1;
                }
            """.trimIndent() + "\n",
            ".gitignore" to "build/\n.cache/\n",
        )
    }

    private fun greetingKt(s: Spec) = """
        package ${s.pkg}

        /** Приветствие по имени. */
        fun greeting(name: String): String = "Привет, ${'$'}name!"
    """.trimIndent() + "\n"

    private fun mainKt(s: Spec) = """
        package ${s.pkg}

        fun main(args: Array<String>) {
            println(greeting(args.firstOrNull() ?: "мир"))
        }
    """.trimIndent() + "\n"

    private fun kotlinConsole(s: Spec): List<Pair<String, String>> = listOf(
        "src/main/kotlin/${pkgPath(s)}/Main.kt" to mainKt(s),
        "src/main/kotlin/${pkgPath(s)}/Greeting.kt" to greetingKt(s),
        "src/test/kotlin/${pkgPath(s)}/GreetingTests.kt" to """
            package ${s.pkg}

            /** Тесты без библиотек: собираются вместе с src/main и запускаются через java. */
            object GreetingTests {
                @JvmStatic
                fun main(args: Array<String>) {
                    val got = greeting("мир")
                    check(got == "Привет, мир!") { "ожидалось «Привет, мир!», получено «${'$'}got»" }
                    println("тесты пройдены")
                }
            }
        """.trimIndent() + "\n",
        ".gitignore" to "build/\n*.class\n.kotlin/\n",
    )

    private fun kotlinGradle(s: Spec): List<Pair<String, String>> = listOf(
        "settings.gradle.kts" to """
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            rootProject.name = "${s.name}"
        """.trimIndent() + "\n",
        "build.gradle.kts" to """
            plugins {
                kotlin("jvm") version "$KOTLIN_VERSION"
                application
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                testImplementation(platform("org.junit:junit-bom:$JUNIT_VERSION"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }

            application {
                mainClass = "${s.pkg}.MainKt"
            }

            tasks.test {
                useJUnitPlatform()
            }
        """.trimIndent() + "\n",
        "gradle.properties" to "org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8\nkotlin.daemon.jvmargs=-Xmx768m\n",
        "src/main/kotlin/${pkgPath(s)}/Main.kt" to mainKt(s),
        "src/main/kotlin/${pkgPath(s)}/Greeting.kt" to greetingKt(s),
        "src/test/kotlin/${pkgPath(s)}/GreetingTest.kt" to """
            package ${s.pkg}

            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Test

            class GreetingTest {
                @Test
                fun greetsByName() {
                    assertEquals("Привет, мир!", greeting("мир"))
                }
            }
        """.trimIndent() + "\n",
        ".gitignore" to ".gradle/\nbuild/\n.kotlin/\nlocal.properties\n",
    )

    private fun kotlinAndroid(s: Spec): List<Pair<String, String>> = listOf(
        "settings.gradle.kts" to """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "${s.name}"
            include(":app")
        """.trimIndent() + "\n",
        "build.gradle.kts" to """
            plugins {
                id("com.android.application") version "$AGP_VERSION" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "$KOTLIN_VERSION" apply false
            }
        """.trimIndent() + "\n",
        "gradle.properties" to "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\n",
        "app/build.gradle.kts" to """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.plugin.compose")
            }

            android {
                namespace = "${s.pkg}"
                compileSdk = $COMPILE_SDK

                defaultConfig {
                    applicationId = "${s.pkg}"
                    minSdk = $MIN_SDK
                    targetSdk = $TARGET_SDK
                    versionCode = 1
                    versionName = "0.1"
                }

                buildFeatures {
                    compose = true
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            dependencies {
                implementation(platform("androidx.compose:compose-bom:$COMPOSE_BOM"))
                implementation("androidx.compose.material3:material3")
                implementation("androidx.activity:activity-compose:$ACTIVITY_COMPOSE")
                testImplementation("junit:junit:$JUNIT4_VERSION")
            }
        """.trimIndent() + "\n",
        "app/src/main/AndroidManifest.xml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                <application
                    android:allowBackup="false"
                    android:label="${s.name}"
                    android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n",
        "app/src/main/kotlin/${pkgPath(s)}/MainActivity.kt" to "package ${s.pkg}\n\n" +
            // Импорты — списком: строки шаблона не должны выглядеть импортами этого модуля (D-02, проверка модулей).
            ANDROID_IMPORTS.joinToString("") { "import $it\n" } + "\n" + """
            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MaterialTheme {
                            Surface(Modifier.fillMaxSize()) {
                                Text(greeting("мир"), Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        """.trimIndent() + "\n",
        "app/src/main/kotlin/${pkgPath(s)}/Greeting.kt" to greetingKt(s),
        "app/src/test/kotlin/${pkgPath(s)}/GreetingTest.kt" to """
            package ${s.pkg}

            import org.junit.Assert.assertEquals
            import org.junit.Test

            class GreetingTest {
                @Test
                fun greetsByName() {
                    assertEquals("Привет, мир!", greeting("мир"))
                }
            }
        """.trimIndent() + "\n",
        ".gitignore" to ".gradle/\nbuild/\n.kotlin/\nlocal.properties\n*.apk\n",
    )

    private val ANDROID_IMPORTS = listOf(
        "android.os.Bundle",
        "androidx.activity.ComponentActivity",
        "androidx.activity.compose.setContent",
        "androidx.compose.foundation.layout.fillMaxSize",
        "androidx.compose.foundation.layout.padding",
        "androidx.compose.material3.MaterialTheme",
        "androidx.compose.material3.Surface",
        "androidx.compose.material3.Text",
        "androidx.compose.ui.Modifier",
        "androidx.compose.ui.unit.dp",
    )

    private fun python(s: Spec): List<Pair<String, String>> {
        val m = s.ident
        return listOf(
            "pyproject.toml" to """
                [build-system]
                requires = ["setuptools>=68"]
                build-backend = "setuptools.build_meta"

                [project]
                name = "${s.name}"
                version = "0.1.0"
                description = "Проект, созданный мастером DP_IDE"
                requires-python = ">=3.10"

                [project.scripts]
                ${s.name} = "$m.__main__:main"

                [tool.setuptools.packages.find]
                where = ["src"]
            """.trimIndent() + "\n",
            "src/$m/__init__.py" to "\"\"\"${s.name}.\"\"\"\n\nfrom .greeting import greeting\n\n__all__ = [\"greeting\"]\n",
            "src/$m/greeting.py" to "def greeting(name: str) -> str:\n    \"\"\"Приветствие по имени.\"\"\"\n    return f\"Привет, {name}!\"\n",
            "src/$m/__main__.py" to """
                import sys

                from .greeting import greeting


                def main() -> int:
                    print(greeting(sys.argv[1] if len(sys.argv) > 1 else "мир"))
                    return 0


                if __name__ == "__main__":
                    raise SystemExit(main())
            """.trimIndent() + "\n",
            "tests/test_greeting.py" to """
                import sys
                import unittest
                from pathlib import Path

                # Пакет — в src/: тесты находят его без установки.
                sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

                from $m import greeting  # noqa: E402


                class GreetingTest(unittest.TestCase):
                    def test_greets_by_name(self) -> None:
                        self.assertEqual(greeting("мир"), "Привет, мир!")


                if __name__ == "__main__":
                    unittest.main()
            """.trimIndent() + "\n",
            ".gitignore" to "__pycache__/\n*.pyc\n.venv/\nbuild/\ndist/\n*.egg-info/\n",
        )
    }

    private fun readme(s: Spec): String {
        val c = commands(s, TargetPlatform.POSIX, inApp = false)
        val lines = buildList {
            add("# ${s.name}")
            add("")
            add("Проект создан мастером DP_IDE: ${s.template.title}. Репозиторий Git — локальный, без GitHub;")
            add("опубликовать его можно позже из приложения.")
            add("")
            add("## Команды")
            add("")
            c.build?.let { b -> add("- Сборка: " + b.steps.joinToString(" && ") { "`" + shown(it.command) + "`" }) }
            add("- Тесты: " + c.check.steps.joinToString(" && ") { "`" + shown(it.command) + "`" })
            c.run?.let { r -> add("- Запуск: `${r.command}`" + (if (r.dir.isNotEmpty()) " (из папки `${r.dir}`)" else "")) }
            if (s.template == Template.KOTLIN_ANDROID) add("- APK: `app/build/outputs/apk/debug/` — в приложении «Артефакты» скачают и подпишут его.")
            add("")
            add("В DP_IDE: «IDE» → «Рабочий проект» — вкладки «Сборка», «Проверки» и «Запуск».")
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Команда для README: у `sh -c` — последняя строка сценария, kotlinc — по имени. */
    private fun shown(argv: List<String>): String =
        if (argv.size >= 3 && argv[0] == "/bin/sh" && argv[1] == "-c") argv[2].lines().last { it.isNotBlank() }.trim().replace("\"\$DPAI_KOTLINC\"", "kotlinc")
        else argv.joinToString(" ")

    /**
     * Строка команды проверки для формы проекта (`ProjectForm`): слова с пробелом или
     * кавычкой — в кавычках другого вида; разбор формы возвращает тот же argv. Слово
     * с обоими видами кавычек — `null` (шаблоны таких не дают).
     */
    fun checkText(argv: List<String>): String? {
        if (argv.any { '"' in it && '\'' in it }) return null
        return argv.joinToString(" ") { w ->
            when {
                w.isNotEmpty() && w.none { it.isWhitespace() || it == '"' || it == '\'' } -> w
                '"' !in w -> "\"" + w + "\""
                else -> "'" + w + "'"
            }
        }
    }

    // ---------- Команды ----------

    private const val BUILD_TIMEOUT = 1_800L
    private const val QUICK_TIMEOUT = 600L

    /**
     * Команды проекта на target платформы [platform]; [inApp] — Linux приложения
     * (kotlinc — из каталога приложения). Шаги — argv без оболочки; где нужна
     * оболочка — `/bin/sh -c` (POSIX) или `cmd.exe /c` (Windows).
     */
    fun commands(spec: Spec, platform: TargetPlatform, inApp: Boolean): Commands {
        val win = platform == TargetPlatform.WINDOWS
        fun step(name: String, timeout: Long, vararg argv: String) = ManualStep(name, argv.toList(), timeout)
        fun profile(kind: ManualProfileKind, steps: List<ManualStep>, artifacts: List<String> = emptyList()) = ManualProfile(kind, steps, artifacts)
        val name = spec.name
        return when (spec.template) {
            Template.CPP_CMAKE -> {
                val configure = step("Настройка CMake", QUICK_TIMEOUT, "cmake", "-S", ".", "-B", "build", "-DCMAKE_BUILD_TYPE=Debug")
                val build = step("Сборка", BUILD_TIMEOUT, "cmake", "--build", "build")
                val test = step("Тесты", QUICK_TIMEOUT, "ctest", "--test-dir", "build", "-C", "Debug", "--output-on-failure")
                Commands(
                    profile(ManualProfileKind.BUILD, listOf(configure, build), if (win) emptyList() else listOf("build/$name")),
                    profile(ManualProfileKind.CHECK, listOf(configure, build, test)),
                    if (win) RunProfile("cmake --build build --target run", "", buildFirst = false) else RunProfile("./build/$name", "", buildFirst = true),
                    if (win) listOf("cmd.exe", "/c", "cmake -S . -B build && cmake --build build && ctest --test-dir build -C Debug --output-on-failure")
                    else listOf("/bin/sh", "-c", "cmake -S . -B build && cmake --build build && ctest --test-dir build --output-on-failure"),
                )
            }
            Template.KOTLIN_CONSOLE -> {
                val jar = "build/$name.jar"
                val test = "build/tests.jar"
                if (win) {
                    Commands(
                        profile(ManualProfileKind.BUILD, listOf(step("Сборка", BUILD_TIMEOUT, "cmd.exe", "/c", "kotlinc src\\main\\kotlin -include-runtime -d build\\$name.jar"))),
                        profile(ManualProfileKind.CHECK, listOf(step("Тесты", BUILD_TIMEOUT, "cmd.exe", "/c",
                            "kotlinc src\\main\\kotlin src\\test\\kotlin -include-runtime -d build\\tests.jar && java -cp build\\tests.jar ${spec.pkg}.GreetingTests"))),
                        RunProfile("java -jar build\\$name.jar", "", buildFirst = true),
                        listOf("cmd.exe", "/c", "kotlinc src\\main\\kotlin src\\test\\kotlin -include-runtime -d build\\tests.jar && java -cp build\\tests.jar ${spec.pkg}.GreetingTests"),
                    )
                } else {
                    val kotlinc = kotlincLookup(inApp)
                    val buildScript = "$kotlinc\nmkdir -p build\n\"\$DPAI_KOTLINC\" src/main/kotlin -jvm-target 17 -include-runtime -d $jar"
                    val testScript = "$kotlinc\nmkdir -p build\n\"\$DPAI_KOTLINC\" src/main/kotlin src/test/kotlin -jvm-target 17 -include-runtime -d $test && java -cp $test ${spec.pkg}.GreetingTests"
                    Commands(
                        profile(ManualProfileKind.BUILD, listOf(step("Сборка", BUILD_TIMEOUT, "/bin/sh", "-c", buildScript)), listOf(jar)),
                        profile(ManualProfileKind.CHECK, listOf(step("Тесты", BUILD_TIMEOUT, "/bin/sh", "-c", testScript))),
                        RunProfile("java -jar $jar", "", buildFirst = true),
                        listOf("/bin/sh", "-c", testScript),
                    )
                }
            }
            Template.KOTLIN_GRADLE -> {
                val gradle = if (win) arrayOf("cmd.exe", "/c", "gradlew.bat", "--no-daemon") else arrayOf("./gradlew", "--no-daemon")
                Commands(
                    profile(ManualProfileKind.BUILD, listOf(step("Сборка", BUILD_TIMEOUT, *gradle, "installDist"))),
                    profile(ManualProfileKind.CHECK, listOf(step("Тесты", BUILD_TIMEOUT, *gradle, "test"))),
                    if (win) RunProfile("build\\install\\$name\\bin\\$name.bat", "", buildFirst = true) else RunProfile("build/install/$name/bin/$name", "", buildFirst = true),
                    listOf(*gradle, "test"),
                )
            }
            Template.KOTLIN_ANDROID -> {
                val gradle = if (win) arrayOf("cmd.exe", "/c", "gradlew.bat", "--no-daemon") else arrayOf("./gradlew", "--no-daemon")
                Commands(
                    profile(ManualProfileKind.BUILD, listOf(step("Сборка APK", BUILD_TIMEOUT, *gradle, "assembleDebug")), listOf("app/build/outputs/apk/debug/*.apk")),
                    profile(ManualProfileKind.CHECK, listOf(step("Тесты", BUILD_TIMEOUT, *gradle, "testDebugUnitTest"))),
                    null,
                    listOf(*gradle, "testDebugUnitTest"),
                )
            }
            Template.PYTHON_PACKAGE -> {
                val py = if (win) "python" else "python3"
                Commands(
                    profile(ManualProfileKind.BUILD, listOf(step("Проверка синтаксиса", QUICK_TIMEOUT, py, "-m", "compileall", "-q", "src"))),
                    profile(ManualProfileKind.CHECK, listOf(step("Тесты", QUICK_TIMEOUT, py, "-m", "unittest", "discover", "-s", "tests", "-v"))),
                    RunProfile("$py -m ${spec.ident}", "src", buildFirst = false),
                    listOf(py, "-m", "unittest", "discover", "-s", "tests", "-v"),
                )
            }
        }
    }

    /** kotlinc: в Linux приложения — свой (`KotlinRuntime`), иначе из PATH; JDK — как у ▶ в IDE. */
    private fun kotlincLookup(inApp: Boolean): String = KotlinRuntime.javaEnvironment + "\n" + (if (inApp) {
        "DPAI_KOTLINC=\"\${DPAI_STUDIO_HOME:-/root/.local/share/dpai}/kotlin/${KotlinRuntime.VERSION}/kotlinc/bin/kotlinc\"\n" +
            "test -x \"\$DPAI_KOTLINC\" || DPAI_KOTLINC=kotlinc"
    } else "DPAI_KOTLINC=kotlinc")

    /** Инструменты шаблона для проверки на target: имя для человека — имя команды. */
    fun tools(template: Template, platform: TargetPlatform): List<String> {
        val win = platform == TargetPlatform.WINDOWS
        return listOf("git") + when (template) {
            Template.CPP_CMAKE -> listOf("cmake", if (win) "ctest" else "c++")
            Template.KOTLIN_CONSOLE -> listOf("java", "kotlinc")
            Template.KOTLIN_GRADLE, Template.KOTLIN_ANDROID -> listOf("java")
            Template.PYTHON_PACKAGE -> listOf(if (win) "python" else "python3")
        }
    }

    /**
     * Проверка инструментов на target: строка `DPAI_MISSING <имя>` на каждый
     * отсутствующий, в конце — [OK_MARKER]. В Linux приложения kotlinc ищется и
     * в каталоге приложения.
     */
    fun probe(template: Template, platform: TargetPlatform, inApp: Boolean): List<String> {
        val tools = tools(template, platform)
        if (platform == TargetPlatform.WINDOWS) {
            val script = tools.joinToString("\n") { t ->
                "if (-not (Get-Command '${t}' -ErrorAction SilentlyContinue)) { Write-Output '$MISSING_MARKER $t' }"
            } + "\nWrite-Output '$OK_MARKER'"
            return powershell(script)
        }
        val studio = "\${DPAI_STUDIO_HOME:-/root/.local/share/dpai}/kotlin/${KotlinRuntime.VERSION}/kotlinc/bin/kotlinc"
        val script = KotlinRuntime.javaEnvironment + "\n" + tools.joinToString("\n") { t ->
            if (t == "kotlinc" && inApp) "test -x \"$studio\" || command -v kotlinc >/dev/null 2>&1 || echo '$MISSING_MARKER kotlinc'"
            else "command -v '$t' >/dev/null 2>&1 || echo '$MISSING_MARKER $t'"
        } + "\necho '$OK_MARKER'"
        return listOf("/bin/sh", "-c", script)
    }

    /** Недостающие инструменты из вывода [probe]; вывода с [OK_MARKER] нет — `null` (проверка не прошла). */
    fun missing(output: List<String>): List<String>? {
        if (output.none { it.trim() == OK_MARKER }) return null
        return output.map { it.trim() }.filter { it.startsWith("$MISSING_MARKER ") }.map { it.removePrefix("$MISSING_MARKER ").trim() }.distinct()
    }

    /**
     * Первый коммит в [root]: `git init`, ветка [BRANCH], автор [identity] — в настройки
     * репозитория (если задан), право запуска `gradlew` — и в индекс (иначе на Windows
     * оно потеряется), коммит; в конце — [OK_MARKER]. Повтор после сбоя безопасен.
     */
    fun gitInit(platform: TargetPlatform, root: String, identity: GitIdentity?): List<String> {
        if (platform == TargetPlatform.WINDOWS) {
            fun q(v: String) = "'" + v.replace("'", "''") + "'"
            val script = buildString {
                appendLine("\$ErrorActionPreference = 'Stop'")
                appendLine("function Run([string[]]\$a) { & git @a; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE } }")
                appendLine("Set-Location -LiteralPath ${q(root)}")
                appendLine("Run @('init', '-q')")
                appendLine("Run @('symbolic-ref', 'HEAD', 'refs/heads/$BRANCH')")
                identity?.let {
                    appendLine("Run @('config', 'user.name', ${q(it.name)})")
                    appendLine("Run @('config', 'user.email', ${q(it.email)})")
                }
                appendLine("Run @('add', '-A')")
                appendLine("if (Test-Path -LiteralPath 'gradlew') { Run @('update-index', '--chmod=+x', 'gradlew') }")
                appendLine("& git rev-parse --verify -q HEAD *> \$null")
                appendLine("if (\$LASTEXITCODE -ne 0) { Run @('commit', '-q', '-m', ${q(FIRST_COMMIT)}) }")
                appendLine("Write-Output '$OK_MARKER'")
            }
            return powershell(script)
        }
        val script = """
            set -e
            cd "${'$'}1"
            git init -q
            git symbolic-ref HEAD refs/heads/$BRANCH
            if test -n "${'$'}2"; then git config user.name "${'$'}2"; git config user.email "${'$'}3"; fi
            if test -f gradlew; then chmod +x gradlew; fi
            git add -A
            if test -f gradlew; then git update-index --chmod=+x gradlew; fi
            git rev-parse --verify -q HEAD >/dev/null || git commit -q -m "${'$'}4"
            printf '$OK_MARKER\n'
        """.trimIndent()
        return listOf("/bin/sh", "-c", script, "dpai-new-project", root, identity?.name.orEmpty(), identity?.email.orEmpty(), FIRST_COMMIT)
    }

    const val PUSHED_MARKER = "DPAI_PUBLISH_OK"
    const val OTHER_ORIGIN_MARKER = "DPAI_OTHER_ORIGIN"

    private val REMOTE = Regex("https://github\\.com/[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9._-]{1,100}\\.git")

    /**
     * «Опубликовать» (`DPmetaApp:D-150`): в [root] — remote `origin` = [url] (если его нет;
     * тот же адрес — повтор) и отправка [BRANCH] с привязкой. Другой `origin` — отказ с
     * [OTHER_ORIGIN_MARKER]. Вход Git не спрашивается в терминале: без входа — отказ
     * словами. Адрес — только `https://github.com/<владелец>/<имя>.git`, иначе `null`.
     */
    fun publish(platform: TargetPlatform, root: String, url: String): List<String>? {
        if (!REMOTE.matches(url)) return null
        if (platform == TargetPlatform.WINDOWS) {
            fun q(v: String) = "'" + v.replace("'", "''") + "'"
            val script = buildString {
                appendLine("\$ErrorActionPreference = 'Stop'")
                appendLine("\$env:GIT_TERMINAL_PROMPT = '0'; \$env:GCM_INTERACTIVE = 'never'")
                appendLine("Set-Location -LiteralPath ${q(root)}")
                // Адрес — как записан, без подмен insteadOf: иначе повтор выглядел бы чужим origin.
                appendLine("\$o = & git config --get remote.origin.url 2>\$null")
                appendLine("if (\$LASTEXITCODE -eq 0) { if (\$o -ne ${q(url)}) { Write-Output \"$OTHER_ORIGIN_MARKER \$o\"; exit 3 } } else { & git remote add origin ${q(url)}; if (\$LASTEXITCODE -ne 0) { exit 1 } }")
                appendLine("& git push -u origin $BRANCH 2>&1 | Write-Output; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }")
                appendLine("Write-Output '$PUSHED_MARKER'")
            }
            return powershell(script)
        }
        val script = """
            set -e
            export GIT_TERMINAL_PROMPT=0
            cd "${'$'}1"
            if o=${'$'}(git config --get remote.origin.url); then
                test "${'$'}o" = "${'$'}2" || { printf '$OTHER_ORIGIN_MARKER %s\n' "${'$'}o"; exit 3; }
            else
                git remote add origin "${'$'}2"
            fi
            git push -u origin $BRANCH 2>&1
            printf '$PUSHED_MARKER\n'
        """.trimIndent()
        return listOf("/bin/sh", "-c", script, "dpai-publish", root, url)
    }

    /** Итог отправки словами: [null] — отправлено. */
    fun publishProblem(output: List<String>): String? {
        if (output.any { it.trim() == PUSHED_MARKER }) return null
        output.firstOrNull { it.startsWith(OTHER_ORIGIN_MARKER) }?.let {
            return "у проекта уже есть remote origin (${it.removePrefix(OTHER_ORIGIN_MARKER).trim().take(200)}): отправьте ветку сами или уберите его"
        }
        val text = output.joinToString("\n")
        return when {
            "could not read Username" in text || "Authentication failed" in text || "terminal prompts disabled" in text ->
                "Git на target не вошёл в GitHub: репозиторий создан, отправьте ветку $BRANCH там (git push -u origin $BRANCH) или настройте вход"
            "Permission" in text && "denied" in text || "403" in text ->
                "GitHub не принял отправку: токену нужна запись содержимого (Contents: write)"
            else -> "ветка не отправлена: " + output.filter { it.isNotBlank() }.takeLast(4).joinToString(" ").take(400)
        }
    }

    /** Отказ Git словами: автор не задан — подсказка вписать его в мастере. */
    fun gitProblem(output: String): String =
        if ("Please tell me who you are" in output || "Author identity unknown" in output || "empty ident" in output) {
            "Git на target не знает автора коммитов: впишите имя и почту в мастере и повторите"
        } else if ("not found" in output && "git" in output || "is not recognized" in output) {
            "на target нет Git: установите его и повторите"
        } else {
            "первый коммит не сделан: " + output.lines().filter { it.isNotBlank() }.takeLast(5).joinToString(" ").take(500)
        }

    private fun powershell(script: String): List<String> =
        listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
}

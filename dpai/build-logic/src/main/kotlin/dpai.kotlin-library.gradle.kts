// Модуль ядра DP_AI_Orcestrator: чистый Kotlin/JVM без Android (D-02).
//
// Байт-код — Java 17: его принимает и Android (D8), и любой JDK 17+.
// Предупреждения компилятора — ошибки: это первая статическая проверка
// (docs/PROCESS.md, «Проверки»).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
    "testImplementation"(catalog.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Предупреждение обнаружения тестов — ошибка прогона: метод @Test,
    // который JUnit не запустит (например, возвращает значение), иначе
    // выглядел бы существующим, ничего не проверяя.
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
    // Корень репозитория — для тестов, читающих tests/data.
    systemProperty("dpai.repoRoot", rootDir.absolutePath)
    // Локаль с UTF-8 при любой локали машины: иначе JVM тестов на POSIX
    // кодирует кириллицу в аргументах процессов (git commit -m) как `?`, и
    // результат тестов зависел бы от машины. Сама защита от этого —
    // ArgumentEncoding (LocalProcessHostTest).
    environment("LC_ALL", "C.UTF-8")
    // Параметры property-тестов и адрес настоящего SSH target из командной
    // строки (docs/TESTING.md, «Свойства», «Слои»): без проброса они
    // остались бы в JVM Gradle.
    for (key in listOf("dpai.property.iterations", "dpai.property.seed", "dpai.test.ssh", "dpai.test.ssh.identity", "dpai.test.ssh.knownHosts", "dpai.test.claude", "dpai.test.proot", "dpai.endurance.minutes")) {
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Воспроизводимая сборка (этап 20): архивы без отметок времени файлов и с
// устойчивым порядком записей — две сборки одного коммита дают побайтно
// одинаковые jar и дистрибутивы (tools/verify/reproducible.sh).
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

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
    // Параметры property-тестов и адрес настоящего SSH target из командной
    // строки (docs/TESTING.md, «Свойства», «Слои»): без проброса они
    // остались бы в JVM Gradle.
    for (key in listOf("dpai.property.iterations", "dpai.property.seed", "dpai.test.ssh", "dpai.test.ssh.identity", "dpai.test.ssh.knownHosts")) {
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

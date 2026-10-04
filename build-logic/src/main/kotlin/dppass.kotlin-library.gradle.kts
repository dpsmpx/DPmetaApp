// Модуль ядра DPpass: чистый Kotlin/JVM без Android.
//
// Байт-код — Java 17: его принимает и Android (D8), и любой JDK 17+.
// Предупреждения компилятора — ошибки: это первая статическая проверка
// (docs/PROCESS.md, «Проверки»).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    // Android Lint и для JVM-модулей: при сборке :app (checkDependencies)
    // их код проверяется на уровни API Android (minSdk приложения).
    id("com.android.lint")
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
    // Корень репозитория — для тестов, читающих tests/data.
    systemProperty("dppass.repoRoot", rootDir.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

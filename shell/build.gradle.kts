// Приложение DPmetaApp — своя оболочка (D-35), один APK (D-53, D-54): пульт :app первым экраном, оболочка «AI»
// (dpai/android, библиотека — DP_AI_Orcestrator:D-103) вкладкой. Личность пакета прежняя —
// com.dpsmpx.dpai (D-49): APK ставится поверх отдельной сборки «AI», данные и ключи Keystore
// сохраняются. Плагины, ключ выпуска и упаковка — convention-плагин dpai/build-logic, общий с
// отдельной сборкой «AI» (DP_AI_Orcestrator:D-102, D-103).
// Подключается только при Android SDK (settings.gradle.kts).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dpai.android-application")
}

/**
 * `versionCode` из версии DPmetaApp (D-54): `0.N.P-dev.M` → N·10000 + P·100 + M, выпуск `0.N.P` →
 * N·10000 + P·100 + 99. Растёт с этапами и сборками и всегда больше, чем у отдельной сборки «AI».
 */
fun versionCodeOf(version: String): Int {
    val m = Regex("""0\.(\d+)\.(\d+)(?:-dev\.(\d+))?""").matchEntire(version)
        ?: throw GradleException("версия $version — не 0.N.P или 0.N.P-dev.M (D-54)")
    val (stage, patch, dev) = m.destructured
    val build = if (dev.isEmpty()) 99 else dev.toInt().also { require(it in 1..98) { "dev.$it: номер сборки этапа — 1…98 (D-54)" } }
    return stage.toInt() * 10_000 + patch.toInt() * 100 + build
}

android {
    namespace = "com.dpsmpx.dpmeta.shell"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dpsmpx.dpai"
        minSdk = 30
        targetSdk = 36
        versionName = project.version.toString()
        versionCode = versionCodeOf(versionName!!)
    }

    // Предупреждения Lint — ошибки (CLAUDE.md, «Правила кода»). Код библиотек проверяет Lint своих
    // модулей по правилам их подсистем (пульт — :app, «AI» — dpai/android); здесь — модуль и
    // слитый манифест.
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        // Подавления — с причиной в самом файле (JGit, D-65): байт-код библиотек в APK Lint смотрит и так.
        lintConfig = file("lint.xml")
        // targetSdk 36 — как у отдельной сборки «AI» (DP_AI_Orcestrator: docs/PROVENANCE.md, §2):
        // поднимается вместе с ней, иначе одно приложение вело бы себя по-разному в двух сборках.
        disable += "OldTargetApi"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// Один исходник контроллера: APK получает его при сборке, копия кода вручную не ведётся.
abstract class SharedVerifyAssets : DefaultTask() {
    @get:InputFile
    abstract val controller: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = output.get().file("shared-verify/runtime.py").asFile
        target.parentFile.mkdirs()
        controller.get().asFile.copyTo(target, overwrite = true)
    }
}
val sharedVerifyAssets by tasks.registering(SharedVerifyAssets::class) {
    controller.set(rootProject.layout.projectDirectory.file("tools/shared_verify/runtime.py"))
    output.set(layout.buildDirectory.dir("generated/shared-verify-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(sharedVerifyAssets, SharedVerifyAssets::output)
}
// AGP 9: модель Lint читает generated assets через preBuild, без зависимости от mergeAssets.
tasks.named("preBuild") { dependsOn(sharedVerifyAssets) }

dependencies {
    implementation(project(":app"))
    implementation("com.dpsmpx.dpai:android")
    // Числа строки «AI» сводки (D-76) — `Measure` ядра: передаются из «AI» в пульт через этот модуль.
    implementation("com.dpsmpx.dpai:core")
    // Разделы приложения для левой панели окна «AI» (D-112) — `AppSection` модели экранов.
    implementation("com.dpsmpx.dpai:ui")
    // DpmetaActivity наследует пульт — ComponentActivity нужна и на classpath компиляции.
    implementation(libs.activity.compose)
    // Слот документа настроек (D-112): оболочка передаёт блок пульта в документ «AI» — Compose на classpath.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
}

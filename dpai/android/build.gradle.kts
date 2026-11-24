// Оболочка Android (D-02, D-34) — библиотека (D-103, DPmetaApp:D-53): Compose рисует
// состояния экранов из ui; логики здесь нет. APK собирает приложение DPmetaApp из корня;
// отдельная упаковка подсистемы снята D-201. Личность пакета, версия,
// подпись и упаковка — у приложения. Подключается только при Android SDK (settings.gradle.kts).
// Плагины Android и Compose — convention-плагин build-logic (D-102).
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dpai.android-library")
}

// Отдельный режим для Linux студии: sysroot r28c с заголовками и библиотеками API 30.
val deviceSysroot = providers.environmentVariable("DPAI_ARM64_SYSROOT").orNull

android {
    namespace = "com.dpsmpx.dpai.android"
    compileSdk = 37

    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    defaultConfig {
        minSdk = 30
        // Версия подсистемы «AI» — не версия APK (DPmetaApp:D-54): с ней `doctor` сверяет
        // версию агента, и её показывает вкладка «AI» в приложении DPmetaApp.
        buildConfigField("String", "DPAI_VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Процессы и псевдотерминал Linux-среды — свой код на C через JNI (D-75, шаг 3; D-17: NDK — только здесь).
    // Версия NDK — та, что AGP берёт по умолчанию: NDK r28 выравнивает библиотеки под страницы 16 КБ.
    ndkVersion = "28.2.13676358"
    if (deviceSysroot == null) externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions.unitTests.all {
        it.useJUnitPlatform()
        it.systemProperty("dpai.editor.assets", file("src/main/assets").absolutePath)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Агент для Termux-target — вложение APK (инструкция «Настройка» сохраняет его в «Загрузки»):
// свежий `dpai` из :cli и обёртка Claude Code для proot — версия агента всегда та же, что у приложения.
val packAgent = tasks.register<Zip>("packAgent") {
    dependsOn(":cli:installDist")
    from(rootProject.file("cli/build/install/dpai")) { into("dpai") }
    // Скрипты — исполняемыми: без этого `dpai/bin/dpai` после unzip не запускался (ревью сборки 0.1.0-dev.2).
    eachFile { if (path == "dpai/bin/dpai") permissions { unix("rwxr-xr-x") } }
    archiveFileName.set("dpai-agent.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/dpai-setup-assets"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Linux-среда в приложении (D-75, D-115): proot — форк Termux, собранный здесь же статически под musl
// (tools/linux/build-proot.sh: закреплённый коммит, clang из NDK, sysroot из пакетов Alpine по суммам), и
// minirootfs Alpine — без изменений. Только aarch64: на устройстве другой архитектуры приложение ставится, но
// без Linux-среды (каталог библиотек ABI устройства proot не содержит). В репозиторий двоичные файлы не
// кладутся (docs/PROVENANCE.md, §4; сумма minirootfs закреплена 23 октября 2026 после проверки цепочки Alpine:
// ключи из minirootfs → подпись APKINDEX → контрольная сумма пакета в индексе → datahash данных). Загрузки — в
// домашнем каталоге Gradle: `clean` их не стирает, и сборка без сети идёт из кеша. Сборка proot — sh, git, curl,
// tar (Linux или macOS). Действие — без ссылок на объект скрипта: так его хранит кеш конфигурации.
val linuxFiles = tasks.register("linuxFiles") {
    val cache = File(gradle.gradleUserHomeDir, "caches/dpai-linux")
    val jni = layout.buildDirectory.dir("generated/linux-jni")
    val assets = layout.buildDirectory.dir("generated/linux-assets")
    val notice = file("linux/NOTICE.txt")
    val gpl = file("linux/GPL-2.0.txt")
    val buildProot = file("../tools/linux/build-proot.sh")
    val compat = file("../tools/linux/proot-musl-compat.h")
    val ndk = if (deviceSysroot == null) androidComponents.sdkComponents.ndkDirectory else null
    val sysroot = deviceSysroot
    val nativeSource = file("src/main/cpp/dpai_native.c")
    val jvmCompatSource = file("src/main/cpp/dpai_jvm_compat.c")
    inputs.property("nativeMode", sysroot ?: "ndk")
    if (sysroot != null) {
        inputs.files(nativeSource, jvmCompatSource)
        inputs.dir(File(sysroot, "usr/include"))
        inputs.dir(File(sysroot, "usr/lib/aarch64-linux-android/30"))
    }
    val work = temporaryDir
    // Адрес → закреплённая сумма SHA-256.
    val minirootfs = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz" to
        "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50"
    inputs.property("pins", minirootfs.toList().joinToString())
    inputs.files(notice, gpl, buildProot, compat)
    outputs.dirs(jni, assets)
    doLast {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun fetch(pin: Pair<String, String>): ByteArray {
            val file = File(cache, pin.first.substringAfterLast('/'))
            if (!file.isFile) {
                cache.mkdirs()
                URI(pin.first).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val bytes = file.readBytes()
            if (sha256(bytes) != pin.second) {
                file.delete()
                throw GradleException("${file.name}: SHA-256 ${sha256(bytes)}, а закреплена ${pin.second}")
            }
            return bytes
        }

        // proot и загрузчик для aarch64 — скриптом; его вывод — в ошибку сборки, если он не справился.
        val built = work.resolve("proot-aarch64")
        val process = ProcessBuilder("sh", buildProot.path, "aarch64", built.path, cache.resolve(if (sysroot == null) "proot" else "proot-native").path)
            .redirectErrorStream(true)
            .apply {
                if (ndk != null) environment()["NDK"] = ndk.get().asFile.path
                else {
                    environment().remove("LD_PRELOAD") // Bionic-библиотека JVM несовместима с musl гостя.
                    environment()["DPAI_PROOT_NATIVE"] = "1"
                }
            }
            .start()
        val log = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("сборка proot (tools/linux/build-proot.sh):\n" + log.lines().takeLast(40).joinToString("\n"))
        logger.info(log)
        val proot = built.resolve("proot").readBytes()
        val loader = built.resolve("loader").readBytes()
        // Заголовок ELF: 64 бита, aarch64 (e_machine 183) — а не то, что оказалось в каталоге.
        for ((name, elf) in listOf("proot" to proot, "загрузчик" to loader)) {
            val ok = elf.size > 64 && elf[0] == 0x7f.toByte() && elf[4] == 2.toByte() &&
                ByteBuffer.wrap(elf, 18, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() == 183
            if (!ok) throw GradleException("$name: не ELF aarch64")
        }
        // Выходы — заново: от прошлой сборки в APK не остаётся лишнего.
        jni.get().asFile.deleteRecursively()
        assets.get().asFile.deleteRecursively()
        val abi = jni.get().asFile.resolve("arm64-v8a").apply { mkdirs() }
        if (sysroot != null) {
            val lib = File(sysroot, "usr/lib/aarch64-linux-android/30")
            val obj = work.resolve("dpai-native.o")
            fun runNative(vararg argv: String) {
                val p = ProcessBuilder(*argv).redirectErrorStream(true)
                    .apply { environment().remove("LD_PRELOAD") }.start()
                val output = p.inputStream.bufferedReader().readText()
                if (p.waitFor() != 0) throw GradleException("${argv.first()}: $output")
            }
            for ((source, name) in listOf(nativeSource to "libdpai-native.so", jvmCompatSource to "libdpai-jvm-compat.so")) {
                runNative("clang", "--target=aarch64-linux-android30", "--sysroot=$sysroot", "-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC", "-c", source.path, "-o", obj.path)
                // Совместимость JVM — без crtbegin_so/crtend_so: её через LD_PRELOAD наследуют и программы musl.
                // Только NEEDED libm.so, как у CMake-сборки: Bionic через неё находит mallopt в libc,
                // а musl отдаёт libm.so из самой libc. crtbegin_so и libdl не нужны (их нет у musl).
                val args = if (name == "libdpai-jvm-compat.so") listOf(obj.path, "-L$lib", "-lm")
                    else listOf("$lib/crtbegin_so.o", obj.path, "-L$lib", "-lc", "-ldl", "$lib/crtend_so.o")
                runNative("ld.lld", "-shared", "-z", "max-page-size=16384", "-z", "defs", "-soname", name, "-o", abi.resolve(name).path, *args.toTypedArray())
            }
        }
        abi.resolve("libproot.so").writeBytes(proot)
        abi.resolve("libproot-loader.so").writeBytes(loader)
        val out = assets.get().asFile.resolve("linux").apply { mkdirs() }
        // Не `.tar.gz`: ресурс с расширением `.gz` AAPT кладёт в APK распакованным и без него.
        out.resolve("rootfs.tgz").writeBytes(fetch(minirootfs))
        notice.copyTo(out.resolve("NOTICE.txt"), overwrite = true)
        gpl.copyTo(out.resolve("GPL-2.0.txt"), overwrite = true)
    }
}

// Мастер нового проекта (DPmetaApp:D-150; D-181): Gradle wrapper шаблонов — тот же, что у этой сборки,
// копией в assets/newproject; в репозиторий второй копии не кладётся.
val newProjectAssets = tasks.register<Sync>("newProjectAssets") {
    from(rootProject.file("gradlew"))
    from(rootProject.file("gradlew.bat"))
    from(rootProject.file("gradle/wrapper")) { include("gradle-wrapper.jar", "gradle-wrapper.properties") }
    into(layout.buildDirectory.dir("generated/new-project-assets/newproject"))
}

// addGeneratedSourceDirectory у ресурсов assets задачу в граф не включал (AGP, 9 октября 2026) — каталоги и
// зависимость слияния заданы явно.
androidComponents {
    onVariants { v ->
        v.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/dpai-setup-assets").get().asFile.path)
        v.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/linux-assets").get().asFile.path)
        v.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/new-project-assets").get().asFile.path)
        v.sources.jniLibs?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/linux-jni").get().asFile.path)
    }
}
// Каталоги читают слияние и упаковка ресурсов и библиотек и lint (generate…LintReportModel и др.) — все
// зависят от задач явно; у библиотеки ресурсы упаковывает package…Assets, библиотеки — copy…JniLibs….
tasks.matching {
    it.name != packAgent.name && it.name != linuxFiles.name && it.name != newProjectAssets.name && (
        (it.name.startsWith("merge") || it.name.startsWith("package") || it.name.startsWith("copy")) &&
            (it.name.endsWith("Assets") || it.name.contains("JniLib") || it.name.endsWith("NativeLibs")) ||
            it.name.contains("lint", ignoreCase = true)
        )
}.configureEach { dependsOn(packAgent, linuxFiles, newProjectAssets) }

// Тот же код C — для машины сборки: JniProcessesTest гоняет его на JVM. Нет Linux или компилятора C —
// задача пропускается, а тест — с пометкой «пропущен» (на телефоне — docs/DEVICE_CHECK.md).
val hostNative = tasks.register<Exec>("hostNative") {
    val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.asFile.path }
    val source = file("src/main/cpp/dpai_native.c")
    val library = layout.buildDirectory.file("host-native/libdpai-native.so")
    val compiler = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "cc") }.firstOrNull { it.canExecute() }
    val linux = System.getProperty("os.name") == "Linux"
    inputs.file(source)
    outputs.file(library)
    onlyIf { linux && compiler != null }
    executable = compiler?.path ?: "cc"
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-std=c11", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC",
                "-I${jdk.get()}/include", "-I${jdk.get()}/include/linux",
                source.path, "-o", library.get().asFile.path,
            )
        },
    )
    doFirst { library.get().asFile.parentFile.mkdirs() }
}
tasks.withType<Test>().configureEach {
    dependsOn(hostNative)
    systemProperty("dpai.native.library", layout.buildDirectory.file("host-native/libdpai-native.so").get().asFile.path)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    // Подпись APK ключом автора из Keystore и проверка подписи (D-157).
    implementation(libs.apksig)
    implementation(project(":ui"))
    implementation(project(":jsch"))
    implementation(project(":terminal"))
    implementation(project(":providers"))
    // Подсказки локальной модели (D-105).
    implementation(project(":localai"))
    implementation(project(":sync"))
    implementation(project(":files"))
    implementation(project(":lsp"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

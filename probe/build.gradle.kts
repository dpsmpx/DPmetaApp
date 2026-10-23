// Пробник B2 (D-61, шаг 1; D-66): отдельное приложение Android, которое на
// телефоне проверяет Linux-среду внутри приложения — proot из
// nativeLibraryDir при targetSdk 36, корневую систему Alpine, жёсткие
// ссылки, скорость, фоновые процессы (docs/research/LINUX_IN_APP.md, §8).
// Не часть продукта: от модулей продукта не зависит и в APK приложения не
// входит. Подключается только при Android SDK (settings.gradle.kts).
//
// Двоичные файлы Alpine в репозиторий не кладутся: сборка загружает их и
// сверяет по SHA-256 (docs/PROVENANCE.md, §4). Суммы закреплены 23 октября
// 2026 после проверки цепочки Alpine: ключи из minirootfs → подпись
// APKINDEX → контрольная сумма пакета в индексе → datahash данных.
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dpsmpx.dpai.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dpsmpx.dpai.probe"
        minSdk = 30
        targetSdk = 36
        // 2 — разбор сети под proot по отчёту с Redmi Note 13 (23 октября 2026).
        versionCode = 2
        versionName = project.version.toString()
        // proot-static Alpine — только aarch64; Redmi Note 13 — arm64-v8a.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Пробник не раздаётся (D-60): release подписан отладочным ключом сборки — ставится рядом с приложением.
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Исполняемые файлы — из каталога библиотек, распакованными на диск: запуск из каталога данных
        // при targetSdk 29+ запрещён (docs/research/LINUX_IN_APP.md, §1).
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Загрузки — в домашнем каталоге Gradle: `clean` их не стирает, и сборка без сети идёт из кеша.
// Действие задачи — без ссылок на объект скрипта (только локальные значения): так его хранит кеш конфигурации.
val probeFiles = tasks.register("probeFiles") {
    val cache = File(gradle.gradleUserHomeDir, "caches/dpai-probe")
    val jni = layout.buildDirectory.dir("generated/probe-jni")
    val assets = layout.buildDirectory.dir("generated/probe-assets")
    val notice = file("NOTICE.txt")
    val gpl = file("GPL-2.0.txt")
    // Адрес → закреплённая сумма SHA-256.
    val minirootfs = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz" to
        "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50"
    val prootPackage = "https://dl-cdn.alpinelinux.org/alpine/v3.24/community/aarch64/proot-static-5.4.0-r2.apk" to
        "31d0e21ee87435ed819112cef84b8ce7d004c2835d8f61eeabe422e122b4b6cf"
    // proot.static из пакета и вшитый в него загрузчик (второй ELF файла, до конца таблицы разделов).
    val prootSha256 = "05e07c2dca260a720b3c191bb1c85c80126bb28991ba9060115bf19c8ba0a9a0"
    val loaderSha256 = "2ce2923d28939c1166e6e7b0676a4fcc3eb741d6fe1feb3e3dcd15819d58eef8"
    inputs.property("pins", listOf(minirootfs, prootPackage).joinToString() + prootSha256 + loaderSha256)
    inputs.files(notice, gpl)
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

        // Файл из tar.gz; пакет apk — несколько потоков gzip подряд, GZIPInputStream читает их все.
        fun fromTarGz(bytes: ByteArray, name: String): ByteArray {
            val tar = GZIPInputStream(bytes.inputStream()).readBytes()
            var off = 0
            while (off + 512 <= tar.size) {
                val header = tar.copyOfRange(off, off + 512)
                if (header.all { it.toInt() == 0 }) break
                val entry = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLong(8).toInt()
                if (entry.removePrefix("./") == name) return tar.copyOfRange(off + 512, off + 512 + size)
                off += 512 + (size + 511) / 512 * 512
            }
            throw GradleException("в архиве нет $name")
        }

        // Вшитый загрузчик proot: второй ELF в файле, длина — до конца таблицы разделов его заголовка.
        fun embeddedLoader(proot: ByteArray): ByteArray {
            val magic = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
            val at = (1 until proot.size - 64).firstOrNull { i -> (0..3).all { proot[i + it] == magic[it] } }
                ?: throw GradleException("в proot.static нет вшитого загрузчика")
            val buf = ByteBuffer.wrap(proot, at, proot.size - at).slice().order(ByteOrder.LITTLE_ENDIAN)
            val end = buf.getLong(0x28) + buf.getShort(0x3a).toLong() * buf.getShort(0x3c).toLong()
            return proot.copyOfRange(at, at + end.toInt())
        }

        val proot = fromTarGz(fetch(prootPackage), "usr/bin/proot.static")
        if (sha256(proot) != prootSha256) throw GradleException("proot.static: SHA-256 ${sha256(proot)}, а закреплена $prootSha256")
        val loader = embeddedLoader(proot)
        if (sha256(loader) != loaderSha256) throw GradleException("загрузчик proot: SHA-256 ${sha256(loader)}, а закреплена $loaderSha256")
        // Выходы — заново: от прошлой сборки в APK не остаётся лишнего.
        jni.get().asFile.deleteRecursively()
        assets.get().asFile.deleteRecursively()
        val abi = jni.get().asFile.resolve("arm64-v8a").apply { mkdirs() }
        abi.resolve("libproot.so").writeBytes(proot)
        abi.resolve("libproot-loader.so").writeBytes(loader)
        val out = assets.get().asFile.apply { mkdirs() }
        // Не `.tar.gz`: ресурс с расширением `.gz` AAPT кладёт в APK распакованным и без него.
        out.resolve("rootfs.tgz").writeBytes(fetch(minirootfs))
        notice.copyTo(out.resolve("NOTICE.txt"), overwrite = true)
        gpl.copyTo(out.resolve("GPL-2.0.txt"), overwrite = true)
    }
}

androidComponents {
    onVariants { v ->
        v.sources.jniLibs?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/probe-jni").get().asFile.path)
        v.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/probe-assets").get().asFile.path)
    }
}
// Каталоги читают слияние библиотек и ресурсов и lint — все зависят от загрузки явно (как packAgent модуля android).
tasks.matching {
    it.name != probeFiles.name && (
        it.name.startsWith("merge") && (it.name.endsWith("Assets") || it.name.endsWith("JniLibFolders") || it.name.endsWith("NativeLibs")) ||
            it.name.contains("lint", ignoreCase = true)
        )
}.configureEach { dependsOn(probeFiles) }

// Распаковщик корневой системы — на JVM, на настоящем minirootfs (RootfsTest).
android.testOptions.unitTests.all { it.useJUnitPlatform() }
tasks.withType<Test>().configureEach {
    dependsOn(probeFiles)
    systemProperty("dpai.probe.rootfs", layout.buildDirectory.file("generated/probe-assets/rootfs.tgz").get().asFile.path)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

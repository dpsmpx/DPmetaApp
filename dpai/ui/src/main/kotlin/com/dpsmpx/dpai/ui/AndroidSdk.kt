package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Android SDK в Linux приложения и на ПК по желанию (D-185, `DPmetaApp:D-159`).
 * Основной путь — официальные источники: архив cmdline-tools — по закреплённой
 * SHA-256, платформа и build-tools — `sdkmanager`, который сам сверяет суммы из
 * манифеста Google. Чего Google не выпускает под linux-arm64 (aapt2 для Gradle, NDK),
 * — по желанию сборкой сообщества: адрес https и SHA-256 вводит автор, сумма
 * сверяется до установки.
 */
object AndroidSdk {
    /** cmdline-tools 23.0 (сборка 16111833); SHA-1 из манифеста Google сверен 15 ноября 2026. */
    const val CMDLINE_VERSION = "23.0"
    const val CMDLINE_URL = "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
    const val CMDLINE_SHA256 = "0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8"
    const val PLATFORM = "platforms;android-36"
    const val BUILD_TOOLS = "build-tools;36.1.0"
    const val NDK = "ndk;29.0.14206865"
    const val LICENSE_URL = "https://developer.android.com/studio/terms"

    /** Папка SDK в госте; её же указывают в окружении профиля сборки: ANDROID_HOME. */
    const val HOME = "/root/android-sdk"

    /** Пакеты Alpine: JDK для sdkmanager и Gradle, загрузка и распаковка. */
    val packages = listOf("openjdk21-jdk", "bash", "curl", "unzip", "xz", "ca-certificates")

    /** Сколько места нужно: SDK — около 1,5 ГБ, с NDK — около 4 ГБ. */
    fun spaceBytes(ndk: Boolean): Long = if (ndk) 4L shl 30 else 3L shl 29

    /**
     * Чего из [needs] профиля нет в госте (D-186): [exists] — есть ли путь гостя, [hasNdk] —
     * есть ли каталог NDK (официальный или сообщества). Пусто — всё на месте.
     */
    fun missing(needs: Set<ManualNeed>, exists: (String) -> Boolean, hasNdk: Boolean): List<String> = buildList {
        if (ManualNeed.ANDROID_SDK in needs || ManualNeed.ANDROID_NDK in needs) {
            for ((path, what) in listOf("$HOME/platforms/android-36" to PLATFORM, "$HOME/build-tools/36.1.0" to BUILD_TOOLS)) if (!exists(path)) add(what)
        }
        if (ManualNeed.ANDROID_NDK in needs && !hasNdk) add("NDK")
    }

    /** Окружение шагов профиля, которому нужен SDK (D-186): ANDROID_HOME и ANDROID_SDK_ROOT, если профиль не задал свои. */
    fun env(needs: Set<ManualNeed>, env: List<Pair<String, String>>): List<Pair<String, String>> {
        if (needs.isEmpty()) return env
        val names = env.map { it.first }.toSet()
        return env + listOf("ANDROID_HOME" to HOME, "ANDROID_SDK_ROOT" to HOME).filter { it.first !in names }
    }

    /** Подсказка экрана для [missing]. */
    fun hint(missing: List<String>): String =
        "Профилю нужно: ${missing.joinToString()} — поставьте: «Настройки AI» → «Linux в приложении» → «Android SDK…»"

    /** Сборка сообщества: адрес https и SHA-256 (64 шестнадцатеричных знака). */
    data class Community(val url: String, val sha256: String)

    private val URL = Regex("https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~%+/-]*)?")
    private val SHA = Regex("[0-9a-f]{64}")

    /** Поле формы → сборка сообщества; пусто — `null` (без неё). */
    fun community(url: String, sha256: String, what: String): Outcome<Community?> {
        val u = url.trim()
        val s = sha256.trim().lowercase()
        if (u.isEmpty() && s.isEmpty()) return Outcome.success(null)
        if (!URL.matches(u)) return Outcome.failure(DiagCode.VALUE_INVALID, "$what: адрес — https, без пробелов и кавычек")
        if (!SHA.matches(s)) return Outcome.failure(DiagCode.VALUE_INVALID, "$what: SHA-256 — 64 шестнадцатеричных знака")
        return Outcome.success(Community(u, s))
    }

    /**
     * Скрипт установки для `/bin/sh -c` в госте. Без согласия с лицензией Android SDK
     * ([licenses]) `sdkmanager` ничего не ставит — согласие даёт человек в форме.
     * Итог — строка `DPAI_ANDROID_SDK_OK`; ошибки — строками `DPAI_ANDROID_…`.
     */
    fun install(licenses: Boolean, ndk: Boolean, aapt2: Community?, ndkCommunity: Community?): List<String> {
        // Адрес и сумма идут в скрипт: проверка — и здесь, конструктор Community открыт (проверка PR #190).
        for (c in listOfNotNull(aapt2, ndkCommunity)) require(URL.matches(c.url) && SHA.matches(c.sha256)) { "сборка сообщества — только через community()" }
        val script = buildString {
            appendLine(KotlinRuntime.javaEnvironment)
            appendLine("set -e")
            appendLine("SDK='$HOME'")
            appendLine("mkdir -p \"\$SDK\"")
            appendLine("ARCH=\$(uname -m)")
            appendLine("STAGE=\$(mktemp -d \"\$SDK/install.XXXXXX\")")
            appendLine("trap 'rm -rf \"\$STAGE\"' EXIT")
            appendLine("if ! test -x \"\$SDK/cmdline-tools/latest/bin/sdkmanager\"; then")
            appendLine("  curl --fail --location --proto '=https' --connect-timeout 30 --max-time 1800 '$CMDLINE_URL' -o \"\$STAGE/cmdline.zip\"")
            appendLine("  printf '%s  %s\\n' '$CMDLINE_SHA256' \"\$STAGE/cmdline.zip\" | sha256sum -c - || { echo 'DPAI_ANDROID_CHECKSUM: cmdline-tools'; exit 1; }")
            appendLine("  unzip -q \"\$STAGE/cmdline.zip\" -d \"\$STAGE/x\"")
            appendLine("  mkdir -p \"\$SDK/cmdline-tools\" && rm -rf \"\$SDK/cmdline-tools/latest\" && mv \"\$STAGE/x/cmdline-tools\" \"\$SDK/cmdline-tools/latest\"")
            appendLine("fi")
            appendLine("SDKM=\"\$SDK/cmdline-tools/latest/bin/sdkmanager\"")
            if (!licenses) {
                appendLine("echo 'DPAI_ANDROID_LICENSE: не принята — sdkmanager ничего не ставит'; exit 3")
            }
            // Только лицензия выбранных SDK-пакетов: --licenses принял бы и чужие/preview.
            appendLine("printf 'y\\n' | \"\$SDKM\" --sdk_root=\"\$SDK\" '$PLATFORM' '$BUILD_TOOLS'")
            appendLine("mkdir -p /root/.gradle")
            appendLine("touch /root/.gradle/gradle.properties")
            // aapt2 для Gradle: официальный — только x86_64; на arm64 — сборка сообщества по желанию.
            if (aapt2 != null) {
                appendLine("if test \"\$ARCH\" = aarch64; then")
                appendLine("  curl --fail --location --proto '=https' --connect-timeout 30 --max-time 900 '${aapt2.url}' -o \"\$STAGE/aapt2\"")
                appendLine("  printf '%s  %s\\n' '${aapt2.sha256}' \"\$STAGE/aapt2\" | sha256sum -c - || { echo 'DPAI_ANDROID_CHECKSUM: aapt2'; exit 1; }")
                appendLine("  mkdir -p \"\$SDK/dpai\" && install -m 755 \"\$STAGE/aapt2\" \"\$SDK/dpai/aapt2\"")
                appendLine("  \"\$SDK/dpai/aapt2\" version || { echo 'DPAI_ANDROID_AAPT2: не запускается на этом процессоре'; exit 1; }")
                appendLine("  grep -v '^android.aapt2FromMavenOverride=' /root/.gradle/gradle.properties > \"\$STAGE/gp\" || true")
                appendLine("  printf 'android.aapt2FromMavenOverride=%s\\n' \"\$SDK/dpai/aapt2\" >> \"\$STAGE/gp\" && mv \"\$STAGE/gp\" /root/.gradle/gradle.properties")
                appendLine("fi")
            } else {
                appendLine("test \"\$ARCH\" = aarch64 && echo 'DPAI_ANDROID_NOTE: aapt2 для Gradle — только x86_64; сборка с ресурсами — с aapt2 сообщества или на ПК'")
            }
            if (ndk) {
                appendLine("if test \"\$ARCH\" = x86_64; then")
                appendLine("  printf 'y\\n' | \"\$SDKM\" --sdk_root=\"\$SDK\" '$NDK'")
                if (ndkCommunity != null) {
                    appendLine("else")
                    appendLine("  curl --fail --location --proto '=https' --connect-timeout 30 --max-time 3600 '${ndkCommunity.url}' -o \"\$STAGE/ndk.pkg\"")
                    appendLine("  printf '%s  %s\\n' '${ndkCommunity.sha256}' \"\$STAGE/ndk.pkg\" | sha256sum -c - || { echo 'DPAI_ANDROID_CHECKSUM: ndk'; exit 1; }")
                    appendLine("  mkdir -p \"\$STAGE/n\" && case '${ndkCommunity.url}' in *.zip) unzip -q \"\$STAGE/ndk.pkg\" -d \"\$STAGE/n\" ;; *) tar -xf \"\$STAGE/ndk.pkg\" -C \"\$STAGE/n\" ;; esac")
                    appendLine("  set -- \"\$STAGE\"/n/*; test -d \"\$1\" || { echo 'DPAI_ANDROID_NDK: в архиве нет каталога'; exit 1; }")
                    appendLine("  mkdir -p \"\$SDK/ndk\" && rm -rf \"\$SDK/ndk/community\" && mv \"\$1\" \"\$SDK/ndk/community\"")
                    appendLine("  echo \"DPAI_ANDROID_NOTE: NDK сообщества — \$SDK/ndk/community; в проекте: android.ndkPath\"")
                }
                appendLine("fi")
                if (ndkCommunity == null) appendLine("test \"\$ARCH\" = aarch64 && echo 'DPAI_ANDROID_NOTE: официальный NDK — только x86_64; на телефоне — по адресу сборки сообщества'")
            }
            appendLine("printf 'DPAI_ANDROID_SDK_OK %s\\n' \"\$SDK\"")
        }
        return listOf("/bin/sh", "-c", script)
    }
}

package com.dpsmpx.dpai.ui

/**
 * JVM для Android ARM64 (D-222): ELF под Bionic/Android, а не OpenJDK Linux/musl.
 * Владелец устанавливает архив по закреплённой SHA-256 внутрь rootfs самого
 * DPmetaApp. Наличие отдельного приложения Termux не требуется.
 *
 * MobileStudio распространяет OpenJDK 21, собранный NDK r29 под Android 29+
 * (ARM64, Bionic). Это сборка сообщества, а не официальный Alpine JDK;
 * реальную совместимость с телефоном автора проверяет запуск java/javac.
 */
object AndroidJdk {
    const val HOME = "/opt/dpai-jdk21"
    const val VERSION = "21.0.14"
    const val URL = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/jdk21-android-21.0.14/jdk21-android-arm64.tar.gz"
    const val SHA256 = "8746f96a348c34b1e21508128478cb613de05f9d0aa9befad2967f83a9542680"
    const val INTERPRETER_MARKER = ".dpai-interpreter"
    const val INTERPRETER_OPTIONS = "-XX:-UseCompressedOops -Xint"

    /** Пакеты Alpine для передачи и распаковки, *не* openjdk21-jdk из musl. */
    val packages = listOf("curl", "ca-certificates", "tar")

    /** Только подтверждённый установленный JDK становится основным в среде гостя. */
    fun environment(base: Map<String, String>, installed: Boolean, interpreter: Boolean): Map<String, String> {
        if (!installed || "JAVA_HOME" in base) return base
        val bin = "$HOME/bin"
        val path = base["PATH"] ?: "/root/tools/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val options = if (interpreter && "JAVA_TOOL_OPTIONS" !in base) mapOf("JAVA_TOOL_OPTIONS" to INTERPRETER_OPTIONS) else emptyMap()
        return base + mapOf("JAVA_HOME" to HOME, "PATH" to if (path.split(':').contains(bin)) path else "$bin:$path") + options
    }

    /** Только для собственного локального aarch64 Linux приложения. */
    fun install(): List<String> = listOf("/bin/sh", "-c", buildString {
        appendLine("set -eu")
        appendLine("test \"\$(uname -m)\" = aarch64 || { echo 'DPAI_JDK_ARCH: нужна Android ARM64'; exit 1; }")
        appendLine("test -x /system/bin/linker64 || { echo 'DPAI_JDK_LINKER: Android /system/bin/linker64 не виден в Linux гостя'; exit 1; }")
        appendLine("JDK='$HOME'")
        appendLine("if test -x \"\$JDK/bin/java\" && \"\$JDK/bin/java\" -Xmx128m -version >/dev/null 2>&1 && \"\$JDK/bin/javac\" -version >/dev/null 2>&1; then echo 'DPAI_ANDROID_JDK_OK existing'; exit 0; fi")
        appendLine("if test -f \"\$JDK/$INTERPRETER_MARKER\" && JAVA_TOOL_OPTIONS='$INTERPRETER_OPTIONS' \"\$JDK/bin/java\" -Xmx128m -version >/dev/null 2>&1 && JAVA_TOOL_OPTIONS='$INTERPRETER_OPTIONS' \"\$JDK/bin/javac\" -version >/dev/null 2>&1; then echo 'DPAI_ANDROID_JDK_OK existing-interpreter'; exit 0; fi")
        appendLine("mkdir -p /opt")
        appendLine("STAGE=\$(mktemp -d /opt/dpai-jdk.XXXXXX)")
        appendLine("trap 'rm -rf \"\$STAGE\"' EXIT")
        appendLine("curl --fail --location --proto '=https' --connect-timeout 30 --max-time 1800 '$URL' -o \"\$STAGE/jdk.tar.gz\"")
        appendLine("printf '%s  %s\\n' '$SHA256' \"\$STAGE/jdk.tar.gz\" | sha256sum -c - || { echo 'DPAI_JDK_CHECKSUM: JDK не установлен'; exit 1; }")
        appendLine("mkdir \"\$STAGE/jdk\"")
        appendLine("tar -xzf \"\$STAGE/jdk.tar.gz\" -C \"\$STAGE/jdk\"")
        appendLine("test -x \"\$STAGE/jdk/bin/java\" && test -x \"\$STAGE/jdk/bin/javac\" && test -f \"\$STAGE/jdk/lib/server/libjvm.so\" || { echo 'DPAI_JDK_LAYOUT: неполная сборка'; exit 1; }")
        appendLine("if test -e \"\$JDK\"; then mv \"\$JDK\" \"\$STAGE/previous\"; fi")
        appendLine("mv \"\$STAGE/jdk\" \"\$JDK\"")
        appendLine("if \"\$JDK/bin/java\" -Xmx128m -version && \"\$JDK/bin/javac\" -version; then")
        appendLine("  echo 'DPAI_ANDROID_JDK_OK native-bionic'")
        appendLine("elif JAVA_TOOL_OPTIONS='$INTERPRETER_OPTIONS' \"\$JDK/bin/java\" -Xmx128m -version && JAVA_TOOL_OPTIONS='$INTERPRETER_OPTIONS' \"\$JDK/bin/javac\" -version; then")
        appendLine("  touch \"\$JDK/$INTERPRETER_MARKER\"")
        appendLine("  echo 'DPAI_JDK_NOTE: совместимый интерпретатор без JIT (-Xint)'")
        appendLine("  echo 'DPAI_ANDROID_JDK_OK native-bionic-interpreter'")
        appendLine("else")
        appendLine("  mv \"\$JDK\" \"\$STAGE/failed\"")
        appendLine("  if test -d \"\$STAGE/previous\"; then mv \"\$STAGE/previous\" \"\$JDK\"; fi")
        appendLine("  echo 'DPAI_JDK_START: Android-native JDK тоже не запускается; сохрани stderr и результат memory' >&2")
        appendLine("  exit 1")
        appendLine("fi")
    })

    fun probe(): List<String> = listOf("/bin/sh", "-c", """
        set -eu
        JAVA_HOME=$HOME
        test -x "${'$'}JAVA_HOME/bin/java"
        "${'$'}JAVA_HOME/bin/java" -Xmx128m -version
        "${'$'}JAVA_HOME/bin/javac" -version
        echo DPAI_ANDROID_JDK_OK
    """.trimIndent())
}

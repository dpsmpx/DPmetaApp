package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.execution.linux.ProotHost

/**
 * Android/Bionic Java, built by Termux for its fixed prefix. It is installed into a
 * *private, emulated* prefix inside DPmetaApp's Alpine rootfs. PRoot translates
 * /data/data/com.termux/... there; it does not access the real Termux application.
 *
 * Termux binaries are NOT relocatable to /root or to another Android package's
 * files directory. The prefix, Android system linker and shared libraries must
 * all be present before testing java. See termux-packages/packages/openjdk-21.
 */
object AndroidNativeJdk {
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val JAVA_HOME = "$PREFIX/lib/jvm/java-21-openjdk"
    const val JAVA = "$JAVA_HOME/bin/java"
    const val JAVAC = "$JAVA_HOME/bin/javac"
    const val COMPAT_ASSET = "/opt/dpai/libdpai-jvm-compat.so"
    const val COMPAT = "$PREFIX/lib/libdpai-jvm-compat.so"
    /** Sysroot API 30 для сборки JNI самой студией (DPAI_ARM64_SYSROOT в dpai/android/build.gradle.kts). */
    const val SYSROOT = "/root/.dpai/arm64-sysroot"

    // Same pinned bootstrap as the official Termux application (apt.android-7),
    // termux-app/app/build.gradle, downloadBootstraps. SHA-256 is checked before extraction.
    const val BOOTSTRAP_URL = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    const val BOOTSTRAP_SHA256 = "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"

    /** Only fixed paths. The Java probe uses the actual Android-native binary. */
    val buildEnvironment = mapOf(
        "JAVA_HOME" to JAVA_HOME,
        "PATH" to "$JAVA_HOME/bin:$PREFIX/bin:/root/tools/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        // Без неё HotSpot на устройствах с маркировкой указателей падает SIGABRT (sdkmanager, Gradle).
        "LD_PRELOAD" to COMPAT,
        "DPAI_ARM64_SYSROOT" to SYSROOT,
    )

    /**
     * Run by /bin/sh in the guest AFTER installing Alpine curl, unzip and ca-certificates.
     * The bootstrap is staged in the same filesystem and only published after its
     * pinned checksum, symlinks and permissions have been processed.
     * No external Termux installation, root, SSH or user-supplied executable needed.
     */
    fun install(): List<String> = listOf(ProotHost.REAL_USER_MARKER, "/bin/sh", "-c", """
        set -eu
        PREFIX='$PREFIX'
        HOME_NATIVE='$HOME'
        JAVA_HOME='$JAVA_HOME'
        JAVA='$JAVA'
        mkdir -p "${'$'}{PREFIX%/usr}"
        if ! test -x "${'$'}PREFIX/bin/bash"; then
          STAGE=${'$'}(mktemp -d "${'$'}{PREFIX}.dpai-stage.XXXXXX")
          trap 'rm -rf "${'$'}STAGE"' EXIT
          curl --fail --location --retry 2 --proto '=https' --connect-timeout 30 --max-time 1800 '$BOOTSTRAP_URL' -o "${'$'}STAGE/bootstrap.zip"
          printf '%s  %s\n' '$BOOTSTRAP_SHA256' "${'$'}STAGE/bootstrap.zip" | sha256sum -c - || { echo 'DPAI_NATIVE_JDK_BOOTSTRAP_SHA256'; exit 1; }
          mkdir -p "${'$'}STAGE/prefix"
          unzip -q "${'$'}STAGE/bootstrap.zip" -d "${'$'}STAGE/prefix"
          test -s "${'$'}STAGE/prefix/SYMLINKS.txt" || { echo 'DPAI_NATIVE_JDK_NO_SYMLINKS'; exit 1; }
          while IFS= read -r record || test -n "${'$'}record"; do
            case "${'$'}record" in
              *'←'*) link_target=${'$'}{record%%←*}; link_path=${'$'}{record#*←} ;;
              *) echo 'DPAI_NATIVE_JDK_BAD_SYMLINK'; exit 1 ;;
            esac
            case "${'$'}link_path" in
              ./*) ;;
              *) echo 'DPAI_NATIVE_JDK_BAD_SYMLINK_PATH'; exit 1 ;;
            esac
            case "${'$'}link_path" in
              *'../'*|*'/..'|*'//'*|*'././'*) echo 'DPAI_NATIVE_JDK_BAD_SYMLINK_PATH'; exit 1 ;;
            esac
            test -n "${'$'}link_target" || { echo 'DPAI_NATIVE_JDK_EMPTY_LINK'; exit 1; }
            mkdir -p "${'$'}(dirname "${'$'}STAGE/prefix/${'$'}{link_path#./}")"
            ln -s "${'$'}link_target" "${'$'}STAGE/prefix/${'$'}{link_path#./}"
          done < "${'$'}STAGE/prefix/SYMLINKS.txt"
          rm "${'$'}STAGE/prefix/SYMLINKS.txt"
          # TermuxInstaller.java also marks lib/apt's helpers and methods executable.
          for exec_dir in bin libexec lib/apt/apt-helper lib/apt/methods; do
            if test -d "${'$'}STAGE/prefix/${'$'}exec_dir"; then
              find "${'$'}STAGE/prefix/${'$'}exec_dir" -type f -exec chmod 755 {} +
            elif test -f "${'$'}STAGE/prefix/${'$'}exec_dir"; then
              chmod 755 "${'$'}STAGE/prefix/${'$'}exec_dir"
            fi
          done
          test -x "${'$'}STAGE/prefix/bin/bash" || { echo 'DPAI_NATIVE_JDK_BOOTSTRAP_INCOMPLETE'; exit 1; }
          if test -e "${'$'}PREFIX"; then
            mv "${'$'}PREFIX" "${'$'}{PREFIX}.incomplete.${'$'}${'$'}"
          fi
          mv "${'$'}STAGE/prefix" "${'$'}PREFIX"
          trap - EXIT
          rm -rf "${'$'}STAGE"
        fi
        mkdir -p "${'$'}HOME_NATIVE" "${'$'}PREFIX/tmp"
        export PREFIX HOME="${'$'}HOME_NATIVE" TMPDIR="${'$'}PREFIX/tmp"
        PATH="${'$'}PREFIX/bin:/system/bin:/usr/bin:/bin"; export PATH
        DPAI_UID=${'$'}(id -u)
        test "${'$'}DPAI_UID" != 0 || { echo 'DPAI_NATIVE_JDK_FAKE_ROOT: Termux apt refuses emulated root; check PRoot real-user marker'; exit 1; }
        echo "DPAI_NATIVE_JDK_REAL_UID=${'$'}DPAI_UID"
        if ! test -x "${'$'}JAVA"; then
          # The official Termux pkg wrapper rotates/checks its mirrors before
          # installing. Use it if present in the verified official bootstrap.
          if test -x "${'$'}PREFIX/bin/pkg"; then
            DEBIAN_FRONTEND=noninteractive "${'$'}PREFIX/bin/pkg" install -y openjdk-21 || { echo 'DPAI_NATIVE_JDK_PKG_FAILED: проверьте Bionic DNS (/dev/socket/dnsproxyd), сеть и зеркала Termux'; exit 1; }
          else
            # Bare bootstrap fallback: apt-get update may return 0 with DNS
            # warnings and stale indexes. Treat any index error as fatal.
            "${'$'}PREFIX/bin/apt-get" -o APT::Update::Error-Mode=any update || { echo 'DPAI_NATIVE_JDK_APT_UPDATE_FAILED: проверьте Bionic DNS (/dev/socket/dnsproxyd), сеть и зеркало Termux'; exit 1; }
            DEBIAN_FRONTEND=noninteractive "${'$'}PREFIX/bin/apt-get" install -y openjdk-21 || { echo 'DPAI_NATIVE_JDK_APT_INSTALL_FAILED'; exit 1; }
          fi
        fi
        # Sysroot Android API 30 для JNI и библиотеки совместимости — без NDK с ПК:
        # заголовки и crt*.o — пакет Termux ndk-sysroot, libc/libdl/libm — системные.
        mkdir -p "${'$'}{PREFIX%/files/usr}/cache/apt/archives/partial"
        if ! test -f "${'$'}PREFIX/lib/crtbegin_so.o"; then
          DEBIAN_FRONTEND=noninteractive "${'$'}PREFIX/bin/apt-get" install -y ndk-sysroot || { echo 'DPAI_NATIVE_SYSROOT_FAILED'; exit 1; }
        fi
        SYSROOT_LIB='$SYSROOT/usr/lib/aarch64-linux-android/30'
        mkdir -p "${'$'}SYSROOT_LIB"
        test -e '$SYSROOT/usr/include' || ln -s "${'$'}PREFIX/include" '$SYSROOT/usr/include'
        for crt in crtbegin_so.o crtend_so.o; do cp "${'$'}PREFIX/lib/${'$'}crt" "${'$'}SYSROOT_LIB/${'$'}crt"; done
        for so in libc libdl libm; do
          test -e "/system/lib64/${'$'}so.so" || { echo "DPAI_NATIVE_SYSROOT_FAILED: нет /system/lib64/${'$'}so.so"; exit 1; }
          ln -sf "/system/lib64/${'$'}so.so" "${'$'}SYSROOT_LIB/${'$'}so.so"
        done
        test -f '$SYSROOT/usr/include/malloc.h' || { echo 'DPAI_NATIVE_SYSROOT_FAILED: нет заголовков'; exit 1; }
        # cacerts JDK Termux — ссылка на файл, который строит postinst ca-certificates-java;
        # без него sdkmanager и Gradle не проверяют TLS. Источник — java-cacerts Alpine.
        CACERTS="${'$'}JAVA_HOME/lib/security/cacerts"
        if ! test -s "${'$'}CACERTS"; then
          test -s /etc/ssl/certs/java/cacerts || { echo 'DPAI_NATIVE_JDK_CACERTS_MISSING: нужен пакет Alpine java-cacerts'; exit 1; }
          CACERTS_TARGET=${'$'}(readlink "${'$'}CACERTS" || true)
          case "${'$'}CACERTS_TARGET" in
            "${'$'}PREFIX"/etc/*) mkdir -p "${'$'}(dirname "${'$'}CACERTS_TARGET")"; cp /etc/ssl/certs/java/cacerts "${'$'}CACERTS_TARGET" ;;
            '') cp /etc/ssl/certs/java/cacerts "${'$'}CACERTS" ;;
            *) echo "DPAI_NATIVE_JDK_CACERTS_LINK: ${'$'}CACERTS_TARGET"; exit 1 ;;
          esac
          test -s "${'$'}CACERTS" || { echo 'DPAI_NATIVE_JDK_CACERTS_MISSING'; exit 1; }
        fi
        test -r '$COMPAT_ASSET' || { echo 'DPAI_NATIVE_JDK_COMPAT_MISSING'; exit 1; }
        /usr/bin/install -m 755 '$COMPAT_ASSET' '$COMPAT'
        LD_PRELOAD='$COMPAT' "${'$'}JAVA" -Xmx128m -version || { echo 'DPAI_NATIVE_JDK_JAVA_FAILED'; exit 1; }
        LD_PRELOAD='$COMPAT' '$JAVAC' -version || { echo 'DPAI_NATIVE_JDK_JAVAC_FAILED'; exit 1; }
        # Versions alone do not prove that javac can compile or that the JVM can
        # load application classes. A real, isolated round-trip gates the SDK.
        CHECK=${'$'}(mktemp -d "${'$'}PREFIX/tmp/dpai-jdk-check.XXXXXX")
        trap 'rm -rf "${'$'}CHECK"' EXIT
        printf 'class DpaiJdkCheck { public static void main(String[] args) { System.out.println("DPAI_JDK_ROUNDTRIP_OK"); } }\n' > "${'$'}CHECK/DpaiJdkCheck.java"
        LD_PRELOAD='$COMPAT' '$JAVAC' -J-Xmx256m -d "${'$'}CHECK" "${'$'}CHECK/DpaiJdkCheck.java" || { echo 'DPAI_NATIVE_JDK_COMPILE_FAILED'; exit 1; }
        LD_PRELOAD='$COMPAT' "${'$'}JAVA" -Xmx128m -cp "${'$'}CHECK" DpaiJdkCheck | grep -qx DPAI_JDK_ROUNDTRIP_OK || { echo 'DPAI_NATIVE_JDK_CLASS_FAILED'; exit 1; }
        # Make unqualified `java` and `javac` in the Linux terminal use the
        # verified Bionic JDK as well. Never overwrite user's own commands.
        mkdir -p /root/tools/bin
        for native_tool in java javac; do
          dest="/root/tools/bin/${'$'}native_tool"
          source="${'$'}JAVA_HOME/bin/${'$'}native_tool"
          if test -e "${'$'}dest" || test -L "${'$'}dest"; then
            test "${'$'}(readlink "${'$'}dest" 2>/dev/null || true)" = "${'$'}source" || echo "DPAI_NATIVE_JDK_PATH_CONFLICT: ${'$'}dest (сохранён пользовательский файл)"
          else
            ln -s "${'$'}source" "${'$'}dest"
          fi
        done
        echo 'DPAI_NATIVE_JDK_OK'
    """.trimIndent())
}

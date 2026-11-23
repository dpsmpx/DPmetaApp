package com.dpsmpx.dpai.ui

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

    // Same pinned bootstrap as the official Termux application (apt.android-7),
    // termux-app/app/build.gradle, downloadBootstraps. SHA-256 is checked before extraction.
    const val BOOTSTRAP_URL = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    const val BOOTSTRAP_SHA256 = "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"

    /** Only fixed paths. The Java probe uses the actual Android-native binary. */
    val buildEnvironment = mapOf(
        "JAVA_HOME" to JAVA_HOME,
        "PATH" to "$JAVA_HOME/bin:$PREFIX/bin:/root/tools/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    /**
     * Run by /bin/sh in the guest AFTER installing Alpine curl, unzip and ca-certificates.
     * The bootstrap is staged in the same filesystem and only published after its
     * pinned checksum, symlinks and permissions have been processed.
     * No external Termux installation, root, SSH or user-supplied executable needed.
     */
    fun install(): List<String> = listOf("/bin/sh", "-c", """
        set -eu
        PREFIX='$PREFIX'
        HOME_NATIVE='$HOME'
        JAVA='$JAVA'
        mkdir -p "${'$'}{PREFIX%/usr}"
        if ! test -x "${'$'}PREFIX/bin/bash"; then
          STAGE="${'$'}{PREFIX}.dpai-stage.${'$'}${'$'}"
          mkdir -p "${'$'}STAGE"
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
        if ! test -x "${'$'}JAVA"; then
          "${'$'}PREFIX/bin/apt-get" update || { echo 'DPAI_NATIVE_JDK_APT_UPDATE_FAILED'; exit 1; }
          DEBIAN_FRONTEND=noninteractive "${'$'}PREFIX/bin/apt-get" install -y openjdk-21 || { echo 'DPAI_NATIVE_JDK_APT_INSTALL_FAILED'; exit 1; }
        fi
        "${'$'}JAVA" -Xmx128m -version || { echo 'DPAI_NATIVE_JDK_JAVA_FAILED'; exit 1; }
        '$JAVAC' -version || { echo 'DPAI_NATIVE_JDK_JAVAC_FAILED'; exit 1; }
        echo 'DPAI_NATIVE_JDK_OK'
    """.trimIndent())
}

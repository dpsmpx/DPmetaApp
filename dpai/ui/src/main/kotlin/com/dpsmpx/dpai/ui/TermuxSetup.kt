package com.dpsmpx.dpai.ui

/** Termux — обычный SSH-target, не отдельный тип и не доступ к каталогу другого Android-приложения (D-202). */
object TermuxSetup {
    const val HOST = "127.0.0.1"
    const val PORT = "8023"
    const val AGENT = "/data/data/com.termux/files/usr/bin/dpai-agent"
    /** Каталог слота для существующего маршрута проектов; сборка не требует входа в Claude. */
    const val CLAUDE_DIR = "/data/data/com.termux/files/home/.claude"

    private val KEY = Regex("^[a-z0-9-]+ [A-Za-z0-9+/]+={0,2} dpai-android$")
    private val HASH = Regex("[a-f0-9]{64}")

    /** Выполняет человек в Termux: пакеты должны быть нативными arm64, а JVM обязана стартовать. */
    val packages: String = "pkg install -y openssh openssh-sftp-server openjdk-21 git unzip aapt2 && java -version && javac -version && aapt2 version"

    /** Ключ публичный; подстановка в скрипт разрешена только после строгой проверки его алфавита. */
    fun pairing(appKey: String?, agentSha256: String?): String? {
        val key = appKey?.trim() ?: return null
        if (!KEY.matches(key) || agentSha256 == null || !HASH.matches(agentSha256)) return null
        return command("""
            set -eu
            zip="${'$'}HOME/storage/downloads/dpai-agent.zip"
            test -f "${'$'}zip" || { echo 'Сначала сохраните dpai-agent.zip в Загрузки через DPmetaApp и выполните termux-setup-storage'; exit 1; }
            printf '%s  %s\n' '$agentSha256' "${'$'}zip" | sha256sum -c - || { echo 'SHA-256 архива агента не совпал с текущим APK'; exit 1; }
            unzip -oq "${'$'}zip" -d "${'$'}HOME"
            chmod 755 "${'$'}HOME/dpai/bin/dpai"
            printf '#!%s/bin/sh\nexec "%s/bin/sh" "%s/dpai/bin/dpai" "${'$'}@"\n' "${'$'}PREFIX" "${'$'}PREFIX" "${'$'}HOME" > "${'$'}PREFIX/bin/dpai-agent"
            chmod 755 "${'$'}PREFIX/bin/dpai-agent"
            mkdir -p "${'$'}HOME/.ssh"
            chmod 700 "${'$'}HOME/.ssh"
            touch "${'$'}HOME/.ssh/authorized_keys"
            key='from="127.0.0.1,::1" $key'
            grep -Fxq "${'$'}key" "${'$'}HOME/.ssh/authorized_keys" || printf '%s\n' "${'$'}key" >> "${'$'}HOME/.ssh/authorized_keys"
            chmod 600 "${'$'}HOME/.ssh/authorized_keys"
            ssh-keygen -A
            "${'$'}PREFIX/bin/dpai-agent" agent doctor
            "${'$'}PREFIX/bin/sshd" -p $PORT -o ListenAddress=$HOST -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no
            printf 'Target: хост=$HOST порт=$PORT пользователь=%s агент=%s\n' "${'$'}(id -un)" "${'$'}PREFIX/bin/dpai-agent"
            for pub in "${'$'}PREFIX"/etc/ssh/ssh_host_*_key.pub; do test ! -f "${'$'}pub" || ssh-keygen -lf "${'$'}pub"; done
        """.trimIndent())
    }

    /** Проверить путь и ARM64 AAPT2 перед сборкой; SDK и лицензия устанавливаются отдельно. */
    val androidBuild: String = command("""
        java -version && javac -version && aapt2 version
        test -n "${'$'}{ANDROID_HOME:-}" || { echo 'Настройте ANDROID_HOME с Android SDK и platform android.jar'; exit 1; }
        test -d "${'$'}ANDROID_HOME/platforms" || { echo 'В ANDROID_HOME нет platforms'; exit 1; }
        mkdir -p "${'$'}HOME/.gradle"
        if ! grep -q '^android.aapt2FromMavenOverride=' "${'$'}HOME/.gradle/gradle.properties" 2>/dev/null; then
            printf 'android.aapt2FromMavenOverride=%s/bin/aapt2\n' "${'$'}PREFIX" >> "${'$'}HOME/.gradle/gradle.properties"
        fi
        grep '^android.aapt2FromMavenOverride=' "${'$'}HOME/.gradle/gradle.properties"
        echo 'Готовность конкретной версии AGP и NDK доказывает только сборка проекта.'
    """.trimIndent())

    /** Копируемая однострочная команда; скрипт записывается в свой mktemp и удаляется после любого результата. */
    private fun command(script: String): String =
        "f=\$(mktemp) || exit 1; printf '%s\\n' ${script.lines().joinToString(" ") { quote(it) }} > \"\$f\" && sh \"\$f\"; rc=\$?; rm -f \"\$f\"; exit \"\$rc\""

    private fun quote(line: String): String = "'" + line.replace("'", "'\\''") + "'"
}

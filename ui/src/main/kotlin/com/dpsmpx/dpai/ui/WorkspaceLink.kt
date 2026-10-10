package com.dpsmpx.dpai.ui

import java.util.Base64

/**
 * Связка «Claude на телефоне — дерево на ПК» (D-53, к D-50) без ручного
 * переноса ключей: приложение уже ходит по SSH и в Termux, и на ПК, поэтому
 * само (1) создаёт в proot Termux ключ, которым Claude Code ходит на ПК,
 * (2) добавляет его открытую строку на ПК — только из сети Tailscale,
 * прежняя строка связки заменяется, (3) кладёт в known_hosts proot ключ
 * хоста ПК, который человек уже подтвердил в приложении (D-31), и
 * (4) проверяет вход этим ключом. Здесь — команды и разбор их вывода;
 * исполняет контроллер приложения.
 */
object WorkspaceLink {
    const val KEY = ProjectForm.DEFAULT_IDENTITY
    const val KNOWN_HOSTS = "/root/.ssh/known_hosts"
    const val COMMENT = "dpai-workspace"
    private val PUBLIC = Regex("^(ssh-ed25519 [A-Za-z0-9+/]+={0,2})(?: .*)?$")

    private fun proot(script: String, vararg args: String): List<String> =
        listOf("proot-distro", "login", "alpine", "--", "sh", "-c", script, "sh", *args)

    /** В Termux: клиент SSH в proot и ключ связки (есть — не пересоздаётся); печатает открытую строку. */
    fun termuxKey(): List<String> = proot(
        "command -v ssh >/dev/null || apk add -q openssh-client || exit 1; " +
            "mkdir -p /root/.ssh && chmod 700 /root/.ssh; " +
            "test -f $KEY || ssh-keygen -q -t ed25519 -N '' -C $COMMENT -f $KEY || exit 1; " +
            "cat $KEY.pub",
    )

    /** Открытая строка ключа из вывода [termuxKey] — с комментарием [COMMENT]: по нему строка заменяется на ПК. */
    fun publicKey(stdout: List<String>): String? =
        stdout.asReversed().firstNotNullOfOrNull { PUBLIC.matchEntire(it.trim())?.groupValues?.get(1) }?.let { "$it $COMMENT" }

    /** На ПК (OpenSSH Windows, пользователь — администратор): строка [publicKey] в `administrators_authorized_keys`. */
    fun pcAuthorize(publicKey: String): List<String> {
        require(PUBLIC.matches(publicKey) && publicKey.endsWith(" $COMMENT")) { "не ключ связки" }
        val script = "\$ak = \"\$env:ProgramData\\ssh\\administrators_authorized_keys\"; " +
            "\$keep = @(if (Test-Path \$ak) { Get-Content \$ak | Where-Object { \$_ -notmatch ' $COMMENT\$' } }); " +
            "Set-Content \$ak (\$keep + 'from=\"100.64.0.0/10\" $publicKey') -Encoding ascii; " +
            "icacls \$ak /inheritance:r /grant Administrators:F /grant SYSTEM:F | Out-Null; 'dpai-linked'"
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
    }

    /** Имя хоста в known_hosts — как его пишут OpenSSH и JSch. */
    fun hostName(pc: TargetEntry): String = if (pc.port == 22) pc.host else "[${pc.host}]:${pc.port}"

    /** Строки ключа хоста ПК из known_hosts приложения — подтверждённые человеком. */
    fun hostLines(appKnownHosts: String, pc: TargetEntry): List<String> =
        appKnownHosts.lines().map(String::trim).filter { it.split(" ").let { p -> p.size >= 3 && p[0] == hostName(pc) } }

    /** В Termux: known_hosts proot — строки ПК заменяются строками со входа (stdin). */
    fun termuxKnownHosts(pc: TargetEntry): List<String> = proot(
        "f=$KNOWN_HOSTS; mkdir -p /root/.ssh && touch \"\$f\" && " +
            "awk -v h=\"\$1\" '\$1 != h' \"\$f\" > \"\$f.tmp\" && cat >> \"\$f.tmp\" && mv \"\$f.tmp\" \"\$f\" && chmod 600 \"\$f\"",
        hostName(pc),
    )

    /** В Termux: вход на ПК ключом связки так же строго, как пойдёт Claude Code; на ПК — `agent doctor`. */
    fun termuxCheck(pc: TargetEntry): List<String> = proot(
        "exec ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$KNOWN_HOSTS -o ConnectTimeout=15 " +
            "-i $KEY -p \"\$1\" \"\$2\" \"\$3\" agent doctor",
        pc.port.toString(), "${pc.user}@${pc.host}", pc.agent,
    )

    /** Ответил ли агент ПК на [termuxCheck]: строка JSON — вход ключом удался. */
    fun checked(stdout: List<String>): Boolean = stdout.any { it.trim().startsWith("{") }
}

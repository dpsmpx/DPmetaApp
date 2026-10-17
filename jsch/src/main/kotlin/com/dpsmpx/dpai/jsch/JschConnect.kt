package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.File
import kotlin.time.Duration

/**
 * Вход на target через JSch — общий для команд агента ([JschProcessHost])
 * и терминала ([JschShell]): только ключом, ключ хоста — из файла
 * известных хостов и строго (D-31, T2), обрыв — по `ServerAlive`.
 */
internal object JschConnect {
    private val DEFAULT_IDENTITIES = listOf("id_ed25519", "id_ecdsa", "id_rsa")

    fun open(endpoint: SshEndpoint, identities: List<Identity>, connectTimeout: Duration, subject: String): Outcome<Session> {
        val jsch = JSch()
        val home = System.getProperty("user.home")
        val knownHosts = endpoint.knownHostsFile ?: home?.let { File(it, ".ssh/known_hosts").path }
        if (knownHosts == null || !File(knownHosts).isFile) {
            return Outcome.failure(DiagCode.SSH_HOST_KEY_REJECTED, "нет файла известных хостов: ключ хоста не проверить (T2)", subject)
        }
        return try {
            jsch.setKnownHosts(knownHosts)
            if (identities.isNotEmpty()) {
                identities.forEach { jsch.addIdentity(it, null) }
            } else {
                val files = endpoint.identityFile?.let(::listOf)
                    ?: DEFAULT_IDENTITIES.mapNotNull { n -> home?.let { File(it, ".ssh/$n").takeIf(File::isFile)?.path } }
                files.forEach(jsch::addIdentity)
            }
            val session = jsch.getSession(endpoint.user, endpoint.host, endpoint.port)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.setServerAliveInterval(15_000)
            session.setServerAliveCountMax(3)
            session.connect(connectTimeout.inWholeMilliseconds.toInt())
            Outcome.success(session)
        } catch (e: JSchException) {
            Outcome.failure(JschProcessHost.classify(e), e.message?.take(300) ?: "отказ SSH", subject)
        }
    }
}

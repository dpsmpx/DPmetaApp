package com.dpsmpx.dpmeta.app.data

import android.content.Context
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.net.AccountLease
import java.io.IOException

/** Только DP-Verify: явный вход выдаёт право до остановки службы; второй копии на диске нет (D-95). */
object ConnectedGitHub {
    private val lease = AccountLease()

    fun request(): Long = lease.request()
    fun active(): Boolean = lease.read() != null
    fun account(): Outcome<Secret?> = Outcome.success(lease.read())
    fun close() = lease.close()

    fun authorize(context: Context, request: Long): Outcome<Unit> = grantFromStore(context, lease, request, "DP-Verify")

    /** Токен из хранилища пульта — в [lease] по входу [request]; [what] — для кого, в отказе. */
    internal fun grantFromStore(context: Context, lease: AccountLease, request: Long, what: String): Outcome<Unit> = try {
        when (val loaded = SecretStore(NioVfs(context.noBackupFilesDir.toPath().resolve("dpmeta"))).load()) {
            is Outcome.Failure -> loaded
            is Outcome.Success -> {
                val secret = loaded.value
                when {
                    secret == null -> Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "Сначала подключите GitHub-аккаунт в пульте")
                    !lease.grant(request, secret) -> Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "Служба или аккаунт изменились; повторите вход для $what")
                    else -> Outcome.success(Unit)
                }
            }
        }
    } catch (_: IOException) {
        Outcome.failure(DiagCode.STORAGE_IO_FAILED, "Токен GitHub не прочитан; проверьте аккаунт в пульте")
    }
}

/**
 * Токен GitHub для ИИ (D-114): явный вход раз за запуск, право — в памяти до остановки
 * процесса или службы, смены или удаления токена; второй копии на диске нет (как D-95).
 */
object AiGitHub {
    private val lease = AccountLease()

    fun request(): Long = lease.request()
    fun active(): Boolean = lease.read() != null
    fun account(): Secret? = lease.read()
    fun close() = lease.close()

    fun authorize(context: Context, request: Long): Outcome<Unit> = ConnectedGitHub.grantFromStore(context, lease, request, "ИИ")
}

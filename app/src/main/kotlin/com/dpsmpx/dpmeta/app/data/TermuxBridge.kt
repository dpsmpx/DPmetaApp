package com.dpsmpx.dpmeta.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.TermuxActions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Мост Termux оболочки (ТЗ, F-41; D-84): вызов [TermuxActions.Call] — официальным
 * намерением `com.termux.RUN_COMMAND` службе `com.termux.app.RunCommandService`, итог —
 * через `PendingIntent` на [TermuxResultReceiver].
 *
 * `PendingIntent` — явный (наш приёмник, `exported="false"`), разовый и **изменяемый**:
 * Termux кладёт итог в его extras при отправке, а у неизменяемого их отбрасывает
 * система (вики Termux, `RUN_COMMAND`). Подменить получателя нельзя: компонент задан,
 * а права `FILL_IN_COMPONENT` не даём. У каждого вызова — свой адрес `dpmeta-termux:<n>`:
 * ответ одного вызова не попадёт к другому. Итог — чужой текст: экран чистит его
 * ([TermuxActions.clean]); на диск и в журнал вывод не пишется.
 */
object TermuxBridge {
    const val PACKAGE: String = "com.termux"
    const val PERMISSION: String = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val RESULT = "result"
    private const val SCHEME = "dpmeta-termux"
    private const val RESULT_ACTION = "com.dpsmpx.dpmeta.TERMUX_RESULT"

    /** Что знает о Termux пакетный менеджер: установлен, версия, объявлено ли разрешение, выдано ли. */
    fun facts(context: Context): TermuxActions.Facts {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val declared = try {
            pm.getPermissionInfo(PERMISSION, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return TermuxActions.Facts(
            installed = info != null,
            versionName = info?.versionName,
            runCommand = declared,
            permitted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED,
            probe = null,
            probeSent = false,
        )
    }

    /** Ждущие итога вызовы: номер → защёлка и итог. */
    private class Waiting {
        val latch = CountDownLatch(1)
        val result = AtomicReference<TermuxActions.Result?>(null)
    }

    private val waiting = ConcurrentHashMap<String, Waiting>()

    /** Номера вызовов — от времени старта процесса: прежний процесс мог оставить неотвеченный `PendingIntent`. */
    private val counter = AtomicLong(Clock.SYSTEM.now().epochMillis)

    /**
     * Послать [call] и ждать итога не дольше [timeoutMs] (не на главном потоке). Нет
     * итога за срок — `Success(null)`: Termux команду не останавливает.
     */
    fun run(context: Context, call: TermuxActions.Call, timeoutMs: Long): Outcome<TermuxActions.Result?> {
        val app = context.applicationContext
        val id = counter.incrementAndGet().toString()
        val w = Waiting()
        waiting[id] = w
        try {
            val reply = Intent(app, TermuxResultReceiver::class.java).setAction(RESULT_ACTION).setData(Uri.fromParts(SCHEME, id, null))
            val pending = PendingIntent.getBroadcast(
                app,
                id.hashCode(),
                reply,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            val intent = Intent(ACTION).setClassName(PACKAGE, SERVICE)
                .putExtra(EXTRA_PATH, call.executable)
                .putExtra(EXTRA_ARGUMENTS, call.args.toTypedArray())
                .putExtra(EXTRA_BACKGROUND, call.background)
                .putExtra(EXTRA_LABEL, call.label)
                .putExtra(EXTRA_PENDING_INTENT, pending)
            call.workdir?.let { intent.putExtra(EXTRA_WORKDIR, it) }
            try {
                app.startForegroundService(intent)
            } catch (e: SecurityException) {
                return Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.OPERATION_NOT_ALLOWED, "Termux не принял команду: нет разрешения", e, "termux")))
            } catch (e: IllegalStateException) {
                return Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.OPERATION_NOT_ALLOWED, "Termux не принял команду", e, "termux")))
            }
            w.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return Outcome.success(w.result.get())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.failure(DiagCode.CANCELLED_BY_USER, "ожидание итога Termux прервано", "termux")
        } finally {
            waiting.remove(id)
        }
    }

    /** Итог от приёмника: ждёт его вызов — отдать; не ждёт (процесс был перезапущен) — `false`. */
    internal fun deliver(id: String, result: TermuxActions.Result): Boolean {
        val w = waiting[id] ?: return false
        w.result.set(result)
        w.latch.countDown()
        return true
    }

    /** Итог из `Bundle` Termux (`result`): ключи — из `TermuxConstants`; чего нет — пусто. */
    internal fun parse(intent: Intent): Pair<String, TermuxActions.Result>? {
        val data = intent.data ?: return null
        if (data.scheme != SCHEME) return null
        val id = data.schemeSpecificPart ?: return null
        val b: Bundle = intent.getBundleExtra(RESULT) ?: return null
        fun int(key: String): Int? = if (b.containsKey(key)) b.getInt(key) else null
        return id to TermuxActions.Result(
            stdout = b.getString("stdout").orEmpty(),
            stderr = b.getString("stderr").orEmpty(),
            exitCode = int("exitCode"),
            err = int("err") ?: TermuxActions.ERR_NONE,
            errmsg = b.getString("errmsg"),
            stdoutLength = b.getString("stdout_original_length")?.toIntOrNull(),
            stderrLength = b.getString("stderr_original_length")?.toIntOrNull(),
        )
    }
}

/**
 * Приёмник итога Termux (D-84): не экспортируется — до него доходит только
 * `PendingIntent`, отданный Termux вместе с вызовом. Разбор чужого `Bundle` — в
 * защите: сломанный итог не роняет приложение, ждущий вызов дождётся срока.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parsed = try {
            TermuxBridge.parse(intent)
        } catch (_: RuntimeException) {
            null
        } ?: return
        TermuxBridge.deliver(parsed.first, parsed.second)
    }
}

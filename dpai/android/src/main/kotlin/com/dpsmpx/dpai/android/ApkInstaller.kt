package com.dpsmpx.dpai.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Установка собранного APK (D-157): системный `PackageInstaller`, каждую установку
 * подтверждает человек в окне Android; приложению нужно разрешение «Установка
 * неизвестных приложений» (`REQUEST_INSTALL_PACKAGES`), Android спрашивает его
 * сам один раз.
 */
internal object ApkInstaller {
    private const val ACTION = "com.dpsmpx.dpai.APK_INSTALL_RESULT"

    /** Что внутри APK по словам самого Android; [installed] — версия уже стоящего пакета. */
    data class Meta(val packageName: String, val versionName: String?, val versionCode: Long, val self: Boolean, val installed: String?)

    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Экран разрешения «Установка неизвестных приложений» для DPmetaApp. */
    fun askPermission(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun meta(context: Context, apk: File): Outcome<Meta> {
        val pm = context.packageManager
        val info: PackageInfo = archive(pm, apk.path) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "Android не читает этот файл как APK", apk.name)
        val installed = runCatching { installedInfo(pm, info.packageName)?.versionName ?: "?" }.getOrNull()
        return Outcome.success(Meta(info.packageName, info.versionName, info.longVersionCode, info.packageName == context.packageName, installed))
    }

    private val waiting = ConcurrentHashMap<Int, (Outcome<String>) -> Unit>()

    @Volatile private var registered = false

    /**
     * Отправить APK в системную установку; [about] — что ставится (пакет, подпись,
     * сумма — [ApkReview.logLine]) для журнала; [done] — итог словами системы (из
     * потока приложения). Ставить — копию, показанную человеку ([ApkReview]).
     */
    fun install(context: Context, apk: File, about: String, done: (Outcome<String>) -> Unit) {
        val app = context.applicationContext
        register(app)
        val installer = app.packageManager.packageInstaller
        val session = try {
            installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        } catch (e: Exception) {
            return done(Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "установка не началась", e, apk.name)))
        }
        try {
            installer.openSession(session).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    s.fsync(out)
                }
                waiting[session] = done
                val intent = Intent(ACTION).setPackage(app.packageName)
                // Изменяемый PendingIntent: система дописывает в него итог установки; адресат — только своё приложение.
                val pi = PendingIntent.getBroadcast(app, session, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pi.intentSender)
            }
            AppLogs.info(app, "установка", "APK отправлен в установку: ${apk.name} — $about")
        } catch (e: Exception) {
            waiting.remove(session)
            runCatching { installer.abandonSession(session) }
            done(Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "APK не передан в установку", e, apk.name)))
        }
    }

    private fun register(app: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            ContextCompat.registerReceiver(app, Receiver(), IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Окно подтверждения Android: установку разрешает только человек.
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else legacyIntent(intent)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    AppLogs.info(context, "установка", "установлено: ${intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()}")
                    waiting.remove(session)?.invoke(Outcome.success("установлено"))
                }
                else -> {
                    val why = when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "установка отменена"
                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "конфликт с установленным приложением: другая подпись или более новая версия"
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK не подходит этому телефону (ABI или версия Android)"
                        PackageInstaller.STATUS_FAILURE_INVALID -> "APK повреждён или не подписан"
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "не хватает места для установки"
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "установка запрещена на этом телефоне"
                        else -> "установка не удалась"
                    }
                    AppLogs.info(context, "установка", "не установлено: $why ${message.take(200)}")
                    waiting.remove(session)?.invoke(Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, if (message.isBlank()) why else "$why ($message)"))
                }
            }
        }

        // API 30–32: типизированного getParcelableExtra ещё нет.
        @Suppress("DEPRECATION")
        private fun legacyIntent(intent: Intent): Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    // API 30–32: флаги-объекты PackageInfoFlags появились в API 33.
    @Suppress("DEPRECATION")
    private fun archive(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0)) else pm.getPackageArchiveInfo(path, 0)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, name: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0)) else pm.getPackageInfo(name, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

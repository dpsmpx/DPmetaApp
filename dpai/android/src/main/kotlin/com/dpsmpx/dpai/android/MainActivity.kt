package com.dpsmpx.dpai.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Что пришло из своего уведомления: ссылка `dpai://…` или ответ, ждущий
 * подтверждения на экране (D-44). Экран забирает и очищает.
 */
data class Incoming(val link: String? = null, val confirm: String? = null, val stop: Boolean = false)

/** Единственная activity: экраны — Compose (D-34), данные — контроллер или демо (D-35). */
class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf(Incoming())

    /**
     * Только через неэкспортируемый вход `InternalEntry`: чужое приложение
     * может открыть экран, но не подсунуть ссылку или ответ на подтверждение.
     */
    private fun take(intent: Intent?) {
        if (intent?.component?.className != INTERNAL_ENTRY) return
        when (intent.action) {
            OPEN -> intent.getStringExtra(EXTRA_LINK)?.let { incoming.value = Incoming(link = it) }
            CONFIRM -> intent.getStringExtra(EXTRA_URI)?.let { incoming.value = Incoming(confirm = it) }
            STOP_CONFIRM -> incoming.value = Incoming(stop = true)
        }
    }

    override fun onResume() {
        super.onResume()
        // Приложение на экране — окно запроса поверх него не нужно (D-44).
        RequestOverlay.appVisible = true
        RequestOverlay.hide()
    }

    override fun onPause() {
        RequestOverlay.appVisible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        enableEdgeToEdge()
        // Android 13+: без разрешения уведомления не показываются; отказ — не ошибка, сводка видна в приложении.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val controller = Controller.get(filesDir)
        val model = DemoModel.get(this)
        AppLogs.get(this).step("экран открыт")
        setContent {
            DpaiTheme {
                DpaiApp(model, controller, incoming.value) { incoming.value = Incoming() }
            }
        }
    }

    companion object {
        const val OPEN = "com.dpsmpx.dpai.OPEN_LINK"
        const val CONFIRM = "com.dpsmpx.dpai.CONFIRM_ANSWER"

        /** «Остановить» из уведомления службы — подтверждение на экране (A-18). */
        const val STOP_CONFIRM = "com.dpsmpx.dpai.CONFIRM_STOP"
        const val EXTRA_LINK = "link"
        const val EXTRA_URI = "uri"

        /** Намерение во внутренний вход экрана — для своих `PendingIntent`. */
        fun internal(context: Context): Intent = Intent().setClassName(context, INTERNAL_ENTRY)
    }
}

/** Имена внутренних действий и вход (D-44). */
private const val INTERNAL_ENTRY = "com.dpsmpx.dpai.android.InternalEntry"

/** Тема: цвета системы (Android 12+) или стандартные Material 3, светлая и тёмная. */
@Composable
fun DpaiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

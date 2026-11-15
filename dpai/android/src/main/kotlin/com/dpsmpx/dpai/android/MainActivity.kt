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

/**
 * Что пришло из своего уведомления: ссылка `dpai://…` или ответ, ждущий
 * подтверждения на экране (D-44). Экран забирает и очищает.
 */
/** Что пришло в окно «AI»: ссылка, подтверждение, остановка или вкладка ([tab] — имя `AiTab`, пункт панели приложения, D-112). */
data class Incoming(val link: String? = null, val confirm: String? = null, val stop: Boolean = false, val tab: String? = null, val settings: Boolean = false, val settingsSub: String? = null, val newProject: Boolean = false, val workspace: Boolean = false)

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
            OPEN_TAB -> intent.getStringExtra(EXTRA_TAB)?.let { incoming.value = Incoming(tab = it) }
            // Подэкран — только из известного списка: чужая строка открывает корень настроек.
            OPEN_SETTINGS -> incoming.value = Incoming(settings = true, settingsSub = intent.getStringExtra(EXTRA_SUB)?.takeIf { it in SETTINGS_SUBS })
            NEW_PROJECT -> incoming.value = Incoming(tab = com.dpsmpx.dpai.ui.AiTab.FILES.name, newProject = true)
            OPEN_WORKSPACE -> incoming.value = Incoming(tab = com.dpsmpx.dpai.ui.AiTab.FILES.name, workspace = true)
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
        // Корнем задачи «AI» не бывает, если над ней есть пульт (DPmetaApp:D-73, UX-1): уведомление после перезагрузки
        // или обновления открыло её первой — стек строится заново «пульт → AI», и «назад» ведёт в пульт, а не на
        // рабочий стол; ярлык потом не кладёт пульт поверх «AI». Намерение (ссылка, подтверждение) едет с собой.
        val parent = (application as? DpaiApplication)?.parentIntent()
        if (savedInstanceState == null && isTaskRoot && parent != null) {
            val again = Intent(intent).setFlags(0)
            startActivities(arrayOf(parent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK), again))
            finish()
            return
        }
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

        /** Вкладка «AI» с пункта левой панели приложения (`DPmetaApp:D-112`): IDE — «Файлы». */
        const val OPEN_TAB = "com.dpsmpx.dpai.OPEN_TAB"
        const val EXTRA_TAB = "tab"

        /** Окно «AI» на вкладке [tab]: уже открытое выходит вперёд, второго нет (UX-1). */
        fun tab(context: Context, tab: com.dpsmpx.dpai.ui.AiTab): Intent =
            internal(context).setAction(OPEN_TAB).putExtra(EXTRA_TAB, tab.name).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        /** «Настройки AI» из документа настроек приложения (`DPmetaApp:D-112`). */
        const val OPEN_SETTINGS = "com.dpsmpx.dpai.OPEN_SETTINGS"

        private const val EXTRA_SUB = "sub"

        /** [sub] — подэкран («Linux в приложении», «Журнал»…): `null` — корень настроек. */
        fun settings(context: Context, sub: String? = null): Intent =
            internal(context).setAction(OPEN_SETTINGS).putExtra(EXTRA_SUB, sub).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        /** Мастер нового проекта (DPmetaApp:D-150): из пульта и окна знакомства — поверх IDE. */
        const val NEW_PROJECT = "com.dpsmpx.dpai.NEW_PROJECT"

        fun newProject(context: Context): Intent = internal(context).setAction(NEW_PROJECT).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        /** Выбранный рабочий проект в IDE — из списка «На устройстве» пульта (DPmetaApp:D-150). */
        const val OPEN_WORKSPACE = "com.dpsmpx.dpai.OPEN_WORKSPACE"

        fun workspace(context: Context): Intent = internal(context).setAction(OPEN_WORKSPACE).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        /** Пункт «IDE» левой панели приложения: «Файлы» — дерево, редактор и терминал проекта. */
        fun ide(context: Context): Intent = tab(context, com.dpsmpx.dpai.ui.AiTab.FILES)

        /** Экран «Проверка» в «Настройках AI» (DPmetaApp:D-123): с полосы и строки «AI» пульта. */
        fun check(context: Context): Intent = settings(context, SETTINGS_CHECK)

        /** Намерение во внутренний вход экрана — для своих `PendingIntent`. */
        fun internal(context: Context): Intent = Intent().setClassName(context, INTERNAL_ENTRY)

        /** Подтверждение опасного ответа на экране (D-44, D-104) — для уведомления и окна поверх приложений. */
        fun confirm(context: Context, uri: String): Intent = internal(context).setAction(CONFIRM).putExtra(EXTRA_URI, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /**
         * Флаги своих уведомлений (UX-1): окно «AI» выходит вперёд, если оно есть, — второго не создаётся,
         * и открытый терминал не закрывается (без CLEAR_TOP; он — только у подтверждения ответа).
         */
        const val FRONT: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    }
}

/** Имена внутренних действий и вход (D-44). */
private const val INTERNAL_ENTRY = "com.dpsmpx.dpai.android.InternalEntry"

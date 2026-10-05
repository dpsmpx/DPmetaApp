package com.dpsmpx.dppass.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dpsmpx.dppass.R
import com.dpsmpx.dppass.app.ui.ConnectScreen
import com.dpsmpx.dppass.app.ui.Host
import com.dpsmpx.dppass.app.ui.LogScreen
import com.dpsmpx.dppass.app.ui.MainThread
import com.dpsmpx.dppass.app.ui.Screen
import com.dpsmpx.dppass.app.ui.ServersScreen
import com.dpsmpx.dppass.app.ui.SubscriptionsScreen
import com.dpsmpx.dppass.app.ui.Ui
import com.dpsmpx.dppass.diagnostics.Diagnostic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Интерфейс DPpass (D-34): одна `Activity`, четыре экрана, нижняя
 * панель. Граф зависимостей собирается в фоне (перенос геоданных,
 * загрузка ядра); до этого экраны пустые, но живые.
 *
 * Экраны показывают конфигурацию и её серверы — снимки экрана запрещены
 * (`FLAG_SECURE`, docs/SECURITY_MODEL.md, T9).
 */
class MainActivity : Activity(), Host {
    override val activity: Activity get() = this
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + MainThread)
    override val prefs: AppPrefs by lazy { AppPrefs(this) }

    @Volatile
    override var graph: AppGraph.Graph? = null
        private set

    private lateinit var screens: List<Screen>
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private var tab = ConnectScreen.TAB_CONNECT
    private var askedNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        screens = listOf(ConnectScreen(this), ServersScreen(this), SubscriptionsScreen(this), LogScreen(this))
        content = FrameLayout(this)
        screens.forEach { content.addView(it.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        nav = navigation()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        edgeToEdge(root)
        setContentView(root)
        DeviceLog.log.info("activity_create")
        showTab(prefs.tab.coerceIn(0, screens.lastIndex))
        scope.launch {
            val g = withContext(Dispatchers.IO) {
                AppGraph.graph(this@MainActivity).also { if (it.core.activation.state.activeConfig == null) it.core.restore() }
            }
            graph = g
            changed()
            (screens[ConnectScreen.TAB_LOG] as LogScreen).refreshReport()
        }
        scope.launch { TunnelState.status.collect { changed() } }
    }

    /**
     * Android 15+ рисует приложение под системными панелями всегда; с API 30
     * так и здесь — отступы по вставкам окна. На API 26–29 окно само
     * оставляет место под панели.
     */
    private fun edgeToEdge(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(bars.left, bars.top, bars.right, 0)
            nav.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    private fun navigation(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(getColor(R.color.surface))
        val items = listOf(
            R.string.tab_connect to R.drawable.ic_power,
            R.string.tab_servers to R.drawable.ic_servers,
            R.string.tab_subscriptions to R.drawable.ic_subscriptions,
            R.string.tab_log to R.drawable.ic_log,
        )
        items.forEachIndexed { i, (label, icon) ->
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = Ui.dp(context, 64)
                setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
                setBackgroundResource(R.drawable.bg_row)
                isClickable = true
                isFocusable = true
                contentDescription = getString(label)
                setOnClickListener { showTab(i) }
                val pill = FrameLayout(context).apply {
                    setBackgroundResource(R.drawable.bg_pill)
                    addView(
                        ImageView(context).apply {
                            setImageResource(icon)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                        FrameLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24), Gravity.CENTER),
                    )
                }
                addView(pill, LinearLayout.LayoutParams(Ui.dp(context, 56), Ui.dp(context, 32)))
                addView(
                    Ui.text(context, R.style.Text_DPpass_Caption, getString(label)).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        maxLines = 1
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Ui.dp(context, 4)
                    },
                )
            }
            addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    override fun showTab(tab: Int) {
        this.tab = tab
        prefs.tab = tab
        screens.forEachIndexed { i, s -> s.view.visibility = if (i == tab) View.VISIBLE else View.GONE }
        for (i in 0 until nav.childCount) {
            val item = nav.getChildAt(i) as LinearLayout
            val on = i == tab
            item.isSelected = on
            val pill = item.getChildAt(0) as FrameLayout
            pill.backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.primary_container else android.R.color.transparent))
            (pill.getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(getColor(if (on) R.color.on_primary_container else R.color.text_secondary))
            (item.getChildAt(1) as TextView).setTextColor(getColor(if (on) R.color.text else R.color.text_secondary))
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item.stateDescription = getString(R.string.a11y_selected)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item.stateDescription = null
            }
        }
    }

    override fun changed() {
        val status = TunnelState.status.value
        screens.forEach { it.render(status) }
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun problem(title: CharSequence, reason: Diagnostic) {
        // Причина целиком, с текстом ядра: на устройстве её больше негде увидеть (D-22).
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(reason.renderWithCauses().joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun connect() {
        askNotifications()
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION") // Activity Result API есть только в AndroidX, а его нет (D-34)
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            DPpassVpnService.start(this)
        }
    }

    override fun disconnect() {
        DPpassVpnService.stop(this)
    }

    /** Android 13+: без разрешения уведомление туннеля не видно; туннель работает и без него. */
    private fun askNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedNotifications = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
            toast(getString(R.string.notification_permission_note))
        }
    }

    @Deprecated("Activity Result API есть только в AndroidX, а его нет (D-34)")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) DPpassVpnService.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Интерфейс ушёл с экрана — работа закончена: журнал в буфер обмена (D-26).
        // Поворот экрана — не уход.
        if (!isChangingConfigurations) DeviceLog.onWorkFinished(this, "activity_stop")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_VPN = 1
        const val REQUEST_NOTIFICATIONS = 2
    }
}

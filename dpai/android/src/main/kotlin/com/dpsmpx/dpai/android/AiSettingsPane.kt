package com.dpsmpx.dpai.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * «Настройки AI» (`DP_AI_Orcestrator:D-112`; AI:A-3): редкое, что раньше
 * стояло над списком targets, — инструкция, Linux в приложении, ключ
 * приложения, контур, копии и восстановление одной карточкой, командная
 * строка `dpai`, диск приложения, окно поверх приложений, маршруты
 * Claude Code (D-116) — и журнал.
 * Инструкция и Linux — своими экранами; «Назад» — к настройкам.
 */
@Composable
fun AiSettingsPane(controller: Controller, model: DemoModel, sub: String?, onSub: (String?) -> Unit) {
    when (sub) {
        GUIDE -> {
            BackHandler { onSub(null) }
            SetupPane(controller, model) { onSub(null) }
            return
        }
        LINUX -> {
            BackHandler { onSub(null) }
            LinuxScreen(onBack = { onSub(null) })
            return
        }
        GITHUB -> {
            BackHandler { onSub(null) }
            GitHubPane(model, onBack = { onSub(null) })
            return
        }
        ROUTINES -> {
            BackHandler { onSub(null) }
            RoutinesPane(model, onBack = { onSub(null) })
            return
        }
        LOG -> {
            BackHandler { onSub(null) }
            Column(Modifier.fillMaxSize()) {
                androidx.compose.material3.TextButton(onClick = { onSub(null) }, modifier = Modifier.padding(start = 4.dp)) { Text("← Настройки") }
                Text("Журнал", Modifier.padding(start = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f)) { LogPane(model) }
            }
            return
        }
    }
    var restoring by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as? DpaiApplication
    // В DPmetaApp «Оформление» и «Хранилище и секреты» — в документе настроек приложения (DPmetaApp:D-112), здесь — ссылка.
    val appSettings = app?.sectionIntent(com.dpsmpx.dpai.ui.AppSection.SETTINGS)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "guide") {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Termux, агент, ключ, вход, проект, dpai — по шагам", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onSub(GUIDE) }) { Text("Инструкция") }
                }
            }
        }
        if (appSettings != null) {
            item(key = "app") { Entry("Все настройки приложения", "оформление, хранилище, секреты, управление, копии — одним документом с поиском") { context.startActivity(appSettings) } }
        } else {
            item(key = "look") { LookCard() }
        }
        item(key = "linux") { Entry("Linux в приложении", "Alpine, пакеты, слоты") { onSub(LINUX) } }
        item(key = "github") { Entry("PR на GitHub", "открытые PR проекта, проверки, отзывы — через gh") { onSub(GITHUB) } }
        item(key = "routines") { Entry("Маршруты Claude Code", "облачные сессии по токену маршрута — для поручений из пульта") { onSub(ROUTINES) } }
        item(key = "log") { Entry("Журнал", "строки журнала, выгрузка, отладка") { onSub(LOG) } }
        // В DPmetaApp карточки ниже — в документе настроек приложения (D-151); здесь — отдельная сборка «AI».
        if (appSettings == null) {
            item(key = "key") { AppKeyCard(controller) }
            item(key = "control") { ControlCard(model) }
            // Три копии — одной карточкой (AI:A-3, §4, п. 6): копия настроек, восстановление доступа, облако.
            item(key = "copies") {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        Text("Копии и восстановление", Modifier.padding(start = 12.dp, top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        BackupCard(controller, framed = false, onRestoreAccess = { restoring = true }) {}
                        if (restoring) {
                            HorizontalDivider()
                            RestoreCard(controller, framed = false, onHide = { restoring = false }) {}
                        }
                        HorizontalDivider()
                        CloudCard(model, framed = false)
                    }
                }
            }
            item(key = "cli") { CliCard() }
            item(key = "storage") { StorageSecretsCard() }
            item(key = "disk") { AppDiskCard() }
            item(key = "overlay") { OverlayCard() }
        }
        item(key = "end") { androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
    }
}

/** Строка-вход в свой экран настроек: название, что внутри, «▸». */
@Composable
internal fun Entry(title: String, detail: String, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Text("▸")
        }
    }
}

/** Подэкраны «Настроек AI»: на них ведут ссылки документа настроек приложения (D-151). */
internal const val SETTINGS_GUIDE = "guide"
internal const val SETTINGS_LINUX = "linux"
internal const val SETTINGS_GITHUB = "github"
internal const val SETTINGS_ROUTINES = "routines"
internal val SETTINGS_SUBS: Set<String> = setOf(SETTINGS_GUIDE, SETTINGS_LINUX, SETTINGS_GITHUB, SETTINGS_ROUTINES, "log")
private const val GUIDE = SETTINGS_GUIDE
private const val LINUX = SETTINGS_LINUX
private const val GITHUB = SETTINGS_GITHUB
private const val ROUTINES = SETTINGS_ROUTINES

/** Под-экран журнала — его открывает и «Журнал» у сбоя контроллера (AI:A-17). */
internal const val SETTINGS_LOG = "log"
private const val LOG = SETTINGS_LOG

package com.dpsmpx.dpai.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.accounts.LoginStatus
import com.dpsmpx.dpai.ui.GuideAction
import com.dpsmpx.dpai.ui.GuideSection
import com.dpsmpx.dpai.ui.SetupGuide
import com.dpsmpx.dpai.ui.SetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Инструкция «Настройка» в приложении: разделы [SetupGuide] с отметками
 * «готово» по тому, что видит приложение, команды Termux с кнопкой
 * «Копировать» и сохранение вложенного агента в «Загрузки».
 */
@Composable
fun SetupPane(controller: Controller, model: DemoModel, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf(SetupState()) }
    // Отметки — заново раз в несколько секунд: человек выполняет шаги в Termux и возвращается.
    LaunchedEffect(Unit) {
        while (true) {
            state = withContext(Dispatchers.IO) { setupState(context, controller, model) }
            kotlinx.coroutines.delay(3000)
        }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Настройка", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Назад") }
            }
        }
        items(SetupGuide.sections(state), key = { it.id }) { SectionCard(it) }
    }
}

@Composable
private fun SectionCard(s: GuideSection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val mark = when (s.done) {
                true -> "✓ "
                false -> "○ "
                null -> ""
            }
            Text(mark + s.title + if (s.optional) " (необязательно)" else "", fontWeight = FontWeight.Bold)
            Text(s.intro, style = MaterialTheme.typography.bodySmall)
            for (step in s.steps) {
                Text("• " + step.text, style = MaterialTheme.typography.bodyMedium)
                step.command?.let { cmd ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            SelectionContainer { Text(cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = {
                                Clipboard.copy(context, "команда", cmd)
                                if (!Clipboard.systemConfirms) note = "Скопировано"
                            }) { Text("Копировать") }
                        }
                    }
                }
                step.action?.let { a ->
                    OutlinedButton(onClick = {
                        when (a) {
                            GuideAction.SAVE_AGENT -> scope.launch {
                                note = withContext(Dispatchers.IO) { runCatching { saveAgent(context) }.fold({ "Сохранено: Загрузки/dpai-agent.zip" }, { "Не сохранено: ${com.dpsmpx.dpai.core.text.Words.failure(it)}" }) }
                            }
                            GuideAction.APP_SETTINGS -> context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(a.label) }
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

private fun setupState(context: Context, controller: Controller, model: DemoModel): SetupState {
    val o = model.liveOrchestrator()
    return SetupState(
        notifications = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled(),
        hasTarget = controller.targets().isNotEmpty(),
        loggedIn = o?.accounts?.slots?.values?.any { it.login == LoginStatus.OK } == true,
        hasProject = controller.project() != null,
        paired = ControlServer.pairing(context).paired(),
        appKey = runCatching { controller.publicKeyLine() }.getOrNull(),
    )
}

/**
 * Вложенный агент — в «Загрузки» (MediaStore, без разрешений): прежняя
 * копия этого приложения заменяется, иначе Android назвал бы новую
 * «dpai-agent (1).zip», и команда распаковки взяла бы старую.
 */
private fun saveAgent(context: Context) {
    val resolver = context.contentResolver
    val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    resolver.delete(downloads, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(AGENT_FILE))
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, AGENT_FILE)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(downloads, values)) { "MediaStore не создал файл" }
    resolver.openOutputStream(uri).use { out -> context.assets.open(AGENT_FILE).use { it.copyTo(checkNotNull(out)) } }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
}

private const val AGENT_FILE = "dpai-agent.zip"

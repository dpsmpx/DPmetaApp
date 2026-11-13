package com.dpsmpx.dpai.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.ui.Adb
import com.dpsmpx.dpai.ui.ComposePreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Предпросмотр Compose на телефоне (`DPmetaApp:D-132`; D-167): функции `@Preview`
 * файла [path] открываются в самом отлаживаемом приложении — `PreviewActivity` его
 * debug-сборки через adb к самому телефону; затем снимок экрана — в эту панель.
 * Пакет приложения — из `build.gradle(.kts)` модуля файла, его можно поправить.
 */
@Composable
internal fun UiPreviewDialog(tree: FileTree, treeKey: String, path: String, text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val found = remember(path, text) { ComposePreviews.find(path, text) }
    var pkg by rememberSaveable(treeKey, path) { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(treeKey, path) {
        if (pkg.isEmpty()) withContext(Dispatchers.IO) { debugPackage(tree, path) }?.let { pkg = it }
    }

    fun open(p: ComposePreviews.Preview) {
        AdbBridge.refresh(app)
        val port = AdbBridge.state.value.connectPort ?: run { note = "Сначала подключите adb к этому телефону: «Файлы» → «Logcat» → «Подключить»"; return }
        val argv = when (val a = Adb.preview(port, pkg.trim(), p.composable)) {
            is Outcome.Failure -> { note = a.diagnostics.first().message; return }
            is Outcome.Success -> a.value
        }
        busy = true
        note = "Открываю ${p.name}…"
        scope.launch {
            try {
                val host = LinuxSetup.guestHost(app) ?: run { note = "Linux в приложении не установлен"; return@launch }
                val started = withContext(Dispatchers.IO) {
                    when (val r = host.run(ProcessSpec(argv), 60.seconds, maxStdoutChars = 8_192)) {
                        is Outcome.Failure -> r
                        is Outcome.Success -> Adb.previewResult(r.value.stdout + r.value.stderr, r.value.exitCode)
                    }
                }
                if (started is Outcome.Failure) { note = started.diagnostics.first().message; return@launch }
                AppLogs.info(app, "предпросмотр", "${pkg.trim()}: ${p.composable}")
                // Дать предпросмотру отрисоваться, затем снять экран — приложение с предпросмотром сейчас поверх.
                delay(SETTLE_MS)
                shot = withContext(Dispatchers.IO) { screencap(app, port) }
                note = if (shot != null) "${p.name} — открыт на телефоне; снимок ниже. Вернуться — «Назад» или список приложений." else "${p.name} открыт на телефоне; снимок экрана не получился."
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Предпросмотр · ${FileNames.name(path)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Функции @Preview открываются в самом приложении (его debug-сборка установлена на этом телефоне), через adb.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pkg, { pkg = it.trim().take(255) }, enabled = !busy, singleLine = true, label = { Text("Пакет debug-сборки") }, modifier = Modifier.fillMaxWidth())
                if (found.previews.isEmpty()) Text("В файле нет функций @Preview верхнего уровня без параметров.", style = MaterialTheme.typography.bodySmall)
                for (p in found.previews) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.name} · стр. ${p.line}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !busy && pkg.isNotBlank(), onClick = { open(p) }) { Text("Открыть") }
                    }
                }
                for ((name, why) in found.skipped) Text("$name — $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                note?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                shot?.let { Image(it, "Снимок предпросмотра", Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

/** Пакет debug-сборки: ближайший вверх от файла `build.gradle(.kts)` с `applicationId`. */
private fun debugPackage(tree: FileTree, path: String): String? {
    var dir = FileNames.parent(path)
    repeat(12) {
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val file = FileNames.join(dir, name)
            val text = (tree.read(file, 512L * 1024) as? Outcome.Success)?.value?.toString(Charsets.UTF_8) ?: continue
            ComposePreviews.applicationId(text)?.let { return it }
        }
        if (dir.isEmpty()) return null
        dir = FileNames.parent(dir)
    }
    return null
}

/** Снимок экрана телефона через adb гостя: PNG из потока процесса, не больше [Adb.MAX_SCREENCAP]. */
private fun screencap(app: android.content.Context, port: Int): ImageBitmap? {
    val streams = LinuxSetup.guestStreams(app) ?: return null
    val p = (streams.open(ProcessSpec(Adb.screencap(port))) as? Outcome.Success)?.value ?: return null
    return try {
        // Не readNBytes: его нет до Android 13 (minSdk 30).
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        p.stdout.use { input ->
            while (out.size() <= Adb.MAX_SCREENCAP) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty() || bytes.size > Adb.MAX_SCREENCAP) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: java.io.IOException) {
        null
    } finally {
        p.stop(2.seconds)
    }
}

private const val SETTLE_MS = 2_000L

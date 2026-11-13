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
import com.dpsmpx.dpai.ui.PcPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Предпросмотр интерфейсов (`DPmetaApp:D-132`). На телефоне: функции `@Preview`
 * файла [path] открываются в самом отлаживаемом приложении — `PreviewActivity` его
 * debug-сборки через adb к самому телефону, затем снимок экрана (D-167); разметка XML —
 * из ресурсов установленного APK (D-168). Пакет приложения — из `build.gradle(.kts)`
 * модуля файла, его можно поправить. Файл на ПК — ещё «Нарисовать на ПК» (D-169).
 */
@Composable
internal fun UiPreviewDialog(tree: FileTree, treeKey: String, path: String, text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val found = remember(path, text) { ComposePreviews.find(path, text) }
    // Разметка XML — из ресурсов установленного APK, без его кода (D-168).
    val layout = remember(path) { XmlLayoutPreview.layoutName(path) }
    var pkg by rememberSaveable(treeKey, path) { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    // Предпросмотр открыт на телефоне: снимок экрана — по кнопке (DPmetaApp:D-132), а не сам.
    var opened by remember { mutableStateOf<String?>(null) }
    // Дерево на ПК: рисует Robolectric в JVM проекта на самом ПК (D-169).
    val pcPlace = LocalPcPreview.current
    var pcShots by remember { mutableStateOf<List<PcPreviewRun.Shot>>(emptyList()) }
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
                opened = p.name
                note = "${p.name} — открыт на телефоне. Вернуться — «Назад» или список приложений; «Снимок экрана» — картинка сюда."
            } finally {
                busy = false
            }
        }
    }

    fun drawOnPc(place: PcPreviewPlace) {
        busy = true
        pcShots = emptyList()
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    PcPreviewRun.run(place, tree, path, found.previews.take(PcPreview.MAX_ITEMS - 1).map { it.composable }, listOfNotNull(layout)) { step -> scope.launch { note = step } }
                }
                when (r) {
                    is Outcome.Failure -> note = r.diagnostics.first().message
                    is Outcome.Success -> {
                        pcShots = r.value.shots
                        val bad = r.value.shots.count { it.image == null }
                        note = if (bad == 0) "Нарисовано на ПК: ${r.value.shots.size}" else "Нарисовано на ПК: ${r.value.shots.size - bad} из ${r.value.shots.size}"
                        AppLogs.info(app, "предпросмотр", "ПК ${place.target.label}: ${r.value.shots.size}, без картинки $bad")
                    }
                }
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
                Text(
                    if (layout != null) "Разметка берётся из установленного на телефоне APK (после правки — соберите и установите заново); код приложения не загружается: виды не из Android — рамки с именем класса."
                    else "Функции @Preview открываются в самом приложении (его debug-сборка установлена на этом телефоне), через adb.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (pcPlace != null) {
                    Text("Файл открыт на ПК «${pcPlace.target.label}»: Robolectric нарисует его в тестовой JVM проекта на ПК (нужны JDK и Android SDK там; файлы проекта не меняются).", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy && (layout != null || found.previews.isNotEmpty()), onClick = { drawOnPc(pcPlace) }) { Text("Нарисовать на ПК") }
                    for (s in pcShots) {
                        Text(s.rendered.name.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
                        val image = s.image
                        if (image != null) Image(image, "Предпросмотр ${s.rendered.name}", Modifier.fillMaxWidth().heightIn(max = 480.dp))
                        else Text(s.rendered.error ?: "картинка не прочиталась", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Или на этом телефоне — debug-сборка должна быть установлена здесь:", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(pkg, { pkg = it.trim().take(255) }, enabled = !busy, singleLine = true, label = { Text("Пакет debug-сборки") }, modifier = Modifier.fillMaxWidth())
                if (layout != null) {
                    TextButton(enabled = !busy && pkg.isNotBlank(), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                // Виды создаются в главном потоке; разметка небольшая.
                                when (val r = XmlLayoutPreview.render(context, pkg.trim(), layout)) {
                                    is Outcome.Failure -> note = r.diagnostics.first().message
                                    is Outcome.Success -> { shot = r.value.asImageBitmap(); note = "$layout — ширина 360 dp" }
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Показать разметку") }
                } else if (found.previews.isEmpty()) Text("В файле нет функций @Preview верхнего уровня без параметров.", style = MaterialTheme.typography.bodySmall)
                for (p in found.previews) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.name} · стр. ${p.line}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !busy && pkg.isNotBlank(), onClick = { open(p) }) { Text("Открыть") }
                    }
                }
                for ((name, why) in found.skipped) Text("$name — $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                note?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                opened?.let { name ->
                    TextButton(enabled = !busy, onClick = {
                        val port = AdbBridge.state.value.connectPort ?: run { note = "adb не подключён"; return@TextButton }
                        busy = true
                        scope.launch {
                            try {
                                shot = withContext(Dispatchers.IO) { screencap(app, port) }
                                note = if (shot != null) "$name — снимок ниже" else "Снимок экрана не получился"
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Снимок экрана") }
                }
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


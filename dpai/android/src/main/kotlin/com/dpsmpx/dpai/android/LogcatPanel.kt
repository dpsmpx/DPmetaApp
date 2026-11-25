package com.dpsmpx.dpai.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.Adb
import com.dpsmpx.dpai.ui.Logcat
import com.dpsmpx.dpai.ui.Tone

/**
 * Logcat в IDE (`DPmetaApp:D-125`; D-160): подключение adb к самому телефону
 * (сопряжение кодом, порты — из NSD или вручную), фильтры — пакет, уровень, текст;
 * пауза держит показанное, поток идёт; строка трассировки `at …(Файл.kt:42)` —
 * переход в файл проекта ([onFrame]; `null` — переход недоступен).
 */
@Composable
internal fun LogcatPanel(initialPackage: String? = null, onClose: () -> Unit, onFrame: ((Logcat.Frame) -> Unit)? = null) {
    val context = LocalContext.current
    val adb by AdbBridge.state.collectAsState()
    val stream by LogcatStream.view.collectAsState()
    var pairPort by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf(initialPackage.orEmpty()) }
    var level by rememberSaveable { mutableStateOf(Logcat.Level.INFO) }
    var query by rememberSaveable { mutableStateOf("") }
    var paused by remember { mutableStateOf<List<Logcat.Line>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var setup by rememberSaveable { mutableStateOf(true) }
    // Поиск портов — пока экран открыт; найденное подставляется, введённое человеком не затирается.
    DisposableEffect(Unit) {
        AdbBridge.refresh(context)
        AdbBridge.check(context)
        AdbBridge.discover(context)
        onDispose { AdbBridge.stopDiscovery(context) }
    }
    LaunchedEffect(adb.pairingPort) { adb.pairingPort?.let { if (pairPort.isBlank()) pairPort = it.toString() } }
    LaunchedEffect(adb.connectPort) { adb.connectPort?.let { if (connectPort.isBlank()) connectPort = it.toString() } }
    LaunchedEffect(adb.connected) { if (adb.connected == true) setup = false }
    fun start() {
        val port = (Adb.port(connectPort) as? Outcome.Success)?.value ?: run { note = "Нужен порт подключения"; return }
        val p = pkg.trim().ifEmpty { null }
        if (p != null && Adb.packageName(p) is Outcome.Failure) { note = "Имя пакета — вида com.example.app"; return }
        paused = null
        note = null
        LogcatStream.start(context, port, level, p)
    }
    LaunchedEffect(initialPackage, adb.connected) { if (initialPackage != null && adb.connected == true && !stream.running) start() }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("←") }
            Text("Logcat", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { setup = !setup }) { Text(if (setup) "Скрыть подключение" else "Подключение") }
        }
        if (setup) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Телефон отлаживает сам себя по adb. Настройки → Для разработчиков → Беспроводная отладка: включите; для сопряжения — «Подключить устройство с помощью кода». " +
                        "Порты подставляются сами, если телефон их объявил. Права adb — права shell: ими пользуются и терминал, и ИИ; выключайте беспроводную отладку, когда она не нужна.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (adb.missing) OutlinedButton(enabled = !adb.busy, onClick = { AdbBridge.install(context) }) { Text("Поставить adb") }
                if (!adb.paired) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Порт сопряжения") }, singleLine = true)
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = { Text("Код") }, singleLine = true)
                    TextButton(enabled = !adb.busy, onClick = {
                        val port = (Adb.port(pairPort) as? Outcome.Success)?.value ?: run { note = "Нужен порт сопряжения"; return@TextButton }
                        when (val c = Adb.code(code)) {
                            is Outcome.Failure -> note = c.diagnostics.first().message
                            is Outcome.Success -> { AdbBridge.pair(context, port, c.value); code = "" }
                        }
                    }) { Text("Сопрячь") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(connectPort, { connectPort = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Порт подключения") }, singleLine = true)
                    TextButton(enabled = !adb.busy, onClick = {
                        when (val p = Adb.port(connectPort)) {
                            is Outcome.Failure -> note = p.diagnostics.first().message
                            is Outcome.Success -> AdbBridge.connect(context, p.value)
                        }
                    }) { Text("Подключить") }
                    if (adb.paired) TextButton(enabled = !adb.busy, onClick = { AdbBridge.forget(context) }) { Text("Забыть", color = MaterialTheme.colorScheme.error) }
                }
                adb.note?.let { DiagnosticText(it, style = MaterialTheme.typography.bodyMedium) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(pkg, { pkg = it.take(255) }, Modifier.weight(1f), label = { Text("Пакет (пусто — весь журнал)") }, singleLine = true)
            OutlinedTextField(query, { query = it.take(200) }, Modifier.weight(1f), label = { Text("Найти") }, singleLine = true)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (l in listOf(Logcat.Level.VERBOSE, Logcat.Level.DEBUG, Logcat.Level.INFO, Logcat.Level.WARN, Logcat.Level.ERROR)) {
                FilterChip(level == l, { level = l; if (stream.running) start() }, label = { Text(l.title) })
            }
            OutlinedButton(onClick = { if (stream.running) LogcatStream.stop() else start() }) { Text(if (stream.running) "Стоп" else "Старт") }
            OutlinedButton(enabled = stream.running || stream.lines.isNotEmpty(), onClick = { paused = if (paused == null) stream.lines else null }) { Text(if (paused == null) "Пауза" else "Дальше") }
            TextButton(onClick = { LogcatStream.clear(); paused = null }) { Text("Очистить") }
        }
        (note ?: stream.note)?.let { DiagnosticText(it, style = MaterialTheme.typography.bodyMedium) }
        val filter = Logcat.Filter(level, stream.pid, query)
        val shown = remember(paused, stream.lines, filter) { (paused ?: stream.lines).filter(filter::matches) }
        val text = remember(shown) { shown.map { l -> if (l.header) "${l.time.substringAfter(' ')} ${l.level.letter} ${l.tag}: ${l.message}" else "    ${l.message}" } }
        CopyDiagnostic(text.joinToString("\n"), copyLabel = R.string.diagnostic_copy_output)
        val list = rememberLazyListState()
        if (paused == null) OutputFollow(list, shown.size, shown.size - 1)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
            items(shown.size) { i ->
                val l = shown[i]
                val frame = if (onFrame != null) Logcat.frame(l.message) else null
                val color = when (l.level) {
                    Logcat.Level.ERROR, Logcat.Level.FATAL -> toneColor(Tone.ERROR)
                    Logcat.Level.WARN -> toneColor(Tone.WARNING)
                    Logcat.Level.VERBOSE, Logcat.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Clipboard.Sensitive { androidx.compose.foundation.text.selection.SelectionContainer { Text(
                    text[i],
                    Modifier.fillMaxWidth().then(if (frame != null) Modifier.clickable { onFrame?.invoke(frame) } else Modifier).padding(vertical = 1.dp),
                    color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    textDecoration = if (frame != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                ) } }
            }
        }
    }
}

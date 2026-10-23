package com.dpsmpx.dpai.probe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Экран пробника B2: кнопки проверок и отчёт текстом. Отчёт копируется
 * или сохраняется в «Загрузки/dpai-probe» и присылается целиком — по нему
 * решается шаг 1 D-61 (`docs/DEVICE_CHECK.md`, §14).
 */
class ProbeActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun append(line: String) = runOnUiThread { text.value = text.value + line + "\n" }

    private fun run(title: String, block: (Probe) -> Unit) {
        if (busy.value) return
        busy.value = true
        append("")
        append("### $title — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}")
        worker.execute {
            try {
                block(Probe(this) { append(it) })
            } catch (e: Exception) {
                append("СБОЙ ПРОБНИКА: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runOnUiThread { busy.value = false }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @androidx.compose.runtime.Composable
    private fun Screen() {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("Пробник B2 — Linux в приложении", style = MaterialTheme.typography.titleLarge)
            Text(
                "Шаг 1 D-61: proot из каталога библиотек, Alpine, жёсткие ссылки, скорость, фон. " +
                    "Порядок — docs/DEVICE_CHECK.md, §14. Отчёт пришлите целиком: ключей и паролей в нём нет.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Пуск пробы") { it.basic() } }, enabled = !busy.value) { Text("Пуск пробы") }
                Button(onClick = { run("Сеть: apk и git") { it.network() } }, enabled = !busy.value) { Text("Сеть: apk и git") }
                Button(onClick = { run("JVM под proot") { it.java() } }, enabled = !busy.value) { Text("JVM (≈180 МБ)") }
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    // Цикл идёт под proot — корневая система нужна до запуска службы.
                    run("Фон: запуск") { p ->
                        if (p.unpack()) {
                            BeatService.start(this@ProbeActivity)
                            append("фон: запущен — заблокируйте экран на 30 мин, потом «Фон: проверить»")
                        }
                    }
                }, enabled = !busy.value) { Text("Фон: запустить") }
                OutlinedButton(onClick = { append(BeatService.check(this@ProbeActivity)) }) { Text("Фон: проверить") }
                OutlinedButton(onClick = {
                    BeatService.stop(this@ProbeActivity)
                    append("фон: остановлен")
                }) { Text("Фон: остановить") }
                OutlinedButton(onClick = { copy() }) { Text("Копировать") }
                OutlinedButton(onClick = { save() }) { Text("В «Загрузки»") }
                OutlinedButton(onClick = { append(assets.open("NOTICE.txt").bufferedReader().readText()) }) { Text("Лицензии") }
            }
            if (busy.value) Text("идёт проверка…", style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(text.value, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("отчёт пробника", text.value))
        append("отчёт скопирован")
    }

    /** Файл в «Загрузки/dpai-probe» через MediaStore — без разрешения на память. */
    private fun save() {
        val name = "dpai-probe-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/dpai-probe")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            append("файл в «Загрузках» не создан")
            return
        }
        contentResolver.openOutputStream(uri)?.use { it.write(text.value.toByteArray()) }
        append("сохранено: Загрузки/dpai-probe/$name")
    }

    companion object {
        /** Отчёт и занятость — на процесс: поворот экрана их не теряет. */
        private val text = mutableStateOf("")
        private val busy = mutableStateOf(false)
    }
}

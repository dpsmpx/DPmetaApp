package com.dpsmpx.dpai.android

import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ключи подписи сборок (`DPmetaApp:D-146`; D-178): глобальный — для всех проектов,
 * свой — у проекта [project]. Сверху — чем подписываются сборки этого проекта; ключа
 * нет нигде — предупреждение. У каждого уровня — «Импортировать…», «Создать…»,
 * «Удалить…». [version] — перечитать ключи; [onChanged] — ключи изменились.
 */
@Composable
internal fun SigningKeysPanel(project: String, projectName: String, enabled: Boolean, version: Int, onChanged: () -> Unit, onNote: (String?) -> Unit) {
    val app = LocalContext.current.applicationContext
    var global by remember(project, version) { mutableStateOf<SigningKeys.Info?>(null) }
    var own by remember(project, version) { mutableStateOf<SigningKeys.Info?>(null) }
    var globalFailed by remember(project, version) { mutableStateOf(false) }
    var ownFailed by remember(project, version) { mutableStateOf(false) }
    var globalStored by remember(project, version) { mutableStateOf(false) }
    var ownStored by remember(project, version) { mutableStateOf(false) }
    var problem by remember(project, version) { mutableStateOf<String?>(null) }
    var loaded by remember(project, version) { mutableStateOf(false) }
    LaunchedEffect(project, version) {
        val (g, p, stored) = withContext(Dispatchers.IO) { Triple(SigningKeys.info(app, null), SigningKeys.info(app, project), SigningKeys.allChecked(app)) }
        global = g.valueOrNull()
        own = p.valueOrNull()
        globalFailed = g is Outcome.Failure
        ownFailed = p is Outcome.Failure
        globalStored = stored.valueOrNull()?.contains(null) == true
        ownStored = stored.valueOrNull()?.contains(project) == true
        problem = listOf(g, p, stored).filterIsInstance<Outcome.Failure>().firstOrNull()?.errors?.firstOrNull()?.message
        loaded = true
    }
    val choice = SigningKeys.choose(own != null, global != null)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (loaded && problem == null) {
            Text("Сборки этого проекта подписываются ${choice.title}" + if (choice == SigningKeys.Choice.NONE) "." else " — сами после успешной сборки и после «На телефон».", style = MaterialTheme.typography.bodySmall)
            if (choice == SigningKeys.Choice.NONE) Text(SignAfterBuild.NO_KEY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        problem?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        androidx.compose.runtime.key(project, version) {
            KeyLevelRow("Ключ проекта «$projectName»", project, own, ownFailed, ownStored, enabled && loaded, onChanged, onNote)
            KeyLevelRow("Глобальный ключ (все проекты)", null, global, globalFailed, globalStored, enabled && loaded, onChanged, onNote)
        }
        var release by remember(project, version) { mutableStateOf(false) }
        TextButton(onClick = { release = true }) { Text("Ключ выпуска на ПК…") }
        if (release) ReleaseKeyDialog(onClose = { release = false }, onNote = onNote)
    }
}

/**
 * Ключ выпуска вне приложения (`DPmetaApp:D-158`): поля — в команду keytool с паролем в
 * окружении, в буфер обмена (с пометкой «секрет»).
 * Приложение эти поля не хранит; созданный файл .p12 потом можно «Импортировать…».
 */
@Composable
private fun ReleaseKeyDialog(onClose: () -> Unit, onNote: (String?) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("RU") }
    var alias by remember { mutableStateOf("release") }
    var file by remember { mutableStateOf("dpmetaapp-release.p12") }
    var years by remember { mutableStateOf(com.dpsmpx.dpai.ui.ReleaseKeyCommand.DEFAULT_YEARS.toString()) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    fun clear() { password = ""; repeat = "" }
    fun copy(shell: com.dpsmpx.dpai.ui.ReleaseKeyCommand.Shell) {
        if (password != repeat) { problem = "Пароли не совпадают"; return }
        val form = com.dpsmpx.dpai.ui.ReleaseKeyCommand.Form(name, org, country, alias, file, years.toIntOrNull() ?: 0, Secret(password))
        when (val r = com.dpsmpx.dpai.ui.ReleaseKeyCommand.build(form, shell)) {
            is Outcome.Failure -> problem = r.errors.first().message
            is Outcome.Success -> {
                val clip = android.content.ClipData.newPlainText("ключ выпуска", r.value)
                if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
                clear()
                onClose()
                onNote(
                    "Команда скопирована — вставьте её в " + (if (shell == com.dpsmpx.dpai.ui.ReleaseKeyCommand.Shell.POWERSHELL) "PowerShell на ПК" else "терминал Linux на ПК") +
                        ". Файл ~/${file.trim()} — копия ключа выпуска: храните его и пароль отдельно. В приложение — «Импортировать…» у нужного уровня; " +
                        "для сборки на ПК — свойства dpai.release.* (DPmetaApp:docs/RELEASE.md). После вставки очистите буфер обмена.",
                )
            }
        }
    }
    AlertDialog(
        onDismissRequest = { clear(); onClose() },
        title = { Text("Ключ выпуска") },
        text = {
            SecureWindow()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Команда keytool создаст ключ RSA 4096 в файле .p12 в домашней папке. На ПК нужен JDK с keytool. " +
                        "В bash и zsh пароль идёт в команду через окружение, и строка убирает себя из истории; в zsh с share_history или inc_append_history без hist_ignore_space строка попадёт в файл до исполнения — вставьте её в bash. В PowerShell пароль не нужен: его спросит keytool. Приложение поля не хранит.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Имя в сертификате") }, singleLine = true)
                OutlinedTextField(org, { org = it.take(64) }, label = { Text("Организация (необязательно)") }, singleLine = true)
                OutlinedTextField(country, { country = it.take(2).uppercase() }, label = { Text("Страна, 2 буквы") }, singleLine = true)
                OutlinedTextField(alias, { alias = it.take(32) }, label = { Text("Имя ключа") }, singleLine = true)
                OutlinedTextField(file, { file = it.take(64) }, label = { Text("Файл") }, singleLine = true)
                OutlinedTextField(years, { years = it.filter(Char::isDigit).take(2) }, label = { Text("Срок, лет") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(password, { password = it.take(256) }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                OutlinedTextField(repeat, { repeat = it.take(256) }, label = { Text("Пароль ещё раз") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                problem?.let { DiagnosticText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { copy(com.dpsmpx.dpai.ui.ReleaseKeyCommand.Shell.POSIX) }) { Text("Скопировать для Linux/bash") }
                TextButton(onClick = { copy(com.dpsmpx.dpai.ui.ReleaseKeyCommand.Shell.POWERSHELL) }) { Text("Скопировать для ПК (PowerShell)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { clear(); onClose() }) { Text("Закрыть") } },
    )
}

/** Один уровень ключа: сведения или «не задан»; импорт, создание с копией-файлом, удаление. */
@Composable
private fun KeyLevelRow(title: String, level: String?, key: SigningKeys.Info?, unreadable: Boolean, stored: Boolean, enabled: Boolean, onChanged: () -> Unit, onNote: (String?) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    // Имя и пароль нового ключа — до ответа системного окна «Сохранить»; затираются в любом исходе.
    var pending by remember { mutableStateOf<Pair<String, CharArray>?>(null) }
    androidx.compose.runtime.DisposableEffect(level) {
        onDispose { picked?.fill(0); pending?.second?.fill('\u0000') }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_KEY_FILE + 1) } }.getOrNull()
            }
            when {
                bytes == null -> onNote("файл ключа не прочитан")
                bytes.size > MAX_KEY_FILE -> onNote("файл больше 1 МБ — это не хранилище ключей")
                else -> picked = bytes
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
        val p = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) { p.second.fill('\u0000'); onNote("Ключ не создан: файл не выбран"); return@rememberLauncherForActivityResult }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    // Порядок D-178: файл у автора → прочитан обратно тем же паролем → только потом Keystore.
                    val bytes = when (val c = KeyFile.create(p.first, p.second, java.time.Instant.ofEpochMilli(Clock.SYSTEM.now().epochMillis), RandomSource.secure())) {
                        is Outcome.Failure -> return@withContext c
                        is Outcome.Success -> c.value
                    }
                    try {
                        val wrote = runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
                        if (!wrote) return@withContext Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл ключа не записан — ключ не создан")
                    } finally {
                        bytes.fill(0)
                    }
                    val back = runCatching { app.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_KEY_FILE + 1) } }.getOrNull()
                        ?: return@withContext Outcome.failure(DiagCode.STORAGE_IO_FAILED, "сохранённый файл ключа не читается — ключ не создан")
                    try {
                        SigningKeys.adopt(app, back, p.second, level)
                    } finally {
                        back.fill(0)
                    }
                } finally {
                    p.second.fill('\u0000')
                }
            }
            when (r) {
                is Outcome.Success -> { onChanged(); onNote("Ключ создан и сохранён в Keystore. Копия — в выбранном файле .p12: храните её и пароль отдельно, без них обновить подписанное приложение на другом телефоне нельзя.") }
                is Outcome.Failure -> onNote(r.diagnostics.first().message)
            }
        }
    }
    Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge)
    if (key == null) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Text(if (unreadable) "не прочитан" else if (stored) "отключён; очистка не завершена" else "не задан", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = enabled, onClick = { pick.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }) { Text("Импортировать…") }
            TextButton(enabled = enabled, onClick = { creating = true }) { Text("Создать…") }
        }
    } else {
        DiagnosticText("${key.subject} (${key.algorithm})\nSHA-256: ${key.certSha256}", style = MaterialTheme.typography.bodySmall)
    }
    if (key != null || unreadable || stored) TextButton(enabled = enabled, onClick = { deleting = true }) { Text("Удалить ключ…") }
    picked?.let { bytes ->
        KeyPasswordDialog(onCancel = { bytes.fill(0); picked = null }) { password, alias ->
            // Вход — до импорта: копия ключа для резервной копии шифруется ключом Keystore со входом (D-184).
            DeviceConfirm.ask(context, "Ключ подписи — в приложение", onOk = { scope.launch {
                val r = withContext(Dispatchers.IO) { SigningKeys.import(app, bytes, password, alias, level) }
                when (r) {
                    is Outcome.Success -> { bytes.fill(0); picked = null; onChanged(); onNote("Ключ импортирован. " + if (r.warnings.isEmpty()) "Зашифрованная копия для резервного копирования сохранена." else r.warnings.joinToString("; ") { it.message }) }
                    is Outcome.Failure -> {
                        val d = r.diagnostics.first()
                        onNote(d.message)
                        // Несколько ключей в файле — окно остаётся, чтобы ввести имя ключа.
                        if (d.code != DiagCode.FIELD_MISSING && d.code != DiagCode.VALUE_INVALID) { bytes.fill(0); picked = null }
                    }
                }
            } }, onDenied = { why -> onNote("Ключ не импортирован: $why") })
        }
    }
    if (creating) CreateKeyDialog(onCancel = { creating = false }) { name, password ->
        creating = false
        pending = name to password
        // Вход — до создания: копия ключа для резервной копии шифруется ключом Keystore со входом (D-184).
        DeviceConfirm.ask(context, "Ключ подписи — в приложение",
            onOk = { save.launch(if (level == null) "dpmetaapp-signing.p12" else "dpmetaapp-signing-${level.lowercase()}.p12") },
            onDenied = { why -> pending?.second?.fill('\u0000'); pending = null; onNote("Ключ не создан: $why") })
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Удалить ключ подписи?") },
        text = { Text("Удалятся все сохранённые поколения ключа этого уровня и их копии на телефоне. Восстановление — из вашего файла или внешней резервной копии. Подписанные APK останутся.", color = MaterialTheme.colorScheme.error) },
        confirmButton = { TextButton(onClick = {
            deleting = false
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { SigningKeys.delete(app, level) }) {
                    is Outcome.Success -> { onChanged(); onNote("Ключ подписи удалён") }
                    is Outcome.Failure -> onNote(r.diagnostics.first().message)
                }
            }
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } },
    )
}

/** Окна с паролями — без снимков экрана. */
@Composable
private fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
}

/** Пароль файла ключа и, если нужно, имя ключа. Пароль не сохраняется. */
@Composable
private fun KeyPasswordDialog(onCancel: () -> Unit, onImport: (Secret, String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Пароль хранилища ключей") },
        text = {
            SecureWindow()
            Column {
                Text("Ключ попадёт в Keystore телефона; подписывать можно будет только после PIN или отпечатка.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(password, { password = it.take(256) }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                OutlinedTextField(alias, { alias = it.take(128) }, label = { Text("Имя ключа (если в файле их несколько)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            val secret = Secret(password)
            password = ""
            onImport(secret, alias.trim().ifEmpty { null })
        }) { Text("Импортировать") } },
        dismissButton = { TextButton(onClick = { password = ""; onCancel() }) { Text("Отмена") } },
    )
}

/**
 * Новый ключ (D-178): имя в сертификате и пароль файла дважды. Дальше — системное окно
 * «Сохранить» для копии `.p12`; без неё ключ не создаётся.
 */
@Composable
private fun CreateKeyDialog(onCancel: () -> Unit, onCreate: (String, CharArray) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val problem = when {
        name.isBlank() -> "Введите имя в сертификате (например, своё имя)"
        password.length < KeyFile.MIN_PASSWORD -> "Пароль — не короче ${KeyFile.MIN_PASSWORD} знаков"
        !KeyFile.passwordUsable(password.toCharArray()) -> "Пароль — латиница, цифры, пробел и знаки: кириллицу файл .p12 не принимает"
        password != repeat -> "Пароли не совпадают"
        else -> null
    }
    AlertDialog(
        onDismissRequest = { password = ""; repeat = ""; onCancel() },
        title = { Text("Создать ключ подписи") },
        text = {
            SecureWindow()
            Column {
                Text(
                    "Создаётся ключ RSA 4096 с сертификатом на ${KeyFile.YEARS} лет. Сначала сохраните копию — файл .p12 под этим паролем: " +
                        "без копии и пароля подписанное приложение нельзя будет обновить с другого телефона. Затем ключ ляжет в Keystore телефона.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Имя в сертификате") }, singleLine = true)
                OutlinedTextField(password, { password = it.take(256) }, label = { Text("Пароль файла") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                OutlinedTextField(repeat, { repeat = it.take(256) }, label = { Text("Пароль ещё раз") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                problem?.let { DiagnosticText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = problem == null, onClick = {
            val chars = password.toCharArray()
            password = ""
            repeat = ""
            onCreate(name.trim(), chars)
        }) { Text("Сохранить копию…") } },
        dismissButton = { TextButton(onClick = { password = ""; repeat = ""; onCancel() }) { Text("Отмена") } },
    )
}

private const val MAX_KEY_FILE = 1 shl 20

/** Не больше [limit] байт потока (`readNBytes` — только с API 33). */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

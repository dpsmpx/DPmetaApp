package com.dpsmpx.dpai.android

import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.SubTree
import com.dpsmpx.dpai.files.TreeArtifacts
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.WorkspaceLocation
import com.dpsmpx.dpai.ui.WorkspacePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Артефакты сборки и APK (D-157): файлы дерева по шаблонам профиля «Сборка»,
 * у APK — сведения, подпись своим ключом из Keystore, установка и копия в
 * «Загрузки»; запуск и logcat (D-160), отладка по JDWP (D-161). Деревья Linux
 * приложения читаются напрямую; дерево ПК-target — по SFTP только для чтения
 * (`DPmetaApp:D-127`; D-162): «На телефон» скачивает файл, дальше — те же действия.
 * [patterns] — шаблоны профиля, [refresh] — признак нового итога сборки.
 */
@Composable
internal fun ArtifactsSection(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation?, patterns: List<String>, refresh: Any?) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val prefix = location?.let { WorkspacePaths.homeRelative(view.target, it.path) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        HorizontalDivider()
        Text("Артефакты", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        val target = view.target
        // Дерево на ПК: список и файлы — по SFTP (D-162), скачанное — в хранилище приложения.
        val pc = location != null && target != null && !target.inApp
        if (location == null || target == null || prefix == null && !pc) {
            Text("Артефакты видны у деревьев Linux в приложении и на ПК-target.", style = MaterialTheme.typography.bodySmall)
            return
        }
        val tree: FileTree? = remember(view.project, location) {
            prefix?.let { p ->
                val inner = SubTree(GuestPaths.homeTree(app), p)
                object : FileTree by inner {
                    override fun list(dir: String) = model.workspaceAccess(view.project, location, false) { inner.list(dir) }
                }
            }
        }
        val hostRoot = remember(location) { prefix?.let { File(GuestPaths.home(app), it).canonicalFile } }
        var downloaded by remember(location) { mutableStateOf<Map<String, File>>(emptyMap()) }
        /** Файл телефона для пути дерева: Linux — сам файл (ссылка наружу корня — `null`), ПК — скачанный. */
        fun host(rel: String): File? {
            val root = hostRoot ?: return downloaded[rel]?.takeIf { it.isFile }
            return File(root, rel).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
        }

        var found by remember { mutableStateOf<TreeArtifacts.Found?>(null) }
        var note by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf<String?>(null) }
        var version by remember { mutableIntStateOf(0) }
        var details by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf<Pair<ApkReview.Review, ApkAction>?>(null) }
        DisposableEffect(Unit) { onDispose { pending?.let { ApkReview.discard(it.first) } } }
        var logcatFor by remember { mutableStateOf<String?>(null) }
        var key by remember { mutableStateOf<SigningKeys.Info?>(null) }
        LaunchedEffect(Unit) { key = withContext(Dispatchers.IO) { SigningKeys.info() } }
        LaunchedEffect(location, patterns, refresh, version) {
            val r = withContext(Dispatchers.IO) {
                if (tree != null) TreeArtifacts.find(tree, patterns, cancelled = { !isActive })
                else model.pcFiles(target, location.path).then { t -> t.use { TreeArtifacts.find(it, patterns, cancelled = { !isActive }) } }
            }
            when (r) {
                is Outcome.Failure -> note = r.diagnostics.first().message
                is Outcome.Success -> {
                    found = r.value
                    if (pc) downloaded = r.value.items.mapNotNull { a -> PcArtifacts.local(app, target.id.value, a.path.substringAfterLast('/'), a.size, a.modified)?.let { a.path to it } }.toMap()
                }
            }
        }
        fun work(label: String, block: suspend () -> String?) {
            if (busy != null) return
            busy = label
            scope.launch {
                try { note = block() } finally { busy = null; version++ }
            }
        }

        /** Копия APK и её сведения — в окно перед действием (D-157, T22); `null` — окно открыто. */
        suspend fun review(file: File, action: ApkAction): String? =
            when (val r = withContext(Dispatchers.IO) { ApkReview.prepare(app, file, key?.certSha256) }) {
                is Outcome.Failure -> r.diagnostics.first().message
                is Outcome.Success -> {
                    pending = r.value to action
                    null
                }
            }

        fun install(r: ApkReview.Review) {
            if (busy != null) return ApkReview.discard(r)
            busy = "Передаю в установку"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ApkInstaller.install(app, r.copy, r.log) { res ->
                            scope.launch { note = when (res) { is Outcome.Success -> "${r.source.name}: ${res.value}"; is Outcome.Failure -> "${r.source.name}: ${res.diagnostics.first().message}" } }
                        }
                    }
                } finally {
                    // Сессия установки уже получила байты копии.
                    ApkReview.discard(r)
                    busy = null
                }
            }
        }

        fun sign(r: ApkReview.Review) {
            val what = r.meta?.let { "${it.packageName} ${it.versionName ?: ""}".trim() } ?: r.source.name
            DeviceConfirm.ask(context, "Подписать своим ключом: $what", onOk = {
                work("Подписываю") {
                    try {
                        val pair = withContext(Dispatchers.IO) { SigningKeys.key() } ?: return@work "ключа подписи нет"
                        val out = File(r.source.parentFile, ApkSign.signedName(r.source.name))
                        // Подписывается копия, сведения которой видел человек, а не файл дерева.
                        when (val s = withContext(Dispatchers.IO) { ApkSign.sign(r.copy, out, pair.first, pair.second) }) {
                            is Outcome.Failure -> s.diagnostics.first().message
                            is Outcome.Success -> {
                                AppLogs.info(app, "подпись", "подписан ${r.source.name} (${r.log}) → ${out.name}: ${s.value.schemes.joinToString()}, SHA-256 сертификата ${s.value.certSha256}")
                                "Подписан: ${out.name} (${s.value.schemes.joinToString()})"
                            }
                        }
                    } finally {
                        ApkReview.discard(r)
                    }
                }
            }, onDenied = { ApkReview.discard(r); note = it })
        }

        Text("Шаблоны: ${patterns.ifEmpty { TreeArtifacts.DEFAULT }.joinToString(" ")} — меняются в профиле выше.", style = MaterialTheme.typography.bodySmall)
        SigningKeyRow(key, busy == null, onChanged = { key = it }, onNote = { note = it })
        busy?.let { Text("$it…", style = MaterialTheme.typography.bodySmall) }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val list = found
        if (list != null && list.items.isEmpty()) Text("Артефактов по шаблонам нет — соберите проект или поправьте шаблоны.", style = MaterialTheme.typography.bodySmall)
        if (list?.limited == true) Text("Показана часть: уточните шаблоны.", style = MaterialTheme.typography.bodySmall)
        for (a in list?.items.orEmpty()) {
            val apk = a.path.endsWith(".apk", ignoreCase = true)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(a.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                Text("${a.path.substringBeforeLast('/', "")} · ${size(a.size)} · ${time(a.modified)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (pc) TextButton(enabled = busy == null, onClick = {
                        work("Скачиваю с ПК") {
                            val r = withContext(Dispatchers.IO) { model.pcFiles(target, location.path).then { t -> t.use { PcArtifacts.download(app, it, a.path, target.id.value) } } }
                            when (r) {
                                is Outcome.Failure -> r.diagnostics.first().message
                                is Outcome.Success -> {
                                    downloaded = downloaded + (a.path to r.value)
                                    AppLogs.info(app, "артефакты", "с ПК ${target.label}: ${a.path} — на телефон, ${r.value.length()} байт")
                                    "На телефоне: ${r.value.name}"
                                }
                            }
                        }
                    }) { Text(if (host(a.path) != null) "Скачать снова" else "На телефон") }
                    if (apk && (!pc || host(a.path) != null)) {
                        TextButton(enabled = busy == null, onClick = {
                            work("Читаю APK") {
                                val file = host(a.path) ?: return@work "файл вне дерева или исчез"
                                details = withContext(Dispatchers.IO) { ApkReview.describe(ApkInstaller.meta(app, file), ApkSign.inspect(file), ApkSign.fileSha256(file), key?.certSha256) }
                                null
                            }
                        }) { Text("Сведения") }
                        TextButton(enabled = busy == null, onClick = {
                            if (!ApkInstaller.allowed(context)) {
                                note = "Разрешите DPmetaApp устанавливать приложения: откроется настройка Android, затем нажмите «Установить» снова."
                                ApkInstaller.askPermission(context)
                                return@TextButton
                            }
                            val file = host(a.path) ?: run { note = "файл вне дерева или исчез"; return@TextButton }
                            work("Проверяю APK") { review(file, ApkAction.INSTALL) }
                        }) { Text("Установить") }
                        // Запуск установленного APK и сразу его logcat (D-160): adb к самому телефону.
                        TextButton(enabled = busy == null, onClick = {
                            val file = host(a.path) ?: run { note = "файл вне дерева или исчез"; return@TextButton }
                            val port = AdbBridge.state.value.connectPort
                            if (port == null) { note = "Сначала подключите adb: «Файлы» → «Logcat» → «Подключить»"; return@TextButton }
                            busy = "Запускаю"
                            scope.launch {
                                val meta = withContext(Dispatchers.IO) { ApkInstaller.meta(app, file) }
                                val pkg = (meta as? Outcome.Success)?.value?.takeIf { it.installed != null }?.packageName
                                if (pkg == null) { busy = null; note = "Приложение не установлено — сначала «Установить»"; return@launch }
                                AdbBridge.launch(app, port, pkg) { r -> scope.launch { busy = null; note = r; if (r.startsWith("Запущено")) logcatFor = pkg } }
                            }
                        }) { Text("Запустить") }
                        // Отладка установленного APK по JDWP (D-161): точки — в файлах .kt/.java этого дерева.
                        if (!pc) TextButton(enabled = busy == null, onClick = {
                            val file = host(a.path) ?: run { note = "файл вне дерева или исчез"; return@TextButton }
                            if (AdbBridge.state.value.connectPort == null) { note = "Сначала подключите adb: «Файлы» → «Logcat» → «Подключить»"; return@TextButton }
                            busy = "Готовлю отладку"
                            scope.launch {
                                val meta = withContext(Dispatchers.IO) { ApkInstaller.meta(app, file) }
                                busy = null
                                val pkg = (meta as? Outcome.Success)?.value?.takeIf { it.installed != null }?.packageName
                                if (pkg == null) { note = "Приложение не установлено — сначала «Установить»"; return@launch }
                                when (val t = com.dpsmpx.dpai.ui.DebugTargets.forApk(location.path, pkg)) {
                                    is Outcome.Failure -> note = t.diagnostics.first().message
                                    is Outcome.Success -> {
                                        Debugger.start(context, t.value, location.key, location.path)
                                        note = "Отладка $pkg — панель ниже; приложение перезапустится и подождёт отладчика"
                                    }
                                }
                            }
                        }) { Text("Отладка") }
                        // У артефакта ПК подписывается копия на телефоне; итог — в «Подписанных копиях» ниже (D-127).
                        TextButton(enabled = busy == null && key != null, onClick = {
                            val file = host(a.path) ?: run { note = "файл вне дерева или исчез"; return@TextButton }
                            work("Проверяю APK") { review(file, ApkAction.SIGN) }
                        }) { Text("Подписать") }
                    }
                    if (!pc || host(a.path) != null) TextButton(enabled = busy == null, onClick = {
                        work("Сохраняю в «Загрузки»") {
                            val file = host(a.path) ?: return@work "файл вне дерева или исчез"
                            if (file.length() > MAX_DOWNLOAD) return@work "файл больше ${MAX_DOWNLOAD shr 20} МБ — заберите его через терминал"
                            val r = withContext(Dispatchers.IO) { Downloads.write(app, "dpmeta-artifacts", file.name, if (apk) "application/vnd.android.package-archive" else "application/octet-stream", file.readBytes(), file.name) }
                            if (r is Outcome.Failure) r.diagnostics.first().message else "Сохранено: Загрузки/dpmeta-artifacts/${file.name}"
                        }
                    }) { Text("В Загрузки") }
                }
            }
        }
        if (pc) {
            // Подписанные копии APK с ПК — в хранилище приложения (`pc-artifacts`, D-127): установить, сохранить, удалить.
            val signed = remember(version, found) {
                PcArtifacts.dir(app, target.id.value).listFiles { f -> f.isFile && f.name.endsWith("-signed.apk") }.orEmpty().sortedByDescending { it.lastModified() }
            }
            if (signed.isNotEmpty()) {
                Text("Подписанные копии на телефоне", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                for (f in signed) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${size(f.length())} · ${time(f.lastModified())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled = busy == null, onClick = {
                                if (!ApkInstaller.allowed(context)) {
                                    note = "Разрешите DPmetaApp устанавливать приложения: откроется настройка Android, затем нажмите «Установить» снова."
                                    ApkInstaller.askPermission(context)
                                    return@TextButton
                                }
                                work("Проверяю APK") { review(f, ApkAction.INSTALL) }
                            }) { Text("Установить") }
                            TextButton(enabled = busy == null, onClick = {
                                work("Сохраняю в «Загрузки»") {
                                    if (f.length() > MAX_DOWNLOAD) return@work "файл больше ${MAX_DOWNLOAD shr 20} МБ — заберите его через терминал"
                                    val r = withContext(Dispatchers.IO) { Downloads.write(app, "dpmeta-artifacts", f.name, "application/vnd.android.package-archive", f.readBytes(), f.name) }
                                    if (r is Outcome.Failure) r.diagnostics.first().message else "Сохранено: Загрузки/dpmeta-artifacts/${f.name}"
                                }
                            }) { Text("В Загрузки") }
                            TextButton(enabled = busy == null, onClick = {
                                work("Удаляю копию") {
                                    withContext(Dispatchers.IO) { runCatching { com.dpsmpx.dpai.storage.vfs.NioVfs(f.parentFile!!.toPath()).delete(f.name) } }
                                    "Удалено: ${f.name}"
                                }
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
        TextButton(enabled = busy == null, onClick = { version++ }) { Text("Обновить список") }
        DebugPanel()
        logcatFor?.let { pkg ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { logcatFor = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) { LogcatPanel(initialPackage = pkg, onClose = { logcatFor = null }) }
            }
        }
        details?.let { text ->
            AlertDialog(onDismissRequest = { details = null }, title = { Text("APK") }, text = { Text(text, style = MaterialTheme.typography.bodySmall) },
                confirmButton = { TextButton(onClick = { details = null }) { Text("Закрыть") } })
        }
        // Перед установкой и подписью — пакет, версия, подпись и сумма копии (D-157, T22).
        pending?.let { (r, action) ->
            val cancel = { ApkReview.discard(r); pending = null }
            AlertDialog(
                onDismissRequest = cancel,
                title = { Text(if (action == ApkAction.INSTALL) "Установить ${r.source.name}?" else "Подписать ${r.source.name} своим ключом?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (r.self && action == ApkAction.INSTALL) {
                            Text(
                                "Это APK самого DPmetaApp: он заменит работающее приложение, оно закроется и откроется заново. Ставьте, только если собирали его сами.",
                                Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(r.text, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (action == ApkAction.INSTALL) "Ставится проверенная копия: изменения файла в дереве после этой проверки в установку не попадут." else "Подписывается проверенная копия; подписанный файл ляжет рядом с исходным.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { pending = null; if (action == ApkAction.INSTALL) install(r) else sign(r) }) { Text(if (action == ApkAction.INSTALL) "Установить" else "Подписать") } },
                dismissButton = { TextButton(onClick = cancel) { Text("Отмена") } },
            )
        }
    }
}

/** Что делается с APK после окна сведений. */
private enum class ApkAction { INSTALL, SIGN }

/** Ключ подписи: нет — «Импортировать…»; есть — субъект, отпечаток и «Удалить…». */
@Composable
private fun SigningKeyRow(key: SigningKeys.Info?, enabled: Boolean, onChanged: (SigningKeys.Info?) -> Unit, onNote: (String?) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var deleting by remember { mutableStateOf(false) }
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
    if (key == null) {
        Row {
            Text("Ключ подписи не задан.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = enabled, onClick = { pick.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }) { Text("Импортировать…") }
        }
    } else {
        Text("Ключ подписи: ${key.subject} (${key.algorithm})\nSHA-256: ${key.certSha256}", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = enabled, onClick = { deleting = true }) { Text("Удалить ключ…") }
    }
    picked?.let { bytes ->
        KeyPasswordDialog(onCancel = { bytes.fill(0); picked = null }) { password, alias ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { SigningKeys.import(app, bytes, password, alias) }
                when (r) {
                    is Outcome.Success -> { bytes.fill(0); picked = null; onChanged(r.value); onNote("Ключ импортирован. Файл ключа приложение не хранит.") }
                    is Outcome.Failure -> {
                        val d = r.diagnostics.first()
                        onNote(d.message)
                        // Несколько ключей в файле — окно остаётся, чтобы ввести имя ключа.
                        if (d.code != DiagCode.FIELD_MISSING && d.code != DiagCode.VALUE_INVALID) { bytes.fill(0); picked = null }
                    }
                }
            }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Удалить ключ подписи?") },
        text = { Text("Ключ сотрётся из Keystore телефона — извлечь его оттуда нельзя, восстановить — только импортом из вашего файла. Подписанные им APK останутся.", color = MaterialTheme.colorScheme.error) },
        confirmButton = { TextButton(onClick = {
            deleting = false
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { SigningKeys.delete(app) }) {
                    is Outcome.Success -> { onChanged(null); onNote("Ключ подписи удалён") }
                    is Outcome.Failure -> onNote(r.diagnostics.first().message)
                }
            }
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } },
    )
}

/** Пароль файла ключа и, если нужно, имя ключа. Окно — без снимков экрана; пароль не сохраняется. */
@Composable
private fun KeyPasswordDialog(onCancel: () -> Unit, onImport: (Secret, String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Пароль хранилища ключей") },
        text = {
            val view = LocalView.current
            DisposableEffect(view) {
                (view.parent as? DialogWindowProvider)?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { }
            }
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
private const val MAX_DOWNLOAD = 256L shl 20

private val TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun size(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1 shl 20 -> "${b / 1024} КБ"
    else -> String.format(java.util.Locale.ROOT, "%.1f МБ", b / 1048576.0)
}

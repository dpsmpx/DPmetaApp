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
import com.dpsmpx.dpai.core.clock.Clock
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
internal fun ArtifactsSection(model: DemoModel, view: ProjectWorkspaceView, location: WorkspaceLocation?, patterns: List<String>, refresh: Any?, built: BuildDone? = null) {
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
        // Ключ сборок проекта (D-178): свой, иначе глобальный, иначе нет.
        val projectId = view.project.project.value
        var keysVersion by remember { mutableIntStateOf(0) }
        var keys by remember { mutableStateOf<Pair<SigningKeys.Choice, SigningKeys.Info?>>(SigningKeys.Choice.NONE to null) }
        LaunchedEffect(projectId, keysVersion) { keys = withContext(Dispatchers.IO) { SigningKeys.forProject(projectId) } }
        val key = keys.second
        var listedAt by remember { mutableStateOf(0L) }
        LaunchedEffect(location, patterns, refresh, version) {
            val r = withContext(Dispatchers.IO) {
                if (tree != null) TreeArtifacts.find(tree, patterns, cancelled = { !isActive })
                else model.pcFiles(target, location.path).then { t -> t.use { TreeArtifacts.find(it, patterns, cancelled = { !isActive }) } }
            }
            when (r) {
                is Outcome.Failure -> note = r.diagnostics.first().message
                is Outcome.Success -> {
                    found = r.value
                    listedAt = Clock.SYSTEM.now().epochMillis
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

        /**
         * Подписать проверенные копии [reviews] ключом сборок проекта (D-178): одно
         * подтверждение входа на все; ключа нет — предупреждение, APK остаётся как собран.
         */
        fun signAll(reviews: List<ApkReview.Review>, why: String) {
            val (choice, info) = keys
            if (info == null) { reviews.forEach(ApkReview::discard); note = SignAfterBuild.NO_KEY; return }
            val what = reviews.joinToString { r -> r.meta?.let { "${it.packageName} ${it.versionName ?: ""}".trim() } ?: r.source.name }
            DeviceConfirm.ask(context, "$why ${choice.title}: $what", onOk = {
                work("Подписываю") {
                    try {
                        val level = if (choice == SigningKeys.Choice.PROJECT) projectId else null
                        val pair = withContext(Dispatchers.IO) { SigningKeys.key(level) } ?: return@work "ключа подписи нет"
                        val lines = ArrayList<String>()
                        for (r in reviews) {
                            // Копия с ПК — в свою папку подписанных (не рядом со скачанными: их чистит очистка до трёх).
                            val outDir = if (pc) PcArtifacts.signedDir(app, target.id.value).apply { mkdirs() } else r.source.parentFile
                            val out = File(outDir, ApkSign.signedName(r.source.name))
                            // Подписывается копия, сведения которой видел человек, а не файл дерева.
                            lines += when (val sig = withContext(Dispatchers.IO) { ApkSign.sign(r.copy, out, pair.first, pair.second) }) {
                                is Outcome.Failure -> "${r.source.name}: ${sig.diagnostics.first().message}"
                                is Outcome.Success -> {
                                    AppLogs.info(app, "подпись", "подписан ${choice.title} ${r.source.name} (${r.log}) → ${out.name}: ${sig.value.schemes.joinToString()}, SHA-256 сертификата ${sig.value.certSha256}")
                                    "Подписан: ${out.name} (${sig.value.schemes.joinToString()})"
                                }
                            }
                        }
                        lines.joinToString("\n")
                    } finally {
                        reviews.forEach(ApkReview::discard)
                    }
                }
            }, onDenied = { reviews.forEach(ApkReview::discard); note = "Не подписано ($it): APK остаётся как собран" })
        }

        fun sign(r: ApkReview.Review) = signAll(listOf(r), "Подписать")

        /** Копии [files] для подписи (D-157, T22); нечитаемый APK — мимо. */
        suspend fun prepareAll(files: List<File>): List<ApkReview.Review> = withContext(Dispatchers.IO) {
            files.mapNotNull { f -> (ApkReview.prepare(app, f, key?.certSha256) as? Outcome.Success)?.value }
        }

        // Подпись после сборки (D-178): новые APK последней успешной сборки, если список уже после неё.
        var autoDone by androidx.compose.runtime.saveable.rememberSaveable(location.key) { mutableStateOf<String?>(null) }
        LaunchedEffect(built, found, listedAt, keys) {
            val b = built ?: return@LaunchedEffect
            val list = found ?: return@LaunchedEffect
            if (pc || autoDone == b.run || listedAt < b.finishedAt || busy != null) return@LaunchedEffect
            val fresh = SignAfterBuild.candidates(list.items, b.startedAt)
            autoDone = b.run
            if (fresh.isEmpty()) return@LaunchedEffect
            if (key == null) { note = SignAfterBuild.NO_KEY; return@LaunchedEffect }
            val reviews = prepareAll(fresh.mapNotNull { host(it.path) })
            if (reviews.isNotEmpty()) signAll(reviews, "Подписать после сборки")
        }

        Text("Шаблоны: ${patterns.ifEmpty { TreeArtifacts.DEFAULT }.joinToString(" ")} — меняются в профиле выше.", style = MaterialTheme.typography.bodySmall)
        SigningKeysPanel(projectId, projectId, busy == null, keysVersion, onChanged = { keysVersion++ }, onNote = { note = it })
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
                                    // APK с ПК подписывается сразу после «На телефон» (D-178); без ключа — предупреждение.
                                    if (apk) {
                                        val file = r.value
                                        scope.launch {
                                            if (key == null) note = "На телефоне: ${file.name}. ${SignAfterBuild.NO_KEY}"
                                            else prepareAll(listOf(file)).takeIf { it.isNotEmpty() }?.let { signAll(it, "Подписать скачанный с ПК") }
                                        }
                                    }
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
                                note = "Разрешите DP_IDE устанавливать приложения: откроется настройка Android, затем нажмите «Установить» снова."
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
                PcArtifacts.signedDir(app, target.id.value).listFiles { f -> f.isFile && f.name.endsWith("-signed.apk") }.orEmpty().sortedByDescending { it.lastModified() }
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
                                    note = "Разрешите DP_IDE устанавливать приложения: откроется настройка Android, затем нажмите «Установить» снова."
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
                                    val r = withContext(Dispatchers.IO) { runCatching { com.dpsmpx.dpai.storage.vfs.NioVfs(f.parentFile!!.toPath()).delete(f.name) } }
                                    r.exceptionOrNull()?.let { "Не удалено: ${f.name} — ${it.javaClass.simpleName}" } ?: "Удалено: ${f.name}"
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
                                "Это APK самого DP_IDE: он заменит работающее приложение, оно закроется и откроется заново. Ставьте, только если собирали его сами.",
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

/**
 * Последняя успешная сборка проекта для подписи после неё (D-178): [run] — id
 * запуска, [startedAt] и [finishedAt] — мс по часам приложения.
 */
internal data class BuildDone(val run: String, val startedAt: Long?, val finishedAt: Long)

/** Что делается с APK после окна сведений. */
private enum class ApkAction { INSTALL, SIGN }

private const val MAX_DOWNLOAD = 256L shl 20

private val TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun size(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1 shl 20 -> "${b / 1024} КБ"
    else -> String.format(java.util.Locale.ROOT, "%.1f МБ", b / 1048576.0)
}

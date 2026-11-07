package com.dpsmpx.dpai.android

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.files.FileEntry
import com.dpsmpx.dpai.files.FileLink
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.LocalTree
import com.dpsmpx.dpai.files.Resolution
import com.dpsmpx.dpai.files.SyncAction
import com.dpsmpx.dpai.files.SyncItem
import com.dpsmpx.dpai.files.SyncOptions
import com.dpsmpx.dpai.files.SyncReport
import com.dpsmpx.dpai.files.TextFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Где смотрим: копия папки в Linux, сама папка (SAF), хранилище телефона, домашняя папка Linux. */
private enum class Kind { COPY, FOLDER, SHARED, HOME }

/**
 * Место в «Файлах» строкой — для хранителя состояния вкладки (AI:A-2): связь
 * — по id, при чтении — из нынешних связей; отвязанной уже нет — места нет.
 */
private fun Place.key(): String = listOf(kind.name, link?.id?.value.orEmpty(), dir).joinToString("\u0001")

private fun placeOf(key: String, links: List<FileLink>): Place? {
    val p = key.split("\u0001")
    if (p.size != 3) return null
    val kind = Kind.entries.firstOrNull { it.name == p[0] } ?: return null
    val link = links.firstOrNull { it.id.value == p[1] }
    if ((kind == Kind.COPY || kind == Kind.FOLDER) && link == null) return null
    return Place(kind, link, p[2])
}

private data class Place(val kind: Kind, val link: FileLink?, val dir: String) {
    val title: String
        get() = when (kind) {
            Kind.COPY -> "Копия «${link?.title}»"
            Kind.FOLDER -> "Папка «${link?.title}»"
            Kind.SHARED -> "Хранилище телефона"
            Kind.HOME -> "Домашняя папка Linux"
        } + if (dir.isEmpty()) "" else " / $dir"

    /** Каталог гостя для «Открыть в терминале»; у папки SAF его нет — терминал работает с копией. */
    val guestDir: String?
        get() = when (kind) {
            Kind.COPY -> link?.let { FileNames.join(GuestPaths.guestCopy(it.dir), dir) }
            Kind.FOLDER -> null
            Kind.SHARED -> GuestPaths.guestShared(dir)
            Kind.HOME -> GuestPaths.guestHome(dir)
        }
}

private fun treeOf(context: android.content.Context, p: Place): FileTree = when (p.kind) {
    Kind.COPY -> FileLinks.local(p.link!!)
    Kind.FOLDER -> FileLinks.remote(p.link!!)
    Kind.SHARED -> LocalTree(AllFiles.root().toPath())
    Kind.HOME -> GuestPaths.homeTree(context)
}

/**
 * Вкладка «Файлы» (D-99): папки автора — копией в Linux с синхронизацией,
 * «Доступ ко всем файлам» — хранилище телефона в Linux, обзор, просмотр,
 * правка текста, «Открыть в терминале».
 */
@Composable
fun FilesPane(onLinux: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { FileLinks.init(context) }
        ready = true
    }
    if (!ready) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
        return
    }
    val links by FileLinks.links.collectAsState()
    val undo = LocalUndo.current
    val problem by FileLinks.problem.collectAsState()
    // Место — строкой в сохранённом состоянии вкладки: смена вкладки его не сбрасывает (AI:A-2).
    var placeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val place = placeKey?.let { placeOf(it, links) }
    var syncing by remember { mutableStateOf<FileLink?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // Разрешение даётся в системном окне: по возвращении — перечитать.
    var granted by remember { mutableStateOf(AllFiles.granted()) }
    var wanted by remember { mutableStateOf(AllFiles.wanted(context)) }
    val lifecycle = (androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = AllFiles.granted() }
        lifecycle?.addObserver(o)
        onDispose { lifecycle?.removeObserver(o) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                note = "связываю…"
                val r = withContext(Dispatchers.IO) { FileLinks.add(uri) }
                when (r) {
                    is Outcome.Failure -> note = r.errors.first().message
                    is Outcome.Success -> {
                        note = null
                        syncing = r.value // первая синхронизация — только копия в Linux
                    }
                }
            }
        }
    }
    place?.let { p ->
        Browser(p, onPlace = { placeKey = it.key() }) { placeKey = null }
        return
    }
    // Готова ли среда — файл на диске: не в главном потоке и не на каждую перекомпозицию (AI:A-16).
    val linux = rememberRead(Unit, initial = false) { LinuxSetup.available(context) && LinuxSetup.environment(context).installed() != null }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SwitchRow(wanted && granted, onChange = { on ->
                AllFiles.set(context, on)
                wanted = on
                if (on && !granted) context.startActivity(AllFiles.settingsIntent(context))
            }) {
                Column(Modifier.weight(1f)) {
                    Text("Доступ ко всем файлам", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            wanted && granted -> "Хранилище телефона в Linux: ~/storage/shared — чтение и запись для всего, что там запущено"
                            wanted -> "Нет разрешения: включите его в системном окне"
                            else -> "Выключен: Linux видит только копии папок"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (wanted && !granted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Папки — копией в Linux", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = linux, onClick = { pick.launch(null) }) { Text("+ Папка") }
            }
            // Пустое состояние — с действием (AI:A-17): к установке среды.
            if (!linux) {
                Text("Нужна Linux-среда приложения.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onLinux) { Text("Установить: «Targets» → «Linux в приложении» ▸") }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        // Отвязанная, но ещё не подтверждённая снэкбаром — не видна (AI:A-4).
        items(links.filter { undo?.hidden?.containsKey("link:${it.id.value}") != true }, key = { it.id.value }) { l ->
            LinkCard(l, onSync = { syncing = l }, onOpen = { placeKey = Place(it, l, "").key() })
        }
        if (wanted && granted) {
            item {
                TextButton(onClick = { placeKey = Place(Kind.SHARED, null, "").key() }) { Text("Хранилище телефона…") }
            }
        }
        if (linux) {
            item { TextButton(onClick = { placeKey = Place(Kind.HOME, null, "").key() }) { Text("Домашняя папка Linux…") } }
        }
    }
    syncing?.let { l -> SyncDialog(l) { syncing = null } }
}

@Composable
private fun LinkCard(l: FileLink, onSync: () -> Unit, onOpen: (Kind) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val undo = LocalUndo.current
    // Касание карточки — копия в Linux; на виду — «Синхронизировать»; остальное — в ⋮ со словами, что откроется (AI:A-26).
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "открыть копию") { onOpen(Kind.COPY) }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Ещё действия" }) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge)
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Открыть папку телефона") }, onClick = {
                            menu = false
                            onOpen(Kind.FOLDER)
                        })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Копия в терминале") }, onClick = {
                            menu = false
                            TerminalActivity.startLinux(context, GuestPaths.guestCopy(l.dir))
                        })
                        // Обратимое — сразу и со снэкбаром «Отменить» (AI:A-4): копия в Linux остаётся.
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Отвязать", color = MaterialTheme.colorScheme.error) }, onClick = {
                            menu = false
                            if (undo != null) {
                                undo.run("link:${l.id.value}", "Папка «${l.title}» отвязана, копия осталась") { FileLinks.remove(l) }
                            } else {
                                scope.launch(Dispatchers.IO) { FileLinks.remove(l) }
                            }
                        })
                    }
                }
            }
            Text(
                "копия: ~/files/${l.dir} · " + (l.lastSync?.let { "синхронизировано ${SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("ru")).format(Date(it))}" } ?: "не синхронизировано") +
                    " · файлов: ${l.base.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onSync) { Text("Синхронизировать") }
        }
    }
}

private fun arrow(a: SyncAction): String = when (a) {
    SyncAction.TO_LOCAL -> "↓ в Linux"
    SyncAction.TO_REMOTE -> "↑ в папку"
    SyncAction.DELETE_LOCAL -> "✕ удалить в копии"
    SyncAction.DELETE_REMOTE -> "✕ удалить в папке"
    SyncAction.CONFLICT -> "⚠ конфликт"
    SyncAction.TOO_LARGE -> "больше 32 МиБ — пропущен"
    SyncAction.ADOPT, SyncAction.FORGET -> ""
}

/**
 * План синхронизации и его выполнение (D-99): что куда, удаления в папке —
 * красным и только с отметкой, конфликт — выбор по файлу.
 */
@Composable
private fun SyncDialog(link: FileLink, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<List<SyncItem>?>(null) }
    var skippedNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var deleteInFolder by remember { mutableStateOf(false) }
    var resolutions by remember { mutableStateOf<Map<String, Resolution>>(emptyMap()) }
    var report by remember { mutableStateOf<SyncReport?>(null) }
    LaunchedEffect(link.id) {
        when (val r = withContext(Dispatchers.IO) { FileLinks.plan(link) }) {
            is Outcome.Success -> {
                plan = r.value.first
                skippedNames = r.value.second
            }
            is Outcome.Failure -> error = r.errors.joinToString("; ") { "${Words.reason(it.code.name)}: ${it.message}" }
        }
        busy = false
    }
    val visible = plan.orEmpty().filter { it.visible }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Синхронизация «${link.title}»") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text(if (plan == null) "сравниваю папку и копию…" else "выполняю…")
                    }
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    report != null -> ReportView(report!!)
                    visible.isEmpty() -> Text("Папка и копия совпадают — делать нечего.")
                    else -> {
                        val counts = visible.groupingBy { it.action }.eachCount()
                        Text(counts.entries.joinToString(" · ") { "${arrow(it.key)}: ${it.value}" }, style = MaterialTheme.typography.bodyMedium)
                        if (counts.containsKey(SyncAction.DELETE_REMOTE)) {
                            Row(Modifier.fillMaxWidth().clickable { deleteInFolder = !deleteInFolder }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = deleteInFolder, onCheckedChange = null)
                                Text(
                                    "Удалить и в папке ${counts[SyncAction.DELETE_REMOTE]} файл(ов), удалённых в Linux",
                                    Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        for (it in visible) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(it.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    arrow(it.action),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (it.action == SyncAction.DELETE_REMOTE || it.action == SyncAction.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (it.action == SyncAction.CONFLICT) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val r = resolutions[it.path]
                                        FilterChip(selected = r == Resolution.KEEP_REMOTE, onClick = { resolutions = resolutions + (it.path to Resolution.KEEP_REMOTE) }, label = { Text(if (it.remote == null) "удалить" else "из папки") })
                                        FilterChip(selected = r == Resolution.KEEP_LOCAL, onClick = { resolutions = resolutions + (it.path to Resolution.KEEP_LOCAL) }, label = { Text(if (it.local == null) "удалить" else "из Linux") })
                                        FilterChip(selected = r == null, onClick = { resolutions = resolutions - it.path }, label = { Text("пропустить") })
                                    }
                                }
                            }
                        }
                        if (skippedNames.isNotEmpty()) {
                            Text("Имена, которые не переносятся (`:`, `\\`, управляющие знаки, повтор): ${skippedNames.take(10).joinToString()}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (report == null && error == null && plan != null && !busy) {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        when (val r = withContext(Dispatchers.IO) { FileLinks.apply(link, plan.orEmpty(), SyncOptions(deleteInFolder, resolutions)) }) {
                            is Outcome.Success -> report = r.value
                            is Outcome.Failure -> error = r.errors.joinToString("; ") { it.message }
                        }
                        busy = false
                    }
                }) { Text(if (visible.isEmpty()) "Запомнить" else "Выполнить") }
            } else {
                TextButton(enabled = !busy, onClick = onDone) { Text("Готово") }
            }
        },
        dismissButton = { if (report == null && !busy) TextButton(onClick = onDone) { Text("Отмена") } },
    )
}

@Composable
private fun ReportView(r: SyncReport) {
    val done = r.done.filterKeys { it != SyncAction.ADOPT && it != SyncAction.FORGET }
    Text(
        if (done.isEmpty()) "Ничего не перенесено." else "Сделано: " + done.entries.joinToString(" · ") { "${if (it.key == SyncAction.CONFLICT) "конфликт решён" else arrow(it.key)}: ${it.value}" },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (r.skipped.isNotEmpty()) Text("Не сделано: ${r.skipped.size} (конфликты без выбора, удаления в папке без отметки, большие файлы).", style = MaterialTheme.typography.bodySmall)
    r.errors.take(10).forEach { Text("${it.subject ?: ""}: ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** Обзор каталога: вход в папки, просмотр файла, «Открыть в терминале». */
@Composable
private fun Browser(p: Place, onPlace: (Place) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var entries by remember(p) { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember(p) { mutableStateOf<String?>(null) }
    var file by rememberSaveable(p.key()) { mutableStateOf<String?>(null) }
    // Системный «назад» — на папку вверх, из корня места — к списку «Файлов» (AI:A-2); у открытого файла — свой.
    androidx.activity.compose.BackHandler(enabled = file == null) { if (p.dir.isEmpty()) onClose() else onPlace(p.copy(dir = FileNames.parent(p.dir))) }
    LaunchedEffect(p) {
        when (val r = withContext(Dispatchers.IO) { treeOf(context, p).list(p.dir) }) {
            is Outcome.Success -> entries = r.value
            is Outcome.Failure -> error = r.errors.first().message
        }
    }
    file?.let { path ->
        FileView(p, path) { file = null }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (p.dir.isEmpty()) onClose() else onPlace(p.copy(dir = FileNames.parent(p.dir))) }, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("←") }
            Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.guestDir?.let { g -> TextButton(onClick = { TerminalActivity.startLinux(context, g) }) { Text("В терминале") } }
        }
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        val list = entries
        if (list == null && error == null) CircularProgressIndicator(Modifier.padding(12.dp))
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            if (list != null && list.isEmpty()) item { Text("Пусто.", style = MaterialTheme.typography.bodySmall) }
            items(list.orEmpty(), key = { it.name }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val path = FileNames.join(p.dir, e.name)
                        if (e.dir) onPlace(p.copy(dir = path)) else file = path
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (e.dir) "▸" else "·", Modifier.padding(end = 8.dp))
                    Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!e.dir) Text(size(e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun size(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КиБ"
    else -> "${b / 1024 / 1024} МиБ"
}

/** Картинка — уменьшенной до 2048 точек по большей стороне; не картинка — `null`. */
private fun decodeImage(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** Просмотр и правка файла: текст (UTF-8, до 2 МиБ) — с правкой; картинка — показом. */
@Composable
private fun FileView(p: Place, path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = TextFile.isImage(path)
    var text by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Правка — черновиком вне окна (Drafts): смена вкладки её не теряет; несохранённая — с вопросом (AI:A-2).
    val draftKey = "file:${p.key()}\u0001$path"
    var editing by remember(draftKey) { mutableStateOf(Drafts.get(draftKey)) }
    androidx.compose.runtime.SideEffect { Drafts.set(draftKey, editing) }
    var status by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val changed = editing != null && editing != text
    // Уходя, черновик снимается явно: экран уходит в том же кадре, и SideEffect его уже не снимет.
    val drop = { editing = null; Drafts.set(draftKey, null) }
    val leave = { if (changed) leaving = true else { drop(); onClose() } }
    androidx.activity.compose.BackHandler(onBack = leave)
    suspend fun saveEdit(e: String): Boolean {
        val r = withContext(Dispatchers.IO) { treeOf(context, p).write(path, TextFile.encode(e)) }
        return if (r is Outcome.Success) {
            text = e
            drop()
            status = "сохранено"
            true
        } else {
            status = "не сохранено: ${(r as Outcome.Failure).errors.first().message}"
            false
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Сохранить правку?") },
            text = { Text("В «${FileNames.name(path)}» есть несохранённые изменения.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    scope.launch { editing?.let { if (saveEdit(it)) onClose() } }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false; drop(); onClose() }) { Text("Не сохранять", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { leaving = false }) { Text("Отмена") }
                }
            },
        )
    }
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            when (val r = treeOf(context, p).read(path, if (image) IMAGE_MAX else TextFile.MAX_EDIT_BYTES)) {
                is Outcome.Failure -> error = r.errors.first().message
                is Outcome.Success -> if (image) {
                    bitmap = decodeImage(r.value)
                    if (bitmap == null) error = "картинка не читается"
                } else {
                    when (val t = TextFile.decode(r.value)) {
                        is Outcome.Success -> text = t.value
                        is Outcome.Failure -> error = t.errors.first().message
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = leave, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("←") }
            Text(FileNames.name(path), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val t = text
            if (t != null && editing == null) TextButton(onClick = { editing = t }) { Text("Править") }
            editing?.let { e ->
                TextButton(onClick = { scope.launch { saveEdit(e) } }) { Text("Сохранить") }
                TextButton(onClick = drop) { Text("Отмена") }
            }
        }
        status?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        val e = editing
        when {
            e != null -> OutlinedTextField(
                e, { editing = it }, Modifier.fillMaxSize().padding(8.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            bitmap != null -> Image(bitmap!!, FileNames.name(path), Modifier.fillMaxSize().padding(8.dp))
            text != null && text.orEmpty().length <= WHOLE_TEXT_MAX -> SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            // Большой файл (до 2 МиБ) — по строкам (AI:A-25): одним Text он раскладывался бы целиком на каждом кадре.
            text != null -> {
                val lines = remember(text) { text.orEmpty().lines() }
                SelectionContainer(Modifier.fillMaxSize()) {
                    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        items(lines.size, key = { it }) { i -> Text(lines[i], style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                    }
                }
            }
            error == null -> CircularProgressIndicator(Modifier.padding(12.dp))
        }
    }
}

/** Текст файла до стольких знаков — одним блоком (выделение через строки); больше — по строкам. */
private const val WHOLE_TEXT_MAX = 64 * 1024

/** Картинка больше этого не читается в память целиком. */
private const val IMAGE_MAX: Long = 32L * 1024 * 1024

package com.dpsmpx.dpai.android

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.log.AppLog
import com.dpsmpx.dpai.providers.UrlTransport
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.sync.CloudArchive
import com.dpsmpx.dpai.sync.CloudCrypto
import com.dpsmpx.dpai.sync.CloudRestore
import com.dpsmpx.dpai.sync.CopyEntry
import com.dpsmpx.dpai.sync.CopyInfo
import com.dpsmpx.dpai.sync.GitHubCopies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Облачная копия контура (D-107, T20): настройки — в настройках
 * приложения, токен и пароль копии — под Keystore (`ApiKeys`); снимок
 * файлов контура — пока контур стоит; шифр — на телефоне; отправка — в
 * приватный репозиторий GitHub. Восстановление раскладывает копию, а
 * применяет её `Controller` при следующем запуске.
 */
object CloudSync {
    private const val PREFS = "dpai.cloud"
    private const val TOKEN = "cloud.token"
    private const val PASSPHRASE = "cloud.passphrase"
    private const val DIR = "dpai-cloud"
    private const val TAG = "облачная копия"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 60L * 60 * 1000

    /** Сколько ждать стоящего контура для снимка файлов. */
    private const val STILL_WAIT_MS = 30_000L

    /** Заголовок шифра и тег GCM — с запасом. */
    private const val SEAL_OVERHEAD = 4096
    private const val FOREIGN = "В репозитории уже есть копия"

    data class Settings(val owner: String, val repo: String, val device: String, val daily: Boolean)

    fun settings(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(p.getString("owner", "").orEmpty(), p.getString("repo", "").orEmpty(), p.getString("device", null) ?: defaultDevice(), p.getBoolean("daily", false))
    }

    /** Есть ли токен и пароль копии — для списка секретов (`DPmetaApp:D-114`): только признак, без значения. */
    fun hasToken(context: Context): Boolean = ApiKeys.hasNamed(context, TOKEN)

    fun hasPassphrase(context: Context): Boolean = ApiKeys.hasNamed(context, PASSPHRASE)

    /** Есть ли всё для отправки: адрес, токен и пароль. */
    fun ready(context: Context): Boolean = canRestore(context) && ApiKeys.hasNamed(context, PASSPHRASE)

    /** Есть ли всё для восстановления: адрес и токен; пароль вводится в окне восстановления. */
    fun canRestore(context: Context): Boolean {
        val s = settings(context)
        return s.owner.isNotEmpty() && s.repo.isNotEmpty() && ApiKeys.hasNamed(context, TOKEN)
    }

    /** Разложенная копия ждёт перезапуска (D-107). */
    fun restorePending(context: Context): Boolean = restore(context).pending()

    /** Отменить ожидающее восстановление: при запуске ничего не заменится. */
    fun cancelRestore(context: Context): Outcome<Unit> = restore(context).cancel()

    /** Последняя отправка: когда (мс эпохи) и итог словами. */
    fun last(context: Context): Pair<Long, String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val note = p.getString("lastNote", null) ?: return null
        return p.getLong("lastAt", 0) to note
    }

    /**
     * Сохранить настройки; пустые [token] и [passphrase] — оставить прежние.
     * Итог — причина отказа или `null`.
     */
    fun save(context: Context, s: Settings, token: Secret, passphrase: Secret): String? {
        if (s.owner.isBlank() || s.repo.isBlank()) return "нужны владелец и репозиторий"
        CloudArchive.deviceProblem(s.device)?.let { return it }
        if (!passphrase.isEmpty) CloudCrypto.passphraseProblem(passphrase)?.let { return it }
        if (!token.isEmpty && !ApiKeys.putNamed(context, TOKEN, token)) return "токен не сохранён: Keystore недоступен"
        if (!passphrase.isEmpty && !ApiKeys.putNamed(context, PASSPHRASE, passphrase)) return "пароль не сохранён: Keystore недоступен"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("owner", s.owner.trim()).putString("repo", s.repo.trim()).putString("device", s.device).putBoolean("daily", s.daily).apply()
        return null
    }

    /** Забыть токен, пароль и настройки копии; сами копии в GitHub не трогаются. */
    fun forget(context: Context) {
        ApiKeys.removeNamed(context, TOKEN)
        ApiKeys.removeNamed(context, PASSPHRASE)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Проверить репозиторий и токен: приватный ли, можно ли писать. */
    fun check(context: Context): String = when (val c = copies(context)) {
        null -> "Нет настроек или токена копии"
        else -> when (val r = c.check()) {
            is Outcome.Success -> "Репозиторий годится: приватный, ветка ${r.value.branch}, запись — есть"
            is Outcome.Failure -> "Не годится: ${r.errors.firstOrNull()?.message}"
        }
    }

    /**
     * Такт службы: копия раз в сутки, если включено; сбой — повтор через
     * час. Отправка — в своём потоке: вывод ключа и сеть не держат такт
     * контура (замок — только на снимок файлов).
     */
    fun tick(app: Context, model: DemoModel, log: AppLog) {
        val s = settings(app)
        if (!s.daily || !ready(app) || sending.get()) return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // По пути — чужая копия: решает человек («Заменить…» или восстановление), такт не шифрует её зря каждый час.
        if (p.getString("lastNote", null)?.let(::isForeignCopy) == true) return
        val now = Clock.SYSTEM.now().epochMillis
        if (now - p.getLong("lastAt", 0) < DAY_MS || now - p.getLong("lastTry", 0) < RETRY_MS) return
        p.edit().putLong("lastTry", now).apply()
        Thread {
            log.info(TAG, runCatching { send(app, model) }.getOrElse { "сбой отправки: ${AppLogs.describe(it)}" })
        }.apply { isDaemon = true; name = "dpai-cloud-copy" }.start()
    }

    /** Отправка идёт: вторая (кнопка и такт разом) не начинается. */
    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Отказ отправки «по пути — чужая копия»: окно предлагает заменить её словом человека. */
    fun isForeignCopy(note: String): Boolean = note.startsWith(FOREIGN)

    /**
     * Отправить копию сейчас; итог — строка для экрана. Блокирующий вызов —
     * с фонового потока. Файл по пути, отправленный не этой установкой, без
     * [overwrite] не заменяется (новая установка не затрёт копию, из которой
     * её ещё не восстановили).
     */
    fun send(context: Context, model: DemoModel, overwrite: Boolean = false): String {
        if (!sending.compareAndSet(false, true)) return "Отправка уже идёт"
        try {
            return sendNow(context, model, overwrite)
        } finally {
            sending.set(false)
        }
    }

    private fun sendNow(context: Context, model: DemoModel, overwrite: Boolean): String {
        val s = settings(context)
        val c = copies(context) ?: return "Нет настроек или токена копии"
        val passphrase = ApiKeys.getNamed(context, PASSPHRASE) ?: return "Нет пароля копии (или Keystore сброшен): задайте его заново"
        val root = Controller.get(context.filesDir).dataRoot
        val collected = model.whileStill(STILL_WAIT_MS) { collect(root) } ?: return "Контур занят дольше ${STILL_WAIT_MS / 1000} с — копия позже"
        val entries = when (collected) {
            is Outcome.Failure -> return note(context, "Копия не собрана: ${collected.errors.firstOrNull()?.message}", false)
            is Outcome.Success -> collected.value
        }
        val packed = when (val r = CloudArchive.pack(entries, Clock.SYSTEM.now(), s.device, (GitHubCopies.MAX_COPY_BYTES - SEAL_OVERHEAD).toLong())) {
            is Outcome.Failure -> return note(context, "Копия не собрана: ${r.errors.firstOrNull()?.message}", false)
            is Outcome.Success -> r.value
        }
        // Предел — до вывода ключа: копия, переросшая его, не шифруется зря каждый час.
        if (packed.size > GitHubCopies.MAX_COPY_BYTES - SEAL_OVERHEAD) {
            return note(context, "Копия ${packed.size / 1024 / 1024} МиБ — больше предела ${GitHubCopies.MAX_COPY_BYTES / 1024 / 1024} МиБ: не отправлена", false)
        }
        val sealed = when (val r = CloudCrypto.seal(packed, passphrase, RandomSource.secure())) {
            is Outcome.Failure -> return note(context, "Копия не зашифрована: ${r.errors.firstOrNull()?.message}", false)
            is Outcome.Success -> r.value
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Блоб прошлой отправки этой установки — по нему GitHubCopies узнаёт свою копию.
        val mine = prefs.getString("lastBlob", null)?.takeIf { prefs.getString("lastBlobDevice", null) == s.device }
        return when (val r = c.upload("$DIR/${s.device}.bin", sealed, "Копия контура «AI»: ${s.device}", mine, overwrite)) {
            is Outcome.Failure -> if (r.diagnostics.any { it.subject == GitHubCopies.FOREIGN } && !overwrite) {
                note(context, "$FOREIGN «${s.device}» — отправленная не с этой установки. Восстановите её или замените словом в окне.", false)
            } else {
                note(context, "Не отправлено: ${r.errors.firstOrNull()?.message}", false)
            }
            is Outcome.Success -> {
                prefs.edit().putString("lastBlob", r.value.blob).putString("lastBlobDevice", s.device).apply()
                note(context, "Отправлено: ${entries.size} файлов, ${sealed.size / 1024} КиБ, коммит ${r.value.commit.take(7)}", true)
            }
        }
    }

    /** Копии в репозитории — имена устройств. */
    fun list(context: Context): Outcome<List<String>> {
        val c = copies(context) ?: return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.FIELD_MISSING, "нет настроек или токена копии")
        return when (val r = c.list(DIR)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(r.value.filter { it.endsWith(".bin") }.map { it.removeSuffix(".bin") })
        }
    }

    /** Загруженная копия: что за копия, её файлы и блоб в репозитории. */
    class Fetched(val info: CopyInfo, val entries: List<CopyEntry>, val device: String, val blob: String)

    /** Загрузить и расшифровать копию [device] паролем [passphrase]; файлы сверены с оглавлением. */
    fun fetch(context: Context, device: String, passphrase: Secret): Outcome<Fetched> {
        CloudArchive.deviceProblem(device)?.let { return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, it) }
        val c = copies(context) ?: return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.FIELD_MISSING, "нет настроек или токена копии")
        val (blob, sealed) = when (val r = c.download("$DIR/$device.bin")) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val plain = when (val r = CloudCrypto.open(sealed, passphrase)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return when (val r = CloudArchive.unpack(plain)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Fetched(r.value.first, r.value.second, device, blob))
        }
    }

    /**
     * Разложить проверенную копию: применит её `Controller` при следующем
     * запуске. Её блоб запоминается своим: первая отправка после
     * восстановления заменит её без «чужая копия».
     */
    fun stage(context: Context, f: Fetched): Outcome<String> = Controller.get(context.filesDir).manualProfiles.edit { restore(context).stage(f.entries) }.also {
        if (it is Outcome.Success) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lastBlob", f.blob).putString("lastBlobDevice", f.device).remove("lastNote").apply()
    }

    private fun restore(context: Context) = CloudRestore(NioVfs(Controller.get(context.filesDir).dataRoot.toPath()), Clock.SYSTEM)

    /**
     * Файлы контура для копии: корни [CloudArchive.ROOTS], без ссылок и
     * без того, что в копию не идёт ([CloudArchive.wanted]).
     */
    private fun collect(root: File): Outcome<List<CopyEntry>> {
        val paths = sequence {
            for (name in CloudArchive.ROOTS) {
                val top = File(root, name)
                if (Files.isSymbolicLink(top.toPath())) continue
                if (top.isFile) { yield(name); continue }
                if (!top.isDirectory) continue
                for (file in top.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }) {
                    if (file.isFile && !Files.isSymbolicLink(file.toPath())) yield(file.relativeTo(root).invariantSeparatorsPath)
                }
            }
        }
        return CloudArchive.collect(NioVfs(root.toPath()), paths, (GitHubCopies.MAX_COPY_BYTES - SEAL_OVERHEAD).toLong())
    }

    private fun copies(context: Context): GitHubCopies? {
        val s = settings(context)
        if (s.owner.isEmpty() || s.repo.isEmpty()) return null
        val token = ApiKeys.getNamed(context, TOKEN) ?: return null
        return GitHubCopies(UrlTransport(maxLineBytes = GitHubCopies.MAX_REPLY_CHARS), s.owner, s.repo, token)
    }

    private fun note(context: Context, text: String, ok: Boolean): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lastNote", text)
        if (ok) p.putLong("lastAt", Clock.SYSTEM.now().epochMillis)
        p.apply()
        return text
    }

    private fun defaultDevice(): String = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(40).ifEmpty { "phone" }
}

/** «Облачная копия» (D-107) — раздел «Копий и восстановления» в «Настройках AI» (AI:A-3). */
@Composable
internal fun CloudCard(model: DemoModel, framed: Boolean = true) {
    val context = LocalContext.current.applicationContext
    var editing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    // Отправка и отмена восстановления — вне экрана (AI:A-14): уход из «Копий» не теряет ход и итог, повтор не запускается.
    val jobs = remember { LongJobs.get(context) }
    val jobState by jobs.state.collectAsState()
    val job = jobState[LongJobs.CLOUD]
    val busy = job?.takeIf { it.running }?.label
    // Итог окна настроек — свой; новая операция его стирает.
    var note by remember { mutableStateOf<String?>(null) }
    val message = note ?: job?.takeIf { !it.running }?.note
    var state by remember { mutableStateOf<CardState?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version, job?.running) {
        state = withContext(Dispatchers.IO) {
            runCatching { CardState(CloudSync.ready(context), CloudSync.canRestore(context), CloudSync.last(context), CloudSync.restorePending(context)) }.getOrNull()
        }
    }

    fun work(label: String, work: () -> String) {
        note = null
        jobs.start(LongJobs.CLOUD, label) { LongJobs.Done(runCatching(work).getOrElse { "Сбой: ${AppLogs.describe(it)}" }) }
    }

    Framed(framed) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Облачная копия", style = MaterialTheme.typography.titleMedium)
            Text(
                "Журнал и задачи контура — зашифрованной копией в ваш приватный репозиторий GitHub. " +
                    "Без пароля копию не прочесть никому, в том числе GitHub. Ключи, API-профили и вход аккаунтов в копию не входят.",
                style = MaterialTheme.typography.bodySmall,
            )
            val s = state
            Text(
                when {
                    s == null -> "…"
                    !s.canRestore -> "Не настроена."
                    !s.ready -> "Пароль копии не задан: отправка выключена, восстановление — можно."
                    else -> s.last?.let { (at, note) -> (if (at > 0) "Последняя отправка: ${EcosystemAuto.stamp(com.dpsmpx.dpai.core.clock.Timestamp(at))}. " else "") + note } ?: "Настроена, копий ещё не было."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (s?.pending == true) {
                Text("Копия из облака разложена и заменит данные контура при следующем запуске.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("Перезапустить") }
                    TextButton(enabled = busy == null, onClick = {
                        work("Отмена восстановления") {
                            when (val r = CloudSync.cancelRestore(context)) {
                                is Outcome.Success -> "Восстановление отменено: данные не заменятся"
                                is Outcome.Failure -> "Не отменено: ${r.errors.firstOrNull()?.message}"
                            }
                        }
                    }) { Text("Отменить восстановление") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = busy == null, onClick = { editing = true }) { Text(if (s?.canRestore == true) "Настройки…" else "Настроить…") }
                if (s?.ready == true) TextButton(enabled = busy == null, onClick = { work("Отправка копии") { CloudSync.send(context, model) } }) { Text("Отправить копию") }
                if (s?.canRestore == true) TextButton(enabled = busy == null, onClick = { restoring = true }) { Text("Восстановить…") }
            }
            // Ход — полосой (AI:A-14), итог — строкой.
            if (busy != null) {
                Text("$busy…", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            // Отказ «чужая копия» — и от кнопки, и от такта службы (последняя отправка).
            if (busy == null && (message ?: s?.last?.second)?.let(CloudSync::isForeignCopy) == true) {
                TextButton(onClick = { replacing = true }) { Text("Заменить копию в репозитории…", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (editing) CloudSettingsDialog(onDismiss = { editing = false; version++ }) { text -> note = text }
    if (restoring) CloudRestoreDialog(onDismiss = { restoring = false; version++ })
    if (replacing) {
        AlertDialog(
            onDismissRequest = { replacing = false },
            title = { Text("Заменить копию?") },
            text = {
                Text(
                    "В репозитории под этим именем устройства лежит копия, отправленная не этой установкой — возможно, та, из которой вы хотели восстановиться. " +
                        "Заменить её копией этого телефона? Прежняя останется только в истории репозитория.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    replacing = false
                    work("Замена копии") { CloudSync.send(context, model, overwrite = true) }
                }) { Text("Заменить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { replacing = false }) { Text("Отмена") } },
        )
    }
}

/** Что показывает карточка: можно ли отправлять и восстанавливать, последняя отправка, ждёт ли восстановление. */
private data class CardState(val ready: Boolean, val canRestore: Boolean, val last: Pair<Long, String>?, val pending: Boolean)

@Composable
private fun CloudSettingsDialog(onDismiss: () -> Unit, onNote: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val initial = remember { CloudSync.settings(context) }
    var owner by remember { mutableStateOf(initial.owner) }
    var repo by remember { mutableStateOf(initial.repo) }
    var device by remember { mutableStateOf(initial.device) }
    var daily by remember { mutableStateOf(initial.daily) }
    var token by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    // Токен и пароль копии — не в снимки «недавних» и запись экрана (D-86, T20).
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Облачная копия") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Приватный репозиторий с README и отдельный fine-grained токен только к нему: Contents — Read and write. " +
                        "Пароль копии не восстановить: забыли — копия не читается.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(owner, { owner = it.trim() }, label = { Text("Владелец") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repo, { repo = it.trim() }, label = { Text("Репозиторий (приватный)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(device, { device = it.trim() }, label = { Text("Имя устройства (латиница)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it.trim() }, label = { Text("Токен GitHub (пусто — прежний)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Пароль копии, от ${CloudCrypto.MIN_PASSPHRASE} знаков (пусто — прежний)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                if (pass.isNotEmpty()) {
                    OutlinedTextField(
                        pass2, { pass2 = it }, label = { Text("Пароль ещё раз") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth().clickable { daily = !daily }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = daily, onCheckedChange = null)
                    Text("Отправлять раз в сутки сама", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (pass.isNotEmpty() && pass != pass2) {
                    problem = "пароли не совпадают"
                    return@TextButton
                }
                busy = true
                scope.launch {
                    val why = withContext(Dispatchers.IO) {
                        runCatching { CloudSync.save(context, CloudSync.Settings(owner, repo, device, daily), Secret(token), Secret(pass)) }.getOrElse { "сбой: ${AppLogs.describe(it)}" }
                    }
                    if (why != null) {
                        problem = why
                        busy = false
                    } else {
                        val checked = withContext(Dispatchers.IO) { runCatching { CloudSync.check(context) }.getOrElse { "Проверка: ${AppLogs.describe(it)}" } }
                        busy = false
                        onNote(checked)
                        onDismiss()
                    }
                }
            }) { Text(if (busy) "Проверяю…" else "Сохранить") }
        },
        dismissButton = {
            Row {
                // Забытый пароль не вернуть — окном (AI:A-4).
                TextButton(enabled = !busy, onClick = { forgetting = true }) { Text("Забыть…") }
                if (forgetting) {
                    ConfirmDialog(
                        "Забыть облачную копию?",
                        "Токен и пароль копии сотрутся с телефона. Копии в GitHub останутся, но без пароля их не прочесть.",
                        "Забыть",
                        onConfirm = {
                            forgetting = false
                            CloudSync.forget(context)
                            onNote("Настройки, токен и пароль копии забыты; копии в GitHub остались")
                            onDismiss()
                        },
                        onDismiss = { forgetting = false },
                    )
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/**
 * Восстановление (D-107): выбрать копию, ввести пароль, увидеть, что за
 * копия, подтвердить. Данные заменяются при следующем запуске; прежние —
 * рядом, `*.replaced-<время>`.
 */
@Composable
private fun CloudRestoreDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<String>?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<CloudSync.Fetched?>(null) }
    var staged by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        when (val r = withContext(Dispatchers.IO) { runCatching { CloudSync.list(context) }.getOrElse { Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, AppLogs.describe(it)) } }) {
            is Outcome.Success -> {
                devices = r.value
                chosen = r.value.firstOrNull { it == CloudSync.settings(context).device } ?: r.value.firstOrNull()
            }
            is Outcome.Failure -> problem = "Список копий: ${r.errors.firstOrNull()?.message}"
        }
    }
    AlertDialog(
        // Разложенная копия: окно не закрывается мимо «Перезапустить» — иначе замена случилась бы при каком-нибудь запуске без предупреждения.
        onDismissRequest = { if (!busy && !staged) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (staged) "Копия готова" else "Восстановить из облака") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val f = found
                when {
                    staged -> Text(
                        "Копия разложена и заменит данные контура при следующем запуске; нынешние останутся рядом (live.replaced-…). " +
                            "«Перезапустить» закроет приложение — откройте его снова. Ключи API, вход аккаунтов и ключ SSH на targets — заново.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    f != null -> Text(
                        "Копия «${f.info.device}» от ${EcosystemAuto.stamp(f.info.createdAt)}: файлов ${f.info.files}, ${f.info.bytes / 1024} КиБ. " +
                            "Восстановить — заменить ею журнал, задачи, targets и проект этого телефона?",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {
                        val ds = devices
                        if (ds == null && problem == null) Text("Ищу копии…", style = MaterialTheme.typography.bodySmall)
                        if (ds != null && ds.isEmpty()) Text("В репозитории копий нет.", style = MaterialTheme.typography.bodySmall)
                        ds?.forEach { d ->
                            Row(Modifier.fillMaxWidth().clickable { chosen = d }, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = chosen == d, onClick = null)
                                Text(d, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (!ds.isNullOrEmpty()) {
                            OutlinedTextField(
                                pass, { pass = it }, label = { Text("Пароль копии") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (busy) Text("Работаю…", style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val f = found
            when {
                staged -> TextButton(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("Перезапустить") }
                f != null -> TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        when (val r = withContext(Dispatchers.IO) { runCatching { CloudSync.stage(context, f) }.getOrElse { Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, AppLogs.describe(it)) } }) {
                            is Outcome.Success -> {
                                staged = true
                                AppLogs.get(context).info("облачная копия", "копия «${f.info.device}» разложена человеком: применится при запуске")
                            }
                            is Outcome.Failure -> problem = "Не разложена: ${r.errors.firstOrNull()?.message}"
                        }
                        busy = false
                    }
                }) { Text("Восстановить при перезапуске", color = MaterialTheme.colorScheme.error) }
                else -> TextButton(enabled = !busy && chosen != null && pass.isNotEmpty(), onClick = {
                    busy = true
                    problem = null
                    val device = chosen!!
                    val secret = Secret(pass)
                    scope.launch {
                        // Загрузка держит копию в памяти: нехватка памяти — отказ на экране, а не падение приложения.
                        when (val r = withContext(Dispatchers.IO) { runCatching { CloudSync.fetch(context, device, secret) }.getOrElse { Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.INTERNAL_ERROR, AppLogs.describe(it)) } }) {
                            is Outcome.Success -> found = r.value
                            is Outcome.Failure -> problem = r.errors.firstOrNull()?.message ?: "копия не прочитана"
                        }
                        busy = false
                    }
                }) { Text("Загрузить") }
            }
        },
        dismissButton = { if (!staged) TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
    )
}

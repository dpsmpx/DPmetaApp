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

    data class Settings(val owner: String, val repo: String, val device: String, val daily: Boolean)

    fun settings(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(p.getString("owner", "").orEmpty(), p.getString("repo", "").orEmpty(), p.getString("device", null) ?: defaultDevice(), p.getBoolean("daily", false))
    }

    /** Есть ли всё для отправки: адрес, токен и пароль. */
    fun ready(context: Context): Boolean {
        val s = settings(context)
        return s.owner.isNotEmpty() && s.repo.isNotEmpty() && ApiKeys.hasNamed(context, TOKEN) && ApiKeys.hasNamed(context, PASSPHRASE)
    }

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

    /** Такт службы: копия раз в сутки, если включено; сбой — повтор через час. */
    fun tick(app: Context, model: DemoModel, log: AppLog) {
        val s = settings(app)
        if (!s.daily || !ready(app)) return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = Clock.SYSTEM.now().epochMillis
        if (now - p.getLong("lastAt", 0) < DAY_MS || now - p.getLong("lastTry", 0) < RETRY_MS) return
        p.edit().putLong("lastTry", now).apply()
        log.info(TAG, send(app, model))
    }

    /** Отправить копию сейчас; итог — строка для экрана. Блокирующий вызов — с фонового потока. */
    fun send(context: Context, model: DemoModel): String {
        val s = settings(context)
        val c = copies(context) ?: return "Нет настроек или токена копии"
        val passphrase = ApiKeys.getNamed(context, PASSPHRASE) ?: return "Нет пароля копии (или Keystore сброшен): задайте его заново"
        val root = Controller.get(context.filesDir).dataRoot
        val entries = model.whileStill(STILL_WAIT_MS) { collect(root) } ?: return "Контур занят дольше ${STILL_WAIT_MS / 1000} с — копия позже"
        val packed = when (val r = CloudArchive.pack(entries, Clock.SYSTEM.now(), s.device)) {
            is Outcome.Failure -> return note(context, "Копия не собрана: ${r.errors.firstOrNull()?.message}", false)
            is Outcome.Success -> r.value
        }
        val sealed = when (val r = CloudCrypto.seal(packed, passphrase, RandomSource.secure())) {
            is Outcome.Failure -> return note(context, "Копия не зашифрована: ${r.errors.firstOrNull()?.message}", false)
            is Outcome.Success -> r.value
        }
        return when (val r = c.upload("$DIR/${s.device}.bin", sealed, "Копия контура «AI»: ${s.device}, файлов ${entries.size}")) {
            is Outcome.Failure -> note(context, "Не отправлено: ${r.errors.firstOrNull()?.message}", false)
            is Outcome.Success -> note(context, "Отправлено: ${entries.size} файлов, ${sealed.size / 1024} КиБ, коммит ${r.value.take(7)}", true)
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

    /** Загрузить и расшифровать копию [device] паролем [passphrase]; файлы сверены с оглавлением. */
    fun fetch(context: Context, device: String, passphrase: Secret): Outcome<Pair<CopyInfo, List<CopyEntry>>> {
        CloudArchive.deviceProblem(device)?.let { return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, it) }
        val c = copies(context) ?: return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.FIELD_MISSING, "нет настроек или токена копии")
        val sealed = when (val r = c.download("$DIR/$device.bin")) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val plain = when (val r = CloudCrypto.open(sealed, passphrase)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return CloudArchive.unpack(plain)
    }

    /** Разложить проверенную копию: применит её `Controller` при следующем запуске. */
    fun stage(context: Context, entries: List<CopyEntry>): Outcome<String> =
        CloudRestore(NioVfs(Controller.get(context.filesDir).dataRoot.toPath()), Clock.SYSTEM).stage(entries)

    /**
     * Файлы контура для копии: корни [CloudArchive.ROOTS], без ссылок и
     * без того, что в копию не идёт ([CloudArchive.wanted]).
     */
    private fun collect(root: File): List<CopyEntry> {
        val out = mutableListOf<CopyEntry>()
        for (name in CloudArchive.ROOTS) {
            val top = File(root, name)
            if (Files.isSymbolicLink(top.toPath())) continue
            if (top.isFile) {
                if (CloudArchive.wanted(name)) out += CopyEntry(name, top.readBytes())
                continue
            }
            if (!top.isDirectory) continue
            top.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .forEach { f ->
                    val rel = f.relativeTo(root).invariantSeparatorsPath
                    if (CloudArchive.wanted(rel)) out += CopyEntry(rel, f.readBytes())
                }
        }
        return out
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

/** Карточка «Облачная копия» во вкладке Targets (D-107). */
@Composable
internal fun CloudCard(model: DemoModel) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<Pair<Boolean, Pair<Long, String>?>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { state = withContext(Dispatchers.IO) { CloudSync.ready(context) to CloudSync.last(context) } }

    fun work(label: String, job: () -> String) {
        busy = label
        scope.launch {
            message = withContext(Dispatchers.IO) { runCatching(job).getOrElse { "Сбой: ${AppLogs.describe(it)}" } }
            busy = null
            version++
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Облачная копия", style = MaterialTheme.typography.titleMedium)
            Text(
                "Журнал и задачи контура — зашифрованной копией в ваш приватный репозиторий GitHub. " +
                    "Без пароля копию не прочесть никому, в том числе GitHub. Ключи и вход аккаунтов в копию не входят.",
                style = MaterialTheme.typography.bodySmall,
            )
            val s = state
            Text(
                when {
                    s == null -> "…"
                    !s.first -> "Не настроена."
                    else -> s.second?.let { (at, note) -> (if (at > 0) "Последняя отправка: ${EcosystemAuto.stamp(com.dpsmpx.dpai.core.clock.Timestamp(at))}. " else "") + note } ?: "Настроена, копий ещё не было."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = busy == null, onClick = { editing = true }) { Text(if (s?.first == true) "Настройки…" else "Настроить…") }
                if (s?.first == true) {
                    TextButton(enabled = busy == null, onClick = { work("Отправляю…") { CloudSync.send(context, model) } }) { Text("Отправить копию") }
                    TextButton(enabled = busy == null, onClick = { restoring = true }) { Text("Восстановить…") }
                }
            }
            (busy ?: message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (editing) CloudSettingsDialog(onDismiss = { editing = false; version++ }) { text -> message = text }
    if (restoring) CloudRestoreDialog(onDismiss = { restoring = false; version++ })
}

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
                    val why = withContext(Dispatchers.IO) { CloudSync.save(context, CloudSync.Settings(owner, repo, device, daily), Secret(token), Secret(pass)) }
                    if (why != null) {
                        problem = why
                        busy = false
                    } else {
                        val checked = withContext(Dispatchers.IO) { CloudSync.check(context) }
                        busy = false
                        onNote(checked)
                        onDismiss()
                    }
                }
            }) { Text(if (busy) "Проверяю…" else "Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    CloudSync.forget(context)
                    onNote("Настройки, токен и пароль копии забыты; копии в GitHub остались")
                    onDismiss()
                }) { Text("Забыть") }
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
    var found by remember { mutableStateOf<Pair<CopyInfo, List<CopyEntry>>?>(null) }
    var staged by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        when (val r = withContext(Dispatchers.IO) { CloudSync.list(context) }) {
            is Outcome.Success -> {
                devices = r.value
                chosen = r.value.firstOrNull { it == CloudSync.settings(context).device } ?: r.value.firstOrNull()
            }
            is Outcome.Failure -> problem = "Список копий: ${r.errors.firstOrNull()?.message}"
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
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
                        "Копия «${f.first.device}» от ${EcosystemAuto.stamp(f.first.createdAt)}: файлов ${f.first.files}, ${f.first.bytes / 1024} КиБ. " +
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
                        when (val r = withContext(Dispatchers.IO) { CloudSync.stage(context, f.second) }) {
                            is Outcome.Success -> {
                                staged = true
                                AppLogs.get(context).info("облачная копия", "копия «${f.first.device}» разложена человеком: применится при запуске")
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
                        when (val r = withContext(Dispatchers.IO) { CloudSync.fetch(context, device, secret) }) {
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

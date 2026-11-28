package com.dpsmpx.dpai.android

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.sync.CloudCrypto
import com.dpsmpx.dpai.ui.AppBackup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Резервная копия приложения (`DPmetaApp:D-151`; D-182): разделы — что брать из
 * `SharedPreferences`, хранилищ документов и файлов; сохранение в выбранное место (с
 * секретами — только под паролем), копия без секретов раз в сутки в «Загрузки» и
 * восстановление выбранных разделов. Секреты пишутся сразу; остальное раскладывается
 * при следующем запуске до чтения настроек ([applyPending]) — живые экраны и служба
 * не перезапишут восстановленное своими копиями из памяти.
 */
object AppBackups {
    /** Файл настроек: [skip] — ключи, которые не переносятся (служебные, устройство-зависимые). */
    class PrefsSpec(val file: String, val skip: (String) -> Boolean = { false })

    /** Хранилище документов: [id] — имя в копии; [take] — какие ключи переносятся. */
    class DocsSpec(val id: String, val open: (Context) -> DocumentStore, val take: (String) -> Boolean = { true })

    /** Текстовый файл целиком. */
    class FileSpec(val id: String, val dir: (Context) -> File, val name: String)

    class Section(
        val id: String,
        val title: String,
        val what: String,
        val prefs: List<PrefsSpec> = emptyList(),
        val docs: List<DocsSpec> = emptyList(),
        val files: List<FileSpec> = emptyList(),
    )

    private val registered = CopyOnWriteArrayList<Section>()

    /** Раздел оболочки (пульт) — регистрирует `:shell`. */
    fun register(s: Section) {
        registered.removeAll { it.id == s.id }
        registered += s
    }

    private fun store(dir: File, ns: String) = DocumentStore(NioVfs(dir.apply { mkdirs() }.toPath()), ns)

    private val AI = Section(
        "ai", "ИИ, targets и проекты",
        "targets и места, рабочие проекты, known_hosts, профили сборки и запуска, API-профили и маршруты без ключей, выдача секретов ИИ",
        prefs = listOf(PrefsSpec("dpmeta.secrets"), PrefsSpec("dpai.profile-colors"), PrefsSpec("usage-auto")),
        docs = listOf(
            DocsSpec("controller", { store(File(it.filesDir, "controller"), "docs") }) { it == "targets" || it == "projects" || it == "project" },
            // Идущий профиль и его итог — состояние, а не настройка.
            DocsSpec("manual-profiles", { store(File(it.filesDir, "manual-profiles"), "docs") }) { it != "active" && it != "result" },
            DocsSpec("models", { store(it.getDir("models", Context.MODE_PRIVATE), "docs") }) { it == "api-profiles" || it == "routines" || it == "model-routing" },
        ),
        files = listOf(FileSpec("controller.known_hosts", { File(it.filesDir, "controller") }, "known_hosts")),
    )

    private val IDE = Section(
        "ide", "IDE и оформление", "тема, шрифт и размер текста, настройки редактора и вкладки, терминал",
        prefs = listOf(PrefsSpec(com.dpsmpx.dpai.ui.UiLook.PREFS), PrefsSpec("terminal")),
        docs = listOf(DocsSpec("editor-ui", { store(it.noBackupFilesDir.resolve("editor-ui"), "ui") })),
    )

    /** Разделы в порядке экрана; секреты — последним, отдельной строкой. */
    fun sections(): List<Section> = registered.toList() + listOf(AI, IDE)

    val SECRETS_TITLE = "Секреты"

    // ---------- Снять ----------

    /** Снять разделы [ids]; [secrets] — со значениями секретов (только для копии под паролем). */
    fun export(context: Context, ids: Set<String>, secrets: Boolean, appVersion: String?): Outcome<AppBackup.Archive> {
        val app = context.applicationContext
        val out = ArrayList<AppBackup.Section>()
        for (s in sections().filter { it.id in ids }) {
            val prefs = s.prefs.associate { p ->
                p.file to app.getSharedPreferences(p.file, Context.MODE_PRIVATE).all.filterKeys { !p.skip(it) }
                    .mapNotNull { (k, v) -> AppBackup.Pref.of(v)?.let { k to it } }.toMap()
            }.filterValues { it.isNotEmpty() }
            val docs = LinkedHashMap<String, Map<String, JsonObject>>()
            for (d in s.docs) {
                when (val r = com.dpsmpx.dpai.ui.AppBackupStores.export(d.open(app), d.take)) {
                    is Outcome.Failure -> return Outcome.failure(r.errors.first().code, "«${s.title}»: ${r.errors.first().message}", d.id)
                    is Outcome.Success -> if (r.value.isNotEmpty()) docs[d.id] = r.value
                }
            }
            val files = s.files.mapNotNull { f -> NioVfs(f.dir(app).toPath()).read(f.name)?.let { f.id to it.toString(Charsets.UTF_8) } }.toMap()
            out += AppBackup.Section(s.id, prefs, docs, files)
        }
        if (secrets) {
            when (val r = AppSecrets.export(app)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> if (r.value.isNotEmpty()) out += AppBackup.Section(AppBackup.SECRETS, secrets = r.value)
            }
        }
        return Outcome.success(AppBackup.Archive(Clock.SYSTEM.now().epochMillis, appVersion, out))
    }

    // ---------- Записать файл ----------

    /** Записать копию в [uri]; [password] — для секретов (обязателен, если они есть). */
    fun save(context: Context, uri: Uri, archive: AppBackup.Archive, password: Secret?): Outcome<Int> {
        val seal: ((ByteArray) -> Outcome<ByteArray>)? = password?.let { p -> { plain -> CloudCrypto.seal(plain, p, RandomSource.secure()) } }
        val bytes = when (val e = AppBackup.encode(archive, seal)) {
            is Outcome.Failure -> return e
            is Outcome.Success -> e.value
        }
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл копии не открылся", uri.toString())
            AppLogs.info(context, "копия", "копия приложения сохранена: разделов ${archive.sections.size}" + if (archive.hasSecrets) ", с секретами под паролем" else "")
            Outcome.success(bytes.size)
        } catch (e: Exception) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "копия не записана", e, uri.toString()))
        }
    }

    /** Прочитать файл копии (не больше предела формата + заголовок шифра). */
    fun read(context: Context, uri: Uri): Outcome<ByteArray> = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val limit = AppBackup.MAX_BYTES + 64 * 1024
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) return Outcome.failure(DiagCode.VALUE_INVALID, "файл слишком большой для копии настроек", uri.toString())
            }
            Outcome.success(out.toByteArray())
        } ?: Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл копии не открылся", uri.toString())
    } catch (e: Exception) {
        Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл копии не прочитан", e, uri.toString()))
    }

    /** Разобрать прочитанное; копия под паролем — с [password]. */
    fun open(bytes: ByteArray, password: Secret?): Outcome<AppBackup.Archive> =
        AppBackup.decode(bytes, password?.let { p -> { sealed -> CloudCrypto.open(sealed, p) } })

    // ---------- Восстановить ----------

    private const val PENDING = "app-backup-restore.json"
    private const val PREFS = "dpmeta.app-backup"

    /**
     * Восстановить разделы [ids] из [archive]: сначала нынешнее — копией без секретов в
     * «Загрузки»; секреты — сразу; остальное — раскладывается при следующем запуске.
     */
    fun restore(context: Context, archive: AppBackup.Archive, ids: Set<String>, appVersion: String?): Outcome<String> {
        val app = context.applicationContext
        val chosen = archive.select(ids)
        if (chosen.sections.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "не выбрано ни одного раздела")
        autoSave(app, appVersion, force = true).let { if (it is Outcome.Failure) return Outcome.failure(it.errors.first().code, "нынешние настройки не сохранились — восстановление не начато: ${it.errors.first().message}") }
        val plain = chosen.copy(sections = chosen.sections.filter { it.id != AppBackup.SECRETS })
        if (plain.sections.isNotEmpty()) {
            val bytes = (AppBackup.encode(plain, null) as? Outcome.Success)?.value
                ?: return Outcome.failure(DiagCode.INTERNAL_ERROR, "разделы не подготовлены")
            try {
                val vfs = NioVfs(app.filesDir.toPath())
                vfs.write("$PENDING.tmp", bytes)
                vfs.fsync("$PENDING.tmp")
                vfs.rename("$PENDING.tmp", PENDING)
            } catch (e: java.io.IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "разделы не подготовлены к перезапуску", e))
            }
        }
        val secrets = chosen.section(AppBackup.SECRETS)?.secrets.orEmpty()
        val wrote = if (secrets.isEmpty()) 0 else when (val r = AppSecrets.import(app, secrets)) {
            is Outcome.Failure -> return Outcome.failure(r.errors.first().code, r.errors.first().message + (if (plain.sections.isNotEmpty()) "; разделы применятся после перезапуска" else ""))
            is Outcome.Success -> r.value
        }
        return Outcome.success(buildString {
            if (wrote > 0) append("Секретов записано: $wrote. ")
            if (plain.sections.isNotEmpty()) append("Разделы (${plain.sections.size}) применятся после перезапуска приложения.")
        }.trim())
    }

    /** Ждёт ли восстановление перезапуска. */
    fun pending(context: Context): Boolean = NioVfs(context.applicationContext.filesDir.toPath()).exists(PENDING)

    /**
     * Разложить восстановленное — при запуске процесса, до чтения настроек и хранилищ
     * (из `Application.onCreate`). Итог — строкой для экрана ([lastApplied]).
     */
    fun applyPending(context: Context) {
        val app = context.applicationContext
        val vfs = NioVfs(app.filesDir.toPath())
        val bytes = try { vfs.read(PENDING) } catch (_: java.io.IOException) { null } ?: return
        val note = try {
            when (val a = AppBackup.decode(bytes, null)) {
                is Outcome.Failure -> "восстановление не применено: ${a.errors.first().message}"
                is Outcome.Success -> apply(app, a.value)
            }
        } catch (e: Exception) {
            "восстановление не применено: ${e.javaClass.simpleName}"
        }
        runCatching { vfs.delete(PENDING) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("applied", note) }
        runCatching { AppLogs.info(app, "копия", note) }
    }

    /** Итог последнего разложения — экран показывает его один раз. */
    fun lastApplied(context: Context, clear: Boolean): String? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("applied", null).also { if (clear && it != null) p.edit { remove("applied") } }
    }

    private fun apply(app: Context, a: AppBackup.Archive): String {
        val done = ArrayList<String>()
        val failed = ArrayList<String>()
        for (data in a.sections) {
            val spec = sections().firstOrNull { it.id == data.id } ?: run { failed += "${data.id} (раздел неизвестен этой версии)"; null } ?: continue
            var ok = true
            for (p in spec.prefs) {
                val values = data.prefs[p.file].orEmpty()
                val sp = app.getSharedPreferences(p.file, Context.MODE_PRIVATE)
                // Ключи раздела — заменяются целиком; пропускаемые (служебные) остаются как были.
                val change = com.dpsmpx.dpai.ui.AppBackupStores.prefs(sp.all.keys, values, p.skip)
                sp.edit(commit = true) {
                    for (k in change.remove) remove(k)
                    for ((k, v) in change.put) when (v) {
                        is AppBackup.Pref.Str -> putString(k, v.value)
                        is AppBackup.Pref.Int32 -> putInt(k, v.value)
                        is AppBackup.Pref.Int64 -> putLong(k, v.value)
                        is AppBackup.Pref.Bool -> putBoolean(k, v.value)
                        is AppBackup.Pref.Real -> putFloat(k, v.value)
                        is AppBackup.Pref.Strings -> putStringSet(k, v.value)
                    }
                }
            }
            for (d in spec.docs) {
                if (com.dpsmpx.dpai.ui.AppBackupStores.apply(d.open(app), data.docs[d.id].orEmpty(), d.take) is Outcome.Failure) ok = false
            }
            for (fs in spec.files) {
                val text = data.files[fs.id] ?: continue
                try {
                    val vfs = NioVfs(fs.dir(app).apply { mkdirs() }.toPath())
                    vfs.write("${fs.name}.tmp", text.toByteArray(Charsets.UTF_8))
                    vfs.fsync("${fs.name}.tmp")
                    vfs.rename("${fs.name}.tmp", fs.name)
                } catch (_: java.io.IOException) {
                    ok = false
                }
            }
            if (ok) done += spec.title else failed += spec.title
        }
        return "восстановлено: " + done.joinToString().ifEmpty { "ничего" } + if (failed.isEmpty()) "" else "; не полностью: ${failed.joinToString()}"
    }

    // ---------- Сама, раз в сутки ----------

    private const val DIR = "dpmeta-backup"
    private const val PREFIX = "dpmeta-backup-"
    private const val KEEP = 7
    private const val EVERY_MS = 24L * 60 * 60 * 1000

    /** Последняя копия «сама»: имя и время — для экрана. */
    fun lastAuto(context: Context): Pair<String, Long>? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("autoName", null)?.let { it to p.getLong("autoAt", 0) }
    }

    /** Такт службы и экрана: прошли сутки — копия без секретов в «Загрузки/dpmeta-backup». */
    fun tick(context: Context, appVersion: String?) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (Clock.SYSTEM.now().epochMillis - p.getLong("autoAt", 0) < EVERY_MS) return
        autoSave(context, appVersion, force = false)
    }

    /** Копия всех разделов без секретов в «Загрузки»; [force] — и раньше суток (перед восстановлением). */
    @Synchronized
    fun autoSave(context: Context, appVersion: String?, force: Boolean): Outcome<String> {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = Clock.SYSTEM.now().epochMillis
        if (!force && now - p.getLong("autoAt", 0) < EVERY_MS) return Outcome.success(p.getString("autoName", "").orEmpty())
        val archive = when (val r = export(app, sections().map { it.id }.toSet(), secrets = false, appVersion)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val bytes = when (val e = AppBackup.encode(archive, null)) {
            is Outcome.Failure -> return e
            is Outcome.Success -> e.value
        }
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".json"
        Downloads.write(app, DIR, name, "application/json", bytes, "копия приложения").let { if (it is Outcome.Failure) return it }
        p.edit { putLong("autoAt", now).putString("autoName", name) }
        Downloads.prune(app, DIR, PREFIX, KEEP)
        return Outcome.success(name)
    }

    /** Имя файла для «Сохранить копию…». */
    fun suggestedName(secrets: Boolean): String =
        PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(Clock.SYSTEM.now().epochMillis)) + if (secrets) ".bin" else ".json"
}

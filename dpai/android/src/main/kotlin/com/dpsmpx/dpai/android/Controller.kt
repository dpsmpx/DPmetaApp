package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.DoctorResult
import com.dpsmpx.dpai.execution.target.ExecutionTargetManager
import com.dpsmpx.dpai.jsch.EcdsaSignerIdentity
import com.dpsmpx.dpai.jsch.HostKeyProbe
import com.dpsmpx.dpai.jsch.JschProcessHost
import com.dpsmpx.dpai.jsch.ProbedHostKey
import com.dpsmpx.dpai.jsch.SshKeys
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.KnownHosts
import com.dpsmpx.dpai.ui.LiveSystem
import com.dpsmpx.dpai.ui.ProjectBook
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.ProjectForm
import com.dpsmpx.dpai.ui.SettingsSnapshot
import com.dpsmpx.dpai.ui.SetupGuide
import com.dpsmpx.dpai.ui.TargetBook
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.ui.TargetForm
import com.dpsmpx.dpai.ui.TermuxRestore
import com.dpsmpx.dpai.ui.WorkspaceLink
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Ключ входа приложения (D-35): ECDSA P-256. С D-184 (`DPmetaApp:D-157`) — переносимый:
 * закрытый ключ файлом в `noBackupFilesDir`, зашифрованный ключом Keystore без входа
 * (фоновые подключения к target идут и при запертом телефоне), и попадает в копию под
 * паролем (`AppSecrets`). Прежний ключ внутри Keystore не извлекается: он работает, пока
 * его не сменят на переносимый ([renew]). В журнал и на target — только открытая строка.
 */
object KeystoreKey {
    private const val ALIAS = "dpai-ssh"
    private const val WRAP = "dpai-ssh-wrap"
    private const val FILE = "ssh.key"
    private const val FORMAT = "dpai.ssh-key"
    const val COMMENT = "dpai-android"

    /** Каталог `noBackupFilesDir` приложения — не сам Context (утечка в статическом поле). */
    @Volatile private var app: java.io.File? = null
    @Volatile private var cached: java.security.KeyPair? = null

    /** Оболочка — при запуске процесса: где лежит переносимый ключ. */
    fun install(context: Context) { app = context.applicationContext.noBackupFilesDir }

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun legacy(): Boolean = try { store().containsAlias(ALIAS) } catch (_: Exception) { false }

    /** Переносимый ключ есть (и попадёт в копию под паролем). */
    fun portable(): Boolean = app?.let { KeyWrap.exists(it, FILE) } == true

    @Synchronized
    private fun loadPortable(): java.security.KeyPair? {
        cached?.let { return it }
        val base = app ?: return null
        val plain = (KeyWrap.read(base, WRAP, userAuth = false, FILE) as? Outcome.Success)?.value ?: return null
        return try { decode(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }?.also { cached = it }
    }

    private fun decode(text: String): java.security.KeyPair? = runCatching {
        val o = com.dpsmpx.dpai.core.serialization.JsonParser().parse(text).valueOrNull() as com.dpsmpx.dpai.core.serialization.JsonObject
        require((o["format"] as com.dpsmpx.dpai.core.serialization.JsonString).value == FORMAT)
        val f = java.security.KeyFactory.getInstance("EC")
        val b64 = java.util.Base64.getDecoder()
        val priv = f.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(b64.decode((o["private"] as com.dpsmpx.dpai.core.serialization.JsonString).value)))
        val pub = f.generatePublic(java.security.spec.X509EncodedKeySpec(b64.decode((o["public"] as com.dpsmpx.dpai.core.serialization.JsonString).value))) as ECPublicKey
        require(pub.params.curve.field.fieldSize == 256)
        java.security.KeyPair(pub, priv)
    }.getOrNull()

    private fun encode(pair: java.security.KeyPair): String {
        val b64 = java.util.Base64.getEncoder()
        return com.dpsmpx.dpai.core.serialization.CanonicalJson.write(com.dpsmpx.dpai.core.serialization.JsonObject.of(
            "format" to com.dpsmpx.dpai.core.serialization.JsonString(FORMAT),
            "version" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(1),
            "private" to com.dpsmpx.dpai.core.serialization.JsonString(b64.encodeToString(pair.private.encoded)),
            "public" to com.dpsmpx.dpai.core.serialization.JsonString(b64.encodeToString(pair.public.encoded)),
        ))
    }

    @Synchronized
    private fun createPortable(): java.security.KeyPair {
        val base = checkNotNull(app) { "KeystoreKey.install не вызван" }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val w = KeyWrap.write(base, WRAP, userAuth = false, FILE, encode(pair).toByteArray(Charsets.UTF_8))
        check(w is Outcome.Success) { "ключ SSH не записан" }
        cached = pair
        return pair
    }

    @Synchronized
    fun publicKey(): ECPublicKey {
        loadPortable()?.let { return it.public as ECPublicKey }
        // Файл есть, но не читается — не подменять ключ молча: строка на target перестала бы подходить
        // (проверка PR #190). Новый — только явно, «Новый переносимый ключ SSH…».
        check(!portable()) { "ключ SSH не читается — восстановите копию или создайте новый ключ явно" }
        if (legacy()) return store().getCertificate(ALIAS).publicKey as ECPublicKey
        return createPortable().public as ECPublicKey
    }

    /** Есть ли ключ — для аварийного отзыва; проверка не создаёт новый. */
    fun exists(): Boolean = portable() || legacy()

    /**
     * Стереть ключ (аварийный отзыв, `DPmetaApp:D-151`): следующее подключение создаст
     * новый — его открытую строку надо снова добавить на target.
     */
    @Synchronized
    fun erase(): Outcome<Unit> = try {
        cached = null
        app?.let { KeyWrap.delete(it, FILE) }
        if (legacy()) store().deleteEntry(ALIAS)
        Outcome.success(Unit)
    } catch (e: Exception) {
        Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "ключ SSH не стёрт", e))
    }

    /** Сменить ключ на новый переносимый (D-184): прежняя строка на target перестаёт подходить. */
    @Synchronized
    fun renew(): Outcome<String> = when (val e = erase()) {
        is Outcome.Failure -> e
        is Outcome.Success -> runCatching { createPortable() }.fold({ Outcome.success(authorizedKey()) },
            { Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "новый ключ SSH не создан", it)) })
    }

    /** Значение для копии под паролем: переносимый ключ; прежний неизвлекаемый — `null`. */
    fun exportValue(): com.dpsmpx.dpai.core.diagnostics.Secret? = loadPortable()?.let { com.dpsmpx.dpai.core.diagnostics.Secret(encode(it)) }

    /** Ключ из копии (D-184): заменяет и прежний неизвлекаемый, и переносимый. */
    @Synchronized
    fun importValue(text: String): Outcome<Unit> {
        val pair = decode(text) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "ключ SSH в копии не читается")
        val base = app ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "приложение не готово")
        if (legacy()) runCatching { store().deleteEntry(ALIAS) }
        return when (val w = KeyWrap.write(base, WRAP, userAuth = false, FILE, encode(pair).toByteArray(Charsets.UTF_8))) {
            is Outcome.Failure -> w
            is Outcome.Success -> { cached = pair; Outcome.success(Unit) }
        }
    }

    fun identity(): EcdsaSignerIdentity = EcdsaSignerIdentity(COMMENT, publicKey()) { data ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(loadPortable()?.private ?: store().getKey(ALIAS, null) as PrivateKey)
            update(data)
            sign()
        }
    }

    /** Строка для `~/.ssh/authorized_keys` на target. */
    fun authorizedKey(): String = SshKeys.authorizedKey(publicKey(), COMMENT)
}

/**
 * Контроллер приложения (D-35): targets приложения, их known_hosts и
 * проверка агента по SSH через JSch с ключом Keystore. Документ targets —
 * `dpai.app-targets` через `DocumentStore` (атомарная замена, D-24).
 * Вызовы сети — с фонового потока.
 */
class Controller(private val root: File, val version: String?) {
    private val vfs = NioVfs(root.toPath())
    private val docs = DocumentStore(vfs, "docs")

    /**
     * Политика прогона и след её задач (D-106): документы контроллера, не
     * копия настроек (D-51) — после переустановки политика выключена.
     */
    val ecosystemAuto = com.dpsmpx.dpai.orchestrator.ecosystem.AutoEcosystem(docs, Clock.SYSTEM)
    val manualProfiles = com.dpsmpx.dpai.ui.ManualProfiles(
        DocumentStore(NioVfs(File(root.parentFile, "manual-profiles").toPath()), "docs"), IdGenerator(Clock.SYSTEM, RandomSource.secure()), Clock.SYSTEM)

    /** Клиент закреплённого target: смена настроек не перенаправит остановку старой команды. */
    fun profileAgent(target: TargetEntry, inApp: com.dpsmpx.dpai.execution.process.ProcessHost?): com.dpsmpx.dpai.execution.agent.AgentClient? {
        val host = if (target.inApp) inApp ?: return null else JschProcessHost(checkNotNull(target.toTarget(knownHostsFile).ssh), target.platform, identities = listOf(KeystoreKey.identity()))
        val t = target.toTarget(knownHostsFile)
        return com.dpsmpx.dpai.execution.agent.AgentClient(host, t.agentCommand, t.agentHome, target.label)
    }

    val knownHostsFile: String = File(root, KNOWN_HOSTS).path

    /** Файлы дерева [root] на ПК-target по SFTP тем же входом, что команды (D-162); [writable] — с записью (D-166). */
    fun pcFiles(target: TargetEntry, root: String, writable: Boolean = false): Outcome<com.dpsmpx.dpai.jsch.SftpTree> {
        if (target.inApp) return Outcome.failure(DiagCode.VALUE_INVALID, "это Linux приложения, не ПК", target.label)
        val ssh = target.toTarget(knownHostsFile).ssh ?: return Outcome.failure(DiagCode.VALUE_INVALID, "у target нет адреса SSH", target.label)
        return com.dpsmpx.dpai.jsch.SftpTree.open(ssh, target.platform, root, listOf(KeystoreKey.identity()), writable = writable)
    }

    /** Команды на ПК-target по SSH тем же входом (D-31): предпросмотр на ПК (D-169). */
    fun pcHost(target: TargetEntry, knownHostsSnapshot: String? = null): Outcome<JschProcessHost> {
        if (target.inApp) return Outcome.failure(DiagCode.VALUE_INVALID, "это Linux приложения, не ПК", target.label)
        val ssh = target.toTarget(knownHostsFile).ssh ?: return Outcome.failure(DiagCode.VALUE_INVALID, "у target нет адреса SSH", target.label)
        return Outcome.success(JschProcessHost(ssh, target.platform, identities = listOf(KeystoreKey.identity()), knownHostsSnapshot = knownHostsSnapshot))
    }
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())

    init {
        root.mkdirs()
    }

    /**
     * Облачная копия, разложенная прошлым запуском (D-107), применяется здесь —
     * при создании контроллера, до первого чтения документов и журнала
     * (свойства инициализируются по порядку: это — после [vfs] и до всего,
     * что читает данные). Итог: `true` — применена; отказ — с причиной.
     */
    val cloudRestored: com.dpsmpx.dpai.core.result.Outcome<Boolean> = if (manualProfiles.blocked) Outcome.success(false) else com.dpsmpx.dpai.sync.CloudRestore(vfs, Clock.SYSTEM).applyPending()

    /** Каталог данных контура — для облачной копии (D-107). */
    val dataRoot: File get() = root

    /**
     * Настройки восстановлены из прежней целой версии (`STORAGE_RECOVERED_LKG`) —
     * об этом стоит сказать человеку: последняя правка targets не сохранилась
     * (D-87). Список заполняется чтением [targets] и [project].
     */
    val recoveredNotices: List<com.dpsmpx.dpai.core.diagnostics.Diagnostic> get() = synchronized(notices) { notices.values.toList() }

    /**
     * По одной находке на код и предмет, не больше [MAX_NOTICES] (аудит
     * стабильности 2 ноября 2026, A-3): настройки читаются около раза в
     * секунду, и одно «прочитана прежняя версия» дописывалось каждый раз —
     * за недели список съедал память.
     */
    private val notices = LinkedHashMap<Pair<String, String?>, com.dpsmpx.dpai.core.diagnostics.Diagnostic>()

    private fun notice(ds: List<com.dpsmpx.dpai.core.diagnostics.Diagnostic>) = synchronized(notices) {
        for (d in ds) {
            notices.remove(d.code.name to d.subject)
            notices[d.code.name to d.subject] = d
        }
        while (notices.size > MAX_NOTICES) notices.remove(notices.keys.first())
    }

    /**
     * Документ настроек целиком: предупреждения чтения — в [recoveredNotices],
     * а не в мусор. Первое — прочитан ли документ (нет его — тоже «да»).
     */
    private fun <T> bookDocument(key: String, parse: (com.dpsmpx.dpai.core.serialization.JsonObject) -> Outcome<T>): Pair<Boolean, T?> =
        when (val read = docs.read(key)) {
            is Outcome.Success -> {
                notice(read.warnings)
                true to read.value?.let { parse(it.content).valueOrNull() }
            }
            is Outcome.Failure -> {
                notice(read.errors)
                false to null
            }
        }

    /**
     * Targets и проект — в памяти (AI:A-16): экраны спрашивают их часто и
     * из главного потока, а документы меняет только этот контроллер
     * (единственный в процессе). С диска — первое чтение и после неудачной
     * записи; неудачное чтение не запоминается.
     */
    @Volatile
    private var targetsCache: List<TargetEntry>? = null

    @Volatile
    private var projectsCache: com.dpsmpx.dpai.ui.Projects? = null

    @Synchronized
    fun targets(): List<TargetEntry> {
        targetsCache?.let { return it }
        val (ok, value) = bookDocument(BOOK) { TargetBook.fromJson(it) }
        return value.orEmpty().also { if (ok) targetsCache = it }
    }

    /** Записать список targets; кэш — записанное, а при отказе — сброс до чтения с диска. */
    private fun writeTargets(list: List<TargetEntry>) = manualProfiles.edit {
        docs.write(BOOK, TargetBook.toJson(list)).also { targetsCache = if (it is Outcome.Success) list else null }
    }

    fun form(label: String, host: String, port: String, user: String, windows: Boolean, agent: String, configDir: String): Outcome<TargetEntry> =
        TargetForm.validate(ids.next(IdKinds.TARGET), label, host, port, user, windows, agent, configDir)

    /**
     * Рабочие проекты (`DPmetaApp:D-147`; D-179): документ `dpai.app-projects`, а до первой
     * записи — прежний одиночный `dpai.app-project` как список из одного. Неудачное чтение
     * не запоминается.
     */
    @Synchronized
    fun projects(): com.dpsmpx.dpai.ui.Projects {
        projectsCache?.let { return it }
        val (ok, value) = bookDocument(PROJECTS) { com.dpsmpx.dpai.ui.Projects.fromJson(it) }
        if (!ok) return com.dpsmpx.dpai.ui.Projects.EMPTY
        if (value != null) return value.also { projectsCache = it }
        val (legacyOk, legacy) = bookDocument(PROJECT) { com.dpsmpx.dpai.ui.Projects.fromJson(it) }
        val v = legacy ?: com.dpsmpx.dpai.ui.Projects.EMPTY
        if (legacyOk) projectsCache = v
        return v
    }

    /** Выбранный рабочий проект — его показывают экраны (D-179); остальные работают в фоне. */
    fun project(): ProjectEntry? = projects().current

    /** Записать список; прежний одиночный документ после этого не нужен. Отказ — кэш сброшен до чтения с диска. */
    private fun writeProjects(p: com.dpsmpx.dpai.ui.Projects): Outcome<Unit> = manualProfiles.edit {
        when (val w = docs.write(PROJECTS, com.dpsmpx.dpai.ui.Projects.toJson(p))) {
            is Outcome.Failure -> { projectsCache = null; w }
            is Outcome.Success -> {
                projectsCache = p
                if ((docs.read(PROJECT) as? Outcome.Success)?.value != null) docs.delete(PROJECT)
                Outcome.success(Unit)
            }
        }
    }

    /** Добавить или заменить рабочий проект и выбрать его. */
    @Synchronized
    fun putProject(p: ProjectEntry): Outcome<Unit> = when (val n = projects().put(p)) {
        is Outcome.Failure -> n
        is Outcome.Success -> writeProjects(n.value)
    }

    @Synchronized
    fun selectProject(id: com.dpsmpx.dpai.core.ids.ProjectId): Outcome<Unit> = when (val n = projects().select(id)) {
        is Outcome.Failure -> n
        is Outcome.Success -> if (n.value == projects()) Outcome.success(Unit) else writeProjects(n.value)
    }

    @Synchronized
    fun removeProject(id: com.dpsmpx.dpai.core.ids.ProjectId): Outcome<Unit> = writeProjects(projects().remove(id))

    /** [claudeOn] — где идёт ход Claude, если не на [target] (D-50); ключ и known_hosts — пути на нём. */
    fun projectForm(
        target: TargetEntry,
        root: String,
        worktreeRoot: String,
        branch: String,
        check: String,
        claudeOn: TargetEntry? = null,
        identityFile: String = "",
        knownHostsFile: String = "",
    ): Outcome<ProjectEntry> = ProjectForm.validate(ids, target, root, worktreeRoot, branch, check, claudeOn, identityFile, knownHostsFile)

    /** Прежний вход экранов: [p] — добавить или заменить и выбрать; `null` — убрать выбранный. */
    @Synchronized
    fun setProject(p: ProjectEntry?): Outcome<Unit> =
        if (p != null) putProject(p) else projects().current?.let { removeProject(it.project) } ?: Outcome.success(Unit)

    /** Настоящий контроллер над [targets]: SSH — JSch с ключом Keystore, журнал — в каталоге контроллера. */
    /** Разрешены ли уведомления приложения (D-36) — сигнал самодиагностики; задаёт экран. */
    @Volatile
    var notificationsOk: () -> Boolean? = { null }

    fun live(
        targets: List<TargetEntry>,
        project: ProjectEntry?,
        onEvents: (List<com.dpsmpx.dpai.core.events.EventEnvelope>) -> Unit = {},
        onProblem: (Diagnostic) -> Unit = {},
        /** Агент target «в приложении» (D-75, шаг 5); `null` — таких targets нет или Linux не установлен. */
        inAppHost: com.dpsmpx.dpai.execution.process.ProcessHost? = null,
        /** Процессы гостя — Git проекта на target «в приложении». */
        inAppGuest: com.dpsmpx.dpai.execution.process.ProcessHost? = null,
        /** Сессии контуров других рабочих проектов (D-179). */
        neighbours: com.dpsmpx.dpai.orchestrator.NeighbourLoad = com.dpsmpx.dpai.orchestrator.NeighbourLoad.NONE,
    ): LiveSystem {
        // Журнал контура — свой у каждого рабочего проекта; первый (прежний) — в `live/` (D-179).
        val dir = NioVfs(File(root, project?.let { projects().journalDir(it.project) } ?: "live").apply { mkdirs() }.toPath())
        // Карантин хвоста журнала — только после «да» человека и только на одну сборку (D-24).
        val confirmed = dir.exists(QUARANTINE_CONFIRMED)
        return LiveSystem(
            Clock.SYSTEM, dir, targets,
            { e, p -> JschProcessHost(e, p, identities = listOf(KeystoreKey.identity())) }, knownHostsFile,
            controllerVersion = version,
            project = project,
            onEvents = onEvents,
            onProblem = onProblem,
            notificationsOk = notificationsOk,
            quarantineConfirmed = confirmed,
            inAppHost = inAppHost,
            inAppGuest = inAppGuest,
            externalPause = { manualProfiles.blocked },
            neighbours = neighbours,
        ).also { if (confirmed) runCatching { dir.delete(QUARANTINE_CONFIRMED) } }
    }

    /**
     * Человек подтвердил: журнал испорчен в середине, хвост после порчи — в
     * карантин (D-24; аудит надёжности, A-1). Следующая сборка контура
     * откроет журнал с карантином; отметка одноразовая.
     */
    fun confirmJournalQuarantine() {
        NioVfs(File(root, "live").apply { mkdirs() }.toPath()).write(QUARANTINE_CONFIRMED, "подтверждено человеком\n".toByteArray())
    }

    /**
     * Строка открытого ключа приложения; копия — в `app_key.pub` каталога
     * контроллера: ключ не секретный, а файл проще перенести на target,
     * чем переписать строку.
     */
    fun publicKeyLine(): String {
        val line = KeystoreKey.authorizedKey()
        runCatching { vfs.write(APP_KEY, "$line\n".toByteArray()) }
        return line
    }

    /** Ключ хоста — для сверки отпечатка человеком; в known_hosts он попадает только через [add]. */
    fun probe(entry: TargetEntry): Outcome<ProbedHostKey> = HostKeyProbe.fetch(entry.host, entry.port)

    /** Сохранить target вместе с подтверждённым ключом его хоста. */
    @Synchronized
    fun add(entry: TargetEntry, confirmed: ProbedHostKey): Outcome<Unit> {
        try {
            KnownHosts.save(vfs, KNOWN_HOSTS, confirmed.knownHostsLine)
        } catch (e: java.io.IOException) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "known_hosts не записан", e, KNOWN_HOSTS))
        }
        return when (val w = writeTargets(targets().filter { it.id != entry.id } + entry)) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    /**
     * Ещё один слот «в приложении» (D-75, шаг 5; D-29): target без SSH с
     * каталогом слота `/root/.claude-slot-N` в госте — следующий свободный
     * номер. Ключа хоста нет — сверять нечего.
     */
    @Synchronized
    fun addInApp(id: com.dpsmpx.dpai.core.ids.ExecutionTargetId, executor: com.dpsmpx.dpai.core.ids.Executor = com.dpsmpx.dpai.core.ids.Executor.CLAUDE): Outcome<TargetEntry> {
        val used = targets().filter { it.inApp && it.executor == executor }.mapNotNull { it.configDir?.substringAfterLast("-")?.toIntOrNull() }.toSet()
        val n = (1..99).firstOrNull { it !in used } ?: return Outcome.failure(DiagCode.VALUE_INVALID, "слотов «в приложении» — не больше 99")
        val label = if (executor == com.dpsmpx.dpai.core.ids.Executor.CLAUDE) "Linux в приложении · слот $n" else "Linux в приложении · ${executor.title} $n"
        val entry = when (val e = TargetForm.inApp(id, label, com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment.slotDir(n, executor), executor)) {
            is Outcome.Failure -> return e
            is Outcome.Success -> e.value
        }
        return when (val w = writeTargets(targets() + entry)) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(entry)
        }
    }

    /** «Держать сессию в tmux» (D-111): настройка target; остальное не меняется. */
    @Synchronized
    fun setTmux(entry: TargetEntry, on: Boolean): Outcome<Unit> =
        when (val w = writeTargets(targets().map { if (it.id == entry.id) it.copy(tmux = on) else it })) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit)
        }

    /**
     * Мест для сессий ИИ на target (`DPmetaApp:D-148`; D-180): 1…4; запись списка targets
     * пересобирает контуры, идущие сессии продолжаются.
     */
    @Synchronized
    fun setSessions(entry: TargetEntry, n: Int): Outcome<Unit> {
        if (n !in com.dpsmpx.dpai.ui.TargetForm.SESSIONS) return Outcome.failure(DiagCode.VALUE_INVALID, "мест — от 1 до 4", "sessions")
        return when (val w = writeTargets(targets().map { if (it.id == entry.id) it.copy(sessions = n) else it })) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    /** Переименовать target (слот): имя — от 1 до 60 знаков, как в форме; остальное не меняется. */
    @Synchronized
    fun rename(entry: TargetEntry, label: String): Outcome<Unit> {
        val name = label.trim()
        if (name.isEmpty() || name.length > 60 || name.any { it.isISOControl() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "имя — от 1 до 60 знаков", "label")
        }
        return when (val w = writeTargets(targets().map { if (it.id == entry.id) it.copy(label = name) else it })) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    @Synchronized
    fun remove(entry: TargetEntry): Outcome<Unit> =
        when (val w = writeTargets(targets().filter { it.id != entry.id })) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit)
        }

    /** Настройки для копии (D-51): targets, проект, ключи хостов. */
    @Synchronized
    fun snapshot(): SettingsSnapshot = SettingsSnapshot(targets(), project(), vfs.read(KNOWN_HOSTS)?.toString(Charsets.UTF_8).orEmpty())

    /**
     * Настройки — как в копии (D-51): targets, проект и ключи хостов
     * заменяются целиком. known_hosts — атомарно (A-85).
     */
    @Synchronized
    fun restore(s: SettingsSnapshot): Outcome<Unit> = manualProfiles.edit { restoreSettings(s) }

    private fun restoreSettings(s: SettingsSnapshot): Outcome<Unit> {
        try {
            val tmp = "$KNOWN_HOSTS.tmp"
            vfs.write(tmp, s.knownHosts.toByteArray(Charsets.UTF_8))
            vfs.fsync(tmp)
            vfs.rename(tmp, KNOWN_HOSTS)
        } catch (e: java.io.IOException) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "known_hosts не записан", e, KNOWN_HOSTS))
        }
        writeTargets(s.targets).let { if (it is Outcome.Failure) return it }
        return setProject(s.project)
    }

    /**
     * Связать Claude в Termux [termux] с деревом на ПК [pc] (D-53): ключ в
     * proot, его строка на ПК, ключ хоста ПК (уже подтверждённый) в
     * known_hosts proot, проверка входа. Каждый шаг — короткая команда по
     * SSH с ключом приложения; [progress] — что сейчас идёт, для экрана.
     */
    fun linkWorkspace(termux: TargetEntry, pc: TargetEntry, progress: (String) -> Unit = {}): Outcome<Unit> {
        fun host(e: TargetEntry) = JschProcessHost(checkNotNull(e.toTarget(knownHostsFile).ssh), e.platform, identities = listOf(KeystoreKey.identity()))
        fun step(e: TargetEntry, what: String, command: List<String>, stdin: String? = null, timeout: kotlin.time.Duration = 1.minutes, accept: (Completed) -> Boolean = { it.ok }): Outcome<Completed> {
            progress(what)
            return when (val r = host(e).run(ProcessSpec(command, stdin = stdin), timeout)) {
                is Outcome.Failure -> Outcome.Failure(listOf(Diagnostic.of(DiagCode.TARGET_CHECK_FAILED, "$what: не выполнено", causes = r.diagnostics)))
                is Outcome.Success -> if (accept(r.value)) {
                    r
                } else {
                    val tail = (r.value.stderr + r.value.stdout).lastOrNull { it.isNotBlank() }.orEmpty()
                    Outcome.failure(DiagCode.TARGET_CHECK_FAILED, "$what: код ${r.value.exitCode} $tail".trim(), e.label)
                }
            }
        }
        val hostLines = WorkspaceLink.hostLines(vfs.read(KNOWN_HOSTS)?.toString(Charsets.UTF_8).orEmpty(), pc)
        if (hostLines.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "ключ хоста ${pc.label} не подтверждён: удалите и добавьте target заново", pc.label)
        val made = when (val r = step(termux, "ключ в Termux", WorkspaceLink.termuxKey(), timeout = 3.minutes)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val key = WorkspaceLink.publicKey(made.stdout) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "Termux не вернул открытый ключ", termux.label)
        step(pc, "ключ на ${pc.label}", WorkspaceLink.pcAuthorize(key, pc.platform)).let { if (it is Outcome.Failure) return it }
        step(termux, "ключ хоста ${pc.label} в Termux", WorkspaceLink.termuxKnownHosts(pc), stdin = hostLines.joinToString("\n", postfix = "\n"))
            .let { if (it is Outcome.Failure) return it }
        // Ответ агента — строка JSON и при `"ok":false` (на ПК нет Claude Code — так и задумано): важен вход ключом.
        val check = when (val c = WorkspaceLink.termuxCheck(pc)) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        return when (val r = step(termux, "вход с Termux на ${pc.label}", check, accept = { WorkspaceLink.checked(it.stdout) })) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    /** Раздел «Инструкции» с командой настройки ПК: 10 — Windows, 11 — Linux (D-70). */
    private fun guideSection(pc: TargetEntry): Int = if (pc.platform == com.dpsmpx.dpai.execution.target.TargetPlatform.WINDOWS) 10 else 11

    /** Копия настроек — и в Termux (D-54): переживает удаление приложения и читается без выбора файла. */
    fun mirrorSettings(text: String): Outcome<Unit> {
        val termux = targets().firstOrNull { it.host == TermuxRestore.HOST && it.port == TermuxRestore.PORT }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "нет target Termux")
        val r = JschProcessHost(checkNotNull(termux.toTarget(knownHostsFile).ssh), termux.platform, identities = listOf(KeystoreKey.identity()))
            .run(ProcessSpec(TermuxRestore.mirror(), stdin = text), 30.seconds)
        return when {
            r is Outcome.Failure -> r
            (r as Outcome.Success).value.ok -> Outcome.success(Unit)
            else -> Outcome.failure(DiagCode.STORAGE_IO_FAILED, "копия в Termux не записана: код ${r.value.exitCode}", termux.label)
        }
    }

    /**
     * Восстановление после переустановки (D-54): человек вставил в Termux
     * команду с кодом [nonce] ([TermuxRestore.command]). Ключ хоста Termux
     * принимается, только если сервер вернул этот код; затем — настройки из
     * копии в Termux (если своих нет), targets по слотам со входом Claude,
     * новый ключ приложения на ПК через ключ связки (D-53). [termuxUser] —
     * пользователь Termux по его uid. Итог — строки отчёта.
     */
    fun restoreAccess(nonce: String, termuxUser: String, progress: (String) -> Unit = {}): Outcome<List<String>> = synchronized(restoring) {
        restoreAccessLocked(nonce, termuxUser, progress)
    }

    /** Одно восстановление за раз; общий замок контроллера не держится минутами сети. */
    private val restoring = Any()

    private fun restoreAccessLocked(nonce: String, termuxUser: String, progress: (String) -> Unit): Outcome<List<String>> {
        progress("ключ хоста Termux")
        val probed = when (val p = HostKeyProbe.fetch(TermuxRestore.HOST, TermuxRestore.PORT)) {
            is Outcome.Failure -> return Outcome.failure(DiagCode.TARGET_CHECK_FAILED, "sshd Termux не отвечает: вставьте команду в Termux", "127.0.0.1:${TermuxRestore.PORT}")
            is Outcome.Success -> p.value
        }
        val pending = "$KNOWN_HOSTS.restore"
        val found = try {
            vfs.write(pending, (probed.knownHostsLine + "\n").toByteArray())
            val probe = TargetForm.validate(ids.next(IdKinds.TARGET), "Termux", TermuxRestore.HOST, TermuxRestore.PORT.toString(), termuxUser, false, SetupGuide.AGENT).valueOrNull()
                ?: return Outcome.failure(DiagCode.VALUE_INVALID, "пользователь Termux недопустим: $termuxUser")
            progress("вход в Termux")
            val host = JschProcessHost(checkNotNull(probe.toTarget(File(root, pending).path).ssh), probe.platform, identities = listOf(KeystoreKey.identity()))
            when (val r = host.run(ProcessSpec(TermuxRestore.discover()), 2.minutes)) {
                is Outcome.Failure -> return Outcome.Failure(listOf(Diagnostic.of(DiagCode.TARGET_CHECK_FAILED, "вход в Termux не удался: вставьте команду в Termux", causes = r.diagnostics)))
                is Outcome.Success -> TermuxRestore.parse(r.value.stdout)
            }
        } finally {
            runCatching { vfs.delete(pending) }
        }
        if (!TermuxRestore.codeMatches(found, nonce)) {
            return Outcome.failure(DiagCode.SSH_HOST_KEY_REJECTED, "код не совпал — ключ хоста не принят: скопируйте команду заново и вставьте в Termux")
        }
        progress("настройки")
        val (merged, report) = TermuxRestore.merge(snapshot(), found, probed.knownHostsLine, ids)
        restore(merged).let { if (it is Outcome.Failure) return it }
        val lines = report.toMutableList()
        val termux = merged.targets.first { it.host == TermuxRestore.HOST && it.port == TermuxRestore.PORT }
        val shell = JschProcessHost(checkNotNull(termux.toTarget(knownHostsFile).ssh), termux.platform, identities = listOf(KeystoreKey.identity()))
        shell.run(ProcessSpec(TermuxRestore.cleanup()), 30.seconds)
        val key = publicKeyLine()
        for (pc in merged.targets.filter { it.host != TermuxRestore.HOST && it.host != "localhost" }) {
            if (!found.workspace) {
                lines += "${pc.label}: ключа связки в Termux нет — новый ключ приложения добавьте командой из «Инструкция → ${guideSection(pc)}»"
                continue
            }
            progress("ключ приложения на ${pc.label}")
            val r = shell.run(ProcessSpec(TermuxRestore.pcReauthorize(pc, key)), 1.minutes)
            lines += when {
                r is Outcome.Success && TermuxRestore.reauthorized(r.value.stdout) -> "${pc.label}: новый ключ приложения добавлен"
                r is Outcome.Success -> "${pc.label}: не вышло (${(r.value.stderr + r.value.stdout).lastOrNull { it.isNotBlank() } ?: "код ${r.value.exitCode}"}) — команда из «Инструкция → ${guideSection(pc)}»"
                else -> "${pc.label}: не вышло (${(r as Outcome.Failure).diagnostics.first().message}) — команда из «Инструкция → ${guideSection(pc)}»"
            }
        }
        mirrorSettings(com.dpsmpx.dpai.ui.SettingsBackup.write(merged, Clock.SYSTEM.now().epochMillis, version))
        lines += "доступ к Termux восстановлен; targets: ${merged.targets.size}"
        return Outcome.success(lines)
    }

    /** `dpai agent doctor` на target — по SSH с ключом приложения; «в приложении» — агентом процесса [inAppHost] (D-75). */
    fun doctor(entry: TargetEntry, inAppHost: com.dpsmpx.dpai.execution.process.ProcessHost? = null): Outcome<DoctorResult> {
        val manager = ExecutionTargetManager(
            Clock.SYSTEM,
            sshHost = { e, p -> JschProcessHost(e, p, identities = listOf(KeystoreKey.identity())) },
            controllerVersion = version,
            inAppHost = inAppHost,
        )
        manager.register(entry.toTarget(knownHostsFile))
        return manager.doctor(entry.id)
    }

    companion object {
        private const val MAX_NOTICES = 20

        /** Отметка «карантин хвоста журнала подтверждён» — в каталоге контура (D-24). */
        private const val QUARANTINE_CONFIRMED = "quarantine-confirmed"
        private const val BOOK = "targets"
        private const val PROJECT = "project"
        private const val PROJECTS = "projects"
        private const val KNOWN_HOSTS = "known_hosts"
        private const val APP_KEY = "app_key.pub"

        @Volatile
        private var instance: Controller? = null

        /**
         * Одна система на процесс; версия — подсистемы «AI» ([BuildConfig.DPAI_VERSION], D-103): с ней
         * `doctor` сверяет версию агента. Кто бы ни создал контроллер первым (экран, служба после
         * перезапуска, терминал), версия та же — и в приложении DPmetaApp, где у APK своя версия.
         */
        fun get(filesDir: File): Controller = instance ?: synchronized(this) {
            instance ?: Controller(File(filesDir, "controller"), BuildConfig.DPAI_VERSION).also { instance = it }
        }
    }
}

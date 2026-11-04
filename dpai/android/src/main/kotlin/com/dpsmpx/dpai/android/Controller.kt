package com.dpsmpx.dpai.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
 * Ключ входа приложения (D-35): ECDSA P-256 в Android Keystore. Закрытая
 * часть не покидает устройство и не попадает ни в журнал, ни в копию
 * (D-14, T6); на target уходит только строка открытого ключа.
 */
object KeystoreKey {
    private const val ALIAS = "dpai-ssh"
    const val COMMENT = "dpai-android"

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun publicKey(): ECPublicKey {
        val ks = store()
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
        return ks.getCertificate(ALIAS).publicKey as ECPublicKey
    }

    fun identity(): EcdsaSignerIdentity = EcdsaSignerIdentity(COMMENT, publicKey()) { data ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(store().getKey(ALIAS, null) as PrivateKey)
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
    val knownHostsFile: String = File(root, KNOWN_HOSTS).path
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())

    init {
        root.mkdirs()
    }

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

    /** Документ настроек целиком: предупреждения чтения — в [recoveredNotices], а не в мусор. */
    private fun <T> bookDocument(key: String, parse: (com.dpsmpx.dpai.core.serialization.JsonObject) -> Outcome<T>): T? =
        when (val read = docs.read(key)) {
            is Outcome.Success -> {
                notice(read.warnings)
                read.value?.let { parse(it.content).valueOrNull() }
            }
            is Outcome.Failure -> {
                notice(read.errors)
                null
            }
        }

    @Synchronized
    fun targets(): List<TargetEntry> = bookDocument(BOOK) { TargetBook.fromJson(it) }.orEmpty()

    fun form(label: String, host: String, port: String, user: String, windows: Boolean, agent: String, configDir: String): Outcome<TargetEntry> =
        TargetForm.validate(ids.next(IdKinds.TARGET), label, host, port, user, windows, agent, configDir)

    /** Проект контроллера (один): репозиторий на target, деревья задач, проверка. */
    @Synchronized
    fun project(): ProjectEntry? = bookDocument(PROJECT) { ProjectBook.fromJson(it) }

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

    @Synchronized
    fun setProject(p: ProjectEntry?): Outcome<Unit> {
        val r = if (p == null) docs.delete(PROJECT) else docs.write(PROJECT, ProjectBook.toJson(p)).let { w -> if (w is Outcome.Failure) w else Outcome.success(Unit) }
        return r
    }

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
    ): LiveSystem {
        val dir = NioVfs(File(root, "live").apply { mkdirs() }.toPath())
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
        return when (val w = docs.write(BOOK, TargetBook.toJson(targets().filter { it.id != entry.id } + entry))) {
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
        return when (val w = docs.write(BOOK, TargetBook.toJson(targets() + entry))) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(entry)
        }
    }

    /** Переименовать target (слот): имя — от 1 до 60 знаков, как в форме; остальное не меняется. */
    @Synchronized
    fun rename(entry: TargetEntry, label: String): Outcome<Unit> {
        val name = label.trim()
        if (name.isEmpty() || name.length > 60 || name.any { it.isISOControl() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "имя — от 1 до 60 знаков", "label")
        }
        return when (val w = docs.write(BOOK, TargetBook.toJson(targets().map { if (it.id == entry.id) it.copy(label = name) else it }))) {
            is Outcome.Failure -> w
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    @Synchronized
    fun remove(entry: TargetEntry): Outcome<Unit> =
        when (val w = docs.write(BOOK, TargetBook.toJson(targets().filter { it.id != entry.id }))) {
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
    fun restore(s: SettingsSnapshot): Outcome<Unit> {
        try {
            val tmp = "$KNOWN_HOSTS.tmp"
            vfs.write(tmp, s.knownHosts.toByteArray(Charsets.UTF_8))
            vfs.fsync(tmp)
            vfs.rename(tmp, KNOWN_HOSTS)
        } catch (e: java.io.IOException) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "known_hosts не записан", e, KNOWN_HOSTS))
        }
        docs.write(BOOK, TargetBook.toJson(s.targets)).let { if (it is Outcome.Failure) return it }
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

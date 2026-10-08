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
import com.dpsmpx.dpai.ui.TargetBook
import com.dpsmpx.dpai.ui.TargetEntry
import com.dpsmpx.dpai.ui.TargetForm
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

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
class Controller(private val root: File, private val version: String?) {
    private val vfs = NioVfs(root.toPath())
    private val docs = DocumentStore(vfs, "docs")
    val knownHostsFile: String = File(root, KNOWN_HOSTS).path
    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())

    init {
        root.mkdirs()
    }

    @Synchronized
    fun targets(): List<TargetEntry> {
        val doc = docs.read(BOOK).valueOrNull() ?: return emptyList()
        return TargetBook.fromJson(doc.content).valueOrNull().orEmpty()
    }

    fun form(label: String, host: String, port: String, user: String, windows: Boolean, agent: String, configDir: String): Outcome<TargetEntry> =
        TargetForm.validate(ids.next(IdKinds.TARGET), label, host, port, user, windows, agent, configDir)

    /** Проект контроллера (один): репозиторий на target, деревья задач, проверка. */
    @Synchronized
    fun project(): ProjectEntry? = docs.read(PROJECT).valueOrNull()?.let { ProjectBook.fromJson(it.content).valueOrNull() }

    fun projectForm(target: TargetEntry, root: String, worktreeRoot: String, branch: String, check: String): Outcome<ProjectEntry> =
        ProjectForm.validate(ids, target, root, worktreeRoot, branch, check)

    @Synchronized
    fun setProject(p: ProjectEntry?): Outcome<Unit> {
        val r = if (p == null) docs.delete(PROJECT) else docs.write(PROJECT, ProjectBook.toJson(p)).let { w -> if (w is Outcome.Failure) w else Outcome.success(Unit) }
        return r
    }

    /** Настоящий контроллер над [targets]: SSH — JSch с ключом Keystore, журнал — в каталоге контроллера. */
    fun live(
        targets: List<TargetEntry>,
        project: ProjectEntry?,
        onEvents: (List<com.dpsmpx.dpai.core.events.EventEnvelope>) -> Unit = {},
        onProblem: (Diagnostic) -> Unit = {},
    ): LiveSystem = LiveSystem(
        Clock.SYSTEM, NioVfs(File(root, "live").apply { mkdirs() }.toPath()), targets,
        { e, p -> JschProcessHost(e, p, identities = listOf(KeystoreKey.identity())) }, knownHostsFile,
        controllerVersion = version,
        project = project,
        onEvents = onEvents,
        onProblem = onProblem,
    )

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
            val current = vfs.read(KNOWN_HOSTS)?.toString(Charsets.UTF_8).orEmpty()
            vfs.write(KNOWN_HOSTS, KnownHosts.add(current, confirmed.knownHostsLine).toByteArray())
            vfs.fsync(KNOWN_HOSTS)
        } catch (e: java.io.IOException) {
            return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "known_hosts не записан", e, KNOWN_HOSTS))
        }
        return when (val w = docs.write(BOOK, TargetBook.toJson(targets().filter { it.id != entry.id } + entry))) {
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

    /** `dpai agent doctor` на target — по SSH с ключом приложения. */
    fun doctor(entry: TargetEntry): Outcome<DoctorResult> {
        val manager = ExecutionTargetManager(
            Clock.SYSTEM,
            sshHost = { e, p -> JschProcessHost(e, p, identities = listOf(KeystoreKey.identity())) },
            controllerVersion = version,
        )
        manager.register(entry.toTarget(knownHostsFile))
        return manager.doctor(entry.id)
    }

    companion object {
        private const val BOOK = "targets"
        private const val PROJECT = "project"
        private const val KNOWN_HOSTS = "known_hosts"
        private const val APP_KEY = "app_key.pub"

        @Volatile
        private var instance: Controller? = null

        /** [version] — версия приложения: `doctor` сверяет с ней версию агента. */
        fun get(filesDir: File, version: String?): Controller = instance ?: synchronized(this) {
            instance ?: Controller(File(filesDir, "controller"), version).also { instance = it }
        }
    }
}

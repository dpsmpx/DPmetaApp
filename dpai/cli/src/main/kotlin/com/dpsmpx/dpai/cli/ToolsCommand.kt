package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.tools.SharedTools
import com.dpsmpx.dpai.execution.tools.SharedToolsStatus
import com.dpsmpx.dpai.orchestrator.agent.SharedToolsStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

/** Настройка и проверенная синхронизация target; аккаунт только в stdin (D-175). */
class ToolsCommand(
    private val out: (String) -> Unit,
    private val err: (String) -> Unit,
    private val windows: Boolean = File.separatorChar == '\\',
    private val input: InputStream = System.`in`,
    private val host: ProcessHost = LocalProcessHost(),
) {
    fun run(args: List<String>): Int {
        val action = args.firstOrNull()
        if (action !in setOf("configure", "status", "disable", "init", "sync")) return usage()
        val tail = args.drop(1)
        val values = linkedMapOf<String, String>()
        var i = 0
        while (i < tail.size) {
            val name = tail[i++]
            if (name in values) return usage()
            if (name in setOf("--json", "--github-stdin")) values[name] = "true" else {
                if (name !in setOf("--home", "--repo", "--controller", "--python") || i == tail.size) return usage()
                values[name] = tail[i++]
            }
        }
        val allowed = when (action) {
            "status" -> setOf("--home", "--json")
            "disable" -> setOf("--home")
            "configure" -> setOf("--home", "--repo", "--controller", "--python")
            else -> setOf("--home", "--repo", "--python", "--github-stdin")
        }
        if (values.keys.any { it !in allowed }) return usage()
        val home = File(values["--home"] ?: AgentCommand.defaultHome()).absoluteFile.toPath()
        val store = SharedToolsStore(NioVfs(home))
        if (action == "status") return status(store, values.containsKey("--json"))
        // Межпроцессный guard: поздняя синхронизация не отменит disable/configure.
        return try {
            val files = NioVfs(home)
            files.mkdirs("")
            val lease = files.tryLease("shared-tools-operation.lock") ?: run { err("Настройка или синхронизация DP-Verify уже выполняется"); return 75 }
            lease.use { modify(action!!, values, home, store) }
        } catch (_: java.io.IOException) {
            err("Не удалось прочитать или сохранить файлы DP-Verify; проверьте права и место")
            1
        }
    }

    private fun status(store: SharedToolsStore, json: Boolean): Int = when (val read = store.read()) {
        is Outcome.Failure -> { read.diagnostics.forEach { err("${it.code}: ${it.message}") }; 1 }
        is Outcome.Success -> {
            if (json) out(CanonicalJson.write(SharedToolsStatus(windows, File(System.getProperty("user.home"), "dp-verify").absolutePath, read.value).toJson())) else {
                read.value?.let { out("Папка: ${it.repository}"); out("Контроллер: ${it.controller}"); out("Python: ${it.python}") }
                    ?: out("Общие инструменты: выключены")
            }
            Cli.OK
        }
    }

    private fun modify(action: String, values: Map<String, String>, home: Path, store: SharedToolsStore): Int {
        if (action in setOf("init", "sync")) return synchronize(action, values, home, store)
        val result = when (action) {
            "configure" -> {
                val repository = values["--repo"] ?: return usage()
                val controller = values["--controller"] ?: return usage()
                val value = runCatching { SharedTools(repository, controller, values["--python"] ?: if (windows) "python" else "python3") }
                    .getOrElse { err(it.message ?: "неверная настройка"); return Cli.USAGE_ERROR }
                if (value.windows != windows) {
                    err("Пути общих инструментов не соответствуют платформе target")
                    return 1
                }
                store.write(value)
            }
            else -> store.disable()
        }
        if (result is Outcome.Failure) {
            result.diagnostics.forEach { err("${it.code}: ${it.message}") }
            return 1
        }
        out(if (action == "disable") "Общие инструменты выключены для новых ходов" else "Общие инструменты настроены для новых ходов")
        return Cli.OK
    }

    private fun synchronize(action: String, values: Map<String, String>, home: Path, store: SharedToolsStore): Int {
        val current = when (val r = store.read()) {
            is Outcome.Failure -> { err("Настройка DP-Verify повреждена; исправьте её перед синхронизацией"); return 1 }
            is Outcome.Success -> r.value
        }
        if ((action == "init") != (current == null)) {
            err(if (current == null) "Сначала подключите общие инструменты" else "Папка уже подключена; используйте sync")
            return 1
        }
        val repository = values["--repo"] ?: current?.repository ?: return usage()
        val python = values["--python"] ?: current?.python ?: if (windows) "python" else "python3"
        val source = javaClass.getResourceAsStream("/shared-verify/runtime.py")?.use { it.readBytes() }
            ?: run { err("В агенте нет контроллера; обновите агент dpai"); return 1 }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val managed = home.resolve("shared-tools-controller")
        val controller = managed.resolve("runtime-${hash(source)}.py")
        val tools = runCatching { SharedTools(repository, controller.toString(), python) }.getOrElse {
            err("Папка и контроллер должны иметь абсолютные пути одной платформы"); return Cli.USAGE_ERROR
        }
        val repo = File(repository).toPath().toAbsolutePath().normalize()
        val state = managed.resolve("state-${hash(repository.toByteArray(Charsets.UTF_8))}")
        if (tools.windows != windows || managed.toAbsolutePath().normalize().startsWith(repo) || repo.startsWith(managed.toAbsolutePath().normalize())) {
            err("Контроллер и состояние должны находиться вне папки инструментов и соответствовать платформе")
            return 1
        }
        if (current != null && (current.repository != repository || current.python != python)) {
            err("Папка или Python изменились; сначала отключите прежнее подключение")
            return 1
        }
        val files = NioVfs(managed)
        files.mkdirs("")
        if (Files.isSymbolicLink(managed) || Files.isSymbolicLink(controller)) {
            err("Каталог контроллера не должен быть символьной ссылкой"); return 1
        }
        if (Files.exists(controller)) {
            if (!Files.readAllBytes(controller).contentEquals(source)) { err("Контроллер изменён; файл сохранён без перезаписи"); return 1 }
        } else {
            val name = controller.fileName.toString()
            files.writeFresh("$name.tmp", source)
            files.fsync("$name.tmp")
            files.rename("$name.tmp", name)
            files.fsyncDir("")
        }
        val credential = if (values.containsKey("--github-stdin")) {
            val bytes = input.readNBytes(8193)
            if (bytes.size > 8192) { err("Слишком большой запрос аккаунта"); return Cli.USAGE_ERROR }
            bytes.toString(Charsets.UTF_8)
        } else null
        val operation = if (action == "init" && !Files.exists(repo.resolve(".git"))) "init" else "sync"
        val command = listOf(python, controller.toString(), "--repo", repository, operation, "--state", state.toString()) +
            if (credential != null) listOf("--github-stdin") else emptyList()
        val completed = when (val r = host.run(ProcessSpec(command, stdin = credential), 30.minutes, maxStdoutChars = 8192, maxStderrChars = 0)) {
            is Outcome.Failure -> { err("Синхронизация не завершена; проверьте Python, Git, права и сеть"); return 1 }
            is Outcome.Success -> r.value
        }
        val reply = (JsonParser().parse(completed.stdout.lastOrNull().orEmpty()) as? Outcome.Success)?.value as? JsonObject
        if (!completed.ok) {
            val reason = (reply?.get("reason") as? JsonString)?.value
            val known = setOf("tools_in_use", "validation_failed", "local_remote_changes", "diverged", "author_identity_required", "tools_already_exist", "unexpected_remote", "main_required", "invalid_account", "io_failed")
            out(CanonicalJson.write(JsonObject.of("status" to JsonString("refused"), "reason" to JsonString(reason?.takeIf { it in known } ?: "operation_failed"))))
            return if (completed.exitCode == 75) 75 else 1
        }
        val revision = (reply?.get("revision") as? JsonString)?.value
        if (revision == null || !revision.matches(Regex("[0-9a-f]{40}")) || completed.truncated) {
            err("Контроллер не подтвердил проверенную версию"); return 1
        }
        if (store.write(tools) is Outcome.Failure) { err("Версия проверена, но подключение к ИИ не сохранено"); return 1 }
        out(CanonicalJson.write(JsonObject.of("status" to JsonString("verified"), "revision" to JsonString(revision))))
        return Cli.OK
    }

    private fun usage(): Int {
        err(USAGE)
        return Cli.USAGE_ERROR
    }

    companion object {
        const val USAGE = "dpai tools configure --repo ABS --controller ABS [--python PROGRAM] [--home DIR]\n" +
            "dpai tools status [--json] [--home DIR]\n" +
            "dpai tools init --repo ABS [--python PROGRAM] [--github-stdin] [--home DIR]\n" +
            "dpai tools sync [--repo ABS] [--python PROGRAM] [--github-stdin] [--home DIR]\n" +
            "dpai tools disable [--home DIR]"
    }
}

package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.execution.target.CheckStatus
import com.dpsmpx.dpai.execution.target.DoctorResult
import com.dpsmpx.dpai.execution.target.ExecutionTarget
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import com.dpsmpx.dpai.storage.vfs.Vfs

/**
 * Target, заведённый в приложении (D-35): куда ходить по SSH и чем
 * вызывать агента. Ключ входа — ключ приложения, ключи хостов — общий
 * known_hosts приложения; здесь их нет.
 */
data class TargetEntry(
    val id: ExecutionTargetId,
    val label: String,
    val host: String,
    val port: Int,
    val user: String,
    val platform: TargetPlatform,
    val agent: String,
    /**
     * Каталог конфигурации Claude слота на target (`CLAUDE_CONFIG_DIR`,
     * D-29) — абсолютный путь; вход в Claude делается сразу в него.
     * `null` — target заведён до v2 формата: только проверка агента, без
     * сессий.
     */
    val configDir: String? = null,
    /**
     * Target «в приложении» (D-75, шаг 5): Alpine под proot в данных
     * приложения, агент — в процессе приложения, без SSH. Каталог слота —
     * путь гостя (`/root/.claude-slot-N`); адреса SSH у него нет.
     */
    val inApp: Boolean = false,
    /**
     * Исполнитель слота target (D-100): Claude Code, Codex или Gemini; его
     * каталог настроек — [configDir] (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`,
     * `GEMINI_CLI_HOME`).
     */
    val executor: Executor = Executor.CLAUDE,
    /**
     * Держать вкладку терминала в `tmux` на target (D-111): по умолчанию —
     * да, и у targets, заведённых раньше; работает только там, где `tmux`
     * есть, и только на POSIX.
     */
    val tmux: Boolean = true,
    /**
     * Мест для одновременных сессий ИИ на target (`DPmetaApp:D-148`; D-180): 1…4, по
     * умолчанию 1. Общие для всех рабочих проектов (D-179).
     */
    val sessions: Int = 1,
) {
    /** Target того же телефона — Termux на `127.0.0.1` (D-26); «в приложении» — Alpine приложения (D-75). */
    val type: TargetType
        get() = when {
            inApp -> TargetType.IN_APP
            platform == TargetPlatform.WINDOWS -> TargetType.WINDOWS_REMOTE
            host == "127.0.0.1" || host == "localhost" -> TargetType.TERMUX_LOCAL
            else -> TargetType.LINUX_SSH
        }

    fun toTarget(knownHostsFile: String): ExecutionTarget =
        if (inApp) {
            ExecutionTarget(id, type, label, platform, null, listOf(agent))
        } else {
            ExecutionTarget(id, type, label, platform, SshEndpoint.of(host, user, port, null, knownHostsFile).valueOrNull()!!, listOf(agent))
        }
}

/** Форма нового target: проверки — те же, что у `SshEndpoint` (параметр `ssh` не подставить, T2). */
object TargetForm {
    /** Допустимое число мест для сессий ИИ на target (D-180). */
    val SESSIONS: IntRange = 1..4

    fun validate(
        id: ExecutionTargetId,
        label: String,
        host: String,
        port: String,
        user: String,
        windows: Boolean,
        agent: String,
        configDir: String? = null,
        executor: Executor = Executor.CLAUDE,
    ): Outcome<TargetEntry> {
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "порт — число от 1 до 65535", "port")
        if (label.isBlank() || label.length > 60) return Outcome.failure(DiagCode.VALUE_INVALID, "имя — от 1 до 60 знаков", "label")
        val a = agent.trim()
        if (a.isEmpty() || a.startsWith("-") || a.any { it.isISOControl() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "команда агента — путь к dpai на target", "agent")
        }
        // Команда уходит на target одним аргументом в кавычках: `~` там не раскрывается (ревью 8 октября 2026).
        if (a.startsWith("~")) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "команда агента — путь без «~»: полный путь, например /data/data/com.termux/files/home/dpai/bin/dpai", "agent")
        }
        // Каталог — как его хранит слот (без `/` в конце): иначе после перезапуска слот не найти.
        val dir = configDir?.trim()?.takeIf { it.isNotEmpty() }?.let { Accounts.normalizeDir(it) ?: it }
        if (dir != null && (!absolute(dir, windows) || dir.any { it.isISOControl() })) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "каталог Claude — абсолютный путь на target", "configDir")
        }
        return when (val e = SshEndpoint.of(host.trim(), user.trim(), p)) {
            is Outcome.Failure -> e
            is Outcome.Success -> Outcome.success(
                TargetEntry(id, label.trim(), e.value.host, p, e.value.user, if (windows) TargetPlatform.WINDOWS else TargetPlatform.POSIX, a, dir, executor = executor),
            )
        }
    }

    /**
     * Target «в приложении» (D-75, шаг 5): имя и каталог слота в госте —
     * абсолютный путь внутри Alpine (`/root/.claude-slot-N`, D-29).
     */
    fun inApp(id: ExecutionTargetId, label: String, configDir: String, executor: Executor = Executor.CLAUDE): Outcome<TargetEntry> {
        if (label.isBlank() || label.length > 60) return Outcome.failure(DiagCode.VALUE_INVALID, "имя — от 1 до 60 знаков", "label")
        val dir = Accounts.normalizeDir(configDir.trim()) ?: configDir.trim()
        if (!dir.startsWith("/") || dir.any { it.isISOControl() }) return Outcome.failure(DiagCode.VALUE_INVALID, "каталог Claude — абсолютный путь в Linux приложения", "configDir")
        return Outcome.success(TargetEntry(id, label.trim(), "", 0, "", TargetPlatform.POSIX, IN_APP_AGENT, dir, inApp = true, executor = executor))
    }

    /** Команда агента «в приложении» — имя для `AgentClient`: исполняет её агент процесса (`InAppAgentHost`). */
    const val IN_APP_AGENT = "dpai"

    private fun absolute(path: String, windows: Boolean): Boolean =
        if (windows) Regex("""[A-Za-z]:[\\/].*""").matches(path) else path.startsWith("/")
}

/** Список targets приложения — документ `dpai.app-targets` (`docs/DATA_MODEL.md`). */
object TargetBook {
    const val FORMAT = "dpai.app-targets"

    /** Вид target «в приложении» в формате v3. */
    const val KIND_IN_APP = "in-app"
    /**
     * v6 — мест для сессий ИИ (`sessions`, D-180; пишется, только если не 1);
     * v5 — «Держать сессию в tmux» (`tmux`, D-111; пишется только `false`,
     * нет поля — включено); v4 — исполнитель слота (`executor`, D-100;
     * пишется, только если не `claude`); v3 — target «в приложении»
     * (`"kind": "in-app"`, без адреса SSH; D-75, шаг 5); v2 — каталог Claude
     * слота (`configDir`); v1…v3 читаются как Claude.
     */
    const val VERSION = 6

    fun toJson(entries: List<TargetEntry>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "targets" to JsonArray(
                entries.map { e ->
                    if (e.inApp) {
                        JsonObject(
                            linkedMapOf<String, JsonValue>("id" to JsonString(e.id.value), "label" to JsonString(e.label), "kind" to JsonString(KIND_IN_APP))
                                .also { m -> e.configDir?.let { m["configDir"] = JsonString(it) } }
                                .also { m -> if (e.executor != Executor.CLAUDE) m["executor"] = JsonString(e.executor.key) }
                                .also { m -> if (!e.tmux) m["tmux"] = com.dpsmpx.dpai.core.serialization.JsonBool(false) }
                                .also { m -> if (e.sessions != 1) m["sessions"] = JsonNumber.of(e.sessions.toLong()) },
                        )
                    } else {
                        JsonObject(
                            linkedMapOf(
                                "id" to JsonString(e.id.value), "label" to JsonString(e.label), "host" to JsonString(e.host),
                                "port" to JsonNumber.of(e.port.toLong()), "user" to JsonString(e.user),
                                "platform" to JsonString(e.platform.name.lowercase()), "agent" to JsonString(e.agent),
                            ).also { m -> e.configDir?.let { m["configDir"] = JsonString(it) } }
                                .also { m -> if (e.executor != Executor.CLAUDE) m["executor"] = JsonString(e.executor.key) }
                                .also { m -> if (!e.tmux) m["tmux"] = com.dpsmpx.dpai.core.serialization.JsonBool(false) }
                                .also { m -> if (e.sessions != 1) m["sessions"] = JsonNumber.of(e.sessions.toLong()) },
                        )
                    }
                },
            ),
        ),
    )

    fun fromJson(value: JsonValue): Outcome<List<TargetEntry>> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        val version = FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("targets")?.items.orEmpty()
        f.finish()
        val out = items.mapIndexedNotNull { i, item ->
            val obj = item as? JsonObject
            if (obj == null) {
                d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.targets[$i]")
                return@mapIndexedNotNull null
            }
            val t = JsonFields(obj, "$.targets[$i]", d)
            // «В приложении» (v3): только имя и каталог слота — адреса SSH нет.
            if (version >= 3 && t.has("kind")) {
                val kind = t.string("kind")
                val id = t.string("id")?.let { IdKinds.TARGET.parse(it).valueOrNull() }
                val label = t.string("label").orEmpty()
                val dir = t.string("configDir")
                val executor = readExecutor(t, version)
                val tmux = readTmux(t, version)
                val sessions = readSessions(t, version, "$.targets[$i]", d)
                t.finish()
                if (kind != KIND_IN_APP || id == null || dir == null || executor == null) {
                    d.error(DiagCode.VALUE_INVALID, "target «в приложении» неполон или вид неизвестен: $kind", "$.targets[$i]")
                    return@mapIndexedNotNull null
                }
                return@mapIndexedNotNull when (val e = TargetForm.inApp(id, label, dir, executor)) {
                    is Outcome.Success -> e.value.copy(tmux = tmux, sessions = sessions ?: return@mapIndexedNotNull null)
                    is Outcome.Failure -> null.also { e.diagnostics.forEach { x -> d.error(x.code, x.message, "$.targets[$i]") } }
                }
            }
            val id = t.string("id")?.let { IdKinds.TARGET.parse(it).valueOrNull() }
            val label = t.string("label").orEmpty()
            val host = t.string("host").orEmpty()
            val port = t.long("port")?.toString().orEmpty()
            val user = t.string("user").orEmpty()
            val windows = t.string("platform") == "windows"
            val agent = t.string("agent").orEmpty()
            val configDir = if (version >= 2) t.optString("configDir") else null
            val executor = readExecutor(t, version)
            val tmux = readTmux(t, version)
            val sessions = readSessions(t, version, "$.targets[$i]", d)
            t.finish()
            if (id == null || executor == null || sessions == null) {
                d.error(DiagCode.VALUE_INVALID, "id target недопустим", "$.targets[$i].id")
                return@mapIndexedNotNull null
            }
            val entry = TargetForm.validate(id, label, host, port, user, windows, agent, configDir, executor)
            when (entry) {
                is Outcome.Success -> entry.value.copy(tmux = tmux, sessions = sessions)
                is Outcome.Failure -> null.also { entry.diagnostics.forEach { x -> d.error(x.code, x.message, "$.targets[$i]") } }
            }
        }
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }

    /** Места для сессий (v6, D-180): нет поля или до v6 — 1; вне 1…4 — находка с путём и `null`. */
    private fun readSessions(t: JsonFields, version: Int, at: String, d: DiagnosticCollector): Int? {
        if (version < 6) return 1
        val n = t.optLong("sessions") ?: return 1
        if (n !in TargetForm.SESSIONS.first.toLong()..TargetForm.SESSIONS.last.toLong()) {
            d.error(DiagCode.VALUE_INVALID, "мест для сессий — ${TargetForm.SESSIONS.first}…${TargetForm.SESSIONS.last}, а не $n", "$at.sessions")
            return null
        }
        return n.toInt()
    }

    /** «Держать сессию в tmux» (v5, D-111): нет поля — да; до v5 поля нет — тоже да. */
    private fun readTmux(t: JsonFields, version: Int): Boolean = if (version < 5) true else t.optBoolean("tmux") ?: true

    /** Исполнитель записи (v4); нет поля — Claude; неизвестный — находка с путём (D-05), `null`. */
    private fun readExecutor(t: JsonFields, version: Int): Executor? {
        if (version < 4) return Executor.CLAUDE
        val key = t.optString("executor") ?: return Executor.CLAUDE
        return Executor.byKey(key) ?: null.also { t.diagnostics.error(DiagCode.VALUE_INVALID, "неизвестный исполнитель: $key", "${t.path}.executor") }
    }
}

/** known_hosts приложения: строка `имя тип ключ`; новая строка того же имени и типа заменяет прежнюю. */
object KnownHosts {
    fun add(text: String, line: String): String {
        val (name, type) = line.trim().split(" ").let { it[0] to it[1] }
        val kept = text.lines().filter { it.isNotBlank() }.filterNot { l -> l.split(" ").let { it.size >= 2 && it[0] == name && it[1] == type } }
        return (kept + line.trim()).joinToString("\n", postfix = "\n")
    }

    fun names(text: String): Set<String> = text.lines().mapNotNull { l -> l.trim().split(" ").takeIf { it.size >= 3 }?.get(0) }.toSet()

    /**
     * Добавить строку в known_hosts [path] атомарно (ревью A-85): новый текст —
     * во временный файл, `fsync`, переименование. Обрыв посреди записи или
     * чтение JSch в этот момент видят прежний файл целиком, а не пустой —
     * пустой отверг бы ключ хоста (`SSH_HOST_KEY_REJECTED`) без повтора.
     */
    fun save(vfs: Vfs, path: String, line: String) {
        val current = vfs.read(path)?.toString(Charsets.UTF_8).orEmpty()
        val tmp = "$path.tmp"
        vfs.write(tmp, add(current, line).toByteArray())
        vfs.fsync(tmp)
        vfs.rename(tmp, path)
    }
}

/** Строка проверки target для экрана. */
data class CheckLine(val name: String, val detail: String, val tone: Tone)

object TargetScreens {
    fun doctor(result: Outcome<DoctorResult>): List<CheckLine> = when (result) {
        is Outcome.Failure -> result.diagnostics.map { CheckLine(Words.reason(it.code.name), it.message, Tone.ERROR) }
        is Outcome.Success -> result.value.checks.map {
            CheckLine(
                it.name, it.detail,
                when (it.status) {
                    CheckStatus.OK -> Tone.DONE
                    CheckStatus.WARN -> Tone.WARNING
                    CheckStatus.FAIL -> Tone.ERROR
                    CheckStatus.UNKNOWN -> Tone.NEUTRAL
                },
            )
        } + result.value.diagnostics
            // Причина, уже сказанная строкой проверки («связь»), второй раз не выводится (A-5).
            .filter { d -> result.value.checks.none { d.message in it.detail } }
            .map { CheckLine(Words.reason(it.code.name), it.message, Tone.WARNING) }
    }
}

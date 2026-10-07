package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
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
import com.dpsmpx.dpai.execution.target.CheckStatus
import com.dpsmpx.dpai.execution.target.DoctorResult
import com.dpsmpx.dpai.execution.target.ExecutionTarget
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType

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
) {
    /** Target того же телефона — Termux на `127.0.0.1` (D-26). */
    val type: TargetType
        get() = when {
            platform == TargetPlatform.WINDOWS -> TargetType.WINDOWS_REMOTE
            host == "127.0.0.1" || host == "localhost" -> TargetType.TERMUX_LOCAL
            else -> TargetType.LINUX_SSH
        }

    fun toTarget(knownHostsFile: String): ExecutionTarget =
        ExecutionTarget(id, type, label, platform, SshEndpoint.of(host, user, port, null, knownHostsFile).valueOrNull()!!, listOf(agent))
}

/** Форма нового target: проверки — те же, что у `SshEndpoint` (параметр `ssh` не подставить, T2). */
object TargetForm {
    fun validate(id: ExecutionTargetId, label: String, host: String, port: String, user: String, windows: Boolean, agent: String): Outcome<TargetEntry> {
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "порт — число от 1 до 65535", "port")
        if (label.isBlank() || label.length > 60) return Outcome.failure(DiagCode.VALUE_INVALID, "имя — от 1 до 60 знаков", "label")
        val a = agent.trim()
        if (a.isEmpty() || a.startsWith("-") || a.any { it.isISOControl() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "команда агента — путь к dpai на target", "agent")
        }
        return when (val e = SshEndpoint.of(host.trim(), user.trim(), p)) {
            is Outcome.Failure -> e
            is Outcome.Success -> Outcome.success(
                TargetEntry(id, label.trim(), e.value.host, p, e.value.user, if (windows) TargetPlatform.WINDOWS else TargetPlatform.POSIX, a),
            )
        }
    }
}

/** Список targets приложения — документ `dpai.app-targets` v1 (`docs/DATA_MODEL.md`). */
object TargetBook {
    const val FORMAT = "dpai.app-targets"
    const val VERSION = 1

    fun toJson(entries: List<TargetEntry>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "targets" to JsonArray(
                entries.map { e ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonString(e.id.value), "label" to JsonString(e.label), "host" to JsonString(e.host),
                            "port" to JsonNumber.of(e.port.toLong()), "user" to JsonString(e.user),
                            "platform" to JsonString(e.platform.name.lowercase()), "agent" to JsonString(e.agent),
                        ),
                    )
                },
            ),
        ),
    )

    fun fromJson(value: JsonValue): Outcome<List<TargetEntry>> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("targets")?.items.orEmpty()
        f.finish()
        val out = items.mapIndexedNotNull { i, item ->
            val obj = item as? JsonObject
            if (obj == null) {
                d.error(DiagCode.FIELD_TYPE, "ожидался объект", "$.targets[$i]")
                return@mapIndexedNotNull null
            }
            val t = JsonFields(obj, "$.targets[$i]", d)
            val id = t.string("id")?.let { IdKinds.TARGET.parse(it).valueOrNull() }
            val label = t.string("label").orEmpty()
            val host = t.string("host").orEmpty()
            val port = t.long("port")?.toString().orEmpty()
            val user = t.string("user").orEmpty()
            val windows = t.string("platform") == "windows"
            val agent = t.string("agent").orEmpty()
            t.finish()
            if (id == null) {
                d.error(DiagCode.VALUE_INVALID, "id target недопустим", "$.targets[$i].id")
                return@mapIndexedNotNull null
            }
            val entry = TargetForm.validate(id, label, host, port, user, windows, agent)
            when (entry) {
                is Outcome.Success -> entry.value
                is Outcome.Failure -> null.also { entry.diagnostics.forEach { x -> d.error(x.code, x.message, "$.targets[$i]") } }
            }
        }
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
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
}

/** Строка проверки target для экрана. */
data class CheckLine(val name: String, val detail: String, val tone: Tone)

object TargetScreens {
    fun doctor(result: Outcome<DoctorResult>): List<CheckLine> = when (result) {
        is Outcome.Failure -> result.diagnostics.map { CheckLine(it.code.name, it.message, Tone.ERROR) }
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
        } + result.value.diagnostics.map { CheckLine(it.code.name, it.message, Tone.WARNING) }
    }
}

package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.control.ControlAuth
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.InputStream
import java.nio.file.Paths

/** Общий подписанный канал D-222; --stdin не оставляет параметры в командной строке. */
class AutomationCommand(private val out: (String) -> Unit, private val err: (String) -> Unit,
    private val input: InputStream = System.`in`,
    private val config: Vfs = NioVfs(Paths.get(System.getProperty("user.home") ?: ".", ".config", "dpai")),
    private val transport: (Int) -> ControlTransport = { port ->
        SignedTransport(JdkWire(System.getenv("DPAI_CONTROL_URL") ?: "http://127.0.0.1:" + port))
    }) {
    fun run(args: List<String>): Int {
        if (args.size !in 2..3 || (args.size == 3 && args[2] != "--stdin") ||
            args[1] !in ACTIONS || IdKinds.PROJECT.parse(args[0]).valueOrNull() == null) {
            err(USAGE); return Cli.USAGE_ERROR
        }
        val body = if (args.size == 3) {
            val bytes = input.readNBytes(262_145)
            if (bytes.size > 262_144) { err("параметры больше 256 КиБ"); return Cli.USAGE_ERROR }
            JsonParser().parse(bytes.toString(Charsets.UTF_8)).valueOrNull() as? JsonObject
                ?: run { err("stdin — один объект JSON"); return Cli.USAGE_ERROR }
        } else JsonObject.of()
        val stored = try {
            val bytes = config.read(ControlCommand.FILE)
            if (bytes == null || bytes.size > 8192) null else JsonParser().parse(bytes.toString(Charsets.UTF_8)).valueOrNull() as? JsonObject
        } catch (_: java.io.IOException) { null }
        val token = (stored?.get("token") as? JsonString)?.value
        val port = (stored?.get("port") as? JsonNumber)?.toLongExact()?.takeIf { it in 1..65535 }?.toInt()
        if (token == null || !ControlAuth.validToken(token) || port == null) {
            err("сначала dpai control pair <код>; код — в настройках приложения")
            return Cli.USAGE_ERROR
        }
        val reply = transport(port).call("POST", "/v1/automation", token, JsonObject.of(
            "project" to JsonString(args[0]), "action" to JsonString(args[1]), "input" to body))
        if (reply.body != null) out(CanonicalJson.write(reply.body))
        else err(reply.error ?: "канал автоматизации недоступен")
        val status = (reply.body?.get("status") as? JsonString)?.value
        val nested = ((reply.body?.get("result") as? JsonObject)?.get("status") as? JsonString)?.value
        val failed = setOf("FAILED", "BLOCKED", "INCOMPLETE", "NEEDS_REVIEW", "CANCELLED")
        return when {
            reply.status == 202 || status in setOf("RUNNING", "STOP_REQUESTED") -> ControlCommand.WAITING
            reply.status != 200 || status in failed || nested in failed -> 1
            else -> Cli.OK
        }
    }

    companion object {
        val ACTIONS = setOf("list", "describe", "run", "workflows", "import", "workflow-run", "status", "stop")
        const val USAGE = "dpai automation PROJECT_ID list|describe|run|workflows|import|workflow-run|status|stop [--stdin]\n" +
            "Параметры — JSON в stdin. 202/код 3: запрос принят; подтверждения — на телефоне. Импорт выключен; включить можно в UI."
    }
}
